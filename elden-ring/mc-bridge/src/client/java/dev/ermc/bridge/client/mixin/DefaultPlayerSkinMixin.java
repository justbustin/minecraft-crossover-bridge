package dev.ermc.bridge.client.mixin;

import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Players without a skin of their own (the bridge's offline player) look like classic Steve
 * (wide model) instead of whichever of the 18 default skins their UUID happens to pick.
 */
@Mixin(DefaultPlayerSkin.class)
public abstract class DefaultPlayerSkinMixin {
	@Shadow
	@Final
	private static PlayerSkin[] DEFAULT_SKINS;

	@Inject(method = "get(Ljava/util/UUID;)Lnet/minecraft/client/resources/PlayerSkin;", at = @At("HEAD"), cancellable = true)
	private static void erbridge$classicSteve(UUID uuid, CallbackInfoReturnable<PlayerSkin> cir) {
		for (PlayerSkin skin : DEFAULT_SKINS) {
			if (skin.model() == PlayerSkin.Model.WIDE && skin.texture().getPath().endsWith("/steve.png")) {
				cir.setReturnValue(skin);
				return;
			}
		}
	}
}
