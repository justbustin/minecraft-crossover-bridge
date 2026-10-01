package dev.ermc.bridge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ermc.bridge.entity.ErEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

/**
 * The host game draws the monster itself, so Minecraft adds nothing visible. The debug view (F9)
 * outlines every hitbox and shows every HP tag.
 */
public class ErEntityRenderer extends EntityRenderer<ErEntity> {
	private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
	public static boolean showHitboxes = false;

	public ErEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	@Override
	public void render(ErEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
		if (showHitboxes) {
			AABB box = entity.getBoundingBox().move(-entity.getX(), -entity.getY(), -entity.getZ());
			LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(RenderType.lines()), box, 1.0F, 0.35F, 0.2F, 1.0F);
		}
		super.render(entity, yaw, partialTick, poseStack, buffers, light);
	}

	@Override
	protected boolean shouldShowName(ErEntity entity) {
		return entity.hasCustomName() && showHitboxes;
	}

	@Override
	protected void renderNameTag(ErEntity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light,
								 float partialTick) {
		Component tag = entity.maxHp() > 0
			? Component.literal(String.format("%s  %.0f / %.0f", name.getString(), entity.hp(), entity.maxHp())) : name;
		super.renderNameTag(entity, tag, poseStack, buffers, light, partialTick);
	}

	@Override
	public ResourceLocation getTextureLocation(ErEntity entity) {
		return TEXTURE;
	}
}
