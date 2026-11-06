package dev.atm10spoof.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.atm10spoof.Atm10SpoofClient;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.tags.TagNetworkSerialization;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The server sends tags for modded datapack registries (Mekanism's chemicals, for example) that
 * don't exist on this client. Vanilla then throws "Missing registry" while finishing configuration,
 * which leaves us stuck. Keep the tags for registries we actually know about and drop the rest.
 */
@Mixin(RegistryDataCollector.class)
public abstract class RegistryDataCollectorMixin {
	@WrapMethod(method = "appendTags")
	private void atm10spoof$skipUnknownRegistryTags(
		Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags,
		Operation<Void> original
	) {
		Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> known = new HashMap<>(tags.size());
		tags.forEach((key, payload) -> {
			if (atm10spoof$isKnownRegistry(key)) {
				known.put(key, payload);
			}
		});
		if (known.size() != tags.size()) {
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Dropped tags for {} unknown registries", tags.size() - known.size());
		}
		original.call(known);
	}

	/**
	 * When the server's registry data can't be loaded (it usually references modded registries this
	 * client has never heard of), reuse the client's own registry access instead. We lose the
	 * server's custom dimensions and biomes, but the world we spawn in is still playable.
	 */
	@WrapMethod(method = "collectGameRegistries")
	private RegistryAccess.Frozen atm10spoof$skipUnloadableRegistries(
		ResourceProvider resources,
		RegistryAccess.Frozen fallback,
		boolean memoryConnection,
		Operation<RegistryAccess.Frozen> original
	) {
		if (Atm10SpoofClient.SKIP_NETWORK_REGISTRIES.getAndSet(false)) {
			Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Using local registries because the server's registry data didn't fit this client");
			return fallback;
		}
		return original.call(resources, fallback, memoryConnection);
	}

	private static boolean atm10spoof$isKnownRegistry(ResourceKey<? extends Registry<?>> key) {
		if (BuiltInRegistries.REGISTRY.containsKey(key.identifier())) {
			return true;
		}
		for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.SYNCHRONIZED_REGISTRIES) {
			if (data.key().equals(key)) {
				return true;
			}
		}
		return false;
	}
}
