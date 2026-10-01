package dev.mhwmc.bridge;

import dev.mhwmc.bridge.link.GameState;
import dev.mhwmc.bridge.link.MhwLink;
import dev.mhwmc.bridge.link.Protocol;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Server side of the bridge (runs on the integrated server thread).
 *
 * <p>Owns the MHW<->Minecraft anchor and keeps invisible terrain blocks under and around
 * the players. Terrain comes from MHW itself: batches of downward rays are cast against
 * MHW's collision geometry (by the DLL, on MHW's game thread) and every hit becomes a
 * column of {@link TerrainBlock}s whose top matches MHW's ground to 1/16 of a block. If
 * the DLL can't answer ray queries, a flat floor at the hunter's feet is used instead.
 */
public final class TerrainManager {
	private TerrainManager() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("mhwbridge");
	/** Only worlds created by the bridge are touched, never the player's normal worlds. */
	public static final String BRIDGE_LEVEL_NAME = "MHW Bridge";
	private static final String ANCHOR_FILE = "mhwbridge-anchor.properties";
	private static final int FLOOR_Y = (int) CoordMap.MC_Y - 1;
	private static final int FLOOR_CHUNK_RADIUS = 2;

	/** Columns within this many blocks of a player are kept sampled. */
	private static final int SAMPLE_RADIUS = 24;
	/** Rays start this far above the player's feet and reach this far below them. */
	private static final double RAY_ABOVE = 4.0;
	private static final double RAY_BELOW = 40.0;
	/** Re-sample a column when the player is this much higher/lower than when it was sampled. */
	private static final double RESAMPLE_DY = 4.0;
	/** Solid terrain thickness below MHW's surface. */
	private static final int THICKNESS = 2;
	/** Obstacle probes: horizontal rays across each column at this height above the player's feet. */
	private static final double PROBE_HEIGHT = 1.0;
	/** Obstacles fill their column up to this far above the player's feet. */
	private static final double WALL_HEIGHT = 3.0;
	/** Rays per column: down from above the head, down from high above, across X, across Z. */
	private static final int RAYS_PER_COLUMN = 4;
	private static final int BATCH = 1020;
	/** High ray: finds ground that rises above the player's head (hills, cliffs ahead). */
	private static final double RAY_HIGH = 40.0;
	/** Hills/cliffs above the player are filled at most this far above the player's feet. */
	private static final double CLIFF_FILL = 6.0;

	/**
	 * Collision filter for terrain rays (Param::setAttr a/b/c), null = MHW default (everything).
	 * The default is the filter MHW's own player code uses: the hunter's movement mesh (layer
	 * bit 0) without the invisible walls that only stop monsters, the Palico or the camera.
	 * With everything, those walls boxed Steve in and blocked ledges the hunter can jump off.
	 */
	public static final int[] HUNTER_FILTER = {1, 0x80040, 1};
	private static volatile int[] rayFilter = HUNTER_FILTER;
	/** Surfaces with any of these attribute bits never count as walls. */
	private static volatile int wallIgnoreMask;
	private static volatile boolean resetRequested;
	private static final java.util.Map<Integer, Integer> GROUND_ATTRS = new java.util.HashMap<>();
	private static final java.util.Map<Integer, Integer> WALL_ATTRS = new java.util.HashMap<>();
	private static long lastAttrLog;
	private static final long RAY_TIMEOUT_MS = 3000;

	/** What was placed in a column: terrain blocks from bottom..top (inclusive). */
	private record Column(double sampleY, int top, int bottom) {
	}

	private static final GameState STATE = new GameState();
	/** Anchors per MHW zone id; each zone gets its own Minecraft region. */
	private static final java.util.Map<Integer, CoordMap.Mapping> ANCHORS = new java.util.HashMap<>();
	/** Anchor from before per-zone regions existed; adopted by the first zone seen. */
	private static CoordMap.Mapping legacyAnchor;
	private static volatile boolean recallRequested;
	/** Incremented each time players were moved to the hunter (recall or zone change). */
	private static volatile int recallServed;
	private static final LongOpenHashSet FLOORED_CHUNKS = new LongOpenHashSet();
	private static final Long2ObjectOpenHashMap<Column> COLUMNS = new Long2ObjectOpenHashMap<>();
	private static boolean bridgeWorld;

	// Ray batch in flight
	private static int pendingSeq = -1;
	private static long pendingSince;
	private static final List<long[]> PENDING_COLUMNS = new ArrayList<>(); // {columnKey, sampleY*16}
	private static final float[] RAYS = new float[BATCH * 6];
	private static final float[] HITS = new float[BATCH * 6];
	private static final int[] HIT_FLAGS = new int[BATCH];
	private static final int[] HIT_ATTRS = new int[BATCH];
	/** 0 = unknown, 1 = rays work, -1 = no ray support (fall back to a flat floor). */
	private static int rayMode;
	private static int rayFailures;

	public static boolean isBridgeWorld() {
		return bridgeWorld;
	}

	public static void reset() {
		bridgeWorld = false;
		FLOORED_CHUNKS.clear();
		COLUMNS.clear();
		PENDING_COLUMNS.clear();
		pendingSeq = -1;
		rayMode = 0;
		rayFailures = 0;
		ANCHORS.clear();
		legacyAnchor = null;
		CoordMap.set(null);
	}

	/** After control returns from MHW: put the Minecraft player next to the hunter again. */
	public static void requestRecall() {
		recallRequested = true;
	}

	public static int recallServed() {
		return recallServed;
	}

	/** Dev: collision filter for terrain rays ({a, b, c} for Param::setAttr), or null for none. */
	public static void setRayFilter(int[] filter) {
		rayFilter = filter;
	}

	public static void setWallIgnoreMask(int mask) {
		wallIgnoreMask = mask;
	}

	/** Dev: remove all sampled terrain and sample again. */
	public static void requestReset() {
		resetRequested = true;
	}

	public static String describeSettings() {
		int[] f = rayFilter;
		return "filter " + (f == null ? "none" : String.format("(%d, %#x, %d)", f[0], f[1], f[2]))
			+ String.format(" wallIgnore %#x columns %d", wallIgnoreMask, COLUMNS.size());
	}

	public static void onServerStarted(MinecraftServer server) {
		reset();
		bridgeWorld = BRIDGE_LEVEL_NAME.equals(server.getWorldData().getLevelName());
		if (!bridgeWorld) {
			return;
		}
		ServerLevel overworld = server.overworld();
		overworld.setDayTime(6000);
		overworld.setDefaultSpawnPos(BlockPos.containing(CoordMap.MC_X, CoordMap.MC_Y, CoordMap.MC_Z), 0.0F);
		loadAnchors(server);
		// The hunter is where the player really is when the world opens.
		recallRequested = true;
	}

	public static void onJoin(MinecraftServer server, ServerPlayer player) {
		if (!bridgeWorld) {
			return;
		}
		// Brand-new worlds spawn players in the void; put them next to the hunter instead.
		CoordMap.Mapping map = CoordMap.get();
		if (player.getY() < 0 && map != null) {
			teleportToHunter(server.overworld(), player, map);
		}
	}

	public static void onServerTick(MinecraftServer server) {
		if (!bridgeWorld) {
			return;
		}
		MhwLink link = MhwLink.get();
		boolean alive = link.poll();
		if (alive && link.snapshot(STATE)) {
			updateAnchor(server);
			if (recallRequested && STATE.has(Protocol.STATE_PLAYER_VALID) && CoordMap.get() != null
				&& !server.getPlayerList().getPlayers().isEmpty()) {
				recallRequested = false;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					teleportToHunter(server.overworld(), p, CoordMap.get());
				}
				recallServed++;
			}
		}
		CoordMap.Mapping map = CoordMap.get();
		ServerLevel level = server.overworld();
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (map == null || map.provisional() || !alive || rayMode < 0) {
			for (ServerPlayer player : players) {
				floorAround(level, player.chunkPosition());
			}
			return;
		}
		if (resetRequested) {
			resetRequested = false;
			for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<Column> en : COLUMNS.long2ObjectEntrySet()) {
				Column c = en.getValue();
				if (c.top() != Integer.MIN_VALUE) {
					for (int y = c.bottom(); y <= c.top(); y++) {
						clearTerrain(level, ChunkPos.getX(en.getLongKey()), y, ChunkPos.getZ(en.getLongKey()));
					}
				}
			}
			COLUMNS.clear();
			pendingSeq = -1;
			PENDING_COLUMNS.clear();
			LOG.info("Terrain reset ({})", describeSettings());
		}
		collectResults(level, map);
		if (pendingSeq < 0 && !players.isEmpty()) {
			submitRays(map, players);
		}
		logAttrStats();
	}

	// -- ray-sampled terrain ----------------------------------------------------------------------

	private static void submitRays(CoordMap.Mapping map, List<ServerPlayer> players) {
		PENDING_COLUMNS.clear();
		int n = 0;
		for (ServerPlayer player : players) {
			int px = Mth.floor(player.getX());
			int pz = Mth.floor(player.getZ());
			double py = player.getY();
			// Nearest columns first, so the ground under the player appears immediately.
			for (int r = 0; r <= SAMPLE_RADIUS && n + RAYS_PER_COLUMN <= BATCH; r++) {
				for (int dx = -r; dx <= r && n + RAYS_PER_COLUMN <= BATCH; dx++) {
					for (int dz = -r; dz <= r && n + RAYS_PER_COLUMN <= BATCH; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r || dx * dx + dz * dz > SAMPLE_RADIUS * SAMPLE_RADIUS) {
							continue;
						}
						int x = px + dx;
						int z = pz + dz;
						long key = ChunkPos.asLong(x, z);
						Column c = COLUMNS.get(key);
						if (c != null && Math.abs(c.sampleY() - py) < RESAMPLE_DY) {
							continue;
						}
						double hy = py + PROBE_HEIGHT;
						putRay(map, n++, x + 0.5, py + RAY_ABOVE, z + 0.5, x + 0.5, py - RAY_BELOW, z + 0.5);
						putRay(map, n++, x + 0.5, py + RAY_HIGH, z + 0.5, x + 0.5, py + RAY_ABOVE, z + 0.5);
						putRay(map, n++, x, hy, z + 0.5, x + 1.0, hy, z + 0.5);
						putRay(map, n++, x + 0.5, hy, z, x + 0.5, hy, z + 1.0);
						PENDING_COLUMNS.add(new long[] {key, Math.round(py * 16)});
					}
				}
			}
		}
		if (n == 0) {
			return;
		}
		int[] filter = rayFilter;
		int seq = MhwLink.get().submitRays(RAYS, n, filter != null ? dev.mhwmc.bridge.link.Protocol.RAYS_CUSTOM_FILTER : 0, filter);
		if (seq >= 0) {
			pendingSeq = seq;
			pendingSince = System.currentTimeMillis();
		} else {
			PENDING_COLUMNS.clear();
		}
	}

	private static void putRay(CoordMap.Mapping map, int i, double x0, double y0, double z0, double x1, double y1, double z1) {
		double[] s = map.toMhw(x0, y0, z0);
		double[] e = map.toMhw(x1, y1, z1);
		for (int k = 0; k < 3; k++) {
			RAYS[i * 6 + k] = (float) s[k];
			RAYS[i * 6 + 3 + k] = (float) e[k];
		}
	}

	/** A horizontal probe hit counts as an obstacle if the surface is steep and stands above the ground. */
	private static boolean isObstacle(int ray, double groundY, double probeY, CoordMap.Mapping map) {
		if (HIT_FLAGS[ray] == 0) {
			return false;
		}
		WALL_ATTRS.merge(HIT_ATTRS[ray], 1, Integer::sum);
		if ((HIT_ATTRS[ray] & wallIgnoreMask) != 0) {
			return false;
		}
		double ny = HITS[ray * 6 + 4];
		return Math.abs(ny) < 0.7 && groundY < probeY - 0.25;
	}

	private static void logAttrStats() {
		long now = System.currentTimeMillis();
		if (now - lastAttrLog < 30000 || (GROUND_ATTRS.isEmpty() && WALL_ATTRS.isEmpty())) {
			return;
		}
		lastAttrLog = now;
		LOG.info("Surface attributes - ground: {} walls: {}", fmtAttrs(GROUND_ATTRS), fmtAttrs(WALL_ATTRS));
	}

	private static String fmtAttrs(java.util.Map<Integer, Integer> m) {
		StringBuilder b = new StringBuilder();
		m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(8)
			.forEach(e -> b.append(String.format("%#x=%d ", e.getKey(), e.getValue())));
		return b.toString();
	}

	private static void collectResults(ServerLevel level, CoordMap.Mapping map) {
		if (pendingSeq < 0) {
			return;
		}
		MhwLink link = MhwLink.get();
		if (!link.raysDone(pendingSeq)) {
			if (System.currentTimeMillis() - pendingSince > RAY_TIMEOUT_MS) {
				pendingSeq = -1;
				PENDING_COLUMNS.clear();
				if (rayMode == 0 && ++rayFailures >= 2) {
					rayMode = -1;
					LOG.warn("MHW does not answer terrain ray queries; using a flat floor at the hunter's feet");
				}
			}
			return;
		}
		int n = PENDING_COLUMNS.size();
		link.readHits(n * RAYS_PER_COLUMN, HITS, HIT_FLAGS, HIT_ATTRS);
		if (rayMode == 0) {
			rayMode = 1;
			LOG.info("Terrain ray queries are working");
		}
		BlockState terrain = MhwBridgeMod.TERRAIN.defaultBlockState();
		for (int i = 0; i < n; i++) {
			long key = PENDING_COLUMNS.get(i)[0];
			double sampleY = PENDING_COLUMNS.get(i)[1] / 16.0;
			int x = ChunkPos.getX(key);
			int z = ChunkPos.getZ(key);
			Column old = COLUMNS.get(key);
			if (old == null) {
				// First sample of this column since the world opened (or since a terrain reset):
				// terrain left by earlier sessions, sampled with other settings, isn't tracked.
				// Clear everything the rays could have produced, except what this sample places.
				int oldTop = Mth.floor(sampleY + RAY_HIGH);
				int oldBottom = Mth.floor(sampleY - RAY_BELOW) - THICKNESS;
				if (FLOORED_CHUNKS.contains(ChunkPos.asLong(x >> 4, z >> 4))) {
					// Also replace the temporary flat floor.
					oldTop = Math.max(oldTop, FLOOR_Y);
					oldBottom = Math.min(oldBottom, FLOOR_Y);
				}
				old = new Column(0, oldTop, oldBottom);
			}
			int top = Integer.MIN_VALUE;
			int bottom = Integer.MIN_VALUE;
			int down = i * RAYS_PER_COLUMN;
			int high = down + 1;
			boolean lowHit = HIT_FLAGS[down] != 0;
			// Ground above the ray start (uphill, a cliff ahead): the low ray started inside the
			// terrain and found nothing, but the high ray lands on an upward-facing surface.
			boolean hillHit = !lowHit && HIT_FLAGS[high] != 0 && HITS[high * 6 + 4] > 0.3F;
			if (lowHit || hillHit) {
				int g = lowHit ? down : high;
				GROUND_ATTRS.merge(HIT_ATTRS[g], 1, Integer::sum);
				Vec3 hit = map.toMc(HITS[g * 6], HITS[g * 6 + 1], HITS[g * 6 + 2]);
				double groundY = hit.y;
				if (hillHit) {
					groundY = Math.min(groundY, sampleY + CLIFF_FILL);
				}
				int topBlock = Mth.floor(groundY - 1e-4);
				int height = Mth.clamp((int) Math.ceil((groundY - topBlock) * 16.0 - 1e-3), 1, 16);
				double probeY = sampleY + PROBE_HEIGHT;
				boolean obstacle = isObstacle(down + 2, groundY, probeY, map) || isObstacle(down + 3, groundY, probeY, map);
				top = topBlock;
				bottom = hillHit ? Math.min(topBlock, Mth.floor(sampleY) - THICKNESS) : topBlock - THICKNESS;
				if (obstacle) {
					// Wall, trunk, rock...: solid from the ground up past head height.
					top = Math.max(topBlock, Mth.floor(sampleY + WALL_HEIGHT));
					for (int y = top; y > topBlock; y--) {
						setTerrain(level, x, y, z, terrain);
					}
					setTerrain(level, x, topBlock, z, terrain);
				} else {
					setTerrain(level, x, topBlock, z, terrain.setValue(TerrainBlock.HEIGHT, height));
				}
				for (int y = topBlock - 1; y >= bottom; y--) {
					setTerrain(level, x, y, z, terrain);
				}
			}
			if (old != null && old.top() != Integer.MIN_VALUE) {
				// Remove what the previous sample placed and this one didn't.
				for (int y = old.bottom(); y <= old.top(); y++) {
					if (top == Integer.MIN_VALUE || y < bottom || y > top) {
						clearTerrain(level, x, y, z);
					}
				}
			}
			COLUMNS.put(key, new Column(sampleY, top, bottom));
		}
		PENDING_COLUMNS.clear();
		pendingSeq = -1;
	}

	private static void setTerrain(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState cur = level.getBlockState(pos);
		if (cur.isAir() || cur.is(MhwBridgeMod.TERRAIN)) {
			if (cur != state) {
				level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
	}

	private static void clearTerrain(ServerLevel level, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		if (level.getBlockState(pos).is(MhwBridgeMod.TERRAIN)) {
			level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	// -- anchor ------------------------------------------------------------------------------------

	private static void updateAnchor(MinecraftServer server) {
		CoordMap.Mapping m = CoordMap.get();
		double upm = STATE.unitsPerMeter > 0 ? STATE.unitsPerMeter : 100.0;
		int zone = STATE.stageId;
		if (STATE.has(Protocol.STATE_PLAYER_VALID)) {
			if (m == null || m.provisional() || m.zone() != zone) {
				CoordMap.Mapping z = ANCHORS.get(zone);
				if (z == null && legacyAnchor != null) {
					z = new CoordMap.Mapping(legacyAnchor.ax(), legacyAnchor.ay(), legacyAnchor.az(), legacyAnchor.unitsPerMeter(),
						legacyAnchor.flipZ(), false, zone, 0);
					legacyAnchor = null;
				}
				if (z == null) {
					int region = ANCHORS.values().stream().mapToInt(CoordMap.Mapping::region).max().orElse(-1) + 1;
					z = new CoordMap.Mapping(STATE.playerPos[0], STATE.playerPos[1], STATE.playerPos[2], upm, false, false, zone, region);
					LOG.info("New MHW zone {}: anchored to hunter at {}, {}, {} (region {})", zone,
						STATE.playerPos[0], STATE.playerPos[1], STATE.playerPos[2], region);
				} else {
					LOG.info("MHW zone {} (region {})", zone, z.region());
				}
				ANCHORS.put(zone, z);
				CoordMap.set(z);
				saveAnchors(server);
				boolean moved = false;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					// Only move players who aren't already in this zone's region.
					if (!z.contains(p.getX())) {
						teleportToHunter(server.overworld(), p, z);
						moved = true;
					}
				}
				if (moved) {
					recallServed++;
				}
			}
		} else if (m == null) {
			// MHW is running but the hunter isn't known yet: map MHW's origin so rendering
			// can already be tested. Replaced as soon as the hunter's position is available.
			CoordMap.set(new CoordMap.Mapping(0, 0, 0, upm, false, true, 0, 0));
		}
	}

	private static void teleportToHunter(ServerLevel level, ServerPlayer player, CoordMap.Mapping map) {
		Vec3 target = STATE.has(Protocol.STATE_PLAYER_VALID)
			? map.toMc(STATE.playerPos[0], STATE.playerPos[1], STATE.playerPos[2])
			: new Vec3(map.originX(), CoordMap.MC_Y, CoordMap.MC_Z);
		// Something to stand on before the terrain rays for this spot come back.
		BlockState terrain = MhwBridgeMod.TERRAIN.defaultBlockState();
		int fy = Mth.floor(target.y - 1e-3) - 1;
		for (int dx = -1; dx <= 2; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				setTerrain(level, Mth.floor(target.x) + dx, fy, Mth.floor(target.z) + dz, terrain);
			}
		}
		// Stand beside the hunter, facing it.
		player.teleportTo(level, target.x + 1.5, target.y + 0.01, target.z, 90.0F, 0.0F);
		LOG.info("Moved {} next to the hunter at {}", player.getName().getString(), target);
	}

	/** Fallback ground: a flat floor at the hunter's feet (exact in flat areas like the Training Area). */
	private static void floorAround(ServerLevel level, ChunkPos center) {
		for (int dx = -FLOOR_CHUNK_RADIUS; dx <= FLOOR_CHUNK_RADIUS; dx++) {
			for (int dz = -FLOOR_CHUNK_RADIUS; dz <= FLOOR_CHUNK_RADIUS; dz++) {
				int cx = center.x + dx;
				int cz = center.z + dz;
				if (FLOORED_CHUNKS.add(ChunkPos.asLong(cx, cz))) {
					BlockState terrain = MhwBridgeMod.TERRAIN.defaultBlockState();
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							long key = ChunkPos.asLong(cx * 16 + x, cz * 16 + z);
							if (!COLUMNS.containsKey(key)) {
								setTerrain(level, cx * 16 + x, FLOOR_Y, cz * 16 + z, terrain);
							}
						}
					}
				}
			}
		}
	}

	// -- persistence -------------------------------------------------------------------------------

	private static Path anchorPath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(ANCHOR_FILE);
	}

	private static void loadAnchors(MinecraftServer server) {
		Path p = anchorPath(server);
		if (!Files.exists(p)) {
			return;
		}
		Properties props = new Properties();
		try (Reader r = Files.newBufferedReader(p)) {
			props.load(r);
			String zones = props.getProperty("zones");
			if (zones == null && props.getProperty("ax") != null) {
				legacyAnchor = new CoordMap.Mapping(
					Double.parseDouble(props.getProperty("ax")), Double.parseDouble(props.getProperty("ay")),
					Double.parseDouble(props.getProperty("az")), Double.parseDouble(props.getProperty("unitsPerMeter", "100")),
					Boolean.parseBoolean(props.getProperty("flipZ", "false")), false, 0, 0);
				LOG.info("Loaded legacy MHW anchor; it becomes region 0 of the first zone seen");
				return;
			}
			if (zones == null || zones.isBlank()) {
				return;
			}
			for (String zs : zones.split(",")) {
				int zone = Integer.parseInt(zs.trim());
				String k = "zone." + zone + ".";
				ANCHORS.put(zone, new CoordMap.Mapping(
					Double.parseDouble(props.getProperty(k + "ax")), Double.parseDouble(props.getProperty(k + "ay")),
					Double.parseDouble(props.getProperty(k + "az")), Double.parseDouble(props.getProperty(k + "unitsPerMeter", "100")),
					Boolean.parseBoolean(props.getProperty(k + "flipZ", "false")), false, zone,
					Integer.parseInt(props.getProperty(k + "region", "0"))));
			}
			LOG.info("Loaded MHW anchors for zones {}", ANCHORS.keySet());
		} catch (IOException | RuntimeException e) {
			LOG.warn("Ignoring unreadable anchor file {}: {}", p, e.toString());
		}
	}

	private static void saveAnchors(MinecraftServer server) {
		Properties props = new Properties();
		StringBuilder zones = new StringBuilder();
		for (CoordMap.Mapping m : ANCHORS.values()) {
			if (zones.length() > 0) {
				zones.append(',');
			}
			zones.append(m.zone());
			String k = "zone." + m.zone() + ".";
			props.setProperty(k + "ax", Double.toString(m.ax()));
			props.setProperty(k + "ay", Double.toString(m.ay()));
			props.setProperty(k + "az", Double.toString(m.az()));
			props.setProperty(k + "unitsPerMeter", Double.toString(m.unitsPerMeter()));
			props.setProperty(k + "flipZ", Boolean.toString(m.flipZ()));
			props.setProperty(k + "region", Integer.toString(m.region()));
		}
		props.setProperty("zones", zones.toString());
		try (Writer w = Files.newBufferedWriter(anchorPath(server))) {
			props.store(w, "Per MHW zone: the MHW position pinned to Minecraft (0.5 + region * 8192, 100, 0.5)");
		} catch (IOException e) {
			LOG.warn("Could not save anchors: {}", e.toString());
		}
	}
}
