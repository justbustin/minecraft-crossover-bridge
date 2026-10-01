package dev.ermc.bridge.client.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ermc.bridge.client.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GUI drawing uses ONE/ZERO for the alpha channel, which would punch translucent holes in
 * Minecraft's layer wherever a semi-transparent HUD element overlaps a block. Use proper
 * "over" alpha compositing instead while overlaying.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
	@Inject(method = "defaultBlendFunc", at = @At("HEAD"), cancellable = true)
	private static void erbridge$overAlpha(CallbackInfo ci) {
		if (Overlay.active()) {
			RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
				GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
			ci.cancel();
		}
	}
}
