package dev.ermc.bridge;

import dev.ermc.bridge.link.ErLink;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One life shared by both games.
 *
 * <ul>
 *   <li>Minecraft's player dies (void, lava, mobs, an Elden Ring hit that took the last hearts...):
 *   the DLL kills the standing-in Tarnished with the game's own death ({@code H_MC_DEATHS}).</li>
 *   <li>The standing-in Tarnished dies in Elden Ring (it never dies of damage, but falling below
 *   the map does it): Minecraft's player dies too ({@code H_HOST_DEATHS}, damage type
 *   {@code erbridge:host_death}, which even creative mode doesn't survive).</li>
 *   <li>Respawn: Elden Ring's "YOU DIED" is the death screen, so Minecraft respawns at once
 *   ({@code doImmediateRespawn}) and draws nothing until Elden Ring has put the Tarnished at the
 *   last Site of Grace; then the player is moved there ({@link TerrainManager#requestRecall()}).</li>
 * </ul>
 */
public final class LifeBridge {
	private LifeBridge() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	/** "Steve died in the Lands Between": the Tarnished died, so Minecraft's player does too. */
	public static final ResourceKey<DamageType> HOST_DEATH = ResourceKey.create(Registries.DAMAGE_TYPE,
		ResourceLocation.fromNamespaceAndPath("erbridge", "host_death"));

	private static int lastHostDeaths = Integer.MIN_VALUE;

	public static void onServerStarted(MinecraftServer server) {
		lastHostDeaths = Integer.MIN_VALUE;
		if (!TerrainManager.isBridgeWorld()) {
			return;
		}
		server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(true, server);
	}

	public static void onServerTick(MinecraftServer server) {
		if (!TerrainManager.isBridgeWorld()) {
			return;
		}
		ErLink link = ErLink.get();
		if (!link.alive()) {
			return;
		}
		int deaths = link.hostDeaths();
		if (lastHostDeaths == Integer.MIN_VALUE) {
			lastHostDeaths = deaths;  // count from now on
			return;
		}
		if (deaths == lastHostDeaths) {
			return;
		}
		lastHostDeaths = deaths;
		DamageSource source = new DamageSource(server.overworld().registryAccess()
			.registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(HOST_DEATH));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isAlive()) {
				LOG.info("The Tarnished died in Elden Ring -> {} dies too", player.getName().getString());
				player.hurt(source, Float.MAX_VALUE);
			}
		}
	}

	/** Any Minecraft player death except the one we caused above. */
	public static void onDeath(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer player) || !TerrainManager.isBridgeWorld() || source.is(HOST_DEATH)) {
			return;
		}
		ErLink.get().bumpMcDeaths();
		LOG.info("{} died ({}) -> the Tarnished dies too if it was standing in", player.getName().getString(),
			source.getMsgId());
	}

	public static void onRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		if (!alive && TerrainManager.isBridgeWorld()) {
			TerrainManager.onRespawn(newPlayer);
		}
	}
}
