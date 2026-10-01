package dev.ermc.bridge.mixin;

import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Explosion exposure of Elden Ring characters' proxies ({@link ErEntity#explosionExposure}):
 * sampled at a fixed number of points however big the body is, and not shielded by the terrain
 * blocks right at the body. Explosions use it for both damage and knockback.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
	@Inject(method = "getSeenPercent", at = @At("HEAD"), cancellable = true)
	private static void erbridge$proxyExposure(Vec3 center, Entity entity, CallbackInfoReturnable<Float> cir) {
		if (entity instanceof ErEntity proxy) {
			cir.setReturnValue(proxy.explosionExposure(center));
		}
	}
}
