package dev.atm10spoof.handshake;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.resources.Identifier;
import dev.atm10spoof.Atm10SpoofClient;
import dev.atm10spoof.mixin.ListenerAccessor;

/**
 * Fakes NeoForge 21.1's configuration-phase handshake (Minecraft 1.21.1, as used by All The Mods 10).
 *
 * Flow, as the server sees it:
 *  1. server -> neoforge:register (query)           we reply neoforge:register with our "channel list"
 *  2. server -> neoforge:modded_network_setup_failed if our list was wrong; we learn from it and save
 *     server -> neoforge:network                     if it was accepted
 *  3. server -> neoforge:frozen_registry_sync_completed  we reply the same id (empty)
 *  4. server -> neoforge:extensible_enum_data        we reply neoforge:extensible_enum_ack
 *  5. server -> neoforge:feature_flags               we reply neoforge:feature_flags_ack
 *  6. server -> neoforge:known_registry_data_maps    we reply ..._reply claiming to know all of them
 */
public final class NeoHandshake {
	private static final String NS = "neoforge";

	// Clientbound (server -> us)
	static final CustomPacketPayload.Type<RawPayload> QUERY = RawPayload.type(NS, "register");
	static final CustomPacketPayload.Type<RawPayload> SETUP_FAILED = RawPayload.type(NS, "modded_network_setup_failed");
	static final CustomPacketPayload.Type<RawPayload> SETUP_OK = RawPayload.type(NS, "network");
	static final CustomPacketPayload.Type<RawPayload> REGISTRY_SYNC_DONE = RawPayload.type(NS, "frozen_registry_sync_completed");
	static final CustomPacketPayload.Type<RawPayload> ENUM_DATA = RawPayload.type(NS, "extensible_enum_data");
	static final CustomPacketPayload.Type<RawPayload> FEATURE_FLAGS = RawPayload.type(NS, "feature_flags");
	static final CustomPacketPayload.Type<RawPayload> DATA_MAPS = RawPayload.type(NS, "known_registry_data_maps");

	// Serverbound (us -> server). QUERY and REGISTRY_SYNC_DONE are reused with the same ids.
	static final CustomPacketPayload.Type<RawPayload> ENUM_ACK = RawPayload.type(NS, "extensible_enum_ack");
	static final CustomPacketPayload.Type<RawPayload> FEATURE_FLAGS_ACK = RawPayload.type(NS, "feature_flags_ack");
	static final CustomPacketPayload.Type<RawPayload> DATA_MAPS_REPLY = RawPayload.type(NS, "known_registry_data_maps_reply");

	// Big clientbound config payloads we don't use; registered so they're read and ignored instead of
	// hitting the size limit for unknown payloads.
	static final CustomPacketPayload.Type<RawPayload> CONFIG_FILE = RawPayload.type(NS, "config_file");
	static final CustomPacketPayload.Type<RawPayload> REGISTRY_SYNC_START = RawPayload.type(NS, "frozen_registry_sync_start");
	static final CustomPacketPayload.Type<RawPayload> REGISTRY_DATA = RawPayload.type(NS, "frozen_registry");

	/**
	 * NeoForge's own configuration-phase channels. They're all registered as optional (version "1"),
	 * so the server never lists them as missing; we have to claim them ourselves or it treats us as a
	 * vanilla client for registry sync, data maps, enum and feature-flag checks.
	 * Values: id, flow ("clientbound", "serverbound" or null for both directions).
	 */
	private static final String[][] NEOFORGE_CONFIG_CHANNELS = {
		{"neoforge:config_file", "clientbound"},
		{"neoforge:frozen_registry_sync_start", "clientbound"},
		{"neoforge:frozen_registry", "clientbound"},
		{"neoforge:frozen_registry_sync_completed", null},
		{"neoforge:known_registry_data_maps", "clientbound"},
		{"neoforge:extensible_enum_data", "clientbound"},
		{"neoforge:feature_flags", "clientbound"},
		{"neoforge:known_registry_data_maps_reply", "serverbound"},
		{"neoforge:extensible_enum_ack", "serverbound"},
		{"neoforge:feature_flags_ack", "serverbound"},
	};

	private static final int MAX_AUTO_RECONNECTS = 20;

	private static ChannelStore current;
	private static ServerData lastServer;
	private static String autoReconnectAddress;
	private static int autoReconnects;
	private static boolean learnedThisConnection;

	private NeoHandshake() {}

	public static void register() {
		for (var type : List.of(QUERY, SETUP_FAILED, SETUP_OK, REGISTRY_SYNC_DONE, ENUM_DATA, FEATURE_FLAGS, DATA_MAPS,
				CONFIG_FILE, REGISTRY_SYNC_START, REGISTRY_DATA)) {
			PayloadTypeRegistry.clientboundConfiguration().registerLarge(type, RawPayload.codec(type), 8 * 1024 * 1024);
		}
		for (var type : List.of(QUERY, REGISTRY_SYNC_DONE, ENUM_ACK, FEATURE_FLAGS_ACK, DATA_MAPS_REPLY)) {
			PayloadTypeRegistry.serverboundConfiguration().register(type, RawPayload.codec(type));
		}

		ClientConfigurationNetworking.registerGlobalReceiver(QUERY, (payload, ctx) -> {
			noteConnection(ctx);
			current = ChannelStore.forServer(serverName(ctx));
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] NeoForge server detected, claiming {} channels (from {})", current.size(), current.file());
			ctx.responseSender().sendPacket(new RawPayload(QUERY, encodeQueryReply(current)));
		});

		ClientConfigurationNetworking.registerGlobalReceiver(SETUP_FAILED, (payload, ctx) -> {
			ChannelStore store = current != null ? current : ChannelStore.forServer(null);
			int changed = 0;
			int total = 0;
			FriendlyByteBuf buf = wrap(payload.data());
			int count = buf.readVarInt();
			for (int i = 0; i < count; i++) {
				Identifier channel = buf.readIdentifier();
				Tag reason = ByteBufCodecs.TAG.decode(buf);
				total++;
				if (total <= 20) {
					Atm10SpoofClient.LOGGER.info("[ATM10 Spoof]   {} -> {}", channel, reason);
				}
				if (FailureParser.apply(store, channel.toString(), reason)) {
					changed++;
				}
			}
			store.save();
			if (changed > 0) {
				learnedThisConnection = true;
			}
			Atm10SpoofClient.LOGGER.warn(
				"[ATM10 Spoof] Server rejected the fake channel list ({} problems, learned {}). Saved {} channels to {}."
					+ (changed > 0 ? " Reconnecting automatically." : " Reconnect to try again."),
				total, changed, store.size(), store.file()
			);
		});

		ClientConfigurationNetworking.registerGlobalReceiver(SETUP_OK, (payload, ctx) ->
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Server accepted the fake mod list"));

		ClientConfigurationNetworking.registerGlobalReceiver(REGISTRY_SYNC_DONE, (payload, ctx) -> {
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Registry sync finished, acknowledging");
			ctx.responseSender().sendPacket(RawPayload.empty(REGISTRY_SYNC_DONE));
			// Fallback for servers that never reach the feature-flag check.
			scheduleForcedFinish(ctx, 20_000);
		});

		ClientConfigurationNetworking.registerGlobalReceiver(ENUM_DATA, (payload, ctx) ->
			ctx.responseSender().sendPacket(RawPayload.empty(ENUM_ACK)));

		ClientConfigurationNetworking.registerGlobalReceiver(FEATURE_FLAGS, (payload, ctx) -> {
			ctx.responseSender().sendPacket(RawPayload.empty(FEATURE_FLAGS_ACK));
			// Feature flags are the last of NeoForge's own configuration tasks. Any mod configuration
			// task after this point may try to send a channel we never claimed (optional channels are
			// never announced by the server), and when that throws the server waits forever. If we
			// haven't been finished off shortly, end the configuration phase ourselves.
			scheduleForcedFinish(ctx, 5_000);
		});

		ClientConfigurationNetworking.registerGlobalReceiver(DATA_MAPS, (payload, ctx) ->
			ctx.responseSender().sendPacket(new RawPayload(DATA_MAPS_REPLY, encodeDataMapsReply(payload.data()))));

		// A successful join resets the automatic reconnect budget.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			learnedThisConnection = false;
			autoReconnects = 0;
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Joined the server");
		});
	}

	/** Remember which server we are talking to and reset the per-connection learning flag. */
	private static void noteConnection(ClientConfigurationNetworking.Context ctx) {
		learnedThisConnection = false;
		try {
			ListenerAccessor listener = (ListenerAccessor) ctx.packetListener();
			ServerData data = listener.atm10spoof$serverData();
			if (data != null && data.ip != null && !data.ip.isBlank()) {
				if (!data.ip.equals(autoReconnectAddress)) {
					autoReconnectAddress = data.ip;
					autoReconnects = 0;
				}
				lastServer = data;
			}
		} catch (Throwable t) {
			Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Couldn't read the server address for auto-reconnect", t);
		}
	}

	/**
	 * After saving newly learned channels, the server kicks us with "Incompatible client! Please use
	 * NeoForge X" and the user would have to click Reconnect manually (often several times). Do it
	 * for them, with a small budget so a broken server can't cause an endless loop.
	 */
	public static void noteDisconnect(String reason) {
		if (!learnedThisConnection) {
			return;
		}
		if (reason == null || !(reason.contains("Incompatible client") || reason.contains("NeoForge"))) {
			learnedThisConnection = false;
			return;
		}
		if (lastServer == null || lastServer.ip == null || lastServer.ip.isBlank()) {
			learnedThisConnection = false;
			return;
		}
		if (autoReconnects >= MAX_AUTO_RECONNECTS) {
			Atm10SpoofClient.LOGGER.warn(
				"[ATM10 Spoof] Still incompatible after {} automatic reconnects; giving up. Delete {} and reconnect manually.",
				autoReconnects, current != null ? current.file() : "config/atm10spoof"
			);
			learnedThisConnection = false;
			return;
		}

		autoReconnects++;
		learnedThisConnection = false;
		ServerData server = lastServer;
		String address = server.ip;
		Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Reconnecting to {} automatically (attempt {}/{})",
			address, autoReconnects, MAX_AUTO_RECONNECTS);

		CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() ->
			Minecraft.getInstance().execute(() -> {
				try {
					Minecraft minecraft = Minecraft.getInstance();
					ConnectScreen.startConnecting(null, minecraft, ServerAddress.parseString(address), server, false, null);
				} catch (Throwable t) {
					Atm10SpoofClient.LOGGER.error("[ATM10 Spoof] Automatic reconnect failed", t);
				}
			})
		);
	}

	/**
	 * The server can get stuck when a mod's configuration task tries to send an optional channel the
	 * server and client never agreed on. NeoForge throws, the task never completes, and the server
	 * only sends keep-alives forever. When that happens, drive the normal client finish path
	 * ourselves: it sends the finish packet the server is waiting for and swaps both protocols to
	 * play. If a real finish arrived first, the listener has already changed and this does nothing.
	 */
	private static void scheduleForcedFinish(ClientConfigurationNetworking.Context ctx, long delayMillis) {
		if (!(ctx.packetListener() instanceof ClientConfigurationPacketListenerImpl config)) {
			return;
		}
		Connection connection = ((ListenerAccessor) config).atm10spoof$connection();
		CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(() ->
			Minecraft.getInstance().execute(() -> {
				if (connection.getPacketListener() != config || config.protocol() != ConnectionProtocol.CONFIGURATION) {
					return; // normal finish already took over
				}
				long idle = Atm10SpoofClient.configurationIdleMillis();
				// Big modpacks can pause for several seconds between config packets (tags,
				// datapack sync, mod config). Forcing the transition while the server is still in
				// configuration leaves Fabric's configuration networking addon in a broken state.
				if (idle < 30_000) {
					// Something is still arriving; give the server time to finish normally.
					scheduleForcedFinish(ctx, 30_200 - idle);
					return;
				}
				Atm10SpoofClient.LOGGER.warn(
					"[ATM10 Spoof] Configuration stalled (a mod tried to send a channel we never claimed); finishing it ourselves"
				);
				// The server's registry data usually references modded registries we don't have.
				// Use our own registries instead of risking an exception halfway through the swap.
				Atm10SpoofClient.SKIP_NETWORK_REGISTRIES.set(true);
				Atm10SpoofClient.FORCE_PIPELINE_TRANSITION.set(true);
				try {
					config.handleConfigurationFinished(ClientboundFinishConfigurationPacket.INSTANCE);
				} catch (Throwable t) {
					Atm10SpoofClient.LOGGER.error("[ATM10 Spoof] Couldn't finish configuration with local registries", t);
				} finally {
					Atm10SpoofClient.FORCE_PIPELINE_TRANSITION.set(false);
				}
			})
		);
	}

	/**
	 * The server announces the optional play channels it knows how to receive through c:register.
	 * NeoForge uses that same list as its "may send" checks, which means adding our channels here
	 * also lets the server send optional payloads it would otherwise refuse to send. That keeps
	 * mods with unconditional config/datapack sync (KubeJS, Accessories, ...) from aborting the join.
	 */
	public static Set<Identifier> expandChannels(Set<?> rawChannels) {
		Set<Identifier> channels = new HashSet<>();
		for (Object value : rawChannels) {
			if (value instanceof Identifier id) {
				channels.add(id);
			}
		}
		if (current != null) {
			for (ChannelStore.Channel channel : current.all()) {
				try {
					channels.add(Identifier.parse(channel.id));
				} catch (Exception ignored) {
				}
			}
		}
		for (ChannelManifest.Entry entry : ChannelManifest.play()) {
			try {
				channels.add(Identifier.parse(entry.id()));
			} catch (Exception ignored) {
			}
		}
		channels.addAll(ClientPlayNetworking.getGlobalReceivers());
		return channels;
	}

	private static String serverName(ClientConfigurationNetworking.Context ctx) {
		ListenerAccessor listener = (ListenerAccessor) ctx.packetListener();
		ServerData data = listener.atm10spoof$serverData();
		if (data != null && data.ip != null && !data.ip.isBlank()) {
			return data.ip;
		}
		ServerData current = ctx.client().getCurrentServer();
		if (current != null && current.ip != null && !current.ip.isBlank()) {
			return current.ip;
		}
		return String.valueOf(listener.atm10spoof$connection().getRemoteAddress());
	}

	/** Map<ConnectionProtocol, Set<ModdedNetworkQueryComponent(id, version, Optional<flow>, optional)>> */
	private static byte[] encodeQueryReply(ChannelStore store) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		List<ChannelStore.Channel> channels = new ArrayList<>();
		store.all().forEach(channels::add);

		// Failure reasons don't say which protocol a channel belongs to, so claim every channel in both.
		// They're all marked optional, so the server drops the ones it doesn't use in a protocol.
		ConnectionProtocol[] protocols = {ConnectionProtocol.CONFIGURATION, ConnectionProtocol.PLAY};
		buf.writeVarInt(protocols.length);
		for (ConnectionProtocol protocol : protocols) {
			List<String[]> builtIn = new ArrayList<>();
			if (protocol == ConnectionProtocol.CONFIGURATION) {
				for (String[] nc : NEOFORGE_CONFIG_CHANNELS) {
					boolean learned = channels.stream().anyMatch(c -> c.id.equals(nc[0]));
					if (!learned) {
						builtIn.add(nc);
					}
				}
			}

			buf.writeVarInt(protocol.ordinal());
			buf.writeVarInt(channels.size() + builtIn.size());
			for (ChannelStore.Channel c : channels) {
				String flowName = protocol == ConnectionProtocol.CONFIGURATION ? c.configFlow() : c.playFlow();
				writeComponent(buf, c.id, c.version, flowName);
			}
			for (String[] nc : builtIn) {
				writeComponent(buf, nc[0], "1", nc[1]);
			}
		}
		return toBytes(buf);
	}

	private static void writeComponent(FriendlyByteBuf buf, String id, String version, String flowName) {
		buf.writeIdentifier(Identifier.parse(id));
		buf.writeUtf(version);
		PacketFlow flow = "clientbound".equals(flowName) ? PacketFlow.CLIENTBOUND : "serverbound".equals(flowName) ? PacketFlow.SERVERBOUND : null;
		buf.writeBoolean(flow != null);
		if (flow != null) {
			buf.writeVarInt(flow.ordinal());
		}
		buf.writeBoolean(true); // optional
	}

	/** Reads Map<registry, List<(id, mandatory)>> and replies Map<registry, List<id>> with everything. */
	private static byte[] encodeDataMapsReply(byte[] request) {
		FriendlyByteBuf in = wrap(request);
		Map<Identifier, List<Identifier>> maps = new LinkedHashMap<>();
		int registries = in.readVarInt();
		for (int i = 0; i < registries; i++) {
			Identifier registry = in.readIdentifier();
			int n = in.readVarInt();
			List<Identifier> ids = new ArrayList<>(n);
			for (int j = 0; j < n; j++) {
				ids.add(in.readIdentifier());
				in.readBoolean(); // mandatory
			}
			maps.put(registry, ids);
		}

		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		out.writeVarInt(maps.size());
		maps.forEach((registry, ids) -> {
			out.writeIdentifier(registry);
			out.writeVarInt(ids.size());
			ids.forEach(out::writeIdentifier);
		});
		return toBytes(out);
	}

	private static FriendlyByteBuf wrap(byte[] data) {
		return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
	}

	private static byte[] toBytes(FriendlyByteBuf buf) {
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		buf.release();
		return bytes;
	}
}
