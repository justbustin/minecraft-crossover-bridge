package dev.ermc.bridge.link;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The shared-memory file both games map. Wine maps its view with MAP_SHARED, so plain
 * loads/stores here are seen by the DLL inside the host game. Seqlocked blocks use acquire/release
 * fences because this JVM runs natively on ARM (weaker ordering than the x86 side).
 */
public final class BridgeShm {
	private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

	private final MappedByteBuffer buf;

	private BridgeShm(MappedByteBuffer buf) {
		this.buf = buf;
	}

	public static BridgeShm open() throws IOException {
		Path file = Path.of(Protocol.SHM_PATH);
		Files.createDirectories(file.getParent());
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
			if (ch.size() < Protocol.SHM_SIZE) {
				ch.write(ByteBuffer.wrap(new byte[1]), Protocol.SHM_SIZE - 1);
			}
			MappedByteBuffer b = ch.map(FileChannel.MapMode.READ_WRITE, 0, Protocol.SHM_SIZE);
			b.order(ByteOrder.LITTLE_ENDIAN);
			BridgeShm shm = new BridgeShm(b);
			shm.initHeader();
			return shm;
		}
	}

	private void initHeader() {
		if (buf.getInt(Protocol.H_MAGIC) != Protocol.MAGIC || buf.getInt(Protocol.H_VERSION) != Protocol.VERSION) {
			for (int i = 0; i < Protocol.OFF_RAYS; i += 8) {
				buf.putLong(i, 0L);
			}
			buf.putInt(Protocol.H_VERSION, Protocol.VERSION);
			buf.putInt(Protocol.H_SIZE, Protocol.SHM_SIZE);
			INT.setRelease(buf, Protocol.H_MAGIC, Protocol.MAGIC);
		}
		buf.putInt(Protocol.H_MC_PID, (int) ProcessHandle.current().pid());
		buf.putLong(Protocol.H_MC_START, System.currentTimeMillis());
	}

	public long hostHeartbeat() {
		return (long) LONG.getAcquire(buf, Protocol.H_HOST_HEARTBEAT);
	}

	public long hostStartMs() {
		return (long) LONG.getAcquire(buf, Protocol.H_HOST_START);
	}

	/** Incremented by the host game each time F8 is pressed in it. */
	public int mcSwitchRequests() {
		return (int) INT.getAcquire(buf, Protocol.H_MC_SWITCH_REQ);
	}

	/** Asks the host game to bring its window to the front. */
	public void requestHostFocus() {
		int v = (int) INT.getOpaque(buf, Protocol.H_HOST_FOCUS_REQ);
		INT.setRelease(buf, Protocol.H_HOST_FOCUS_REQ, v + 1);
	}

	public int hostLife() {
		return (int) INT.getAcquire(buf, Protocol.H_HOST_LIFE);
	}

	public int hostDeaths() {
		return (int) INT.getAcquire(buf, Protocol.H_HOST_DEATHS);
	}

	public int requestAction() {
		int v = (int) INT.getOpaque(buf, Protocol.H_MC_ACTION_REQ) + 1;
		INT.setRelease(buf, Protocol.H_MC_ACTION_REQ, v);
		return v;
	}

	public int actionAck() {
		return (int) INT.getAcquire(buf, Protocol.H_HOST_ACTION_ACK);
	}

	public int actionResult() {
		return (int) INT.getAcquire(buf, Protocol.H_HOST_ACTION_RESULT);
	}

	public String hostPrompt() {
		byte[] b = new byte[64];
		int n = 0;
		for (; n < 63; n++) {
			b[n] = buf.get(Protocol.H_HOST_PROMPT + n);
			if (b[n] == 0) {
				break;
			}
		}
		return new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
	}

	public void bumpMcDeaths() {
		int v = (int) INT.getOpaque(buf, Protocol.H_MC_DEATHS);
		INT.setRelease(buf, Protocol.H_MC_DEATHS, v + 1);
	}

	public void bumpMcHeartbeat() {
		long v = (long) LONG.getOpaque(buf, Protocol.H_MC_HEARTBEAT);
		LONG.setRelease(buf, Protocol.H_MC_HEARTBEAT, v + 1);
	}

	/** Seqlock read of the host game's state. Returns false if nothing consistent was published yet. */
	public boolean readState(GameState out) {
		int base = Protocol.OFF_STATE;
		for (int tries = 0; tries < 2000; tries++) {
			int s1 = (int) INT.getAcquire(buf, base + Protocol.S_SEQ);
			if ((s1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			out.read(buf, base);
			VarHandle.acquireFence();
			int s2 = (int) INT.getAcquire(buf, base + Protocol.S_SEQ);
			if (s1 == s2) {
				return s1 != 0;
			}
		}
		return false;
	}

	/** Seqlock write of the control block. */
	public void writeControl(ControlState c) {
		int base = Protocol.OFF_CONTROL;
		int s = (int) INT.getOpaque(buf, base + Protocol.C_SEQ);
		if ((s & 1) != 0) {
			s++;
		}
		INT.setOpaque(buf, base + Protocol.C_SEQ, s + 1);
		VarHandle.releaseFence();
		buf.putInt(base + Protocol.C_FLAGS, c.flags);
		buf.putLong(base + Protocol.C_MC_FRAME, c.mcFrame);
		putFloats(base + Protocol.C_CAM_POS, c.camPos);
		putFloats(base + Protocol.C_CAM_TARGET, c.camTarget);
		putFloats(base + Protocol.C_CAM_UP, c.camUp);
		buf.putFloat(base + Protocol.C_FOVY, c.fovYDeg);
		putFloats(base + Protocol.C_HUNTER_POS, c.hunterPos);
		buf.putInt(base + Protocol.C_POSE_LAG, c.poseLag);
		buf.putInt(base + Protocol.C_DEPTH_INDEX, c.depthIndex);
		buf.putFloat(base + Protocol.C_HUNTER_YAW, c.hunterYawDeg);
		INT.setRelease(buf, base + Protocol.C_SEQ, s + 2);
	}

	// -- ray queries -------------------------------------------------------------------------

	/** True when the host game has answered the last batch (or none was ever sent). */
	public boolean raysIdle() {
		int base = Protocol.OFF_RAYS;
		return (int) INT.getAcquire(buf, base + Protocol.R_REQ_SEQ) == (int) INT.getAcquire(buf, base + Protocol.R_RESP_SEQ);
	}

	/**
	 * Submits {@code count} rays: {@code rays} holds start.xyz, end.xyz per ray, in host-game units.
	 * Returns the request sequence number to wait for.
	 */
	public int submitRays(float[] rays, int count, int flags, int[] filter) {
		int base = Protocol.OFF_RAYS;
		if (filter != null) {
			buf.putInt(base + Protocol.R_FILTER_A, filter[0]);
			buf.putInt(base + Protocol.R_FILTER_B, filter[1]);
			buf.putInt(base + Protocol.R_FILTER_C, filter[2]);
		}
		count = Math.min(count, Protocol.MAX_RAYS);
		for (int i = 0; i < count * 6; i++) {
			buf.putFloat(base + Protocol.R_RAYS + i * 4, rays[i]);
		}
		buf.putInt(base + Protocol.R_COUNT, count);
		buf.putInt(base + Protocol.R_FLAGS, flags);
		int seq = (int) INT.getOpaque(buf, base + Protocol.R_REQ_SEQ) + 1;
		INT.setRelease(buf, base + Protocol.R_REQ_SEQ, seq);
		return seq;
	}

	public boolean raysDone(int seq) {
		return (int) INT.getAcquire(buf, Protocol.OFF_RAYS + Protocol.R_RESP_SEQ) == seq;
	}

	/** Reads results: {@code out} gets pos.xyz, normal.xyz per ray; {@code hit} the hit flags; {@code attr} the surface attributes. */
	public void readHits(int count, float[] out, int[] hit, int[] attr) {
		int base = Protocol.OFF_RAYS + Protocol.R_HITS;
		for (int i = 0; i < count; i++) {
			int o = base + i * Protocol.HIT_SIZE;
			for (int k = 0; k < 6; k++) {
				out[i * 6 + k] = buf.getFloat(o + k * 4);
			}
			hit[i] = buf.getInt(o + 24);
			attr[i] = buf.getInt(o + 28);
		}
	}

	/**
	 * Seqlock read of the stand-in hunter's hit counters: {hitCount, totalDamage, lastDamage,
	 * fromX, fromY, fromZ, maxHp, lastKind, totalStatusDamage}.
	 */
	public boolean readHunterEvents(float[] out) {
		int base = Protocol.OFF_HUNTER;
		for (int tries = 0; tries < 2000; tries++) {
			int s1 = (int) INT.getAcquire(buf, base + Protocol.HE_SEQ);
			if ((s1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			out[0] = buf.getInt(base + Protocol.HE_HIT_COUNT);
			out[1] = buf.getFloat(base + Protocol.HE_TOTAL_DAMAGE);
			out[2] = buf.getFloat(base + Protocol.HE_LAST_DAMAGE);
			for (int i = 0; i < 3; i++) {
				out[3 + i] = buf.getFloat(base + Protocol.HE_LAST_FROM + i * 4);
			}
			out[6] = buf.getFloat(base + Protocol.HE_MAX_HP);
			out[7] = buf.getInt(base + Protocol.HE_LAST_KIND);
			out[8] = buf.getFloat(base + Protocol.HE_TOTAL_STATUS_DAMAGE);
			VarHandle.acquireFence();
			if ((int) INT.getAcquire(buf, base + Protocol.HE_SEQ) == s1) {
				return true;
			}
		}
		return false;
	}

	// -- entities & damage ----------------------------------------------------------------------

	/** Seqlock read of the host game's hittable-entity table into {@code out} (resized as needed). */
	public boolean readEntities(java.util.List<EntityInfo> out) {
		int base = Protocol.OFF_ENTITIES;
		for (int tries = 0; tries < 2000; tries++) {
			int s1 = (int) INT.getAcquire(buf, base + Protocol.E_SEQ);
			if ((s1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int count = Math.min(buf.getInt(base + Protocol.E_COUNT), Protocol.MAX_ENTITIES);
			while (out.size() < count) {
				out.add(new EntityInfo());
			}
			while (out.size() > count) {
				out.remove(out.size() - 1);
			}
			for (int i = 0; i < count; i++) {
				out.get(i).read(buf, base + Protocol.E_ENTRIES + i * Protocol.ENTITY_SIZE);
			}
			VarHandle.acquireFence();
			if ((int) INT.getAcquire(buf, base + Protocol.E_SEQ) == s1) {
				return s1 != 0;
			}
		}
		return false;
	}

	/**
	 * Seqlock read of the open-doorway table: {@link Protocol#PASSAGE_FLOATS} floats per passage
	 * into {@code out} (the id as a float-encoded int bit pattern). Returns the count, or -1 if no
	 * consistent read; {@code zoneOut[0]} gets the zone the positions belong to.
	 */
	public int readPassages(float[] out, int[] zoneOut) {
		int base = Protocol.OFF_PASSAGES;
		for (int tries = 0; tries < 2000; tries++) {
			int s1 = (int) INT.getAcquire(buf, base + Protocol.P_SEQ);
			if ((s1 & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int count = Math.min(Math.max(buf.getInt(base + Protocol.P_COUNT), 0),
				Math.min(Protocol.MAX_PASSAGES, out.length / Protocol.PASSAGE_FLOATS));
			zoneOut[0] = buf.getInt(base + Protocol.P_ZONE);
			for (int i = 0; i < count; i++) {
				int e = base + Protocol.P_ENTRIES + i * Protocol.PASSAGE_SIZE;
				for (int k = 0; k < 7; k++) {
					out[i * Protocol.PASSAGE_FLOATS + k] = buf.getFloat(e + k * 4);
				}
				out[i * Protocol.PASSAGE_FLOATS + 7] = Float.intBitsToFloat(buf.getInt(e + 0x1C));
			}
			VarHandle.acquireFence();
			if ((int) INT.getAcquire(buf, base + Protocol.P_SEQ) == s1) {
				return count;
			}
		}
		return -1;
	}

	/** Queues a hit for the host game to apply. Returns false if the ring is full. */
	public boolean pushDamage(long id, float amount, float x, float y, float z, int flags) {
		int base = Protocol.OFF_DAMAGE;
		int write = (int) INT.getOpaque(buf, base + Protocol.DQ_WRITE);
		int read = (int) INT.getAcquire(buf, base + Protocol.DQ_READ);
		if (write - read >= Protocol.DAMAGE_RING) {
			return false;
		}
		int o = base + Protocol.DQ_RING + Math.floorMod(write, Protocol.DAMAGE_RING) * Protocol.DAMAGE_SIZE;
		buf.putLong(o, id);
		buf.putFloat(o + 8, amount);
		buf.putFloat(o + 12, x);
		buf.putFloat(o + 16, y);
		buf.putFloat(o + 20, z);
		buf.putInt(o + 24, flags);
		INT.setRelease(buf, base + Protocol.DQ_WRITE, write + 1);
		return true;
	}

	private void putFloats(int off, float[] v) {
		for (int i = 0; i < v.length; i++) {
			buf.putFloat(off + i * 4, v[i]);
		}
	}
}
