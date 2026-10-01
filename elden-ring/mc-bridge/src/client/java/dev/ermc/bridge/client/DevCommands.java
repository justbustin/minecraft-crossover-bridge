package dev.ermc.bridge.client;

import dev.ermc.bridge.CoordMap;
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
 * Development helper: executes commands written to /tmp/ermc/mc_cmd.txt (one per line), so
 * the bridge can be tested from a terminal while the game is running. Results go to the log
 * with a "[devcmd]" prefix.
 *
 * <pre>
 *   cam first|third|front      look &lt;yaw&gt; &lt;pitch&gt;      run &lt;minecraft command&gt;
 *   use | attack               screenshot &lt;name&gt;         status
 *   critattack (jump, turn to the nearest Elden Ring enemy, swing while falling)
 *   save | quit
 * </pre>
 */
public final class DevCommands {
	private DevCommands() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	private static final Path FILE = Path.of("/tmp/ermc/mc_cmd.txt");

	/** "critattack": 0 idle, 1 jump next tick, 2 airborne: swing once falling (a Minecraft critical hit). */
	private static int critStep;
	private static int critTicks;

	private static void tickCrit(Minecraft mc) {
		if (critStep == 0 || mc.player == null) {
			return;
		}
		critTicks++;
		if (critStep == 1) {
			if (mc.player.onGround()) {
				mc.player.jumpFromGround();
				critStep = 2;
			}
		} else if (!mc.player.onGround() && mc.player.getDeltaMovement().y < -0.08) {
			aimAtNearestEnemy(mc);
			KeyMapping.click(mc.options.keyAttack.getDefaultKey());
			LOG.info("[devcmd] critattack: swung while falling (dy {})", mc.player.getDeltaMovement().y);
			critStep = 0;
		}
		if (critTicks > 40) {
			critStep = 0;
		}
	}

	/**
	 * Turns the player toward the nearest Elden Ring enemy within reach. The jump lifts the view
	 * about a block, so a crosshair aimed from the ground would pass over the enemy's head.
	 */
	private static void aimAtNearestEnemy(Minecraft mc) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 best = null;
		double bestD = 4.5 * 4.5;
		for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof dev.ermc.bridge.entity.ErEntity && e.isAlive()) {
				Vec3 c = e.getBoundingBox().getCenter();
				double d = c.distanceToSqr(eye);
				if (d < bestD) {
					bestD = d;
					best = c;
				}
			}
		}
		if (best == null) {
			return;
		}
		Vec3 to = best.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		mc.player.yRotO = yaw;
		mc.player.xRotO = pitch;
	}

	public static void tick(Minecraft mc) {
		tickCrit(mc);
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
			case "spin" -> Overlay.spinDegPerSec = Float.parseFloat(arg);  // dev: turn smoothly (pose-lag check)
			case "critattack" -> {  // dev: jump, then swing on the way down (Minecraft's critical hit)
				critStep = 1;
				critTicks = 0;
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
					case "filter" -> dev.ermc.bridge.TerrainManager.setRayFilter(v.length > 1 && v[1].equals("off") ? null
						: new int[] {Integer.decode(v[1]), Integer.decode(v[2]), Integer.decode(v[3])});
					case "wallignore" -> dev.ermc.bridge.TerrainManager.setWallIgnoreMask(Integer.decode(v[1]));
					case "reset" -> dev.ermc.bridge.TerrainManager.requestReset();
					default -> { }
				}
				LOG.info("[devcmd] terrain {}", dev.ermc.bridge.TerrainManager.describeSettings());
			}
			case "switch" -> {
				if (arg.equals("host")) {
					Overlay.switchToHost(mc);
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
				LOG.info("[devcmd] passthrough {} wanted {} activeInHost {} lag {} depthIndex {} noDepth {} debug {}",
					FramePassthrough.enabled(), FramePassthrough.wanted(), FramePassthrough.activeInHost(),
					CameraSync.poseLag, CameraSync.depthIndex, CameraSync.noDepthTest, CameraSync.debugDepth);
			}
			default -> LOG.info("[devcmd] unknown command: {}", line);
		}
		LOG.info("[devcmd] ok: {}", line);
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
