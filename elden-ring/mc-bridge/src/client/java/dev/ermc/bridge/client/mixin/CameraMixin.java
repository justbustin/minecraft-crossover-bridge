package dev.ermc.bridge.client.mixin;

import dev.ermc.bridge.client.CameraSync;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin implements CameraSync.CameraAccess {
	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "setup", at = @At("TAIL"))
	private void erbridge$sync(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick,
								CallbackInfo ci) {
		CameraSync.afterCameraSetup((Camera) (Object) this);
	}

	@Override
	public void erbridge$setPosition(Vec3 pos) {
		setPosition(pos);
	}

	@Override
	public void erbridge$setRotation(float yaw, float pitch) {
		setRotation(yaw, pitch);
	}
}
