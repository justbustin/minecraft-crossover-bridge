package dev.mhwmc.bridge.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mhwmc.bridge.client.Overlay;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MHW supplies the sky, clouds and weather; Minecraft's would paint over them. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
	private void mhwbridge$noSky(Matrix4f modelView, Matrix4f projection, float partialTick, Camera camera,
								 boolean foggy, Runnable setupFog, CallbackInfo ci) {
		if (Overlay.active()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
	private void mhwbridge$noClouds(PoseStack poseStack, Matrix4f modelView, Matrix4f projection, float partialTick,
									double camX, double camY, double camZ, CallbackInfo ci) {
		if (Overlay.active()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
	private void mhwbridge$noWeather(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ,
									 CallbackInfo ci) {
		if (Overlay.active()) {
			ci.cancel();
		}
	}
}
