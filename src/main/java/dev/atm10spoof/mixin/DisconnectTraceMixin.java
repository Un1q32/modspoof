package dev.atm10spoof.mixin;

import dev.atm10spoof.Atm10SpoofClient;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class DisconnectTraceMixin {
	@Inject(method = "handleDisconnect", at = @At("HEAD"))
	private void atm10spoof$logKick(ClientboundDisconnectPacket packet, CallbackInfo ci) {
		Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Server sent disconnect packet: {}", packet.reason());
	}

	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void atm10spoof$logDisconnect(DisconnectionDetails details, CallbackInfo ci) {
		Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Connection closed, raw reason: {}", details.reason());
		String reason = details.reason().getString();
		dev.atm10spoof.handshake.FmlHandshake.noteDisconnectReason(reason);
		dev.atm10spoof.handshake.NeoHandshake.noteDisconnect(reason);
	}
}
