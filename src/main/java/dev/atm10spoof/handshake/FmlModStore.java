package dev.atm10spoof.handshake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import dev.atm10spoof.Atm10SpoofClient;

/**
 * The mod ids/versions we claim during Forge 1.7.10's FML handshake, learned from the server's
 * "Mod rejections [...]" kick message and saved per server.
 * File: config/atm10spoof/fml-&lt;server address&gt;.json
 */
public final class FmlModStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;
	private final Map<String, String> mods = new LinkedHashMap<>();

	private FmlModStore(Path file) {
		this.file = file;
	}

	public static FmlModStore forServer(String address) {
		String safe = (address == null || address.isBlank() ? "default" : address).replaceAll("[^A-Za-z0-9._-]", "_");
		Path file = FabricLoader.getInstance().getConfigDir().resolve("atm10spoof").resolve("fml-" + safe + ".json");
		FmlModStore store = new FmlModStore(file);
		store.load();
		return store;
	}

	public Map<String, String> mods() {
		return mods;
	}

	public int size() {
		return mods.size();
	}

	public Path file() {
		return file;
	}

	/** Returns true when the entry is new or its version changed. */
	public boolean put(String modId, String version) {
		String previous = mods.put(modId, version);
		return previous == null || !previous.equals(version);
	}

	public void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			Map<String, String> loaded = GSON.fromJson(Files.readString(file), new TypeToken<Map<String, String>>() {}.getType());
			if (loaded != null) {
				loaded.forEach((id, version) -> {
					if (id != null && !id.isBlank()) {
						mods.put(id, version == null ? "" : version);
					}
				});
			}
		} catch (Exception e) {
			Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Couldn't read FML mod list from {}", file, e);
		}
	}

	public void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(mods));
		} catch (IOException e) {
			Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Couldn't save FML mod list to {}", file, e);
		}
	}
}
