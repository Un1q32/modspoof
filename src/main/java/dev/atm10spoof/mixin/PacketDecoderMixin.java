package dev.atm10spoof.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.atm10spoof.Atm10SpoofClient;
import dev.atm10spoof.Trace;
import net.minecraft.network.protocol.Packet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.SkipPacketDecoderException;
import net.minecraft.network.SkipPacketException;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * If a clientbound packet can't be decoded (modded item data, modded entity, too-big payload...),
 * throw away the rest of its bytes and turn the error into vanilla's "skip this packet" exception,
 * instead of disconnecting. Packets are length-framed, so the stream stays in sync.
 */
@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin {
	@Shadow @Final private ProtocolInfo<?> protocolInfo;

	@WrapMethod(method = "decode")
	private void atm10spoof$skipBrokenPackets(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, Operation<Void> original) throws Exception {
		int start = input.readerIndex();
		int before = out.size();
		try {
			original.call(ctx, input, out);
			ConnectionProtocol protocol = this.protocolInfo.id();
			if (this.protocolInfo.flow() == PacketFlow.CLIENTBOUND) {
				for (int i = before; i < out.size(); i++) {
					if (out.get(i) instanceof Packet<?> packet) {
						if (protocol == ConnectionProtocol.CONFIGURATION && !(packet instanceof ClientboundKeepAlivePacket)) {
							Atm10SpoofClient.noteConfigurationPacket();
						}
						if (protocol == ConnectionProtocol.CONFIGURATION || protocol == ConnectionProtocol.LOGIN) {
							Trace.packet("<- " + protocol.id(), packet);
						}
					}
				}
			}
		} catch (Exception e) {
			ConnectionProtocol protocol = this.protocolInfo.id();
			boolean ours = this.protocolInfo.flow() == PacketFlow.CLIENTBOUND
				&& (protocol == ConnectionProtocol.PLAY || protocol == ConnectionProtocol.CONFIGURATION);
			if (!ours) {
				throw e;
			}
			int packetId = peekVarInt(input, start);
			input.skipBytes(input.readableBytes());
			if (e instanceof SkipPacketException) {
				throw e;
			}
			Atm10SpoofClient.logDropped(protocol.id() + " packet 0x" + Integer.toHexString(packetId), e);
			throw new SkipPacketDecoderException(e);
		}
	}

	private static int peekVarInt(ByteBuf buf, int index) {
		int value = 0;
		for (int i = 0; i < 5 && index + i < buf.writerIndex(); i++) {
			byte b = buf.getByte(index + i);
			value |= (b & 0x7F) << (i * 7);
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		return -1;
	}
}
