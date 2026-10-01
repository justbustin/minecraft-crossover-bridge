package dev.ermc.bridge.mixin;

import dev.ermc.bridge.entity.ErEnemyTargetGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hostile mobs, golems and tamed wolves fight Elden Ring enemies ({@link ErEnemyTargetGoal}).
 * The goal goes in after the mob's own goals, which {@code Mob}'s constructor registers on the
 * server side.
 */
@Mixin(Mob.class)
public abstract class MobMixin {
	@Shadow
	@Final
	protected GoalSelector targetSelector;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void erbridge$fightEldenRingEnemies(EntityType<? extends Mob> type, Level level, CallbackInfo ci) {
		if (level != null && !level.isClientSide) {
			ErEnemyTargetGoal.addTo((Mob) (Object) this, this.targetSelector);
		}
	}
}
