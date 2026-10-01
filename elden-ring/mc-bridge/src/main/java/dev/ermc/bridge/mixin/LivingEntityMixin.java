package dev.ermc.bridge.mixin;

import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Line of sight from Minecraft mobs to Elden Ring characters' proxies. Mobs need it to pick a
 * target, swing, shoot and light a creeper fuse. Minecraft checks one eye-to-eye line; for a
 * proxy, a few points of the body count too, and terrain right at the body doesn't block
 * ({@link ErEntity#visibleFrom}). A proxy that can't be fought (an NPC, a character that died)
 * is never in sight, so a creeper stops its fuse when its target dies.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "hasLineOfSight", at = @At("RETURN"), cancellable = true)
	private void erbridge$seeProxyBody(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof ErEntity proxy) {
			if (!proxy.isTargetableEnemy()) {
				cir.setReturnValue(false);
			} else if (!cir.getReturnValueZ()) {
				cir.setReturnValue(proxy.visibleFrom((LivingEntity) (Object) this));
			}
		}
	}
}
