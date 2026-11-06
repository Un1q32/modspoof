package dev.atm10spoof.mixin.via;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ViaVersion maps block states it doesn't know (all modded blocks) to 0, which is air. Air means you
 * fall through modded ores and get rubber-banded, since the server thinks they're solid. Map them to
 * 1 instead, which is plain stone in every modern version.
 *
 * Targets a ViaVersion class by name so this mod doesn't need ViaVersion to compile or to load.
 */
@Pseudo
@Mixin(targets = "com.viaversion.viaversion.api.data.MappingDataBase", remap = false)
public abstract class MappingDataBaseMixin {
	@Inject(method = "checkValidity(IILjava/lang/String;)I", at = @At("HEAD"), cancellable = true, remap = false)
	private void atm10spoof$unknownBlocksAreStone(int id, int mappedId, String type, CallbackInfoReturnable<Integer> cir) {
		if (mappedId == -1 && "blockstate".equals(type)) {
			cir.setReturnValue(1);
		}
	}
}
