package dev.mhwmc.bridge.client.mixin;

import dev.mhwmc.bridge.client.FramePassthrough;
import dev.mhwmc.bridge.client.Overlay;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void mhwbridge$frame(boolean tick, CallbackInfo ci) {
		Overlay.onFrame((Minecraft) (Object) this);
	}

	/** While frames go to MHW, rendering faster than MHW (~60 fps) only wastes readbacks. */
	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void mhwbridge$capFps(CallbackInfoReturnable<Integer> cir) {
		if (FramePassthrough.wanted() && cir.getReturnValue() > 60) {
			cir.setReturnValue(60);
		}
	}
}
