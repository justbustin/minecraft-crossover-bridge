package dev.mhwmc.bridge.link;

import java.nio.ByteBuffer;

/** One live MHW monster attack that affects Minecraft's world (MhmcHazard). Coordinates are MHW world units. */
public final class HazardInfo {
	public long id;
	public long ownerId;
	public final float[] pos = new float[3];
	public float radius;
	public int flags;
	/** New for each shell MHW sees: a freed shell's address can be reused by the next one. */
	public int serial;

	void read(ByteBuffer b, int o) {
		id = b.getLong(o);
		ownerId = b.getLong(o + 0x08);
		for (int i = 0; i < 3; i++) {
			pos[i] = b.getFloat(o + 0x10 + i * 4);
		}
		radius = b.getFloat(o + 0x1C);
		flags = b.getInt(o + 0x20);
		serial = b.getInt(o + 0x24);
	}
}
