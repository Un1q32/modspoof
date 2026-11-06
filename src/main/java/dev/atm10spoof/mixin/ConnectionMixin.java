package dev.atm10spoof.mixin;

import dev.atm10spoof.Atm10SpoofClient;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.SkipPacketException;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ViaFabricPlus translates packets before vanilla decodes them. When its translation fails on modded
 * data, the error lands here as a DecoderException. On the client, during configuration and play,
 * drop that packet instead of disconnecting.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
	@Shadow private volatile PacketListener packetListener;
	@Shadow @Final private Channel channel;

	@Shadow public abstract PacketFlow getReceiving();

	/**
	 * Fabric normally removes its packet merger/splitter when it sees the server's finish packet.
	 * We can finish without that packet, so remove the old ones and put the protocol-swap handlers
	 * back before vanilla writes its decoder/encoder swap tasks into the channel.
	 */
	@Inject(method = "setupInboundProtocol", at = @At("HEAD"))
	private void atm10spoof$prepareInboundTransition(ProtocolInfo<?> protocol, PacketListener listener, CallbackInfo ci) {
		if (!Atm10SpoofClient.FORCE_PIPELINE_TRANSITION.get()) {
			return;
		}
		ChannelPipeline pipeline = this.channel.pipeline();
		if (pipeline.get("fabric:merger") != null) {
			pipeline.remove("fabric:merger");
		}
		if (pipeline.get("inbound_config") == null && pipeline.get("decoder") != null) {
			pipeline.replace("decoder", "inbound_config", new UnconfiguredPipelineHandler.Inbound());
		}
	}

	@Inject(method = "setupOutboundProtocol", at = @At("HEAD"))
	private void atm10spoof$prepareOutboundTransition(ProtocolInfo<?> protocol, CallbackInfo ci) {
		if (!Atm10SpoofClient.FORCE_PIPELINE_TRANSITION.get()) {
			return;
		}
		ChannelPipeline pipeline = this.channel.pipeline();
		if (pipeline.get("fabric:splitter") != null) {
			pipeline.remove("fabric:splitter");
		}
		// Don't add an outbound_config handler here: Connection.send(finish) runs on the event loop
		// after this method, and ProtocolSwapHandler adds that handler itself. Adding one now makes
		// that fail with "Duplicate handler name: outbound_config".
	}

	@Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
	private void atm10spoof$ignoreDecodeErrors(ChannelHandlerContext ctx, Throwable cause, CallbackInfo ci) {
		if (cause instanceof SkipPacketException || !(cause instanceof DecoderException)) {
			return; // vanilla already skips SkipPacketException; anything else (timeouts, etc.) stays fatal
		}
		if (this.getReceiving() != PacketFlow.CLIENTBOUND) {
			return;
		}
		PacketListener listener = this.packetListener;
		if (listener == null) {
			return;
		}
		ConnectionProtocol protocol = listener.protocol();
		if (protocol == ConnectionProtocol.PLAY || protocol == ConnectionProtocol.CONFIGURATION) {
			Atm10SpoofClient.logDropped(protocol.id() + " (translation)", cause);
			ci.cancel();
		}
	}
}
