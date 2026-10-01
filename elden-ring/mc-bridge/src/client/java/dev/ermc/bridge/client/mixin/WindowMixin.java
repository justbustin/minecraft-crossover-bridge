package dev.ermc.bridge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import dev.ermc.bridge.client.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Creates Minecraft's window with a transparent framebuffer so it can overlay the host game. */
@Mixin(Window.class)
public abstract class WindowMixin {
	@WrapOperation(method = "<init>", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
	private long erbridge$createWindow(int width, int height, CharSequence title, long monitor, long share, Operation<Long> original) {
		Overlay.applyWindowHints();
		long handle = original.call(width, height, title, monitor, share);
		Overlay.onWindowCreated(handle);
		return handle;
	}
}
