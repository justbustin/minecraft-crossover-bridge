package dev.ermc.bridge.client.mixin;

import dev.ermc.bridge.client.Overlay;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Elden Ring decides how bright things are (the compositor relights Minecraft's pixels from
 * its frame), so Minecraft itself always renders with full daylight. The bridge world is kept
 * at midnight so undead mobs don't burn; without this they would also look like midnight.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "getSkyDarken", at = @At("HEAD"), cancellable = true)
	private void erbridge$daylight(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (Overlay.active()) {
			cir.setReturnValue(1.0F);
		}
	}
}
