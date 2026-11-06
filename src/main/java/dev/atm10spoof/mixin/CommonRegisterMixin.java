package dev.atm10spoof.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.atm10spoof.Atm10SpoofClient;
import dev.atm10spoof.handshake.NeoHandshake;
import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric answers c:register itself with its own channels. Replace that one answer with a larger one
 * containing every channel we know about. NeoForge uses the list as its "allowed to send" set, which
 * lets mods that skip the optional-channel checks finish the join.
 */
@Mixin(targets = "net.fabricmc.fabric.impl.networking.client.ClientConfigurationNetworkAddon", remap = false)
public abstract class CommonRegisterMixin {
	private static final Set<Identifier> SERVER_COMMON_CHANNELS = ConcurrentHashMap.newKeySet();

	@Inject(method = "handle", at = @At("HEAD"), remap = false)
	private void atm10spoof$rememberServerChannels(CustomPacketPayload payload, CallbackInfoReturnable<Boolean> cir) {
		if (payload instanceof CommonRegisterPayload register && CommonRegisterPayload.PLAY_PROTOCOL.equals(register.protocol())) {
			SERVER_COMMON_CHANNELS.clear();
			SERVER_COMMON_CHANNELS.addAll(register.channels());
		}
	}

	@WrapMethod(method = "createPacket", remap = false)
	private Packet<?> atm10spoof$expandCommonRegister(CustomPacketPayload payload, Operation<Packet<?>> original) {
		if (payload instanceof CommonRegisterPayload register && CommonRegisterPayload.PLAY_PROTOCOL.equals(register.protocol())) {
			Set<Identifier> combined = new HashSet<>(SERVER_COMMON_CHANNELS);
			combined.addAll(register.channels());
			Set<Identifier> channels = NeoHandshake.expandChannels(combined);
			Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Telling the server we can receive {} play channels", channels.size());
			return original.call(new CommonRegisterPayload(register.version(), register.protocol(), channels));
		}
		return original.call(payload);
	}
}
