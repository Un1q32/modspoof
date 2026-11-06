package dev.atm10spoof.handshake;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import dev.atm10spoof.Atm10SpoofClient;

/**
 * Reads NeoForge's "modded network setup failed" reasons and updates the channel list so the next
 * connection attempt gets further. The reasons are text components (sent as NBT) using these keys:
 *
 *   neoforge.network.negotiation.failure.mod                (mod name, reason)        - wrapper
 *   neoforge.network.negotiation.failure.missing.server.client                       - server needs a channel we lack
 *   neoforge.network.negotiation.failure.flow.client.missing (server flow)
 *   neoforge.network.negotiation.failure.flow.server.missing (client flow)
 *   neoforge.network.negotiation.failure.flow.client.mismatch (server flow, client flow)
 *   neoforge.network.negotiation.failure.flow.server.mismatch (client flow, server flow)
 *   neoforge.network.negotiation.failure.version.mismatch    (left version, right version)
 */
public final class FailureParser {
	private static final String PREFIX = "neoforge.network.negotiation.failure.";

	private FailureParser() {}

	/** A translatable text: key plus arguments (each a String or another Translatable). */
	record Translatable(String key, List<Object> args) {}

	/** Returns true if the store changed. */
	public static boolean apply(ChannelStore store, String channelId, Tag reason) {
		Object parsed = parse(reason);
		if (!(parsed instanceof Translatable t)) {
			Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Unrecognised failure for {}: {}", channelId, reason);
			return false;
		}
		return applyTranslatable(store, channelId, t);
	}

	private static boolean applyTranslatable(ChannelStore store, String channelId, Translatable t) {
		String key = t.key();
		if (key.equals(PREFIX + "mod")) {
			if (t.args().size() >= 2 && t.args().get(1) instanceof Translatable inner) {
				return applyTranslatable(store, channelId, inner);
			}
			return false;
		}

		ChannelStore.Channel channel = store.getOrCreate(channelId);
		switch (key.substring(Math.min(key.length(), PREFIX.length()))) {
			case "missing.server.client" -> {
				// Channel now exists in the store (created above).
				return true;
			}
			case "missing.client.server" -> {
				// We sent a channel the server doesn't know. Shouldn't happen since ours are optional.
				return false;
			}
			case "flow.client.missing", "flow.client.mismatch" -> {
				return learnFlow(channel, flow(arg(t, 0)));
			}
			case "flow.server.missing" -> {
				return learnFlow(channel, null);
			}
			case "flow.server.mismatch" -> {
				return learnFlow(channel, flow(arg(t, 1)));
			}
			case "version.mismatch" -> {
				String left = arg(t, 0);
				String right = arg(t, 1);
				// One side is ours; take the other.
				String serverVersion = channel.version.equals(left) ? right : left;
				if (serverVersion == null || serverVersion.equals(channel.version)) {
					return false;
				}
				channel.version = serverVersion;
				return true;
			}
			default -> {
				Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Unknown failure key {} for {} (args {})", key, channelId, t.args());
				return false;
			}
		}
	}

	private static boolean learnFlow(ChannelStore.Channel channel, String serverFlow) {
		if (channel.split) {
			// Still failing with both flows known: we have them on the wrong phases, swap.
			channel.swapped = !channel.swapped;
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] {} uses different flows per phase, swapping (config={}, play={})",
				channel.id, channel.configFlow(), channel.playFlow());
			return true;
		}
		if (channel.isFlowKnown() && !java.util.Objects.equals(channel.flow, serverFlow)) {
			// Server wants a different flow than one it already taught us: registered in both phases.
			channel.split = true;
			channel.altFlow = serverFlow;
			channel.swapped = false;
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] {} uses different flows per phase ({} and {})",
				channel.id, channel.flow, serverFlow);
			return true;
		}
		channel.flow = serverFlow;
		channel.flowKnown = true;
		return true;
	}

	private static String arg(Translatable t, int index) {
		if (index >= t.args().size()) {
			return null;
		}
		Object o = t.args().get(index);
		return o instanceof String s ? s : String.valueOf(o);
	}

	private static String flow(String raw) {
		if (raw == null) {
			return null;
		}
		String lower = raw.toLowerCase(Locale.ROOT);
		if (lower.contains("clientbound")) {
			return "clientbound";
		}
		if (lower.contains("serverbound")) {
			return "serverbound";
		}
		return null;
	}

	/** Turns a text component NBT tag into a String or Translatable. */
	static Object parse(Tag tag) {
		if (tag instanceof StringTag(String value)) {
			return value;
		}
		if (tag instanceof ListTag list) {
			return list.isEmpty() ? "" : parse(list.get(0));
		}
		if (tag instanceof CompoundTag compound) {
			if (compound.contains("translate")) {
				List<Object> args = new ArrayList<>();
				Tag with = compound.get("with");
				if (with instanceof ListTag withList) {
					for (Tag arg : withList) {
						args.add(parse(arg));
					}
				}
				return new Translatable(compound.getStringOr("translate", ""), args);
			}
			if (compound.contains("text")) {
				return compound.getStringOr("text", "");
			}
			if (compound.contains("")) {
				// Mixed-type NBT lists wrap each element as {"": value}
				return parse(compound.get(""));
			}
		}
		return tag == null ? "" : tag.toString();
	}
}
