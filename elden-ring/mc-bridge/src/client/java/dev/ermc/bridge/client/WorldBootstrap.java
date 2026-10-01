package dev.ermc.bridge.client;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.ErLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * When Minecraft starts while the host game (Elden Ring) is running, skip the menus and drop
 * straight into the bridge world: an empty (void) creative world whose only terrain is the
 * host game's.
 */
public final class WorldBootstrap {
	private WorldBootstrap() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	private static final String LEVEL_ID = "er-bridge";
	private static long lastAttemptMs;
	private static long menuSinceMs;

	/**
	 * Client tick: while the host game runs, the title screen is of no use, so enter the bridge
	 * world: at once at startup, and again when the world gets closed ("Save and Quit to Title")
	 * after the title screen has been up for 3 s (at most every 30 s, in case opening fails).
	 */
	public static void tick(Minecraft mc) {
		boolean onMenu = mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen;
		if (Boolean.getBoolean("erbridge.noAutoWorld") || !onMenu) {
			menuSinceMs = 0;
			return;
		}
		long now = System.currentTimeMillis();
		if (menuSinceMs == 0) {
			menuSinceMs = now;
		}
		boolean first = lastAttemptMs == 0;
		if (!ErLink.get().alive() || (!first && (now - menuSinceMs < 3000 || now - lastAttemptMs < 30000))) {
			return;
		}
		lastAttemptMs = now;
		if (mc.options.onboardAccessibility) {
			mc.options.onboardAccessibility = false;
			mc.options.save();
		}
		mc.execute(() -> {
			if (mc.getLevelSource().levelExists(LEVEL_ID)) {
				LOG.info("Opening bridge world");
				mc.createWorldOpenFlows().openWorld(LEVEL_ID, () -> mc.setScreen(new TitleScreen()));
			} else {
				LOG.info("Creating bridge world");
				create(mc);
			}
		});
	}

	private static void create(Minecraft mc) {
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		LevelSettings settings = new LevelSettings(TerrainManager.BRIDGE_LEVEL_NAME, GameType.CREATIVE, false,
			Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
		WorldOptions options = new WorldOptions(0L, false, false);
		mc.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, options, registries -> {
			HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
			HolderSet<StructureSet> noStructures = HolderSet.direct();
			FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.of(noStructures), biomes.getOrThrow(Biomes.THE_VOID), List.of());
			flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));
			flat.updateLayers();
			WorldDimensions dims = WorldPresets.createNormalWorldDimensions(registries);
			return dims.replaceOverworldGenerator(registries, new FlatLevelSource(flat));
		}, new TitleScreen());
	}
}
