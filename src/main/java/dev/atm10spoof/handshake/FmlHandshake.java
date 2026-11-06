package dev.atm10spoof.handshake;

import dev.atm10spoof.Atm10SpoofClient;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Forge 1.7.10's FML handshake, used by old packs such as GregTech: New Horizons.
 * ViaFabricPlus translates the legacy {@code FML|HS} plugin channel to {@code fml:hs} and moves it
 * into the modern configuration phase.
 *
 * The wire format is a discriminator byte followed by the message body:
 *   0 = ServerHello, 1 = ClientHello, 2 = ModList, 3 = ModIdData, -1 = HandshakeAck, -2 = HandshakeReset.
 *
 * The server rejects the connection with "Mod rejections [...]" when our mod list does not contain
 * one of its network mods. We remember that list per server and echo it back on the next attempt.
 */
public final class FmlHandshake {
	static final CustomPacketPayload.Type<RawPayload> HS = RawPayload.type("fml", "hs");
	static final CustomPacketPayload.Type<RawPayload> MP = RawPayload.type("fml", "mp");

	private static final int SERVER_HELLO = 0;
	private static final int CLIENT_HELLO = 1;
	private static final int MOD_LIST = 2;
	private static final int MOD_ID_DATA = 3;
	private static final int HANDSHAKE_ACK = 0xFF;   // -1
	private static final int HANDSHAKE_RESET = 0xFE; // -2

	/** Forge 1.7.10's NetworkRegistry.FML_PROTOCOL. */
	private static final byte PROTOCOL = 2;

	// FMLHandshakeClientState ordinals, used as HandshakeAck phases.
	private static final int START = 0;
	private static final int HELLO = 1;
	private static final int WAITINGSERVERDATA = 2;
	private static final int WAITINGSERVERCOMPLETE = 3;
	private static final int PENDINGCOMPLETE = 4;
	private static final int COMPLETE = 5;
	private static final int DONE = 6;

	// Mod ids may contain '|' (BuildCraft|Core), so don't restrict the character set too much.
	private static final Pattern REJECTION = Pattern.compile("FMLMod:([^\\s{},]+)\\{([^}]*)}");

	private static int state = START;
	private static FmlModStore store;
	private static String serverAddress;
	private static boolean sawServerHello;
	private static boolean finishScheduled;

	private FmlHandshake() {}

	public static void register() {
		StreamCodec<FriendlyByteBuf, RawPayload> hs = RawPayload.codec(HS);
		StreamCodec<FriendlyByteBuf, RawPayload> mp = RawPayload.codec(MP);
		PayloadTypeRegistry.clientboundConfiguration().register(HS, hs);
		PayloadTypeRegistry.serverboundConfiguration().register(HS, hs);
		PayloadTypeRegistry.clientboundPlay().register(HS, hs);
		PayloadTypeRegistry.serverboundPlay().register(HS, hs);
		PayloadTypeRegistry.clientboundPlay().register(MP, mp);
		PayloadTypeRegistry.serverboundPlay().register(MP, mp);

		ClientConfigurationNetworking.registerGlobalReceiver(HS, (payload, ctx) ->
			handle(payload.data(), data -> ctx.responseSender().sendPacket(new RawPayload(HS, data)), ctx.packetListener()));
		ClientPlayNetworking.registerGlobalReceiver(HS, (payload, ctx) ->
			handle(payload.data(), data -> ctx.responseSender().sendPacket(new RawPayload(HS, data)), null));

		ClientConfigurationConnectionEvents.START.register((listener, client) -> reset(true));
		// Keep the server address around for the disconnect handler, which learns the rejection list.
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> reset(false));
	}

	private static void reset(boolean newConnection) {
		if (!newConnection) {
			// Keep the store and address: the disconnect handler still needs them to save rejections.
			return;
		}
		state = START;
		store = null;
		serverAddress = null;
		sawServerHello = false;
		finishScheduled = false;
	}

	private static void handle(byte[] data, java.util.function.Consumer<byte[]> sender,
			net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl configuration) {
		if (data.length == 0) {
			return;
		}
		// Forge writes large custom payloads with a 3-byte "var short" length:
		//   2 bytes (low 15 bits | 0x8000), then 1 byte (high bits).
		// ViaLegacy reads the first 2 bytes as a normal unsigned short and forgets the third,
		// leaving that high length byte at the start of the payload. Strip it again.
		int offset = 0;
		if (data.length >= 32768) {
			int originalLength = data.length - 1;
			int high = (originalLength & 0x7F8000) >> 15;
			if ((data[0] & 0xFF) == high) {
				offset = 1;
				Atm10SpoofClient.LOGGER.info(
					"[ATM10 Spoof] Stripping legacy payload length byte ({} bytes, high {})", data.length, high);
			}
		}
		int discriminator = data[offset] & 0xFF;
		FriendlyByteBuf buf = wrap(data, offset + 1);
		switch (discriminator) {
			case SERVER_HELLO -> onServerHello(buf, sender, configuration);
			case MOD_LIST -> onModList(buf, sender);
			case MOD_ID_DATA -> onModIdData(buf, sender);
			case HANDSHAKE_ACK -> onAck(buf.readUnsignedByte(), sender);
			case HANDSHAKE_RESET -> {
				Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] FML handshake reset by server");
				state = START;
				sawServerHello = false;
			}
			default -> Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Ignoring FML message type {}", discriminator);
		}
	}

	private static void onServerHello(FriendlyByteBuf buf, java.util.function.Consumer<byte[]> sender,
			net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl configuration) {
		if (configuration != null) {
			dev.atm10spoof.mixin.ListenerAccessor listener = (dev.atm10spoof.mixin.ListenerAccessor) configuration;
			ServerData data = listener.atm10spoof$serverData();
			if (data != null && data.ip != null && !data.ip.isBlank()) {
				serverAddress = data.ip;
			}
		}

		int protocol = buf.readUnsignedByte();
		int overrideDimension = protocol > 1 ? buf.readInt() : 0;
		if (!sawServerHello) {
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] FML server hello (protocol {}, dim {})", protocol, overrideDimension);
			sawServerHello = true;
		}
		state = HELLO;

		FmlModStore current = store();
		Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Sending FML client hello and {} claimed mods (from {})",
			current.size(), current.file());
		sender.accept(bytes(CLIENT_HELLO, PROTOCOL));
		sender.accept(encodeModList(current.mods()));
		state = WAITINGSERVERDATA;

		// ViaVersion queues serverbound configuration payloads until the client finishes configuration,
		// so the reply above would never reach the 1.7.10 server. The server has already switched its
		// connection to PLAY before sending ServerHello, so finish configuration now and continue the
		// handshake with normal play payloads.
		if (configuration != null && !finishScheduled) {
			finishScheduled = true;
			// Configuration payloads are handled on a netty thread; the configuration listener must be
			// finished on the client thread.
			Minecraft.getInstance().execute(() -> finishConfiguration(configuration));
		}
	}

	private static void finishConfiguration(net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl configuration) {
		if (configuration.protocol() != net.minecraft.network.ConnectionProtocol.CONFIGURATION) {
			return;
		}
		Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Finishing configuration to deliver the FML reply");
		Atm10SpoofClient.SKIP_NETWORK_REGISTRIES.set(true);
		Atm10SpoofClient.FORCE_PIPELINE_TRANSITION.set(true);
		try {
			configuration.handleConfigurationFinished(
				net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket.INSTANCE);
		} catch (Throwable t) {
			Atm10SpoofClient.LOGGER.error("[ATM10 Spoof] Couldn't finish configuration for the FML handshake", t);
		} finally {
			Atm10SpoofClient.FORCE_PIPELINE_TRANSITION.set(false);
			Atm10SpoofClient.SKIP_NETWORK_REGISTRIES.set(false);
		}
	}

	private static void onModList(FriendlyByteBuf buf, java.util.function.Consumer<byte[]> sender) {
		int count = buf.readVarInt();
		Map<String, String> mods = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			mods.put(buf.readUtf(), buf.readUtf());
		}
		Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] FML server sent its mod list ({} mods)", count);
		if (state == WAITINGSERVERDATA) {
			sender.accept(bytes(HANDSHAKE_ACK, WAITINGSERVERDATA));
			state = WAITINGSERVERCOMPLETE;
		}
	}

	private static void onModIdData(FriendlyByteBuf buf, java.util.function.Consumer<byte[]> sender) {
		int count = buf.readVarInt();
		for (int i = 0; i < count; i++) {
			buf.readUtf();
			buf.readVarInt();
		}
		Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] FML server sent {} world id mappings, acknowledging", count);
		if (state == WAITINGSERVERCOMPLETE) {
			sender.accept(bytes(HANDSHAKE_ACK, WAITINGSERVERCOMPLETE));
			state = PENDINGCOMPLETE;
		}
	}

	private static void onAck(int phase, java.util.function.Consumer<byte[]> sender) {
		if (state == PENDINGCOMPLETE) {
			sender.accept(bytes(HANDSHAKE_ACK, PENDINGCOMPLETE));
			state = COMPLETE;
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] FML handshake complete");
		} else if (state == COMPLETE) {
			sender.accept(bytes(HANDSHAKE_ACK, COMPLETE));
			state = DONE;
		} else {
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] FML ack {} in state {}", phase, state);
		}
	}

	private static FmlModStore store() {
		if (store == null) {
			store = FmlModStore.forServer(serverAddress());
		}
		return store;
	}

	private static String serverAddress() {
		if (serverAddress != null && !serverAddress.isBlank()) {
			return serverAddress;
		}
		ServerData data = Minecraft.getInstance().getCurrentServer();
		if (data != null && data.ip != null && !data.ip.isBlank()) {
			return data.ip;
		}
		return "default";
	}

	/**
	 * Called from the disconnect mixin. The 1.7.10 server's "Mod rejections [...]" message names every
	 * network mod we failed to claim, so remember the exact server versions and retry on reconnect.
	 */
	public static void noteDisconnectReason(String reason) {
		if (reason == null || !reason.contains("Mod rejections")) {
			return;
		}
		Matcher matcher = REJECTION.matcher(reason);
		// Use the same store the handshake used, so we always update the file for the right server.
		FmlModStore current = store != null ? store : FmlModStore.forServer(serverAddress());
		int learned = 0;
		while (matcher.find()) {
			if (current.put(matcher.group(1), matcher.group(2))) {
				learned++;
			}
		}
		if (learned > 0) {
			current.save();
			Atm10SpoofClient.LOGGER.warn(
				"[ATM10 Spoof] Server rejected the fake FML mod list (learned {} mods, {} total). "
					+ "Saved to {}. Reconnect to try again.",
				learned, current.size(), current.file()
			);
		} else {
			Atm10SpoofClient.LOGGER.warn(
				"[ATM10 Spoof] FML mod list was rejected but no new mods could be learned: {}", reason
			);
		}
	}

	private static byte[] encodeModList(Map<String, String> mods) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeByte(MOD_LIST);
		buf.writeVarInt(mods.size());
		mods.forEach((id, version) -> {
			buf.writeUtf(id);
			buf.writeUtf(version);
		});
		return toBytes(buf);
	}

	private static byte[] bytes(int discriminator, int... body) {
		byte[] result = new byte[body.length + 1];
		result[0] = (byte) discriminator;
		for (int i = 0; i < body.length; i++) {
			result[i + 1] = (byte) body[i];
		}
		return result;
	}

	private static FriendlyByteBuf wrap(byte[] data, int offset) {
		byte[] body = new byte[data.length - offset];
		System.arraycopy(data, offset, body, 0, body.length);
		return new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
	}

	private static byte[] toBytes(FriendlyByteBuf buf) {
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		buf.release();
		return bytes;
	}
}
