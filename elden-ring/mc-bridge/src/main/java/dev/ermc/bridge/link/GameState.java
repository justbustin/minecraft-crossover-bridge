package dev.ermc.bridge.link;

import java.nio.ByteBuffer;

/** Snapshot of ErmcGameState (what the host game published for its latest frame). */
public final class GameState {
	public int flags;
	public long frame;
	public final float[] camPos = new float[3];
	public final float[] camTarget = new float[3];
	public final float[] camUp = new float[3];
	public float fovYDeg;
	public float nearZ;
	public float farZ;
	public float aspect;
	public float unitsPerMeter;
	public final float[] playerPos = new float[3];
	public final float[] playerQuat = new float[4];
	public int winX, winY, winW, winH;
	public int bbW, bbH;
	public int stageId;

	public boolean has(int flag) {
		return (flags & flag) != 0;
	}

	void read(ByteBuffer b, int base) {
		flags = b.getInt(base + Protocol.S_FLAGS);
		frame = b.getLong(base + Protocol.S_FRAME);
		readFloats(b, base + Protocol.S_CAM_POS, camPos);
		readFloats(b, base + Protocol.S_CAM_TARGET, camTarget);
		readFloats(b, base + Protocol.S_CAM_UP, camUp);
		fovYDeg = b.getFloat(base + Protocol.S_FOVY);
		nearZ = b.getFloat(base + Protocol.S_NEAR);
		farZ = b.getFloat(base + Protocol.S_FAR);
		aspect = b.getFloat(base + Protocol.S_ASPECT);
		unitsPerMeter = b.getFloat(base + Protocol.S_UNITS_PER_METER);
		readFloats(b, base + Protocol.S_PLAYER_POS, playerPos);
		readFloats(b, base + Protocol.S_PLAYER_QUAT, playerQuat);
		winX = b.getInt(base + Protocol.S_WIN);
		winY = b.getInt(base + Protocol.S_WIN + 4);
		winW = b.getInt(base + Protocol.S_WIN + 8);
		winH = b.getInt(base + Protocol.S_WIN + 12);
		bbW = b.getInt(base + Protocol.S_BB);
		bbH = b.getInt(base + Protocol.S_BB + 4);
		stageId = b.getInt(base + Protocol.S_STAGE);
	}

	public void copyFrom(GameState o) {
		flags = o.flags;
		frame = o.frame;
		System.arraycopy(o.camPos, 0, camPos, 0, 3);
		System.arraycopy(o.camTarget, 0, camTarget, 0, 3);
		System.arraycopy(o.camUp, 0, camUp, 0, 3);
		fovYDeg = o.fovYDeg;
		nearZ = o.nearZ;
		farZ = o.farZ;
		aspect = o.aspect;
		unitsPerMeter = o.unitsPerMeter;
		System.arraycopy(o.playerPos, 0, playerPos, 0, 3);
		System.arraycopy(o.playerQuat, 0, playerQuat, 0, 4);
		winX = o.winX;
		winY = o.winY;
		winW = o.winW;
		winH = o.winH;
		bbW = o.bbW;
		bbH = o.bbH;
		stageId = o.stageId;
	}

	private static void readFloats(ByteBuffer b, int off, float[] out) {
		for (int i = 0; i < out.length; i++) {
			out[i] = b.getFloat(off + i * 4);
		}
	}
}
