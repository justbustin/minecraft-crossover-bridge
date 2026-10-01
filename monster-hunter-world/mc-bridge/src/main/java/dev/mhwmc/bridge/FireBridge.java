package dev.mhwmc.bridge;

import dev.mhwmc.bridge.entity.EntityBridge;
import dev.mhwmc.bridge.entity.MhwEntity;
import dev.mhwmc.bridge.link.HazardInfo;
import dev.mhwmc.bridge.link.MhwLink;
import dev.mhwmc.bridge.link.Protocol;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MHW monster flames in Minecraft's world: TNT they touch is lit, as a flaming arrow lights it
 * (the usual 4-second fuse), unless mobGriefing is off (vanilla keeps mobs' flaming arrows from
 * lighting TNT then). MHW publishes its monsters' live fire attacks as hazards: a center and a
 * reach. A fireball crosses a block or two between server ticks, so the whole path since the
 * last tick counts.
 */
public final class FireBridge {
	private FireBridge() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("mhwbridge");
	/** Largest reach honoured, in blocks, whatever MHW reports. */
	private static final double MAX_REACH = 6.0;
	/** Smallest reach, in blocks: a flame touching a block lights it. */
	private static final double MIN_REACH = 0.5;
	/**
	 * A table more than this many MHW frames old is stale (loading screen, reload...). MHW's
	 * frame counter restarts with each DLL core, so a table from a "future" frame is stale too.
	 */
	private static final long STALE_FRAMES = 30;
	/** A hazard that moved further than this since the last tick jumped (new zone...): no path. */
	private static final double MAX_STEP = 8.0;
	private static final List<HazardInfo> HAZARDS = new ArrayList<>();

	/** Where a live hazard was at the previous tick (Minecraft coordinates). */
	private record Seen(int serial, Vec3 pos) {
	}

	private static Long2ObjectOpenHashMap<Seen> last = new Long2ObjectOpenHashMap<>();
	private static Long2ObjectOpenHashMap<Seen> seen = new Long2ObjectOpenHashMap<>();

	public static void reset() {
		last.clear();
		seen.clear();
	}

	public static void onServerTick(MinecraftServer server) {
		if (!TerrainManager.isBridgeWorld()) {
			return;
		}
		CoordMap.Mapping map = CoordMap.get();
		long age = MhwLink.get().readHazards(HAZARDS);
		boolean fresh = age >= 0 && age <= STALE_FRAMES;
		ServerLevel level = server.overworld();
		seen.clear();
		if (map != null && !map.provisional() && fresh && level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
			for (HazardInfo h : HAZARDS) {
				if ((h.flags & Protocol.HAZARD_FIRE) != 0 && Float.isFinite(h.pos[0]) && Float.isFinite(h.pos[1])
					&& Float.isFinite(h.pos[2]) && Float.isFinite(h.radius)) {
					Vec3 c = map.toMc(h.pos[0], h.pos[1], h.pos[2]);
					double reach = Math.clamp(h.radius / map.unitsPerMeter(), MIN_REACH, MAX_REACH);
					Seen before = last.get(h.id);
					Vec3 from = before != null && before.serial() == h.serial ? before.pos() : c;
					if (from.distanceTo(c) > MAX_STEP) {
						from = c;
					}
					// Along the path since the last tick, in steps no longer than the reach.
					int steps = (int) Math.ceil(from.distanceTo(c) / reach);
					for (int i = 0; i <= steps; i++) {
						lightTnt(level, steps == 0 ? c : from.lerp(c, (double) i / steps), reach, h.ownerId);
					}
					seen.put(h.id, new Seen(h.serial, c));
				}
			}
		}
		Long2ObjectOpenHashMap<Seen> t = last;
		last = seen;
		seen = t;
	}

	/** Lights every TNT block within {@code reach} blocks of {@code c} (measured to the nearest point of the block). */
	private static void lightTnt(ServerLevel level, Vec3 c, double reach, long ownerId) {
		BlockPos min = BlockPos.containing(c.x - reach, c.y - reach, c.z - reach);
		BlockPos max = BlockPos.containing(c.x + reach, c.y + reach, c.z + reach);
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.TNT)) {
				continue;
			}
			double dx = Math.max(Math.max(pos.getX() - c.x, 0.0), c.x - (pos.getX() + 1));
			double dy = Math.max(Math.max(pos.getY() - c.y, 0.0), c.y - (pos.getY() + 1));
			double dz = Math.max(Math.max(pos.getZ() - c.z, 0.0), c.z - (pos.getZ() + 1));
			if (dx * dx + dy * dy + dz * dz > reach * reach) {
				continue;
			}
			BlockPos lit = pos.immutable();
			TntBlock.explode(level, lit);
			level.removeBlock(lit, false);
			MhwEntity owner = EntityBridge.proxy(ownerId);
			LOG.info("MHW fire from {} lit TNT at {} {} {}", owner != null ? owner.getName().getString() : "a monster",
				lit.getX(), lit.getY(), lit.getZ());
		}
	}
}
