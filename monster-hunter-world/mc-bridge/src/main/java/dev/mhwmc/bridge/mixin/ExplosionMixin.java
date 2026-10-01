package dev.mhwmc.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mhwmc.bridge.MhwBridgeMod;
import dev.mhwmc.bridge.TerrainBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Explosions against MHW's ground. A {@link TerrainBlock} voxel holds ground only up to its
 * HEIGHT, but Minecraft's explosion rays count any block as filling its whole voxel. Primed TNT
 * resting on sloped MHW ground sits inside such a voxel, so its explosion was absorbed at the
 * first step of every ray: it broke nothing, and TNT next to it never went off. Rays now pass
 * through the empty part of a terrain voxel, and terrain itself never breaks.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
	/** Height of the current ray point; {@code explode()} walks one ray at a time on one thread. */
	@Unique
	private double mhwbridge$rayY;

	@WrapOperation(method = "explode", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"))
	private BlockPos mhwbridge$trackRay(double x, double y, double z, Operation<BlockPos> original) {
		this.mhwbridge$rayY = y;
		return original.call(x, y, z);
	}

	@WrapOperation(method = "explode", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;getBlockExplosionResistance(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;"))
	private Optional<Float> mhwbridge$terrainSurface(ExplosionDamageCalculator calculator, Explosion explosion, BlockGetter level,
		BlockPos pos, BlockState state, FluidState fluid, Operation<Optional<Float>> original) {
		if (state.is(MhwBridgeMod.TERRAIN) && this.mhwbridge$rayY - pos.getY() >= state.getValue(TerrainBlock.HEIGHT) / 16.0) {
			return Optional.empty();  // above MHW's ground in this voxel: air
		}
		return original.call(calculator, explosion, level, pos, state, fluid);
	}

	@WrapOperation(method = "explode", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;shouldBlockExplode(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;F)Z"))
	private boolean mhwbridge$terrainStays(ExplosionDamageCalculator calculator, Explosion explosion, BlockGetter level,
		BlockPos pos, BlockState state, float power, Operation<Boolean> original) {
		return !state.is(MhwBridgeMod.TERRAIN) && original.call(calculator, explosion, level, pos, state, power);
	}
}
