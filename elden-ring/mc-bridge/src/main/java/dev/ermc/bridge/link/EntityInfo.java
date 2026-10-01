package dev.ermc.bridge.link;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * One host-game character (ErmcEntity). Coordinates and box are host-game world units (Elden
 * Ring: metres, stable frame); convert them with {@code CoordMap}.
 */
public final class EntityInfo {
	public long id;
	public int kind;
	public int emId;
	/** Feet. */
	public final float[] pos = new float[3];
	public final float[] quat = new float[4];
	public final float[] boxCenter = new float[3];
	public final float[] boxHalf = new float[3];
	public float hp;
	public float maxHp;
	public int flags;
	public String name = "";

	/** Dead or dying there: the dead flag, or no HP left (when HP is known). */
	public boolean dead() {
		return (flags & Protocol.ENTITY_DEAD) != 0 || (maxHp > 0 && hp <= 0);
	}

	/** A hostile character (enemy or boss), never a friendly or neutral NPC ({@link Protocol#ENT_OTHER}). */
	public boolean enemy() {
		return kind == Protocol.ENT_LARGE_MONSTER || kind == Protocol.ENT_SMALL_MONSTER;
	}

	/** {@link #boxHalf} is along the world axes rather than the character's own. */
	public boolean worldAlignedBox() {
		return (flags & Protocol.ENTITY_WORLD_BOX) != 0;
	}

	void read(ByteBuffer b, int o) {
		id = b.getLong(o);
		kind = b.getInt(o + 0x08);
		emId = b.getInt(o + 0x0C);
		for (int i = 0; i < 3; i++) {
			pos[i] = b.getFloat(o + 0x10 + i * 4);
			boxCenter[i] = b.getFloat(o + 0x2C + i * 4);
			boxHalf[i] = b.getFloat(o + 0x38 + i * 4);
		}
		for (int i = 0; i < 4; i++) {
			quat[i] = b.getFloat(o + 0x1C + i * 4);
		}
		hp = b.getFloat(o + 0x44);
		maxHp = b.getFloat(o + 0x48);
		flags = b.getInt(o + 0x4C);
		int len = 0;
		while (len < 48 && b.get(o + 0x50 + len) != 0) {
			len++;
		}
		byte[] bytes = new byte[len];
		for (int i = 0; i < len; i++) {
			bytes[i] = b.get(o + 0x50 + i);
		}
		name = new String(bytes, StandardCharsets.UTF_8);
	}
}
