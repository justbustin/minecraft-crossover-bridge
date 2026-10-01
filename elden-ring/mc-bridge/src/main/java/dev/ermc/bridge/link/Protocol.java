package dev.ermc.bridge.link;

/**
 * Java mirror of elden-ring/er-bridge/include/bridge_protocol.h. Keep both in sync.
 */
public final class Protocol {
	private Protocol() {
	}

	public static final String SHM_PATH = "/tmp/ermc/bridge.shm";
	public static final int MAGIC = 0x434D484D;
	public static final int VERSION = 1;
	public static final int SHM_SIZE = 8 * 1024 * 1024;

	public static final int OFF_HEADER = 0x000000;
	public static final int OFF_STATE = 0x000100;
	public static final int OFF_CONTROL = 0x000800;
	public static final int OFF_CMD = 0x001000;

	// ErmcHeader
	public static final int H_MAGIC = 0x00;
	public static final int H_VERSION = 0x04;
	public static final int H_SIZE = 0x08;
	public static final int H_HOST_HEARTBEAT = 0x10;
	public static final int H_MC_HEARTBEAT = 0x18;
	public static final int H_HOST_PID = 0x20;
	public static final int H_MC_PID = 0x24;
	public static final int H_HOST_START = 0x28;
	public static final int H_MC_START = 0x30;
	public static final int H_MC_SWITCH_REQ = 0x48;
	public static final int H_HOST_FOCUS_REQ = 0x4C;
	/** Elden Ring: +1 each time the Tarnished is usable again where Minecraft didn't put it (load, respawn, warp). */
	public static final int H_HOST_LIFE = 0x58;
	/** Minecraft: +1 each time its player dies (the standing-in Tarnished dies too). */
	public static final int H_MC_DEATHS = 0x5C;
	/** Elden Ring: +1 each time the standing-in Tarnished died there (Minecraft's player dies too). */
	public static final int H_HOST_DEATHS = 0x60;
	/** Minecraft: +1 = perform Elden Ring's current action (open a door, pick something up...). */
	public static final int H_MC_ACTION_REQ = 0x70;
	public static final int H_HOST_ACTION_ACK = 0x74;
	/** 1 = done, 0 = nothing to do there, negative = not supported. */
	public static final int H_HOST_ACTION_RESULT = 0x78;
	public static final int H_HOST_PROMPT_SEQ = 0x7C;
	/** The action Elden Ring currently offers, NUL-terminated UTF-8, 64 bytes. */
	public static final int H_HOST_PROMPT = 0x80;

	// ErmcGameState.flags
	public static final int STATE_CAMERA_VALID = 1;
	public static final int STATE_PLAYER_VALID = 1 << 1;
	public static final int STATE_WINDOW_VALID = 1 << 2;
	public static final int STATE_CAM_OVERRIDDEN = 1 << 3;
	public static final int STATE_MATRICES_VALID = 1 << 4;
	public static final int STATE_WINDOW_FOCUSED = 1 << 5;
	public static final int STATE_COMPOSITING = 1 << 6;
	/** The Tarnished is dying or dead: Elden Ring's death camera and "YOU DIED" are showing. */
	public static final int STATE_PLAYER_DEAD = 1 << 7;
	/** Dead, loading or settling after one: keep the overlay, draw nothing over Elden Ring. */
	public static final int STATE_HOST_BUSY = 1 << 8;

	// ErmcGameState offsets (relative to OFF_STATE)
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

	// ErmcControl.flags
	public static final int CTRL_OVERRIDE_CAMERA = 1;
	public static final int CTRL_MOVE_HUNTER = 1 << 1;
	public static final int CTRL_HIDE_HUNTER = 1 << 2;
	public static final int CTRL_CAPTURE_DEPTH = 1 << 3;
	public static final int CTRL_COMPOSITE = 1 << 4;
	public static final int CTRL_NO_DEPTH_TEST = 1 << 5;
	public static final int CTRL_DEBUG_DEPTH = 1 << 6;

	// ErmcControl offsets (relative to OFF_CONTROL)
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

	// ErmcHunterEvents (hits the stand-in hunter took), absolute offset
	public static final int OFF_HUNTER = 0x0A00;
	public static final int HE_SEQ = 0x00;
	public static final int HE_HIT_COUNT = 0x04;
	public static final int HE_TOTAL_DAMAGE = 0x08;
	public static final int HE_LAST_DAMAGE = 0x0C;
	public static final int HE_LAST_FROM = 0x10;
	public static final int HE_MAX_HP = 0x1C;
	public static final int HE_LAST_KIND = 0x28;
	public static final int HE_TOTAL_STATUS_DAMAGE = 0x2C;

	// Ray queries (ErmcRayHeader), relative to OFF_RAYS
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

	// Entity table (ErmcEntityTable), relative to OFF_ENTITIES
	public static final int OFF_ENTITIES = 0x200000;
	public static final int MAX_ENTITIES = 256;
	public static final int E_SEQ = 0x00;
	public static final int E_COUNT = 0x04;
	public static final int E_FRAME = 0x08;
	public static final int E_ENTRIES = 0x10;
	public static final int ENTITY_SIZE = 0x80;
	/** Boss (team 7) or big hostile character: an enemy for Minecraft mobs. */
	public static final int ENT_LARGE_MONSTER = 1;
	/** Hostile character (team 6): an enemy for Minecraft mobs. */
	public static final int ENT_SMALL_MONSTER = 2;
	/** Friendly or neutral NPC: never an enemy; the DLL ignores all damage to it. */
	public static final int ENT_OTHER = 3;
	// ErmcEntity.flags
	/** Dead or dying in the host game (a corpse still in its character list). */
	public static final int ENTITY_DEAD = 1;
	/** boxHalf is along the world axes (ignore quat). */
	public static final int ENTITY_WORLD_BOX = 1 << 1;

	// Open doorways (ErmcPassageTable), relative to OFF_PASSAGES: corridors where Minecraft
	// places no wall blocks (its 1-block columns can't represent a 1 m opening at an angle).
	public static final int OFF_PASSAGES = 0x300000;
	public static final int MAX_PASSAGES = 64;
	public static final int P_SEQ = 0x00;
	public static final int P_COUNT = 0x04;
	public static final int P_ZONE = 0x08;
	public static final int P_ENTRIES = 0x10;
	public static final int PASSAGE_SIZE = 0x20;
	/** Floats per passage in {@link BridgeShm#readPassages}: x, y, z, yaw, halfWidth, halfDepth, height, id. */
	public static final int PASSAGE_FLOATS = 8;

	// Damage ring (ErmcDamageQueue), relative to OFF_DAMAGE.
	// ErmcDamage.amount is in Minecraft damage points, after all of Minecraft's own modifiers
	// (critical hits, enchantments, Strength, explosion falloff): exactly what Minecraft's hurt()
	// got. The DLL converts it to host-game HP by the enemy's max HP itself (Elden Ring:
	// hp = amount * maxHp / clamp(20 * sqrt(maxHp / 100), 10, 300)) and ignores amounts <= 0.
	public static final int OFF_DAMAGE = 0x280000;
	public static final int DAMAGE_RING = 256;
	public static final int DQ_WRITE = 0x00;
	public static final int DQ_READ = 0x04;
	public static final int DQ_RING = 0x10;
	public static final int DAMAGE_SIZE = 0x20;
	/** A Minecraft critical hit by a player: a falling melee attack, or a fully drawn bow/crossbow arrow. */
	public static final int DAMAGE_CRITICAL = 1;
	/** Explosion/projectile: the hit point is where the blow met the body, not the attacker's aim. */
	public static final int DAMAGE_OUTWARD = 1 << 1;
	/**
	 * Not dealt by a player: a Minecraft mob (zombie, skeleton arrow, creeper, iron golem...),
	 * or poison/wither/lightning with no one behind it. The DLL may leave the Tarnished out of it
	 * (no kill credit, no aggro poke). 0 = a player's hit, the old meaning of every entry.
	 * Minecraft-side addition; bridge_protocol.h should mirror it as ERMC_DAMAGE_NOT_BY_PLAYER.
	 */
	public static final int DAMAGE_NOT_BY_PLAYER = 1 << 2;
}
