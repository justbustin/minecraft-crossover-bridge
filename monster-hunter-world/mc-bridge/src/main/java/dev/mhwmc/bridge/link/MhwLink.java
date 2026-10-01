package dev.mhwmc.bridge.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide handle on the MHW bridge. Used from both the client render thread and the
 * integrated server thread, so all access is synchronized (every operation is cheap).
 */
public final class MhwLink {
	private static final Logger LOG = LoggerFactory.getLogger("mhwbridge");
	private static final MhwLink INSTANCE = new MhwLink();
	/** MHW counts as connected if its frame counter moved within this window. */
	private static final long ALIVE_TIMEOUT_MS = 2000;

	private BridgeShm shm;
	private long lastOpenAttempt;
	private long lastHeartbeat = Long.MIN_VALUE;
	private long lastHeartbeatChange;
	private final GameState latest = new GameState();
	private final GameState scratch = new GameState();
	private boolean hasState;

	public static MhwLink get() {
		return INSTANCE;
	}

	/** Refreshes the cached state. Returns true while MHW is running and publishing frames. */
	public synchronized boolean poll() {
		long now = System.currentTimeMillis();
		if (shm == null) {
			if (now - lastOpenAttempt < 1000) {
				return false;
			}
			lastOpenAttempt = now;
			try {
				shm = BridgeShm.open();
				LOG.info("Mapped {}", Protocol.SHM_PATH);
			} catch (Exception e) {
				LOG.warn("Could not map {}: {}", Protocol.SHM_PATH, e.toString());
				return false;
			}
		}
		long hb = shm.mhwHeartbeat();
		if (lastHeartbeat == Long.MIN_VALUE) {
			// First sample: a stale counter from a previous MHW session must not count as live.
			lastHeartbeat = hb;
			lastHeartbeatChange = 0;
		} else if (hb != lastHeartbeat) {
			lastHeartbeat = hb;
			lastHeartbeatChange = now;
		}
		boolean alive = lastHeartbeatChange > 0 && now - lastHeartbeatChange < ALIVE_TIMEOUT_MS;
		if (alive && shm.readState(scratch)) {
			latest.copyFrom(scratch);
			hasState = true;
		}
		return alive;
	}

	/** MHW's frame counter (one per presented frame) as of the last {@link #poll()}. */
	public synchronized long mhwFrame() {
		return lastHeartbeat;
	}

	public synchronized boolean alive() {
		return shm != null && lastHeartbeatChange > 0 && System.currentTimeMillis() - lastHeartbeatChange < ALIVE_TIMEOUT_MS;
	}

	/** Copies the latest MHW state into {@code out}; returns false if none is available. */
	public synchronized boolean snapshot(GameState out) {
		if (!hasState) {
			return false;
		}
		out.copyFrom(latest);
		return true;
	}

	public synchronized void writeControl(ControlState c) {
		if (shm != null) {
			shm.writeControl(c);
		}
	}

	/** Returns the request sequence, or -1 if a batch is still in flight / MHW is not attached. */
	public synchronized int submitRays(float[] rays, int count, int flags, int[] filter) {
		if (shm == null || !alive() || !shm.raysIdle()) {
			return -1;
		}
		return shm.submitRays(rays, count, flags, filter);
	}

	public synchronized boolean raysDone(int seq) {
		return shm != null && shm.raysDone(seq);
	}

	public synchronized void readHits(int count, float[] out, int[] hit, int[] attr) {
		if (shm != null) {
			shm.readHits(count, out, hit, attr);
		}
	}

	public synchronized boolean readHunterEvents(float[] out) {
		return shm != null && shm.readHunterEvents(out);
	}

	public synchronized boolean readEntities(java.util.List<EntityInfo> out) {
		return shm != null && alive() && shm.readEntities(out);
	}

	/**
	 * MHW's live hazards; returns their age in MHW frames (see {@link BridgeShm#readHazards}),
	 * or {@link Long#MIN_VALUE} if MHW is not running.
	 */
	public synchronized long readHazards(java.util.List<HazardInfo> out) {
		return shm != null && alive() ? shm.readHazards(out) : Long.MIN_VALUE;
	}

	public synchronized boolean pushDamage(long id, float amount, float x, float y, float z, int flags) {
		return shm != null && shm.pushDamage(id, amount, x, y, z, flags);
	}

	public synchronized int mcSwitchRequests() {
		return shm != null ? shm.mcSwitchRequests() : 0;
	}

	public synchronized void requestMhwFocus() {
		if (shm != null) {
			shm.requestMhwFocus();
		}
	}

	public synchronized void bumpMcHeartbeat() {
		if (shm != null) {
			shm.bumpMcHeartbeat();
		}
	}
}
