package dev.mhwmc.bridge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dying in Minecraft while it drives MHW. Vanilla would show the death screen and respawn Steve
 * at the void world's spawn point, and MHW's camera and hunter would follow him there. Instead:
 * <ul>
 *   <li>no death screen ({@code doImmediateRespawn}), and the inventory stays
 *   ({@code keepInventory}): items dropped in a monster's arena would be out of reach;</li>
 *   <li>after the respawn the player goes straight back to the hunter, which stayed where Steve
 *   died (the client holds MHW's camera and hunter until then, see MhwBridgeClient).</li>
 * </ul>
 * MHW's hunter itself never faints while standing in (its HP is refilled), so a Minecraft death
 * costs no cart in the quest.
 */
public final class LifeBridge {
	private LifeBridge() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("mhwbridge");

	public static void onServerStarted(MinecraftServer server) {
		if (!TerrainManager.isBridgeWorld()) {
			return;
		}
		server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(true, server);
		server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
	}

	public static void onRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		if (!TerrainManager.isBridgeWorld() || alive) {
			return;
		}
		LOG.info("{} respawned: back to the hunter", newPlayer.getName().getString());
		TerrainManager.requestRecall();
	}
}
