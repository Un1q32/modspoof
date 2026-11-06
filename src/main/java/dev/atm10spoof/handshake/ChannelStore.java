package dev.atm10spoof.handshake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import dev.atm10spoof.Atm10SpoofClient;

/**
 * The channels we pretend to have, learned from the server's handshake failures and saved per server.
 * File: config/atm10spoof/<server address>.json
 */
public final class ChannelStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** One faked network channel. flow is "clientbound", "serverbound" or null (none). */
	public static final class Channel {
		public String id;
		public String version = "";
		public String flow = null;
		/** True once the server has told us this channel's flow (null can be a learned value). */
		public boolean flowKnown = false;
		/**
		 * Some mods register the same channel in both the configuration and play phase with different
		 * flows. Failure reasons don't say which phase, so when we see conflicting flows we keep both
		 * and try each assignment: config gets flow / play gets altFlow, or swapped.
		 */
		public boolean split = false;
		public String altFlow = null;
		public boolean swapped = false;

		public Channel() {}

		public boolean isFlowKnown() {
			return flowKnown || flow != null;
		}

		public String configFlow() {
			return split && swapped ? altFlow : flow;
		}

		public String playFlow() {
			return split ? (swapped ? flow : altFlow) : flow;
		}

		public Channel(String id) {
			this.id = id;
		}
	}

	private final Path file;
	private final Map<String, Channel> channels = new LinkedHashMap<>();

	private ChannelStore(Path file) {
		this.file = file;
	}

	public static ChannelStore forServer(String address) {
		String safe = (address == null || address.isBlank() ? "default" : address).replaceAll("[^A-Za-z0-9._-]", "_");
		Path file = FabricLoader.getInstance().getConfigDir().resolve("atm10spoof").resolve(safe + ".json");
		ChannelStore store = new ChannelStore(file);
		store.load();
		store.addWellKnownChannels();
		return store;
	}

	private void addWellKnownChannels() {
		Map<String, ChannelManifest.Entry> configuration = new LinkedHashMap<>();
		for (ChannelManifest.Entry entry : ChannelManifest.configuration()) {
			configuration.put(entry.id(), entry);
		}
		Map<String, ChannelManifest.Entry> play = new LinkedHashMap<>();
		for (ChannelManifest.Entry entry : ChannelManifest.play()) {
			play.put(entry.id(), entry);
		}

		java.util.Set<String> ids = new java.util.LinkedHashSet<>(configuration.keySet());
		ids.addAll(play.keySet());
		for (String id : ids) {
			ChannelManifest.Entry config = configuration.get(id);
			ChannelManifest.Entry playEntry = play.get(id);
			Channel channel = getOrCreate(id);
			if (channel.version == null || channel.version.isEmpty()) {
				channel.version = config != null ? config.version() : playEntry.version();
			}
			if (!channel.isFlowKnown()) {
				String configFlow = config != null ? config.flow() : playEntry.flow();
				String playFlow = playEntry != null ? playEntry.flow() : configFlow;
				channel.flow = configFlow;
				channel.flowKnown = true;
				if (!java.util.Objects.equals(configFlow, playFlow)) {
					channel.split = true;
					channel.altFlow = playFlow;
					channel.swapped = false;
				}
			}
		}
	}

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			List<Channel> list = GSON.fromJson(Files.readString(file), new TypeToken<List<Channel>>() {}.getType());
			if (list != null) {
				for (Channel c : list) {
					if (c != null && c.id != null) {
						if (c.version == null) {
							c.version = "";
						}
						channels.put(c.id, c);
					}
				}
			}
		} catch (Exception e) {
			Atm10SpoofClient.LOGGER.error("[ATM10 Spoof] Couldn't read {}, starting fresh", file, e);
		}
	}

	public void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(new ArrayList<>(channels.values())));
		} catch (IOException e) {
			Atm10SpoofClient.LOGGER.error("[ATM10 Spoof] Couldn't save {}", file, e);
		}
	}

	public Channel getOrCreate(String id) {
		return channels.computeIfAbsent(id, Channel::new);
	}

	public Iterable<Channel> all() {
		return channels.values();
	}

	public int size() {
		return channels.size();
	}

	public Path file() {
		return file;
	}
}
