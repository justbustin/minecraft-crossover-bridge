package dev.mhwmc.bridge.entity;

import dev.mhwmc.bridge.MhwBridgeMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class MhwBridgeEntities {
	private MhwBridgeEntities() {
	}

	public static final EntityType<MhwEntity> MHW_ENTITY = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		ResourceLocation.fromNamespaceAndPath(MhwBridgeMod.MOD_ID, "mhw_entity"),
		EntityType.Builder.<MhwEntity>of(MhwEntity::new, MobCategory.MISC)
			.sized(1.0F, 1.0F)
			.noSave()
			.noSummon()
			.clientTrackingRange(16)
			.updateInterval(1)
			.build("mhw_entity"));

	public static void init() {
		// A living entity (Minecraft's melee rules, crits, shields) needs its attributes.
		net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(MHW_ENTITY, MhwEntity.createAttributes());
	}
}
