package dev.mhwmc.bridge.link;

/** What Minecraft asks MHW to do this frame (MhmcControl). */
public final class ControlState {
	public int flags;
	public long mcFrame;
	public final float[] camPos = new float[3];
	public final float[] camTarget = new float[3];
	public final float[] camUp = new float[3];
	public float fovYDeg;
	public final float[] hunterPos = new float[3];
	public int poseLag = 1;
	public int depthIndex;
	public float hunterYawDeg;
}
