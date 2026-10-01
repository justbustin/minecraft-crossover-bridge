package dev.mhwmc.bridge;

import dev.mhwmc.bridge.entity.CombatBridge;
import dev.mhwmc.bridge.entity.EntityBridge;
import dev.mhwmc.bridge.entity.MhwBridgeEntities;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

public class MhwBridgeMod implements ModInitializer {
	public static final String MOD_ID = "mhwbridge";

	public static final TerrainBlock TERRAIN = Registry.register(
		BuiltInRegistries.BLOCK,
		ResourceLocation.fromNamespaceAndPath(MOD_ID, "terrain"),
		new TerrainBlock(BlockBehaviour.Properties.of()
			.strength(-1.0F, 3600000.0F)
			.noLootTable()
			.noOcclusion()
			.sound(SoundType.STONE)
			.pushReaction(PushReaction.BLOCK)
			.isValidSpawn((state, level, pos, type) -> false)
			.isRedstoneConductor((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isViewBlocking((state, level, pos) -> false))
	);

	@Override
	public void onInitialize() {
		MhwBridgeEntities.init();
		ServerLifecycleEvents.SERVER_STARTED.register(TerrainManager::onServerStarted);
		ServerLifecycleEvents.SERVER_STARTED.register(LifeBridge::onServerStarted);
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(LifeBridge::onRespawn);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TerrainManager.reset();
			EntityBridge.reset();
			CombatBridge.reset();
			FireBridge.reset();
		});
		ServerTickEvents.END_SERVER_TICK.register(TerrainManager::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(EntityBridge::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(CombatBridge::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(FireBridge::onServerTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> TerrainManager.onJoin(server, handler.player));

		// MHW's ground is not breakable, even in creative mode.
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !state.is(TERRAIN));
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
			level.getBlockState(pos).is(TERRAIN) ? InteractionResult.FAIL : InteractionResult.PASS);
	}
}
