package dev.atm10spoof.mixin;

import dev.atm10spoof.Atm10SpoofClient;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packets carrying modded entity data, item stacks or components often decode but then blow up in
 * their handler. Vanilla disconnects the client. Drop the packet instead so the world stays usable.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class PacketErrorMixin {
	@Inject(method = "onPacketError", at = @At("HEAD"), cancellable = true)
	private void atm10spoof$dropBrokenPacket(Packet<?> packet, Exception exception, CallbackInfo ci) {
		Atm10SpoofClient.logDropped("handler", exception);
		ci.cancel();
	}
}
