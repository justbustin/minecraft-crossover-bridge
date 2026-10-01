package dev.mhwmc.bridge.client;

import dev.mhwmc.bridge.CoordMap;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Development helper: executes commands written to /tmp/mhwmc/mc_cmd.txt (one per line), so
 * the bridge can be tested from a terminal while the game is running. Results go to the log
 * with a "[devcmd]" prefix.
 *
 * <pre>
 *   cam first|third|front      look &lt;yaw&gt; &lt;pitch&gt;      run &lt;minecraft command&gt;
 *   use | attack               screenshot &lt;name&gt;         status
 *   save | quit                blocks &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;r&gt;   (what the player built near a point)
 * </pre>
 */
public final class DevCommands {
	private DevCommands() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("mhwbridge");
	private static final Path FILE = Path.of("/tmp/mhwmc/mc_cmd.txt");

	public static void tick(Minecraft mc) {
		if (!Files.exists(FILE)) {
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(FILE);
			Files.delete(FILE);
		} catch (IOException e) {
			return;
		}
		for (String line : lines) {
			line = line.strip();
			if (!line.isEmpty()) {
				try {
					run(mc, line);
				} catch (RuntimeException e) {
					LOG.warn("[devcmd] {} failed: {}", line, e.toString());
				}
			}
		}
	}

	private static void run(Minecraft mc, String line) {
		String[] a = line.split("\\s+", 2);
		String arg = a.length > 1 ? a[1] : "";
		switch (a[0]) {
			case "cam" -> mc.options.setCameraType(switch (arg) {
				case "third" -> CameraType.THIRD_PERSON_BACK;
				case "front" -> CameraType.THIRD_PERSON_FRONT;
				default -> CameraType.FIRST_PERSON;
			});
			case "look" -> {
				String[] v = arg.split("\\s+");
				if (mc.player != null) {
					mc.player.setYRot(Float.parseFloat(v[0]));
					mc.player.setXRot(Float.parseFloat(v[1]));
				}
			}
			case "run" -> {
				if (mc.player != null) {
					mc.player.connection.sendCommand(arg.startsWith("/") ? arg.substring(1) : arg);
				}
			}
			case "use" -> KeyMapping.click(mc.options.keyUse.getDefaultKey());
			case "attack" -> KeyMapping.click(mc.options.keyAttack.getDefaultKey());
			case "screenshot" -> Screenshot.grab(mc.gameDirectory, (arg.isEmpty() ? "devcmd" : arg) + ".png",
				mc.getMainRenderTarget(), msg -> LOG.info("[devcmd] {}", msg.getString()));
			case "status" -> status(mc);
			case "blocks" -> blocks(mc, arg);
			case "quit" -> mc.stop();  // saves the world, like the Quit button
			case "save" -> {
				// Save the world now without pausing or quitting (single-player has no /save-all).
				var server = mc.getSingleplayerServer();
				if (server != null) {
					server.execute(() -> LOG.info("[devcmd] world saved: {}", server.saveEverything(false, true, true)));
				}
			}
			case "terrain" -> {
				String[] v = arg.split("\\s+");
				switch (v[0]) {
					case "filter" -> dev.mhwmc.bridge.TerrainManager.setRayFilter(v.length > 1 && v[1].equals("off") ? null
						: new int[] {Integer.decode(v[1]), Integer.decode(v[2]), Integer.decode(v[3])});
					case "wallignore" -> dev.mhwmc.bridge.TerrainManager.setWallIgnoreMask(Integer.decode(v[1]));
					case "reset" -> dev.mhwmc.bridge.TerrainManager.requestReset();
					default -> { }
				}
				LOG.info("[devcmd] terrain {}", dev.mhwmc.bridge.TerrainManager.describeSettings());
			}
			case "switch" -> {
				if (arg.equals("mhw")) {
					Overlay.switchToMhw(mc);
				} else {
					Overlay.switchToMc(mc);
				}
			}
			case "pt" -> {
				String[] v = arg.split("\\s+");
				switch (v[0]) {
					case "on" -> { if (!FramePassthrough.enabled()) FramePassthrough.toggle(); }
					case "off" -> { if (FramePassthrough.enabled()) FramePassthrough.toggle(); }
					case "lag" -> CameraSync.poseLag = Integer.parseInt(v[1]);
					case "depth" -> CameraSync.depthIndex = Integer.parseInt(v[1]);
					case "nodepth" -> CameraSync.noDepthTest = !CameraSync.noDepthTest;
					case "debug" -> CameraSync.debugDepth = !CameraSync.debugDepth;
					default -> LOG.info("[devcmd] pt on|off|lag N|depth N|nodepth|debug");
				}
				LOG.info("[devcmd] passthrough {} wanted {} activeInMhw {} lag {} depthIndex {} noDepth {} debug {}",
					FramePassthrough.enabled(), FramePassthrough.wanted(), FramePassthrough.activeInMhw(),
					CameraSync.poseLag, CameraSync.depthIndex, CameraSync.noDepthTest, CameraSync.debugDepth);
			}
			default -> LOG.info("[devcmd] unknown command: {}", line);
		}
		LOG.info("[devcmd] ok: {}", line);
	}

	/** Counts the blocks within r of (x, y, z), MHW's terrain and air left out, e.g. before a TNT test. */
	private static void blocks(Minecraft mc, String arg) {
		String[] v = arg.split("\\s+");
		var server = mc.getSingleplayerServer();
		if (server == null || v.length < 4) {
			LOG.info("[devcmd] blocks x y z r");
			return;
		}
		int x = Integer.parseInt(v[0]), y = Integer.parseInt(v[1]), z = Integer.parseInt(v[2]), r = Integer.parseInt(v[3]);
		server.execute(() -> {
			var level = server.overworld();
			java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
			for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(x - r, y - r, z - r, x + r, y + r, z + r)) {
				if (!level.isLoaded(p)) {
					counts.merge("(unloaded)", 1, Integer::sum);
					continue;
				}
				var state = level.getBlockState(p);
				if (!state.isAir() && !state.is(dev.mhwmc.bridge.MhwBridgeMod.TERRAIN)) {
					counts.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
				}
			}
			LOG.info("[devcmd] blocks within {} of {} {} {}: {}", r, x, y, z, counts);
		});
	}

	private static void status(Minecraft mc) {
		if (mc.player == null) {
			LOG.info("[devcmd] status: no player");
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
		HitResult hit = mc.hitResult;
		String target = hit == null ? "none" : switch (hit.getType()) {
			case BLOCK -> "block " + ((BlockHitResult) hit).getBlockPos() + " " + mc.level.getBlockState(((BlockHitResult) hit).getBlockPos());
			case ENTITY -> "entity " + ((EntityHitResult) hit).getEntity();
			default -> "miss";
		};
		LOG.info("[devcmd] status: player {} yaw {} pitch {} onGround {} flying {} cam {} camType {} overlay {} target {} anchor {}",
			mc.player.position(), mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(),
			mc.player.getAbilities().flying, cam, mc.options.getCameraType(), Overlay.active(), target, CoordMap.get());
	}
}
