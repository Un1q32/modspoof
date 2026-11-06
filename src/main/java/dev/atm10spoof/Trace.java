package dev.atm10spoof;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;

/** Logs every packet in the login/configuration phase so we can see where a join stops. */
public final class Trace {
	private Trace() {}

	public static void packet(String direction, Packet<?> packet) {
		String what;
		if (packet instanceof ClientboundCustomPayloadPacket p) {
			what = "custom payload " + p.payload().type().id();
		} else if (packet instanceof ServerboundCustomPayloadPacket p) {
			what = "custom payload " + p.payload().type().id();
		} else {
			what = String.valueOf(packet.type());
			if (packet instanceof ClientboundLoginDisconnectPacket p) {
				Atm10SpoofClient.LOGGER.warn("[ATM10 Spoof] Login refused: {}", p.reason());
			}
		}
		Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] {} {}", direction, what);
	}
}
