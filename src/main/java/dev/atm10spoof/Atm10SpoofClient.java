package dev.atm10spoof;

import dev.atm10spoof.handshake.NeoHandshake;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Atm10SpoofClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("atm10spoof");

	private static final AtomicLong DROPPED = new AtomicLong();

	/**
	 * Set just before a forced configuration finish: the server's registry data usually references
	 * modded registries this client doesn't have, so use the client's own registries instead.
	 */
	public static final java.util.concurrent.atomic.AtomicBoolean SKIP_NETWORK_REGISTRIES =
		new java.util.concurrent.atomic.AtomicBoolean();

	/** Set while we drive a configuration finish ourselves, so the pipeline handlers can be restored. */
	public static final java.util.concurrent.atomic.AtomicBoolean FORCE_PIPELINE_TRANSITION =
		new java.util.concurrent.atomic.AtomicBoolean();

	private static volatile long lastConfigurationPacket = System.currentTimeMillis();

	/** Keep-alives don't count: a stalled server keeps sending those. */
	public static void noteConfigurationPacket() {
		lastConfigurationPacket = System.currentTimeMillis();
	}

	public static long configurationIdleMillis() {
		return System.currentTimeMillis() - lastConfigurationPacket;
	}

	@Override
	public void onInitializeClient() {
		NeoHandshake.register();
		dev.atm10spoof.handshake.FmlHandshake.register();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> forceViaFabricPlusVersion());
		LOGGER.info("[ATM10 Spoof] Loaded");
	}

	/**
	 * Quick-connect doesn't always run ViaFabricPlus's version picker, so the client would speak
	 * the native protocol and the 1.21.1 server rejects it. Set the target version explicitly.
	 * ViaFabricPlus is optional, so this is all reflective.
	 */
	private static void forceViaFabricPlusVersion() {
		forceViaFabricPlusVersion(System.getProperty("atm10spoof.vfpVersion", "1.21.1"));
	}

	public static void forceViaFabricPlusVersion(String wanted) {
		dev.atm10spoof.handshake.ChannelManifest.selectTarget(wanted);
		try {
			Class<?> vfp = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
			Object api = vfp.getMethod("api").invoke(null);
			Class<?> protocolVersion = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
			Object version = protocolVersion.getMethod("getClosest", String.class).invoke(null, wanted);
			api.getClass().getMethod("setTargetVersion", protocolVersion).invoke(api, version);
			LOGGER.info("[ATM10 Spoof] ViaFabricPlus target version set to {}", wanted);
		} catch (ClassNotFoundException e) {
			LOGGER.warn("[ATM10 Spoof] ViaFabricPlus isn't installed; install it and set the server version to {}", wanted);
		} catch (Throwable t) {
			LOGGER.warn("[ATM10 Spoof] Couldn't set the ViaFabricPlus target version", t);
		}
	}

	/** Logs a dropped packet: the first 200 in full, then every 500th so the log stays readable. */
	public static void logDropped(String where, Throwable cause) {
		long n = DROPPED.incrementAndGet();
		if (n <= 200) {
			LOGGER.warn("[ATM10 Spoof] Dropped unreadable packet #{} ({}): {}", n, where, rootMessage(cause));
		} else if (n % 500 == 0) {
			LOGGER.warn("[ATM10 Spoof] {} unreadable packets dropped so far (latest, {}): {}", n, where, rootMessage(cause));
		}
	}

	private static String rootMessage(Throwable t) {
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root == t ? String.valueOf(t) : t.getClass().getSimpleName() + " <- " + root;
	}
}
