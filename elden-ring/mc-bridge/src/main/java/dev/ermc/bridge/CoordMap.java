package dev.ermc.bridge;

import net.minecraft.world.phys.Vec3;

/**
 * Maps the host game (Elden Ring) world coordinates to Minecraft block coordinates and back.
 *
 * <p>The host game measures in {@code unitsPerMeter} units (Elden Ring: metres), Minecraft in
 * blocks (1 block = 1 m). The anchor pins one host-game point (the player's feet when the bridge
 * first saw a zone) to a fixed Minecraft point, so the player's ground sits exactly on a block
 * boundary.
 *
 * <p>Elden Ring is left-handed (D3D convention: +X right, +Y up, +Z forward) and Minecraft is
 * right-handed, so every mapping mirrors Z ({@link #HOST_FLIP_Z}). Mirroring one axis keeps the
 * two rendered images identical instead of mirror images of each other.
 */
public final class CoordMap {
	private CoordMap() {
	}

	/** Minecraft position the host-game anchor maps to. Floor blocks go at MC_Y - 1. */
	public static final double MC_X = 0.5;
	public static final double MC_Y = 100.0;
	public static final double MC_Z = 0.5;

	/**
	 * Each host-game zone (map) lives in its own region of the Minecraft world, this far apart.
	 * Elden Ring's open world is one zone about 10 km across, so a region spans +-16 km.
	 */
	public static final double REGION_SPACING = 32768.0;

	/** Elden Ring is left-handed: Minecraft z = -host z (relative to the anchor). */
	public static final boolean HOST_FLIP_Z = true;

	/**
	 * @param zone   host-game zone id this mapping belongs to (0 = unknown)
	 * @param region index of the Minecraft region used for that zone
	 */
	public record Mapping(double ax, double ay, double az, double unitsPerMeter, boolean flipZ, boolean provisional,
						  int zone, int region) {
		public double originX() {
			return MC_X + region * REGION_SPACING;
		}

		public boolean contains(double mcX) {
			return Math.abs(mcX - originX()) < REGION_SPACING / 2;
		}

		public Vec3 toMc(double x, double y, double z) {
			double s = 1.0 / unitsPerMeter;
			double dz = (z - az) * s;
			return new Vec3((x - ax) * s + originX(), (y - ay) * s + MC_Y, (flipZ ? -dz : dz) + MC_Z);
		}

		public double[] toHost(double x, double y, double z) {
			double dz = z - MC_Z;
			return new double[] {
				(x - originX()) * unitsPerMeter + ax,
				(y - MC_Y) * unitsPerMeter + ay,
				(flipZ ? -dz : dz) * unitsPerMeter + az
			};
		}

		/** Direction vector host game -> Minecraft (unit length is preserved up to scale). */
		public Vec3 dirToMc(double dx, double dy, double dz) {
			return new Vec3(dx, dy, flipZ ? -dz : dz);
		}

		public double[] dirToHost(double dx, double dy, double dz) {
			return new double[] {dx, dy, flipZ ? -dz : dz};
		}
	}

	private static volatile Mapping current;

	public static Mapping get() {
		return current;
	}

	public static void set(Mapping m) {
		current = m;
	}
}
