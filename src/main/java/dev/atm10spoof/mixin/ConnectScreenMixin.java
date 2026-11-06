package dev.atm10spoof.mixin;

import dev.atm10spoof.Atm10SpoofClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pick the ViaFabricPlus target version from the server's advertised version, so the same install
 * works for ATM10 (1.21.1), ATM11 (26.1.2) and GTNH (1.7.10) without command-line flags.
 * The {@code -Datm10spoof.vfpVersion=...} property overrides this.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	@Inject(method = "startConnecting", at = @At("HEAD"))
	private static void atm10spoof$chooseTarget(Screen parent, Minecraft minecraft, ServerAddress address,
			ServerData data, boolean quickPlay, TransferState transferState, CallbackInfo ci) {
		if (System.getProperty("atm10spoof.vfpVersion") != null) {
			return;
		}
		if (data == null || data.version == null) {
			return;
		}
		String version = data.version.getString();
		if (version == null || version.isBlank()) {
			return;
		}
		Atm10SpoofClient.LOGGER.info("[ATM10 Spoof] Server advertises {}; selecting ViaFabricPlus target", version);
		Atm10SpoofClient.forceViaFabricPlusVersion(version);
	}
}
