package dev.mhwmc.bridge.link;

/**
 * Java mirror of mhw-bridge/include/bridge_protocol.h. Keep both in sync.
 */
public final class Protocol {
	private Protocol() {
	}

	public static final String SHM_PATH = "/tmp/mhwmc/bridge.shm";
	public static final int MAGIC = 0x434D484D;
	public static final int VERSION = 1;
	public static final int SHM_SIZE = 8 * 1024 * 1024;

	public static final int OFF_HEADER = 0x000000;
	public static final int OFF_STATE = 0x000100;
	public static final int OFF_CONTROL = 0x000800;
	public static final int OFF_CMD = 0x001000;

	// MhmcHeader
	public static final int H_MAGIC = 0x00;
	public static final int H_VERSION = 0x04;
	public static final int H_SIZE = 0x08;
	public static final int H_MHW_HEARTBEAT = 0x10;
	public static final int H_MC_HEARTBEAT = 0x18;
	public static final int H_MHW_PID = 0x20;
	public static final int H_MC_PID = 0x24;
	public static final int H_MHW_START = 0x28;
	public static final int H_MC_START = 0x30;
	public static final int H_MC_SWITCH_REQ = 0x48;
	public static final int H_MHW_FOCUS_REQ = 0x4C;

	// MhmcGameState.flags
	public static final int STATE_CAMERA_VALID = 1;
	public static final int STATE_PLAYER_VALID = 1 << 1;
	public static final int STATE_WINDOW_VALID = 1 << 2;
	public static final int STATE_CAM_OVERRIDDEN = 1 << 3;
	public static final int STATE_MATRICES_VALID = 1 << 4;
	public static final int STATE_WINDOW_FOCUSED = 1 << 5;
	public static final int STATE_COMPOSITING = 1 << 6;

	// MhmcGameState offsets (relative to OFF_STATE)
	public static final int S_SEQ = 0x00;
	public static final int S_FLAGS = 0x04;
	public static final int S_FRAME = 0x08;
	public static final int S_CAM_POS = 0x10;
	public static final int S_CAM_TARGET = 0x1C;
	public static final int S_CAM_UP = 0x28;
	public static final int S_FOVY = 0x34;
	public static final int S_NEAR = 0x38;
	public static final int S_FAR = 0x3C;
	public static final int S_ASPECT = 0x40;
	public static final int S_UNITS_PER_METER = 0x44;
	public static final int S_PLAYER_POS = 0x48;
	public static final int S_PLAYER_QUAT = 0x54;
	public static final int S_WIN = 0x64;
	public static final int S_BB = 0x74;
	public static final int S_STAGE = 0x7C;
	public static final int S_VIEW = 0x80;
	public static final int S_PROJ = 0xC0;

	// MhmcControl.flags
	public static final int CTRL_OVERRIDE_CAMERA = 1;
	public static final int CTRL_MOVE_HUNTER = 1 << 1;
	public static final int CTRL_HIDE_HUNTER = 1 << 2;
	public static final int CTRL_CAPTURE_DEPTH = 1 << 3;
	public static final int CTRL_COMPOSITE = 1 << 4;
	public static final int CTRL_NO_DEPTH_TEST = 1 << 5;
	public static final int CTRL_DEBUG_DEPTH = 1 << 6;

	// MhmcControl offsets (relative to OFF_CONTROL)
	public static final int C_SEQ = 0x00;
	public static final int C_FLAGS = 0x04;
	public static final int C_MC_FRAME = 0x08;
	public static final int C_CAM_POS = 0x10;
	public static final int C_CAM_TARGET = 0x1C;
	public static final int C_CAM_UP = 0x28;
	public static final int C_FOVY = 0x34;
	public static final int C_HUNTER_POS = 0x38;
	public static final int C_POSE_LAG = 0x44;
	public static final int C_DEPTH_INDEX = 0x48;
	public static final int C_HUNTER_YAW = 0x58;

	// MhmcHunterEvents (hits the stand-in hunter took), absolute offset
	public static final int OFF_HUNTER = 0x0A00;
	public static final int HE_SEQ = 0x00;
	public static final int HE_HIT_COUNT = 0x04;
	public static final int HE_TOTAL_DAMAGE = 0x08;
	public static final int HE_LAST_DAMAGE = 0x0C;
	public static final int HE_LAST_FROM = 0x10;
	public static final int HE_MAX_HP = 0x1C;
	public static final int HE_LAST_KIND = 0x28;
	public static final int HE_TOTAL_STATUS_DAMAGE = 0x2C;

	// Ray queries (MhmcRayHeader), relative to OFF_RAYS
	public static final int OFF_RAYS = 0x100000;
	public static final int MAX_RAYS = 8192;
	public static final int RAYS_CAMERA_FILTER = 1;
	public static final int R_REQ_SEQ = 0x00;
	public static final int R_RESP_SEQ = 0x04;
	public static final int R_COUNT = 0x08;
	public static final int R_FLAGS = 0x0C;
	public static final int R_PROCESSED = 0x10;
	public static final int R_FILTER_A = 0x14;
	public static final int R_FILTER_B = 0x18;
	public static final int R_FILTER_C = 0x1C;
	public static final int RAYS_CUSTOM_FILTER = 2;
	public static final int R_RAYS = 0x20;            // MAX_RAYS x {f32 start[3], f32 end[3]}
	public static final int R_HITS = 0x20 + MAX_RAYS * 24; // MAX_RAYS x {f32 pos[3], f32 normal[3], u32 hit, u32 pad}
	public static final int RAY_SIZE = 24;
	public static final int HIT_SIZE = 32;

	// Entity table (MhmcEntityTable), relative to OFF_ENTITIES
	public static final int OFF_ENTITIES = 0x200000;
	public static final int MAX_ENTITIES = 256;
	public static final int E_SEQ = 0x00;
	public static final int E_COUNT = 0x04;
	public static final int E_FRAME = 0x08;
	public static final int E_ENTRIES = 0x10;
	public static final int ENTITY_SIZE = 0x80;
	public static final int ENT_LARGE_MONSTER = 1;
	public static final int ENT_SMALL_MONSTER = 2;
	public static final int ENT_OTHER = 3;

	// Damage ring (MhmcDamageQueue), relative to OFF_DAMAGE
	public static final int OFF_DAMAGE = 0x280000;
	public static final int DAMAGE_RING = 256;
	public static final int DQ_WRITE = 0x00;
	public static final int DQ_READ = 0x04;
	public static final int DQ_RING = 0x10;
	public static final int DAMAGE_SIZE = 0x20;
	public static final int DAMAGE_CRITICAL = 1;
	/** Explosion/projectile: the hit point is where the blow met the body, not the attacker's aim. */
	public static final int DAMAGE_OUTWARD = 1 << 1;

	// Hazards: live MHW monster attacks that affect Minecraft's world (MhmcHazardTable), relative to OFF_HAZARDS
	public static final int OFF_HAZARDS = 0x290000;
	public static final int MAX_HAZARDS = 64;
	public static final int HZ_SEQ = 0x00;
	public static final int HZ_COUNT = 0x04;
	public static final int HZ_FRAME = 0x08;
	public static final int HZ_ENTRIES = 0x10;
	public static final int HAZARD_SIZE = 0x30;
	/** Flames: they light TNT. */
	public static final int HAZARD_FIRE = 1;
}
