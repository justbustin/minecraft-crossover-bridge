package dev.ermc.bridge.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ermc.bridge.client.Overlay;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overlay mode: clear to fully transparent black (the frame is effectively premultiplied
 * alpha, which is what macOS composites) and disable fog, so distant blocks stay solid
 * instead of fading into a sky color that isn't there.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "setupColor", at = @At("TAIL"))
	private static void erbridge$transparentClear(Camera camera, float partialTick, ClientLevel level, int renderDistance,
												   float darken, CallbackInfo ci) {
		if (Overlay.active()) {
			RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
		}
	}

	@Inject(method = "setupNoFog", at = @At("TAIL"))
	private static void erbridge$transparentClearNoFog(CallbackInfo ci) {
		if (Overlay.active()) {
			RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
		}
	}

	@Inject(method = "setupFog", at = @At("TAIL"))
	private static void erbridge$noFog(Camera camera, FogRenderer.FogMode mode, float farPlane, boolean thickFog,
										float partialTick, CallbackInfo ci) {
		if (Overlay.active()) {
			RenderSystem.setShaderFogStart(Float.MAX_VALUE);
			RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
		}
	}
}
