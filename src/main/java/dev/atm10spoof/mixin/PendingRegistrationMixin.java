package dev.atm10spoof.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Decoder;
import dev.atm10spoof.Atm10SpoofClient;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Server-sent registry entries (biomes, enchantments, damage types...) from mods may reference
 * things this client doesn't have, which normally aborts the whole login. Instead:
 *  1. accept a partial parse if the codec can give one, else
 *  2. load another entry of the same registry that did parse, under the broken entry's name.
 * The entry keeps its slot, so the numeric IDs the server uses (e.g. biomes in chunks) still line up.
 */
@Mixin(targets = "net.minecraft.resources.RegistryLoadTask$PendingRegistration")
public abstract class PendingRegistrationMixin {
	@Unique
	private static final Map<Identifier, Tag> atm10spoof$lastGood = new ConcurrentHashMap<>();

	@WrapMethod(method = "loadFromNetwork")
	private static Either<Object, Exception> atm10spoof$lenient(
		Decoder<Object> decoder, RegistryOps<Tag> ops, ResourceKey<Object> key, Tag contents, Operation<Either<Object, Exception>> original
	) {
		Either<Object, Exception> result = original.call(decoder, ops, key, contents);
		if (result.left().isPresent()) {
			atm10spoof$lastGood.put(key.registry(), contents);
			return result;
		}

		try {
			Optional<Object> partial = decoder.parse(ops, contents).resultOrPartial();
			if (partial.isPresent()) {
				Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Registry entry {} only partly loaded", key.identifier());
				return Either.left(partial.get());
			}
		} catch (Exception ignored) {
		}

		Tag fallback = atm10spoof$lastGood.get(key.registry());
		if (fallback != null) {
			try {
				Either<Object, Exception> substitute = original.call(decoder, ops, key, fallback);
				if (substitute.left().isPresent()) {
					Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Registry entry {} couldn't load; using a stand-in", key.identifier());
					return substitute;
				}
			} catch (Exception ignored) {
			}
		}

		Atm10SpoofClient.LOGGER.error("[ATM10 Spoof] Registry entry {} couldn't load and has no stand-in", key.identifier(), result.right().get());
		return result;
	}
}
