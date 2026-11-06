package dev.atm10spoof.mixin;

import dev.atm10spoof.Trace;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {
	@Shadow @Final private ProtocolInfo<?> protocolInfo;

	@Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("HEAD"))
	private void atm10spoof$trace(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
		ConnectionProtocol protocol = this.protocolInfo.id();
		if (this.protocolInfo.flow() == PacketFlow.SERVERBOUND
			&& (protocol == ConnectionProtocol.CONFIGURATION || protocol == ConnectionProtocol.LOGIN)) {
			Trace.packet("-> " + protocol.id(), packet);
		}
	}
}
