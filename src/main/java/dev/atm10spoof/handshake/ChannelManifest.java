package dev.atm10spoof.handshake;

import dev.atm10spoof.Atm10SpoofClient;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Channel names collected from real servers (protocol, id, version, flow, optional).
 * Mandatory channels are learned from the server's own errors, but optional ones are never
 * announced, so a client without the mods has to know them in advance. Sending extra optional
 * channels is harmless: the server discards the ones it doesn't have.
 *
 * 1.21.1 (ATM10 / ATM10 Sky) and 26.1 (ATM11) use different registries, so they have separate
 * manifests. The target is selected when ViaFabricPlus's target version is set.
 */
public final class ChannelManifest {
	public record Entry(String id, String version, String flow) {}

	private static final List<Entry> CONFIGURATION = new ArrayList<>();
	private static final List<Entry> PLAY = new ArrayList<>();
	private static String loadedResource;
	private static String target = "1.21.1";

	static {
		selectTarget("1.21.1");
	}

	private ChannelManifest() {}

	public static synchronized void selectTarget(String version) {
		if (version == null || version.isBlank()) {
			version = "1.21.1";
		}
		String resource = version.startsWith("26.1") ? "/atm11spoof-channels.tsv" : "/atm10spoof-channels.tsv";
		if (resource.equals(loadedResource)) {
			target = version;
			return;
		}
		target = version;
		loadedResource = resource;
		load(resource);
	}

	private static void load(String resource) {
		CONFIGURATION.clear();
		PLAY.clear();
		try (InputStream in = ChannelManifest.class.getResourceAsStream(resource)) {
			if (in == null) {
				Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Channel manifest {} is missing", resource);
				return;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split("\t");
				if (parts.length < 4) {
					continue;
				}
				Entry entry = new Entry(parts[1], parts[2], normalizeFlow(parts[3]));
				if ("CONFIGURATION".equals(parts[0])) {
					CONFIGURATION.add(entry);
				} else if ("PLAY".equals(parts[0])) {
					PLAY.add(entry);
				}
			}
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Loaded {} configuration and {} play channels from {} (target {})",
				CONFIGURATION.size(), PLAY.size(), resource, target);
		} catch (Exception e) {
			Atm10SpoofClient.LOGGER.error("[ATM10 Spoof] Couldn't read the channel manifest {}", resource, e);
		}
	}

	private static String normalizeFlow(String raw) {
		if (raw == null || raw.isBlank() || "BOTH".equalsIgnoreCase(raw)) {
			return null;
		}
		return raw.toLowerCase(Locale.ROOT);
	}

	public static synchronized List<Entry> configuration() {
		return CONFIGURATION;
	}

	public static synchronized List<Entry> play() {
		return PLAY;
	}
}
