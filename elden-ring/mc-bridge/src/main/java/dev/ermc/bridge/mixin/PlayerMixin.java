package dev.ermc.bridge.mixin;

import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells an Elden Ring enemy's proxy whether the player's swing at it is a Minecraft critical
 * hit (so the damage goes out with DAMAGE_CRITICAL). {@code Player.attack} decides that in a
 * local variable just before calling {@code hurt}, from state that is still unchanged here.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void erbridge$noteCritical(Entity target, CallbackInfo ci) {
		if (target instanceof ErEntity proxy) {
			Player self = (Player) (Object) this;
			proxy.noteAttack(self, ErEntity.isCriticalSwing(self));
		}
	}

	@Inject(method = "attack", at = @At("RETURN"))
	private void erbridge$endAttack(Entity target, CallbackInfo ci) {
		if (target instanceof ErEntity proxy) {
			proxy.noteAttack(null, false);
		}
	}
}
