package dev.ermc.bridge.entity;

import dev.ermc.bridge.ErBridgeMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ErBridgeEntities {
	private ErBridgeEntities() {
	}

	public static final EntityType<ErEntity> ER_ENTITY = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		ResourceLocation.fromNamespaceAndPath(ErBridgeMod.MOD_ID, "er_entity"),
		EntityType.Builder.<ErEntity>of(ErEntity::new, MobCategory.MISC)
			.sized(1.0F, 1.0F)
			.fireImmune()
			.noSave()
			.noSummon()
			.clientTrackingRange(16)
			.updateInterval(1)
			.build("er_entity"));

	public static void init() {
		// Every LivingEntity type needs default attributes (on both sides, before any is created).
		FabricDefaultAttributeRegistry.register(ER_ENTITY, ErEntity.createAttributes());
	}
}
