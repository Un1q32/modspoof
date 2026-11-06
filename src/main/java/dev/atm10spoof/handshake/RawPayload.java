package dev.atm10spoof.handshake;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A custom payload that just carries raw bytes. NeoForge's payload classes don't exist on this
 * client, so we read and write their wire formats by hand.
 */
public record RawPayload(CustomPacketPayload.Type<RawPayload> type, byte[] data) implements CustomPacketPayload {
	public static CustomPacketPayload.Type<RawPayload> type(String namespace, String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(namespace, path));
	}

	public static StreamCodec<FriendlyByteBuf, RawPayload> codec(CustomPacketPayload.Type<RawPayload> type) {
		return CustomPacketPayload.codec(
			(payload, buf) -> buf.writeBytes(payload.data()),
			buf -> {
				byte[] bytes = new byte[buf.readableBytes()];
				buf.readBytes(bytes);
				return new RawPayload(type, bytes);
			}
		);
	}

	public static RawPayload empty(CustomPacketPayload.Type<RawPayload> type) {
		return new RawPayload(type, new byte[0]);
	}
}
