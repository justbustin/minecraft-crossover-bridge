package dev.mhwmc.bridge;

import net.minecraft.world.phys.Vec3;

/**
 * Maps MHW world coordinates to Minecraft block coordinates and back.
 *
 * <p>MHW measures in {@code unitsPerMeter} units (centimetres), Minecraft in blocks
 * (1 block = 1 m). The anchor pins one MHW point (the hunter's feet when the bridge first
 * connected) to a fixed Minecraft point, so the hunter's ground sits exactly on a block
 * boundary.
 */
public final class CoordMap {
	private CoordMap() {
	}

	/** Minecraft position the MHW anchor maps to. Floor blocks go at MC_Y - 1. */
	public static final double MC_X = 0.5;
	public static final double MC_Y = 100.0;
	public static final double MC_Z = 0.5;

	/** Each MHW zone (map) lives in its own region of the Minecraft world, this far apart. */
	public static final double REGION_SPACING = 8192.0;

	/**
	 * @param zone   MHW zone id this mapping belongs to (0 = unknown)
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

		public double[] toMhw(double x, double y, double z) {
			double dz = z - MC_Z;
			return new double[] {
				(x - originX()) * unitsPerMeter + ax,
				(y - MC_Y) * unitsPerMeter + ay,
				(flipZ ? -dz : dz) * unitsPerMeter + az
			};
		}

		/** Direction vector MHW -> Minecraft (unit length is preserved up to scale). */
		public Vec3 dirToMc(double dx, double dy, double dz) {
			return new Vec3(dx, dy, flipZ ? -dz : dz);
		}

		public double[] dirToMhw(double dx, double dy, double dz) {
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
