package dev.mhwmc.bridge.mixin;

import dev.mhwmc.bridge.entity.MhwEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a monster's proxy whether the player's swing at it is a Minecraft critical hit, so the
 * damage goes to MHW with DAMAGE_CRITICAL (MHW's critical damage number). {@code Player.attack}
 * decides that in a local variable just before calling {@code hurt}, from state that is still
 * unchanged here; the x1.5 itself, the crit particles and the sound are vanilla's.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void mhwbridge$noteCritical(Entity target, CallbackInfo ci) {
		if (target instanceof MhwEntity proxy) {
			proxy.noteAttack(MhwEntity.isCriticalSwing((Player) (Object) this));
		}
	}

	@Inject(method = "attack", at = @At("RETURN"))
	private void mhwbridge$endAttack(Entity target, CallbackInfo ci) {
		if (target instanceof MhwEntity proxy) {
			proxy.noteAttack(false);
		}
	}
}
