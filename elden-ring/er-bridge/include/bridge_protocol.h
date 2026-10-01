// Shared-memory protocol between er-bridge (Windows DLL inside Elden Ring, running under
// CrossOver/Wine) and elden-ring/mc-bridge (Fabric mod "erbridge", native macOS JVM).
//
// Same layout as the Monster Hunter: World bridge (monster-hunter-world/mhw-bridge/include/bridge_protocol.h),
// including the magic value, but in its own directory (/tmp/ermc/), so the two never mix.
// "Hunter" fields refer to the host game's player character (the Tarnished here).
//
// Transport: one file-backed mapping. Wine maps file views with MAP_SHARED, so both
// processes see the same physical pages (verified under CrossOver on Apple Silicon):
//     macOS : /tmp/ermc/bridge.shm
//     Wine  : Z:\tmp\ermc\bridge.shm
//
// Everything is little-endian. Blocks that are written while the other side may be
// reading use a seqlock: the writer bumps `seq` to an odd value, writes the payload,
// then bumps it to the next even value. Readers retry if `seq` was odd or changed.
//
// The Java mirror of these offsets lives in elden-ring/mc-bridge: dev.ermc.bridge.link.Protocol.
// Keep both files in sync.
#pragma once
#include <stdint.h>

#define ERMC_MAGIC 0x434D484Du  /* bytes "MHMC" (kept identical to the MHW bridge) */
#define ERMC_VERSION 1u
#define ERMC_SHM_SIZE (8u * 1024u * 1024u)

#define ERMC_OFF_HEADER   0x000000u
#define ERMC_OFF_STATE    0x000100u  /* ErmcGameState, written by ER          */
#define ERMC_OFF_CONTROL  0x000800u  /* ErmcControl,   written by Minecraft    */
#define ERMC_OFF_HUNTER   0x000A00u  /* ErmcHunterEvents, written by ER              */
#define ERMC_OFF_CMD      0x001000u  /* ErmcCmdBlock,  debug/RE command mailbox */
#define ERMC_OFF_CMD_RESP 0x002000u  /* command response payload                */
#define ERMC_CMD_RESP_MAX (0x100000u - ERMC_OFF_CMD_RESP)
#define ERMC_OFF_RAYS     0x100000u  /* ErmcRayHeader + rays + hits (terrain queries)  */
#define ERMC_OFF_ENTITIES 0x200000u  /* ErmcEntityTable, written by ER                */
#define ERMC_OFF_DAMAGE   0x280000u  /* ErmcDamageQueue, written by Minecraft          */
#define ERMC_OFF_PASSAGES 0x300000u  /* ErmcPassageTable, written by ER (open doorways)  */

#pragma pack(push, 4)

typedef struct ErmcHeader {
    uint32_t magic;               /* 0x00 */
    uint32_t version;             /* 0x04 */
    uint32_t size;                /* 0x08 */
    uint32_t reserved0;           /* 0x0C */
    volatile uint64_t hostHeartbeat;  /* 0x10 incremented once per ER frame (Present) */
    volatile uint64_t mcHeartbeat;   /* 0x18 incremented once per Minecraft frame      */
    volatile uint32_t hostPid;     /* 0x20 */
    volatile uint32_t mcPid;      /* 0x24 */
    volatile uint64_t hostStartMs; /* 0x28 unix ms when the DLL attached */
    volatile uint64_t mcStartMs;  /* 0x30 unix ms when the mod attached */
    /* Hot reload of erbridge_core.dll: a client bumps coreReloadReq after copying a new
     * build to <game>\erbridge\core_next.dll; the dinput8 loader swaps the core and sets
     * coreReloadAck = coreReloadReq. coreGeneration counts successful loads. */
    volatile uint32_t coreReloadReq;  /* 0x38 */
    volatile uint32_t coreReloadAck;  /* 0x3C */
    volatile uint32_t coreGeneration; /* 0x40 */
    volatile int32_t coreStatus;      /* 0x44 1 = running, 0 = not loaded, <0 = load error */
    /* Switching control between the games (F8 on either side). */
    volatile uint32_t mcSwitchReq;    /* 0x48 ER -> MC: F8 pressed in ER, give control back to Minecraft */
    volatile uint32_t hostFocusReq;    /* 0x4C MC -> ER: bring ER's window to the front */
    /* er-bridge only: the RWX page holding the per-frame task objects registered with the
     * game's task system (they can't be unregistered, so they must outlive core reloads). */
    volatile uint64_t hostTaskPage;    /* 0x50 */
    /* One life shared by both games (see game.cpp "Life"). Counters, so no edge is missed. */
    volatile uint32_t hostLife;        /* 0x58 ER -> MC: +1 each time the Tarnished is usable again at a place
                                        * Minecraft didn't put it (after loading, respawn, a game warp); Minecraft
                                        * moves its player there before driving again */
    volatile uint32_t mcDeaths;        /* 0x5C MC -> ER: +1 each time Minecraft's player dies; if the Tarnished
                                        * was standing in, it dies too (Elden Ring respawns it at a grace) */
    volatile uint32_t hostDeaths;      /* 0x60 ER -> MC: +1 each time the standing-in Tarnished died in Elden Ring
                                        * (kill plane...); Minecraft's player dies too */
    volatile uint32_t debugFlags;      /* 0x64 dev toggles set by erctl.py: bit0 = compositor test pattern */
    /* er-bridge only: the RWX page with the Present hook stubs patched into D3DMetal's shared
     * IDXGISwapChain table (like hostTaskPage, it must outlive core reloads). */
    volatile uint64_t hostPresentPage; /* 0x68 */
    /* Elden Ring's "action" (interact: doors, levers, items, graces, lost runes) from Minecraft. */
    volatile uint32_t mcActionReq;     /* 0x70 MC -> ER: +1 = perform the action the game currently offers */
    volatile uint32_t hostActionAck;   /* 0x74 ER -> MC: = mcActionReq once handled */
    volatile int32_t hostActionResult; /* 0x78 1 = done, 0 = nothing to do there, <0 = not supported */
    volatile uint32_t hostPromptSeq;   /* 0x7C ER -> MC: +1 whenever hostPrompt changes */
    char hostPrompt[64];               /* 0x80 ER -> MC: the action on offer ("Open", "Pick up"...), "" if none */
} ErmcHeader;

/* ErmcGameState.flags */
#define ERMC_STATE_CAMERA_VALID   (1u << 0)
#define ERMC_STATE_PLAYER_VALID   (1u << 1)
#define ERMC_STATE_WINDOW_VALID   (1u << 2)
#define ERMC_STATE_CAM_OVERRIDDEN (1u << 3) /* ER rendered this frame with the Minecraft camera */
#define ERMC_STATE_MATRICES_VALID (1u << 4)
#define ERMC_STATE_WINDOW_FOCUSED (1u << 5)
#define ERMC_STATE_COMPOSITING    (1u << 6) /* ER is drawing Minecraft's frames itself */
#define ERMC_STATE_PLAYER_DEAD    (1u << 7) /* the Tarnished is dying or dead: Elden Ring's own death camera and
                                             * "YOU DIED" play, Minecraft draws nothing over them */
#define ERMC_STATE_HOST_BUSY      (1u << 8) /* in a session but no usable Tarnished (death, loading screen, settling
                                             * after one): Minecraft keeps its overlay but draws nothing */

typedef struct ErmcGameState {
    volatile uint32_t seq;        /* 0x00 seqlock */
    uint32_t flags;               /* 0x04 ERMC_STATE_* */
    uint64_t frame;               /* 0x08 ER frame counter */
    float camPos[3];              /* 0x10 camera eye, ER world units */
    float camTarget[3];           /* 0x1C camera look-at point */
    float camUp[3];               /* 0x28 */
    float fovYDeg;                /* 0x34 vertical field of view, degrees */
    float nearZ;                  /* 0x38 */
    float farZ;                   /* 0x3C */
    float aspect;                 /* 0x40 */
    float unitsPerMeter;          /* 0x44 game units per meter (Elden Ring: 1, metres) */
    float playerPos[3];           /* 0x48 hunter position */
    float playerQuat[4];          /* 0x54 hunter rotation (x,y,z,w) */
    int32_t winX, winY, winW, winH; /* 0x64 game client area in screen coordinates */
    uint32_t bbW, bbH;            /* 0x74 swapchain back buffer size */
    uint32_t stageId;             /* 0x7C current zone id (sPlayer+0xAED0: 504 Training Area, 101 Ancient Forest...), 0 if unknown */
    float view[16];               /* 0x80 view matrix as the game stores it (if known) */
    float proj[16];               /* 0xC0 projection matrix as the game stores it */
} ErmcGameState;                  /* 0x100 */

/* ErmcControl.flags */
#define ERMC_CTRL_OVERRIDE_CAMERA (1u << 0) /* drive the ER camera from camPos/camTarget/fov */
#define ERMC_CTRL_MOVE_HUNTER     (1u << 1) /* hunter stands in for the Minecraft player at hunterPos (takes its hits) */
#define ERMC_CTRL_HIDE_HUNTER     (1u << 2)
#define ERMC_CTRL_CAPTURE_DEPTH   (1u << 3) /* reserved */
#define ERMC_CTRL_COMPOSITE       (1u << 4) /* draw Minecraft's frames (frames.shm) into ER's frame */
#define ERMC_CTRL_NO_DEPTH_TEST   (1u << 5) /* debug: composite without occlusion */
#define ERMC_CTRL_DEBUG_DEPTH     (1u << 6) /* debug: show ER's depth buffer instead */
#define ERMC_CTRL_NO_RELIGHT      (1u << 7) /* debug: no ER lighting on Minecraft pixels */

typedef struct ErmcControl {
    volatile uint32_t seq;        /* 0x00 seqlock */
    uint32_t flags;               /* 0x04 ERMC_CTRL_* */
    uint64_t mcFrame;             /* 0x08 */
    float camPos[3];              /* 0x10 desired camera eye, ER units */
    float camTarget[3];           /* 0x1C desired look-at point */
    float camUp[3];               /* 0x28 */
    float fovYDeg;                /* 0x34 desired vertical FOV */
    float hunterPos[3];           /* 0x38 */
    uint32_t poseLag;             /* 0x44 game frames between applying a pose and presenting it */
    uint32_t depthIndex;          /* 0x48 which ER depth buffer is the scene depth (0/1) */
    float lightGain;              /* 0x4C relighting: light = lightMin + ambientLum * lightGain (0 = default) */
    float lightMin;               /* 0x50 */
    float fogStrength;            /* 0x54 0 = default */
    float hunterYawDeg;           /* 0x58 hunter facing (Minecraft yaw, degrees) when MOVE_HUNTER */
} ErmcControl;                    /* 0x5C */

/* Hits taken by the hunter while it stands in for the Minecraft player (MOVE_HUNTER):
 * monotonic counters, so Minecraft applies the difference since its last read. */
typedef struct ErmcHunterEvents {
    volatile uint32_t seq;        /* 0x00 seqlock */
    uint32_t hitCount;            /* 0x04 */
    float totalDamage;            /* 0x08 ER HP lost, summed */
    float lastDamage;             /* 0x0C */
    float lastHitFrom[3];         /* 0x10 position of the monster closest to the hunter at the last hit */
    float hunterMaxHp;            /* 0x1C */
    uint64_t lastHitFrame;        /* 0x20 */
    uint32_t lastHitKind;         /* 0x28 ERMC_ENT_* of the monster closest to the hunter at the last hit */
    float totalStatusDamage;      /* 0x2C ER HP lost to status damage (poison, fire...: < 1 HP per frame), summed */
} ErmcHunterEvents;               /* 0x30 */

/* Debug/RE command mailbox. The client fills cmd/argLen/args, then increments reqSeq.
 * The DLL executes it, fills status/respLen and the payload at ERMC_OFF_CMD_RESP, then
 * sets respSeq = reqSeq. */
enum ErmcCmd {
    ERMC_CMD_PING       = 1, /* -> text info */
    ERMC_CMD_READ       = 2, /* {u64 addr, u32 len} -> bytes */
    ERMC_CMD_WRITE      = 3, /* {u64 addr, u32 len, bytes} -> nothing */
    ERMC_CMD_READ_MANY  = 4, /* {u32 count, u32 len, u64 addrs[count]} -> u8 ok[count], then count*len bytes */
    ERMC_CMD_SCAN       = 5, /* {u64 start, u64 end, u32 patLen, u32 maxResults, u32 flags, u8 pat[patLen], u8 mask[patLen]} -> u64[] */
    ERMC_CMD_SCAN_FLOAT = 6, /* {u64 start, u64 end, f32 lo, f32 hi, u32 align, u32 maxResults, u32 flags} -> u64[] */
    ERMC_CMD_QUERY      = 7, /* {u64 addr} -> {u64 base, u64 allocBase, u64 size, u32 state, u32 protect, u32 type} */
    ERMC_CMD_MODULES    = 8, /* -> repeated {u64 base, u32 size, u16 nameLen, char name[nameLen]} */
    ERMC_CMD_RAYCAST    = 9, /* {f32 start[3], f32 end[3], u32 flags} -> ErmcRayHit, run on the game thread */
    ERMC_CMD_FIRE_SHELL = 10, /* {u32 slingerShellIndex, f32 origin[3], f32 target[3]} -> u64 shell, game thread */
};

/* scan flags: which memory to scan */
#define ERMC_SCAN_IMAGE   (1u << 0)
#define ERMC_SCAN_PRIVATE (1u << 1)
#define ERMC_SCAN_MAPPED  (1u << 2)
#define ERMC_SCAN_EXEC_ONLY (1u << 3)

typedef struct ErmcCmdBlock {
    volatile uint32_t reqSeq;     /* 0x00 */
    volatile uint32_t respSeq;    /* 0x04 */
    uint32_t cmd;                 /* 0x08 */
    uint32_t argLen;              /* 0x0C */
    int32_t status;               /* 0x10 0 = ok, negative = error */
    uint32_t respLen;             /* 0x14 */
    uint8_t args[0x1000 - 0x18];  /* 0x18 */
} ErmcCmdBlock;

/* Ray queries: Minecraft asks ER's own collision system for terrain. The client writes
 * `count` ErmcRay entries, then bumps reqSeq. The DLL runs them on the game thread (a few
 * hundred per frame), fills ErmcRayHit entries and sets respSeq = reqSeq when done. */
#define ERMC_MAX_RAYS 8192
#define ERMC_RAYS_CAMERA_FILTER (1u << 0) /* use the camera's collision attribute filter */
#define ERMC_RAYS_CUSTOM_FILTER (1u << 1) /* use filterA/B/C (Param::setAttr arguments) */
#define ERMC_RAYS_HIT_SELF      (1u << 2) /* debug rays only: don't ignore our own character */

typedef struct ErmcRay {
    float start[3];
    float end[3];
} ErmcRay;                        /* 24 bytes */

typedef struct ErmcRayHit {
    float pos[3];                 /* hit position (valid if hit != 0) */
    float normal[3];              /* surface normal at the hit */
    uint32_t hit;                 /* 0 = no hit, otherwise the raw return value */
    uint32_t attr;                /* collision attribute bits of the surface that was hit */
} ErmcRayHit;                     /* 32 bytes */

typedef struct ErmcRayHeader {
    volatile uint32_t reqSeq;     /* 0x00 */
    volatile uint32_t respSeq;    /* 0x04 */
    uint32_t count;               /* 0x08 rays in this batch (<= ERMC_MAX_RAYS) */
    uint32_t flags;               /* 0x0C ERMC_RAYS_* */
    volatile uint32_t processed;  /* 0x10 progress, written by ER */
    uint32_t filterA;             /* 0x14 with ERMC_RAYS_CUSTOM_FILTER: Param::setAttr(a, b, c) */
    uint32_t filterB;             /* 0x18 */
    uint32_t filterC;             /* 0x1C */
    /* ErmcRay rays[ERMC_MAX_RAYS] at 0x20, ErmcRayHit hits[ERMC_MAX_RAYS] after them */
} ErmcRayHeader;

#define ERMC_RAYS_OFF_RAYS 0x20u
#define ERMC_RAYS_OFF_HITS (ERMC_RAYS_OFF_RAYS + ERMC_MAX_RAYS * 24u)

/* Hittable ER entities (monsters etc.), republished every frame. Each gets an invisible
 * proxy entity in Minecraft with the same hitbox, so Minecraft attacks can target it. */
#define ERMC_MAX_ENTITIES 256
#define ERMC_ENT_LARGE_MONSTER 1u
#define ERMC_ENT_SMALL_MONSTER 2u
#define ERMC_ENT_OTHER         3u

typedef struct ErmcEntity {
    uint64_t id;                  /* 0x00 ER object address; stable while the entity lives */
    uint32_t kind;                /* 0x08 ERMC_ENT_* */
    uint32_t emId;                /* 0x0C species id if known */
    float pos[3];                 /* 0x10 origin (feet), ER world units */
    float quat[4];                /* 0x1C rotation (x,y,z,w) */
    float boxCenter[3];           /* 0x2C hitbox center, world units */
    float boxHalf[3];             /* 0x38 hitbox half size: entity axes (x side, y up, z forward), or world axes if flags bit1 */
    float hp;                     /* 0x44 */
    float maxHp;                  /* 0x48 */
    uint32_t flags;               /* 0x4C bit0 dead/capturing, bit1 box is world-aligned (ignore quat) */
    char name[48];                /* 0x50 class or display name, NUL-terminated */
} ErmcEntity;                     /* 0x80 */

typedef struct ErmcEntityTable {
    volatile uint32_t seq;        /* 0x00 seqlock */
    uint32_t count;               /* 0x04 */
    uint64_t frame;               /* 0x08 */
    ErmcEntity entities[ERMC_MAX_ENTITIES]; /* 0x10 */
} ErmcEntityTable;

/* Minecraft -> ER: hits to apply. Single-producer ring: Minecraft writes ring[write % N]
 * then bumps `write`; ER applies entries until `read == write`. */
#define ERMC_DAMAGE_RING 256
typedef struct ErmcDamage {
    uint64_t id;                  /* target ErmcEntity.id; 0 = strike the world at hitPos (Minecraft hit Elden Ring ground or a prop: breaks breakable objects) */
    float amount;                 /* Minecraft damage points after all of Minecraft's modifiers (crit, enchantments,
                                   * explosion falloff); ER converts to HP by the target's max HP (game.cpp er_damage) */
    float hitPos[3];              /* where the hit landed (world units) */
    uint32_t flags;               /* ERMC_DAMAGE_* */
    uint32_t reserved;
} ErmcDamage;                     /* 0x20 */

#define ERMC_DAMAGE_CRITICAL (1u << 0)
#define ERMC_DAMAGE_OUTWARD  (1u << 1) /* explosion/projectile: hitPos is the body point nearest the blow; the
                                          hunter's slinger shot comes from outside the body at that point */
#define ERMC_DAMAGE_NOT_BY_PLAYER (1u << 2) /* a Minecraft mob or effect hit it: no kill credit or aggro for the
                                              player (Elden Ring: no last_hit_by, no poke) */

typedef struct ErmcDamageQueue {
    volatile uint32_t write;      /* 0x00 */
    volatile uint32_t read;       /* 0x04 */
    uint32_t reserved[2];         /* 0x08 */
    ErmcDamage ring[ERMC_DAMAGE_RING]; /* 0x10 */
} ErmcDamageQueue;

/* ------------------------------------------------------------------------------------
 * Open doorways, republished about twice a second while the Tarnished stands in. Minecraft
 * samples Elden Ring's walls into 1-block columns, which can't represent a 1 m opening at an
 * angle to the grid (the chapel door: 60 degrees), so it places no wall blocks inside
 * these corridors (floors stay). A corridor is found through an animated map object (a door)
 * when its centre line is clear in Elden Ring's collision and both sides hit a wall: an open
 * door in a wall. Positions are in the stable frame of `zone`. */
#define ERMC_MAX_PASSAGES 64

typedef struct ErmcPassage {
    float pos[3];                 /* 0x00 corridor centre at floor level (the object's origin) */
    float yaw;                    /* 0x0C through it: (sin yaw, 0, cos yaw) in Elden Ring */
    float halfWidth;              /* 0x10 across */
    float halfDepth;              /* 0x14 along */
    float height;                 /* 0x18 */
    uint32_t id;                  /* 0x1C per object, stable while it stays loaded */
} ErmcPassage;                    /* 0x20 */

typedef struct ErmcPassageTable {
    volatile uint32_t seq;        /* 0x00 odd while being written */
    uint32_t count;               /* 0x04 */
    uint32_t zone;                /* 0x08 */
    uint32_t reserved;            /* 0x0C */
    ErmcPassage p[ERMC_MAX_PASSAGES]; /* 0x10 */
} ErmcPassageTable;

/* ------------------------------------------------------------------------------------
 * Frame passthrough: Minecraft's rendered frames, composited by the DLL into ER's own frame
 * (depth-tested against ER's depth buffer, with the exact camera pose ER rendered).
 * Separate file so the control file stays small:
 *     macOS : /tmp/ermc/frames.shm        Wine : Z:\tmp\ermc\frames.shm
 * Layout: ErmcFramesHeader at 0, then ERMC_FRAME_SLOTS slots of ERMC_FRAME_SLOT_SIZE bytes.
 * Slot = ErmcFrameHeader + world RGBA8 + world depth (float32, OpenGL window depth [0,1])
 *        + GUI RGBA8 [+ hand RGBA8 when flags bit2], each width*height, rows bottom-up (OpenGL
 *        order), premultiplied alpha. The hand layer (hand, held item, screen effects) is lit
 *        like the world but never occluded.
 * ErmcFramesHeader.version must be ERMC_FRAMES_VERSION (2: room for four layers per slot);
 * the DLL ignores other layouts.
 * Minecraft writes a slot, then publishes the frame's pose in ErmcControl (mcFrame = poseId),
 * so by the time ER renders a pose, the matching pixels are already available. */
#define ERMC_FRAMES_MAGIC 0x524D484Du  /* "MHMR" */
#define ERMC_FRAME_MAX_W 1920u
#define ERMC_FRAME_MAX_H 1200u
#define ERMC_FRAME_SLOTS 3u
#define ERMC_FRAME_HDR 0x100u
#define ERMC_FRAME_SLOT_SIZE (ERMC_FRAME_HDR + ERMC_FRAME_MAX_W * ERMC_FRAME_MAX_H * 16u)
#define ERMC_FRAMES_VERSION 2u
#define ERMC_FRAMES_FILE_SIZE (0x1000u + ERMC_FRAME_SLOTS * ERMC_FRAME_SLOT_SIZE)
#define ERMC_FRAME_HAND (1u << 2)  /* ErmcFrameHeader.flags: the slot has a hand layer */

typedef struct ErmcFramesHeader {
    uint32_t magic;               /* 0x00 */
    uint32_t version;             /* 0x04 ERMC_FRAMES_VERSION */
    volatile uint32_t latestSlot; /* 0x08 slot of the newest complete frame */
    uint32_t reserved;            /* 0x0C */
    volatile uint64_t latestFrameId; /* 0x10 */
} ErmcFramesHeader;

typedef struct ErmcFrameHeader {
    volatile uint32_t seq;        /* 0x00 odd while Minecraft writes the slot */
    uint32_t width;               /* 0x04 */
    uint32_t height;              /* 0x08 */
    uint32_t flags;               /* 0x0C bit0 world layer valid, bit1 GUI layer valid, bit2 hand layer */
    uint64_t frameId;             /* 0x10 Minecraft frame counter */
    uint64_t poseId;              /* 0x18 matches ErmcControl.mcFrame for this frame's camera */
    float mcNear;                 /* 0x20 Minecraft projection near/far, blocks */
    float mcFar;                  /* 0x24 */
    float fovYDeg;                /* 0x28 */
    float aspect;                 /* 0x2C */
} ErmcFrameHeader;

#pragma pack(pop)

#ifdef __cplusplus
static_assert(sizeof(ErmcHeader) == 0xC0, "header size");
static_assert(sizeof(ErmcGameState) == 0x100, "state size");
static_assert(sizeof(ErmcControl) == 0x5C, "control size");
static_assert(sizeof(ErmcHunterEvents) == 0x30, "hunter events size");
static_assert(sizeof(ErmcCmdBlock) == 0x1000, "cmd size");
static_assert(sizeof(ErmcRayHeader) == 0x20, "ray header size");
static_assert(sizeof(ErmcRay) == 24 && sizeof(ErmcRayHit) == 32, "ray sizes");
static_assert(sizeof(ErmcEntity) == 0x80, "entity size");
static_assert(sizeof(ErmcDamage) == 0x20, "damage size");
static_assert(sizeof(ErmcFrameHeader) <= ERMC_FRAME_HDR, "frame header size");
static_assert(ERMC_OFF_ENTITIES + sizeof(ErmcEntityTable) <= ERMC_OFF_DAMAGE, "entity table fits");
static_assert(ERMC_OFF_DAMAGE + sizeof(ErmcDamageQueue) <= ERMC_OFF_PASSAGES, "damage queue fits");
static_assert(sizeof(ErmcPassage) == 0x20, "passage size");
static_assert(ERMC_OFF_PASSAGES + sizeof(ErmcPassageTable) <= ERMC_SHM_SIZE, "passages fit");
#endif
