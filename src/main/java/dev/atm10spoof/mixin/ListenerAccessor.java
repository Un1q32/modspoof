package dev.atm10spoof.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientCommonPacketListenerImpl.class)
public interface ListenerAccessor {
	@Accessor("serverData")
	ServerData atm10spoof$serverData();

	@Accessor("connection")
	Connection atm10spoof$connection();
}
