// MHW-specific memory access and game-thread logic: camera override, hunter lookup/hide,
// and terrain ray queries against MHW's own collision system.
//
// Everything targets build 421810 (Ver. 15.23.00, the final PC patch), which has no ASLR.
// Addresses were found live and cross-checked by reverse engineering; each is
// verified against its byte signature at startup and features are disabled (never
// guessed) if anything differs.
#include "common.h"
#include <math.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <vector>
#include "MinHook.h"

namespace mb {

namespace addr {
constexpr uintptr_t kSMhCameraPtr = 0x1451C4400;   // static sMhCamera*
constexpr uintptr_t kSMhCameraVt = 0x1433F82A8;
constexpr uintptr_t kUMhCameraVt = 0x143497340;   // gameplay camera class
constexpr uintptr_t kSPlayerPtr = 0x14500ECA0;     // static sPlayer*
constexpr uintptr_t kSPlayerVt = 0x1433FE5B0;
constexpr uintptr_t kSCollisionPtr = 0x1451C4C50;  // static sCollision*
constexpr uintptr_t kSMhCameraMove = 0x141AC42F0;  // sMhCamera vtable slot 6, game thread
constexpr uintptr_t kFindMasterPlayer = 0x141B42010;
constexpr uintptr_t kCheckSegment = 0x14231AC00;
constexpr uintptr_t kTriInfoCtor = 0x142319440;
constexpr uintptr_t kTriInfoReset = 0x1423194B0;
constexpr uintptr_t kParamCtor = 0x14231A6E0;
constexpr uintptr_t kParamSetAttr = 0x14231ABA0;
constexpr uintptr_t kParamDtor = 0x140282390;
constexpr uintptr_t kTriGetAttr = 0x140329C20;   // TriangleInfo attribute bits (tri, index)
constexpr uintptr_t kSEnemyPtr = 0x14500CF40;     // static sEnemy*
constexpr uintptr_t kAddHP = 0x141216880;         // cpHealthManager::AddHP(this, float delta)
constexpr uintptr_t kSetPosition = 0x141C03AA0;   // uCharacterModel::SetPosition(model, pos*, quat*)
constexpr uintptr_t kHealthVt = 0x143240EA0;      // cpHealthManager
constexpr uintptr_t kRefreshEntity = 0x141F605F0; // RefreshEntityParams(entity, p2): recomputes opacity
constexpr uintptr_t kShowDamage = 0x141CC5F80;    // damage-number display (HunterPie FUN_DEAL_DAMAGE)
constexpr uintptr_t kCalcCamera = 0x141FA5380;    // uMhCamera CalculateCamera (inside its per-frame move)
constexpr uintptr_t kHitHandler = 0x1402BF450;    // monster damage check: (damageCheck*, hitInfo*) -> int
constexpr uintptr_t kPlayerCreateShell = 0x141AA74A0;  // (shellParam, owner, creator, creationParams*) (SPL)
constexpr uintptr_t kCondForceActivate = 0x1402B9CC0;  // cEmConditionParam: activate now (MHW's own network-packet path)
constexpr uintptr_t kLaunchAction = 0x141CC5360;       // Monster:LaunchAction(uEnemy*, int index in action set 1) (SPL)
constexpr uintptr_t kCondParamVt = 0x142F178F8;        // cEmConditionParam
}  // namespace addr

// sMhCamera
constexpr size_t kViewport0 = 0x50;       // 8 viewports of 0x1A0 bytes
constexpr size_t kVp0Camera = 0x58;       // viewport 0 camera (uCamera*)
constexpr size_t kVpView = 0xA0;          // 4x4 row-major view (row vectors, right-handed)
constexpr size_t kVpProj = 0xE0;          // 4x4 projection (D3D RH, z in [0,1])
constexpr size_t kShakeMatrix = 0x1D70;   // multiplied into the view; identity when calm
// uCamera
constexpr size_t kCamFar = 0x138;
constexpr size_t kCamNear = 0x13C;
constexpr size_t kCamAspect = 0x140;
constexpr size_t kCamFov = 0x144;         // vertical, degrees
constexpr size_t kCamPos = 0x150;
constexpr size_t kCamUp = 0x160;
constexpr size_t kCamTarget = 0x170;
// sPlayer
constexpr size_t kSPlayerZone = 0xAED0;   // current zone id (HunterPie ZoneOffsets, 421810)
// uPlayer (cUnit)
constexpr size_t kUnitFlags = 0x14;       // bit1 = Draw
constexpr size_t kPlayerPos = 0x160;
constexpr size_t kPlayerQuat = 0x170;
constexpr size_t kPlayerHealth = 0x7630;  // cpHealthManager*: +0x60 max, +0x64 current
constexpr size_t kPlayerWeapon = 0x76B0;  // weapon object; byte +0x1A98 = 1 hides it
constexpr size_t kPlayerSlingerShll = 0x56E8;  // rShellParamList "hm\\common\\shell\\slinger" (SPL "Ammo shll")
// rShellParamList: +0xA8 entries {u64, rShellParam*} (16 bytes each), +0xB0 count
constexpr size_t kWeaponHide = 0x1A98;
constexpr size_t kOpacity = 0x78E0;       // 1.0 = opaque; recomputed by RefreshEntityParams

struct CodeSig {
    uintptr_t addr;
    const char* sig;  // bytes expected at addr ("??" = wildcard)
    const char* name;
};

static const CodeSig kSigs[] = {
    {addr::kSMhCameraMove, "48 8B C4 48 89 58 20 55 48 8D 68 B8 48 81 EC 40 01 00 00 48 89 70 08", "sMhCamera::move"},
    {addr::kFindMasterPlayer, "45 33 C9 4C 8D 41 50", "FindMasterPlayer"},
    {addr::kCheckSegment, "48 89 5C 24 08 48 89 6C 24 10 48 89 74 24 18 57 48 81 EC B0 01 00 00 48 8B F2 49 8B D9 33 D2 41 0F B6 F8", "sCollision::CheckSegment"},
    {addr::kTriInfoCtor, "33 D2 C7 41 08 FF FF FF FF 33 C0", "TriangleInfo::ctor"},
    {addr::kTriInfoReset, "33 C0 C7 41 08 FF FF FF FF 48 89 41 20", "TriangleInfo::reset"},
    {addr::kParamCtor, "45 33 C9 48 8D 05 ?? ?? ?? ?? 48 89 01 44 89 89 BC 00 00 00", "Param::ctor"},
    {addr::kParamSetAttr, "89 51 08 44 89 41 10 44 89 49 0C C6 81 F8 00 00 00 01 C3", "Param::setAttr"},
    {addr::kParamDtor, "48 8D 05 ?? ?? ?? ?? 48 89 01 C3", "Param::dtor"},
    {addr::kTriGetAttr, "48 89 5C 24 08 48 89 74 24 10 57 48 83 EC 20 8B F2 48 8B D9 80 7C 0E 7E", "TriangleInfo::attr"},
    {addr::kAddHP, "F3 0F 58 49 64 0F 2F 0D ?? ?? ?? ?? 73 12 F3 0F 10 41 60", "cpHealthManager::AddHP"},
    {addr::kSetPosition, "48 89 5C 24 08 57 48 83 EC 20 8B 02 33 FF 89 81 50 0A 00 00", "uCharacterModel::SetPosition"},
    {addr::kShowDamage, "48 83 EC 68 83 B9 80 22 01 00 0F 75 59", "ShowDamageNumber"},
    {addr::kPlayerCreateShell, "48 8B C4 57 48 81 EC 90 01 00 00 48 89 58 08", "Player::CreateShell"},
    {addr::kHitHandler, "40 53 56 48 81 EC A8 00 00 00 83 B9 00 44 00 00 3A 48 8B DA 48 8B F1 75 0F", "EmDamageCheck::hit"},
    {addr::kCalcCamera, "48 8B C4 55 41 57 48 81 EC D8 00 00 00 44 0F 29 40 B8 45 33 FF ?? ?? ?? ?? ?? ?? ?? ?? ?? 48 8B E9", "CalculateCamera"},
    {addr::kRefreshEntity, "48 89 5C 24 08 48 89 6C 24 10 48 89 74 24 18 57 41 56 41 57 48 83 EC 40 48 8B 81 D0 8F 00 00", "RefreshEntityParams"},
    {addr::kCondForceActivate, "40 53 48 83 EC 50 83 B9 50 01 00 00 01 48 8B D9 0F 84", "cEmConditionParam::forceActivate"},
    {addr::kLaunchAction, "89 54 24 10 53 48 83 EC 20 48 8B D9 48 8B 0D ?? ?? ?? ?? E8 ?? ?? ?? ?? 84 C0 74 ?? 48 8B 8B E0 89 00 00", "Monster::LaunchAction"},
};

static bool bytes_match(uintptr_t at, const char* sig) {
    const uint8_t* p = (const uint8_t*)at;
    const char* s = sig;
    size_t i = 0;
    while (*s) {
        while (*s == ' ') s++;
        if (!*s) break;
        if (s[0] == '?') {
            s += (s[1] == '?') ? 2 : 1;
        } else {
            char hex[3] = {s[0], s[1], 0};
            if (!mem_readable(p + i, 1) || p[i] != (uint8_t)strtol(hex, nullptr, 16)) return false;
            s += 2;
        }
        i++;
    }
    return true;
}

typedef void(__fastcall* Move_t)(void* self);
typedef void*(__fastcall* FindMasterPlayer_t)(void* sPlayer);
typedef int(__fastcall* CheckSegment_t)(void* coll, const float* seg, uint8_t flag, void* tri, void* param);
typedef void(__fastcall* Obj_t)(void* obj);
typedef void(__fastcall* ParamCtor_t)(void* p, uint32_t a, uint32_t b, uint32_t c, uint32_t s20, uint32_t s28,
                                      uint64_t s30, uint8_t s38, uint32_t s40, uint32_t s48, uint8_t s50);
typedef void(__fastcall* ParamSetAttr_t)(void* p, uint32_t a, uint32_t b, uint32_t c, uint8_t d);
typedef uint32_t(__fastcall* TriGetAttr_t)(void* tri, uint32_t index);
typedef void(__fastcall* AddHP_t)(void* healthManager, float delta);
typedef void(__fastcall* SetPosition_t)(void* model, const float* pos, const float* quat);

static Move_t oCamMove = nullptr;
typedef void(__fastcall* Refresh_t)(void* entity, void* p2);
static Refresh_t oRefresh = nullptr;
static volatile uintptr_t g_hideEntity = 0;  // hunter to keep invisible (0 = none)

// The gameplay camera's own calculation. Culling/streaming systems read the camera right
// after this (before sMhCamera::move), so Minecraft's camera must already be in place here
// or MHW only draws/loads what the hunter's camera would see (NewCamera found the same).
typedef void(__fastcall* CalcCamera_t)(void* cam);
static CalcCamera_t oCalcCamera = nullptr;
static bool apply_camera_override(uint8_t* cam, uint8_t* mgr, const MhmcControl* snapshot);

// The hook only logs the arguments of the game's own damage-number calls. Minecraft's hits call
// the original (oShowDamage) with the same argument pattern to show MHW's damage numbers.
typedef void(__fastcall* ShowDamage_t)(void* target, int damage, float* pos, int a4, int a5, int a6, int a7,
                                       uint8_t a8, int a9);
static ShowDamage_t oShowDamage = nullptr;
static volatile LONG g_showDamageLogs = 40;

static void __fastcall hkShowDamage(void* target, int damage, float* pos, int a4, int a5, int a6, int a7, uint8_t a8,
                                    int a9) {
    InflightGuard guard;
    if (g_showDamageLogs > 0) {
        InterlockedDecrement(&g_showDamageLogs);
        log("show-damage: target %p dmg %d pos (%.0f %.0f %.0f) a4 %d a5 %d a6 %d a7 %d a8 %u a9 %d species %d ret %p",
            target, damage, pos ? pos[0] : 0.f, pos ? pos[1] : 0.f, pos ? pos[2] : 0.f, a4, a5, a6, a7, a8, a9,
            target ? *(int*)((uint8_t*)target + 0x12280) : -1, __builtin_return_address(0));
    }
    oShowDamage(target, damage, pos, a4, a5, a6, a7, a8, a9);
}

// The game recomputes the hunter's opacity here every frame; zero it right after for the
// hunter standing in for the Minecraft player (hides body, armour and attachments).
static void __fastcall hkRefresh(void* entity, void* p2) {
    InflightGuard guard;
    oRefresh(entity, p2);
    if (entity && (uintptr_t)entity == g_hideEntity) *(float*)((uint8_t*)entity + kOpacity) = 0.0f;
}
static bool g_sigsOk = false;
static bool g_hooked = false;
static volatile uintptr_t g_player = 0;
static volatile LONG g_overrideApplied = 0;
static bool g_hunterHidden = false;
static DWORD g_gameThread = 0;

// Control-block liveness: only obey Minecraft while it keeps publishing.
static uint32_t g_lastCtrlSeq = 0;
static uint64_t g_lastCtrlChangeMs = 0;

// ---------------------------------------------------------------------------------------
// Ray queries (game thread only)

struct RayFilter {
    bool on;
    uint32_t a, b, c;
};

static bool raycast(const float* start, const float* end, RayFilter filter, MhmcRayHit* out) {
    memset(out, 0, sizeof(*out));
    void* coll = *(void**)addr::kSCollisionPtr;
    if (!coll) return false;
    alignas(16) uint8_t param[0x140];
    alignas(16) uint8_t tri[0x140];
    alignas(16) float seg[8] = {start[0], start[1], start[2], 0.0f, end[0], end[1], end[2], 0.0f};
    memset(param, 0, sizeof(param));
    memset(tri, 0, sizeof(tri));
    // Same arguments as every call site in the game (e.g. the camera's ground clamp).
    ((ParamCtor_t)addr::kParamCtor)(param, 0x7FFFFFFF, 0x3FFFFFFF, 0, 0, 0xA, 0, 1, 1, 0, 1);
    if (filter.on) ((ParamSetAttr_t)addr::kParamSetAttr)(param, filter.a, filter.b, filter.c, 1);
    param[0xF9] = 0;
    ((Obj_t)addr::kTriInfoCtor)(tri);
    int r = ((CheckSegment_t)addr::kCheckSegment)(coll, seg, 1, tri, param);
    out->hit = (uint32_t)r;
    if (r) {
        memcpy(out->pos, tri + 0xC0, 12);
        memcpy(out->normal, tri + 0xB0, 12);
        out->attr = ((TriGetAttr_t)addr::kTriGetAttr)(tri, 0);
    }
    ((Obj_t)addr::kTriInfoReset)(tri);
    ((Obj_t)addr::kParamDtor)(param);
    return r != 0;
}

// One call handed from the mailbox thread to the game thread (debug rays, shells, monster
// requests). The game thread claims a request (1 -> 3) before running it, and the mailbox
// thread withdraws only a request nobody claimed (1 -> 0), so a late run never mixes with
// the next request's parameters or result. The waits give up when the core shuts down.
struct GameThreadCall {
    volatile LONG state = 0;  // 0 idle, 1 requested, 3 running on the game thread, 2 done

    // Mailbox thread, before writing the parameters: false while an earlier call still runs.
    bool idle() {
        for (ULONGLONG t = GetTickCount64() + 2000; state == 3 && GetTickCount64() < t && !core_stopping();) Sleep(1);
        return state != 3;
    }
    // Mailbox thread, after writing the parameters: true once the game thread has run the call.
    bool run() {
        InterlockedExchange(&state, 1);
        for (ULONGLONG t = GetTickCount64() + 1000; state == 1 && GetTickCount64() < t && !core_stopping();) Sleep(1);
        if (InterlockedCompareExchange(&state, 0, 1) == 1) return false;  // never picked up (loading screen?)
        for (ULONGLONG t = GetTickCount64() + 2000; state == 3 && GetTickCount64() < t && !core_stopping();) Sleep(1);
        return InterlockedCompareExchange(&state, 0, 2) == 2;  // still running: idle() waits for it next time
    }
    // Game thread.
    bool claim() { return InterlockedCompareExchange(&state, 3, 1) == 1; }
    void done() { InterlockedExchange(&state, 2); }
};

// One pending debug ray, handed from the mailbox thread to the game thread.
static GameThreadCall g_dbgRay;
static float g_dbgRayStart[3], g_dbgRayEnd[3];
static uint32_t g_dbgRayFlags;
static uint32_t g_dbgFilter[3];
static MhmcRayHit g_dbgRayHit;

bool game_debug_raycast(const float* start, const float* end, uint32_t flags, const uint32_t* filter, MhmcRayHit* out) {
    if (!g_hooked || !g_dbgRay.idle()) return false;
    memcpy(g_dbgRayStart, start, 12);
    memcpy(g_dbgRayEnd, end, 12);
    g_dbgRayFlags = flags;
    if (filter) memcpy(g_dbgFilter, filter, 12);
    if (!g_dbgRay.run()) return false;
    *out = g_dbgRayHit;
    return true;
}

// ---------------------------------------------------------------------------------------
// Shells: the hunter fires one of its slinger shells (game thread only). A shell hit is a
// real MHW attack by the hunter: the monster notices and targets it, and MHW's own hit
// handling runs (damage number, death once HP is at 0).

typedef void*(__fastcall* CreateShell_t)(void* shellParam, void* owner, void* creator, void* params);

static void* fire_player_shell(uint32_t index, const float* origin, const float* target) {
    uint8_t* pl = (uint8_t*)g_player;
    if (!pl || !mem_readable(pl + kPlayerSlingerShll, 8)) return nullptr;
    uint8_t* shll = *(uint8_t**)(pl + kPlayerSlingerShll);
    if (!shll || !mem_readable(shll, 0xB8)) return nullptr;
    uint32_t count = *(uint32_t*)(shll + 0xB0);
    uint8_t* entries = *(uint8_t**)(shll + 0xA8);
    if (index >= count || !entries || !mem_readable(entries + index * 16, 16)) return nullptr;
    void* shellParam = *(void**)(entries + index * 16 + 8);
    if (!shellParam || !mem_readable(shellParam, 0x100)) return nullptr;
    // Creation parameters as SharpPluginLoader builds them (ShellCreationParams, 0x128 bytes).
    alignas(16) uint8_t params[0x130];
    memset(params, 0, sizeof(params));
    memcpy(params, origin, 12);
    params[0x10] = 1;
    memcpy(params + 0x40, target, 12);
    params[0x50] = 1;
    const int32_t ids[3] = {0x12, -1, -1};
    memcpy(params + 0xA0, ids, sizeof(ids));
    return ((CreateShell_t)addr::kPlayerCreateShell)(shellParam, pl, pl, params);
}

// One pending debug shell, handed from the mailbox thread to the game thread.
static GameThreadCall g_dbgShell;
static uint32_t g_dbgShellIndex;
static float g_dbgShellOrigin[3], g_dbgShellTarget[3];
static void* g_dbgShellResult;

bool game_debug_fire_shell(uint32_t index, const float* origin, const float* target, uint64_t* result) {
    if (!g_hooked || !g_dbgShell.idle()) return false;
    g_dbgShellIndex = index;
    memcpy(g_dbgShellOrigin, origin, 12);
    memcpy(g_dbgShellTarget, target, 12);
    if (!g_dbgShell.run()) return false;
    *result = (uint64_t)g_dbgShellResult;
    return true;
}

static void service_debug_shell() {
    if (!g_dbgShell.claim()) return;
    g_dbgShellResult = fire_player_shell(g_dbgShellIndex, g_dbgShellOrigin, g_dbgShellTarget);
    log("shell: fired slinger shell %u from (%.0f %.0f %.0f) at (%.0f %.0f %.0f) -> %p", g_dbgShellIndex,
        g_dbgShellOrigin[0], g_dbgShellOrigin[1], g_dbgShellOrigin[2], g_dbgShellTarget[0], g_dbgShellTarget[1],
        g_dbgShellTarget[2], g_dbgShellResult);
    g_dbgShell.done();
}

static void service_rays() {
    if (g_dbgRay.claim()) {
        RayFilter f = {false, 0, 0, 0};
        if (g_dbgRayFlags & MHMC_RAYS_CAMERA_FILTER) f = {true, 1, 0x40000000, 1};
        if (g_dbgRayFlags & MHMC_RAYS_CUSTOM_FILTER) f = {true, g_dbgFilter[0], g_dbgFilter[1], g_dbgFilter[2]};
        raycast(g_dbgRayStart, g_dbgRayEnd, f, &g_dbgRayHit);
        g_dbgRay.done();
    }

    MhmcRayHeader* h = shm_rays();
    uint32_t req = h->reqSeq;
    if (req == h->respSeq) return;
    __asm__ __volatile__("" ::: "memory");
    uint32_t count = h->count < MHMC_MAX_RAYS ? h->count : MHMC_MAX_RAYS;
    uint32_t done = h->processed;
    if (done > count) done = 0;
    const MhmcRay* rays = (const MhmcRay*)((uint8_t*)h + MHMC_RAYS_OFF_RAYS);
    MhmcRayHit* hits = (MhmcRayHit*)((uint8_t*)h + MHMC_RAYS_OFF_HITS);
    RayFilter filter = {false, 0, 0, 0};
    if (h->flags & MHMC_RAYS_CAMERA_FILTER) filter = {true, 1, 0x40000000, 1};
    if (h->flags & MHMC_RAYS_CUSTOM_FILTER) filter = {true, h->filterA, h->filterB, h->filterC};
    // Spend at most ~2 ms of the frame; the rest continues next frame.
    LARGE_INTEGER f, t0, t;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&t0);
    const LONGLONG budget = f.QuadPart / 500;
    while (done < count) {
        raycast(rays[done].start, rays[done].end, filter, &hits[done]);
        done++;
        if ((done & 15) == 0) {
            QueryPerformanceCounter(&t);
            if (t.QuadPart - t0.QuadPart > budget) break;
        }
    }
    h->processed = done;
    if (done >= count) {
        __asm__ __volatile__("" ::: "memory");
        h->respSeq = req;
        h->processed = 0;
    }
}

// ---------------------------------------------------------------------------------------
// Monsters (sEnemy list): published for Minecraft, and damaged when Minecraft hits them.

// uEnemy
constexpr size_t kEnemySlots = 0x38;        // sEnemy: 128 inline pointers to AI controllers
constexpr int kEnemySlotCount = 128;
constexpr size_t kAiEnemy = 0x138;          // controller -> uEnemy*
constexpr size_t kUnitState = 0x0C;         // (state & 0xE) != 0 => alive/active
constexpr size_t kEnemyPath = 0x2A0;        // "em\\em101_00\\..." inline string
constexpr size_t kEnemyScale = 0x180;
constexpr size_t kEnemyHealth = 0x7670;     // cpHealthManager*: +0x60 max, +0x64 current
constexpr size_t kEnemySpecies = 0x12280;
constexpr size_t kEnemyAi = 0x12278;        // AI data (SPL Monster.AiData); +0x138 -> uEnemy
constexpr size_t kAiStateFlags = 0x14780;   // bit 2: dead (Monster:Die returns early on it)

struct EmName {
    const char* code;
    const char* name;
};
static const EmName kEmNames[] = {
    {"em001", "Rathian"},       {"em002", "Rathalos"},      {"em007", "Diablos"},
    {"em011", "Kirin"},         {"em024", "Kushala Daora"}, {"em026", "Lunastra"},
    {"em027", "Teostra"},       {"em032", "Tigrex"},        {"em037", "Nargacuga"},
    {"em043", "Deviljho"},      {"em044", "Barroth"},       {"em045", "Uragaan"},
    {"em057", "Zinogre"},       {"em063", "Brachydios"},    {"em080", "Glavenus"},
    {"em100", "Anjanath"},      {"em101", "Great Jagras"},  {"em102", "Pukei-Pukei"},
    {"em103", "Nergigante"},    {"em105", "Xeno'jiiva"},    {"em107", "Kulu-Ya-Ku"},
    {"em108", "Jyuratodus"},    {"em109", "Tobi-Kadachi"},  {"em110", "Paolumu"},
    {"em111", "Legiana"},       {"em112", "Great Girros"},  {"em113", "Odogaron"},
    {"em114", "Radobaan"},      {"em115", "Vaal Hazak"},    {"em116", "Dodogama"},
    {"em117", "Kulve Taroth"},  {"em118", "Bazelgeuse"},    {"em120", "Tzitzi-Ya-Ku"},
    {"em121", "Behemoth"},      {"em122", "Beotodus"},      {"em123", "Banbaro"},
    {"em124", "Velkhana"},      {"em125", "Namielle"},      {"em126", "Shara Ishvalda"},
    {"em127", "Leshen"},
};

static bool unit_alive(const uint8_t* u) { return u && (u[kUnitState] & 0xE) != 0; }

// Collects live monsters (game thread). Returns the count.
static int collect_enemies(uint8_t** out, int max) {
    uint8_t* se = *(uint8_t**)addr::kSEnemyPtr;
    if (!se) return 0;
    int n = 0;
    for (int i = 0; i < kEnemySlotCount && n < max; i++) {
        uint8_t* ai = *(uint8_t**)(se + kEnemySlots + i * 8);
        if (!unit_alive(ai)) continue;
        uint8_t* em = *(uint8_t**)(ai + kAiEnemy);
        if (!unit_alive(em)) continue;
        out[n++] = em;
    }
    return n;
}

// Model culling bounds, kept up to date by the renderer (verified on the hunter: a
// 1.0 x 1.72 x 0.8 m box): rotation rows at +0x3A0/+0x3B0/+0x3C0, center +0x3D0, half
// extents +0x3E0, all world space. Used as the Minecraft hitbox.
constexpr size_t kModelObbRot = 0x3A0;
constexpr size_t kModelObbCenter = 0x3D0;
constexpr size_t kModelObbHalf = 0x3E0;

static void describe_enemy(uint8_t* em, MhmcEntity* e) {
    // +0x2A0 -> model resource; its path ("em\\ems062\\00\\mod\\ems062_00") is at +0x0C.
    char code[16] = {0};
    uint8_t* res = *(uint8_t**)(em + kEnemyPath);
    if (res && mem_readable(res + 0x0C, 32)) {
        const char* path = (const char*)(res + 0x0C);
        const char* p = strchr(path, '\\');
        p = p ? p + 1 : path;
        size_t k = 0;
        while (p[k] && p[k] != '\\' && p[k] != '_' && k < sizeof(code) - 1 && (unsigned char)p[k] >= 0x20) {
            code[k] = p[k];
            k++;
        }
    }
    bool small = strncmp(code, "ems", 3) == 0;
    e->kind = small ? MHMC_ENT_SMALL_MONSTER : MHMC_ENT_LARGE_MONSTER;
    const char* name = strcmp(code, "ems062") == 0 ? "Training Target" : nullptr;
    for (const EmName& en : kEmNames)
        if (strcmp(code, en.code) == 0) name = en.name;
    snprintf(e->name, sizeof(e->name), "%s", name ? name : (code[0] ? code : "?"));

    const float* r = (const float*)(em + kModelObbRot);  // 3 rows of 4 floats
    const float* c = (const float*)(em + kModelObbCenter);
    const float* h = (const float*)(em + kModelObbHalf);
    bool ok = h[0] > 1.0f && h[1] > 1.0f && h[2] > 1.0f && h[0] < 5000.0f && h[1] < 5000.0f && h[2] < 5000.0f;
    if (ok) {
        // World AABB of the OBB, so Minecraft can use it directly.
        for (int i = 0; i < 3; i++) {
            e->boxHalf[i] = fabsf(r[0 * 4 + i]) * h[0] + fabsf(r[1 * 4 + i]) * h[1] + fabsf(r[2 * 4 + i]) * h[2];
            e->boxCenter[i] = c[i];
        }
        e->flags |= 2;  // box is world-aligned
    } else {
        float scale = *(float*)(em + kEnemyScale + 4);
        if (!(scale > 0.2f && scale < 5.0f)) scale = 1.0f;
        e->boxHalf[0] = (small ? 45.0f : 160.0f) * scale;
        e->boxHalf[1] = (small ? 55.0f : 170.0f) * scale;
        e->boxHalf[2] = (small ? 90.0f : 420.0f) * scale;
        e->boxCenter[0] = e->pos[0];
        e->boxCenter[1] = e->pos[1] + e->boxHalf[1];
        e->boxCenter[2] = e->pos[2];
    }
}

static bool enemy_dead(uint8_t* em) {
    uint8_t* ai = *(uint8_t**)(em + kEnemyAi);
    return ai && mem_readable(ai + kAiStateFlags, 4) && (*(uint32_t*)(ai + kAiStateFlags) & 4);
}

static void publish_entities() {
    uint8_t* ems[MHMC_MAX_ENTITIES];
    int n = collect_enemies(ems, MHMC_MAX_ENTITIES);
    static MhmcEntityTable local;  // game thread only
    local.count = 0;
    for (int i = 0; i < n; i++) {
        uint8_t* em = ems[i];
        // Carcasses stay in sEnemy until they despawn; they're no longer something to hit.
        if (enemy_dead(em)) continue;
        MhmcEntity* e = &local.entities[local.count];
        memset(e, 0, sizeof(*e));
        e->id = (uint64_t)em;
        e->emId = *(uint32_t*)(em + kEnemySpecies);
        memcpy(e->pos, em + kPlayerPos, 12);
        memcpy(e->quat, em + kPlayerQuat, 16);
        uint8_t* hm = *(uint8_t**)(em + kEnemyHealth);
        if (hm) {
            e->maxHp = *(float*)(hm + 0x60);
            e->hp = *(float*)(hm + 0x64);
        }
        describe_enemy(em, e);
        local.count++;
    }
    MhmcEntityTable* t = shm_entities();
    t->seq = t->seq + 1;
    __asm__ __volatile__("" ::: "memory");
    t->count = local.count;
    t->frame = shm_state()->frame;
    memcpy(t->entities, local.entities, sizeof(MhmcEntity) * local.count);
    __asm__ __volatile__("" ::: "memory");
    t->seq = t->seq + 1;
}

// ---------------------------------------------------------------------------------------
// Monster conditions and actions (debug mailbox, game thread). When a hit puts a monster to
// sleep, MHW activates its sleep condition (timer, HUD icon, wake-up on the next hit) and
// the monster starts its own sleep action (nActEm002::DamageSleep...). A request does both.

constexpr size_t kEnemyConditions = 0x1BC40;  // cEmConditionParam*[25] by condition id (3 = sleep)
constexpr int kEnemyConditionCount = 25;
constexpr size_t kCondOwner = 0x148;          // uEnemy*
constexpr size_t kCondActive = 0x150;         // 1 while the condition is active
constexpr size_t kCondId = 0x158;
constexpr size_t kEnemyActionCtrl = 0x61C8;   // inline action controller
constexpr size_t kActionSet1 = 0x78;          // the monster's own actions: void** list, +8 u32 count
constexpr size_t kActionCurrent = 0xAC;       // {int set, int index}

typedef bool(__fastcall* CondActivate_t)(void* cond);
typedef bool(__fastcall* LaunchAction_t)(void* em, int32_t index);

static GameThreadCall g_dbgMonster;
static uint8_t* g_dbgMonsterEm;
static int32_t g_dbgMonsterCond, g_dbgMonsterAction, g_dbgMonsterResult;

// -11 = no such live monster, -2 = bad condition, -3 = bad action index.
static int32_t monster_request(uint8_t* em, int32_t condition, int32_t action) {
    uint8_t* ems[MHMC_MAX_ENTITIES];
    int n = collect_enemies(ems, MHMC_MAX_ENTITIES);
    bool live = false;
    for (int i = 0; i < n && !live; i++) live = ems[i] == em;
    if (!live || enemy_dead(em)) return -11;
    uint8_t* cond = nullptr;
    if (condition >= 0) {
        if (condition >= kEnemyConditionCount) return -2;
        cond = *(uint8_t**)(em + kEnemyConditions + condition * 8);
        if (!cond || !mem_readable(cond, 0x1E0) || *(uintptr_t*)cond != addr::kCondParamVt ||
            *(uint8_t**)(cond + kCondOwner) != em || *(int32_t*)(cond + kCondId) != condition)
            return -2;
    }
    if (action >= 0) {
        uint8_t* ctrl = em + kEnemyActionCtrl;
        void** list = *(void***)(ctrl + kActionSet1);
        uint32_t count = *(uint32_t*)(ctrl + kActionSet1 + 8);
        if (!list || (uint32_t)action >= count || !mem_readable(list + action, 8) || !list[action]) return -3;
    }
    bool activated = cond && ((CondActivate_t)addr::kCondForceActivate)(cond);
    bool launched = action >= 0 && ((LaunchAction_t)addr::kLaunchAction)(em, action);
    const int32_t* cur = (const int32_t*)(em + kEnemyActionCtrl + kActionCurrent);
    log("monster: %p condition %d %s (active %d), action %d %s; current action (%d, %d)", (void*)em, condition,
        cond ? (activated ? "activated" : "not activated") : "-", cond ? *(int32_t*)(cond + kCondActive) : -1, action,
        action >= 0 ? (launched ? "launched" : "requested") : "-", cur[0], cur[1]);
    return 0;
}

bool game_debug_monster(uint64_t em, int32_t condition, int32_t action, int32_t* result) {
    if (!g_hooked || !g_dbgMonster.idle()) return false;
    g_dbgMonsterEm = (uint8_t*)em;
    g_dbgMonsterCond = condition;
    g_dbgMonsterAction = action;
    if (!g_dbgMonster.run()) return false;
    *result = g_dbgMonsterResult;
    return true;
}

static void service_debug_monster() {
    if (!g_dbgMonster.claim()) return;
    g_dbgMonsterResult = monster_request(g_dbgMonsterEm, g_dbgMonsterCond, g_dbgMonsterAction);
    g_dbgMonster.done();
}

// ---------------------------------------------------------------------------------------
// Monster fire for Minecraft (hazard table): the live shells (fireballs, flames...) of
// monsters that use fire. Minecraft lights the TNT they touch.
//
// Shells are units in sUnit line 18 (the spawn function adds them to the line at
// params+0xA0, 0x12 for the hunter's slinger), the owner is referenced at +0xB58, and the
// position is uCoord's +0x160. The game owns these lists and
// frees units between frames, so every read is checked, and a shell counts only if its
// owner is a live monster that it is near.

constexpr uintptr_t kSUnitPtr = 0x1451238C8;  // static sUnit*
constexpr size_t kUnitLineSize = 0xF8;
constexpr size_t kUnitLineTop = 0x80;
constexpr size_t kUnitNext = 0x30;
constexpr int kShellLine = 18;
constexpr size_t kShellOwner = 0xB58;
constexpr int kMaxShellWalk = 512;
constexpr float kFireReach = 150.0f;       // around a fire shell, MHW units (until its collision size is known)
constexpr float kShellMaxDist = 20000.0f;  // a shell further than 200 m from its monster is not believed

// Monsters whose shells are fire, by model code and variant (-1 = all).
struct FireEm {
    const char* code;
    int variant;
};
static const FireEm kFireEms[] = {
    {"em001", -1}, {"em002", -1},  // Rathian, Rathalos and their subspecies
    {"em026", -1}, {"em027", -1},  // Lunastra, Teostra
    {"em100", 0},                  // Anjanath (Fulgur Anjanath, variant 1, uses thunder)
    {"em080", 0},                  // Glavenus
    {"em018", -1},                 // Yian Garuga
    {"em036", -1}, {"em045", -1},  // Lavasioth, Uragaan (its sleep gas counts too)
    {"em117", -1}, {"em118", -1},  // Kulve Taroth, Bazelgeuse
    {"em106", -1}, {"em013", -1},  // Zorah Magdaros, Fatalis
};

// mem_readable for many small reads in one frame: remembers the last committed, readable
// region VirtualQuery returned. Reset every frame, as the game may release memory in between.
struct RegionCache {
    uintptr_t lo = 0, hi = 0;
    bool readable(const void* p, size_t len) {
        uintptr_t a = (uintptr_t)p;
        if (a >= lo && a + len <= hi && a + len >= a) return true;
        MEMORY_BASIC_INFORMATION mbi;
        if (a < 0x10000 || !VirtualQuery(p, &mbi, sizeof(mbi)) || mbi.State != MEM_COMMIT ||
            (mbi.Protect & (PAGE_GUARD | PAGE_NOACCESS)) ||
            !(mbi.Protect & (PAGE_READONLY | PAGE_READWRITE | PAGE_WRITECOPY | PAGE_EXECUTE_READ |
                             PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY)))
            return false;
        lo = (uintptr_t)mbi.BaseAddress;
        hi = lo + mbi.RegionSize;
        return a + len <= hi || mem_readable(p, len);  // a read across regions checks them all
    }
};

// "em002" and variant 0 from the model path "em\em002\00\mod\em002_00".
static bool enemy_model(uint8_t* em, char* code, size_t codeLen, int* variant) {
    uint8_t* res = *(uint8_t**)(em + kEnemyPath);
    if (!res || !mem_readable(res + 0x0C, 32)) return false;
    char path[33];
    memcpy(path, res + 0x0C, 32);
    path[32] = 0;
    const char* a = strchr(path, '\\');
    if (!a) return false;
    a++;
    const char* b = strchr(a, '\\');
    if (!b || (size_t)(b - a) >= codeLen) return false;
    memcpy(code, a, b - a);
    code[b - a] = 0;
    *variant = atoi(b + 1);
    return true;
}

static bool fire_monster(uint8_t* em) {
    char code[16];
    int variant = 0;
    if (!enemy_model(em, code, sizeof(code), &variant)) return false;
    for (const FireEm& f : kFireEms)
        if (strcmp(code, f.code) == 0 && (f.variant < 0 || f.variant == variant)) return true;
    return false;
}

enum ShellKind : uint8_t { kNotShell = 1, kShell = 2, kFireShell = 3 };  // 0 = not cached yet
struct VtClass {
    uintptr_t vt;
    uint8_t kind;
    char name[40];
};
constexpr int kVtClassSlots = 512;
static VtClass g_vtClasses[kVtClassSlots];  // game thread only; vtables are static, so entries never go stale

// Is a vtable a shell class? Its slot 4 is GetDTI (`lea rax,[rip+X]; ret`), the DTI's name at +8.
// Only vtables inside MHW's image are classified (and cached); anything else is not a unit.
static const VtClass* classify_vtable(uintptr_t vt) {
    static const VtClass kForeign = {0, kNotShell, {0}};
    uintptr_t base = main_module_base();
    if (vt < base || vt >= base + main_module_size()) return &kForeign;
    unsigned h = (unsigned)((vt >> 3) * 2654435761u);
    for (int i = 0; i < kVtClassSlots; i++) {
        VtClass& c = g_vtClasses[(h + i) & (kVtClassSlots - 1)];
        if (c.vt == vt) return &c;
        if (c.vt) continue;
        c.vt = vt;
        c.kind = kNotShell;
        uintptr_t fn = 0, name = 0;
        if (!mem_read((void*)(vt + 4 * 8), &fn, 8)) return &c;
        uint8_t code[8];
        if (!mem_read((void*)fn, code, 8) || code[0] != 0x48 || code[1] != 0x8D || code[2] != 0x05 || code[7] != 0xC3)
            return &c;
        int32_t rel;
        memcpy(&rel, code + 3, 4);
        if (!mem_read((void*)(fn + 7 + rel + 8), &name, 8) || !mem_read((void*)name, c.name, sizeof(c.name) - 1))
            return &c;
        c.name[sizeof(c.name) - 1] = 0;
        if (strncmp(c.name, "uShell", 6) == 0)
            c.kind = (strstr(c.name, "Fire") || strstr(c.name, "Flame")) ? kFireShell : kShell;
        return &c;
    }
    return nullptr;  // cache full: treat as unknown
}

// Empties the hazard table (core start and stop): a table left behind by an earlier core
// or game session must not light TNT.
static void clear_hazards() {
    MhmcHazardTable* t = shm_hazards();
    uint32_t seq = t->seq & ~1u;
    t->seq = seq + 1;
    __asm__ __volatile__("" ::: "memory");
    t->count = 0;
    t->frame = 0;
    __asm__ __volatile__("" ::: "memory");
    t->seq = seq + 2;
}

static void publish_hazards() {
    static MhmcHazardTable local;   // game thread only
    // Last frame's hazards: a shell seen again keeps its serial (and isn't logged again).
    static uint64_t prevIds[MHMC_MAX_HAZARDS];
    static uintptr_t prevVts[MHMC_MAX_HAZARDS];
    static uint32_t prevSerials[MHMC_MAX_HAZARDS];
    static uintptr_t curVts[MHMC_MAX_HAZARDS];
    static uint32_t prevCount, nextSerial;
    int logs = 0;
    RegionCache mem;
    local.count = 0;
    uint8_t* ems[MHMC_MAX_ENTITIES];
    int n = collect_enemies(ems, MHMC_MAX_ENTITIES);
    int8_t fire[MHMC_MAX_ENTITIES];  // per monster: -1 unknown, 0 no, 1 yes
    memset(fire, -1, sizeof(fire));
    uint8_t* su = nullptr;
    uint8_t* u = nullptr;
    if (n > 0 && mem_read((void*)kSUnitPtr, &su, 8) && su)
        mem_read(su + kShellLine * kUnitLineSize + kUnitLineTop, &u, 8);
    for (int k = 0; u && k < kMaxShellWalk && local.count < MHMC_MAX_HAZARDS; k++) {
        if (!mem.readable(u, kUnitNext + 8)) break;
        uint8_t* next = *(uint8_t**)(u + kUnitNext);
        const VtClass* vc = classify_vtable(*(uintptr_t*)u);
        if (vc && vc->kind >= kShell && mem.readable(u, kShellOwner + 8)) {
            uint8_t* owner = *(uint8_t**)(u + kShellOwner);
            int oi = -1;
            for (int i = 0; i < n && oi < 0; i++)
                if (ems[i] == owner) oi = i;
            if (oi >= 0 && fire[oi] < 0) fire[oi] = !enemy_dead(owner) && fire_monster(owner);
            const float* p = (const float*)(u + kPlayerPos);
            const float* o = oi >= 0 ? (const float*)(owner + kPlayerPos) : nullptr;
            bool nearby = o && isfinite(p[0]) && isfinite(p[1]) && isfinite(p[2]) &&
                        fabsf(p[0] - o[0]) < kShellMaxDist && fabsf(p[1] - o[1]) < kShellMaxDist &&
                        fabsf(p[2] - o[2]) < kShellMaxDist;
            if (nearby && (vc->kind == kFireShell || fire[oi] == 1)) {
                MhmcHazard* hz = &local.hazards[local.count++];
                memset(hz, 0, sizeof(*hz));
                hz->id = (uint64_t)u;
                hz->ownerId = (uint64_t)owner;
                memcpy(hz->pos, p, 12);
                hz->radius = kFireReach;
                hz->flags = MHMC_HAZARD_FIRE;
                curVts[local.count - 1] = vc->vt;
                for (uint32_t i = 0; i < prevCount && !hz->serial; i++)
                    if (prevIds[i] == hz->id && prevVts[i] == vc->vt) hz->serial = prevSerials[i];
                if (!hz->serial) {  // a new shell (possibly at a freed one's address)
                    if (!++nextSerial) ++nextSerial;
                    hz->serial = nextSerial;
                    if (logs++ < 4)
                        log("fire: %s %p of monster %p at (%.0f %.0f %.0f)", vc->name, (void*)u, (void*)owner, p[0], p[1], p[2]);
                }
            }
        }
        u = next;
    }
    prevCount = local.count;
    for (uint32_t i = 0; i < local.count; i++) {
        prevIds[i] = local.hazards[i].id;
        prevVts[i] = curVts[i];
        prevSerials[i] = local.hazards[i].serial;
    }
    MhmcHazardTable* t = shm_hazards();
    if (local.count == 0 && t->count == 0) return;  // nothing new to say
    uint32_t seq = t->seq & ~1u;  // even whatever the region held before
    t->seq = seq + 1;
    __asm__ __volatile__("" ::: "memory");
    t->count = local.count;
    t->frame = shm_state()->frame;
    memcpy(t->hazards, local.hazards, sizeof(MhmcHazard) * local.count);
    __asm__ __volatile__("" ::: "memory");
    t->seq = seq + 2;
}

// Slinger shell 0 ("slinger_00"): a plain slinger shot, 2 damage.
constexpr uint32_t kPokeShell = 0;

// Minecraft's damage is applied directly; a slinger shot from the hunter comes with it so
// the hit is also a real MHW attack: the monster notices the hunter (standing in for Steve)
// and fights back, and a monster at 0 HP dies through MHW's own hit handling.
static void poke_with_shell(uint8_t* em, const float* hitPos, const float* eye, bool outward) {
    const float* c = (const float*)(em + kModelObbCenter);
    const float* h = (const float*)(em + kModelObbHalf);
    bool obbOk = h[0] > 1.0f && h[1] > 1.0f && h[2] > 1.0f && h[0] < 5000.0f && h[1] < 5000.0f && h[2] < 5000.0f;
    const float* target = obbOk ? c : hitPos;
    // Start 1.5 m out from where Minecraft's hit landed and fly into the body: on the
    // player's side for melee, straight out of the body where an explosion or arrow met it.
    const float* away = (outward && obbOk) ? c : eye;
    float sign = (outward && obbOk) ? -1.0f : 1.0f;
    float d[3] = {sign * (away[0] - hitPos[0]), sign * (away[1] - hitPos[1]), sign * (away[2] - hitPos[2])};
    float len = sqrtf(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
    if (len < 1.0f) {
        d[0] = 0.0f, d[1] = 1.0f, d[2] = 0.0f;
        len = 1.0f;
    }
    float origin[3];
    for (int i = 0; i < 3; i++) origin[i] = hitPos[i] + d[i] / len * 150.0f;
    fire_player_shell(kPokeShell, origin, target);
}

static void apply_damage(const float* eye) {
    MhmcDamageQueue* q = shm_damage();
    uint32_t write = q->write;
    __asm__ __volatile__("" ::: "memory");
    if (q->read == write) return;
    uint8_t* ems[MHMC_MAX_ENTITIES];
    int n = collect_enemies(ems, MHMC_MAX_ENTITIES);
    while (q->read != write) {
        MhmcDamage d = q->ring[q->read % MHMC_DAMAGE_RING];
        uint8_t* target = nullptr;
        for (int i = 0; i < n; i++)
            if ((uint64_t)ems[i] == d.id) target = ems[i];  // never trust a stale pointer
        if (target && d.amount > 0.0f && d.amount < 1.0e6f) {
            uint8_t* hm = *(uint8_t**)(target + kEnemyHealth);
            if (hm) {
                float before = *(float*)(hm + 0x64);
                ((AddHP_t)addr::kAddHP)(hm, -d.amount);
                // MHW's own damage number, with the argument pattern the game uses for hits.
                if (oShowDamage) {
                    float pos[3] = {d.hitPos[0], d.hitPos[1], d.hitPos[2]};
                    oShowDamage(target, (int)(d.amount + 0.5f), pos, 1, (d.flags & MHMC_DAMAGE_CRITICAL) ? 1 : 0, 0, 0, 0, 0);
                }
                log("damage: %s em %p %.0f -> %.0f HP", "hit", (void*)target, before, *(float*)(hm + 0x64));
                if (eye) poke_with_shell(target, d.hitPos, eye, (d.flags & MHMC_DAMAGE_OUTWARD) != 0);
            }
        }
        q->read = q->read + 1;
    }
}

// ---------------------------------------------------------------------------------------
// Game-thread tick (runs inside sMhCamera::move, before the view matrices are built)

static bool control_active(MhmcControl* out) {
    MhmcControl c;
    memset(&c, 0, sizeof(c));
    if (!control_snapshot(&c)) return false;
    uint64_t now = now_ms();
    if (c.seq != g_lastCtrlSeq) {
        g_lastCtrlSeq = c.seq;
        g_lastCtrlChangeMs = now;
    }
    if (now - g_lastCtrlChangeMs > 1000) return false;  // Minecraft stopped updating
    *out = c;
    return true;
}


// ---------------------------------------------------------------------------------------
// The hunter as the Minecraft player's body in MHW: it follows the Minecraft player
// (hidden), so monsters target and hit it there; its HP loss is reported to Minecraft as
// damage and refilled so it never faints.

static float g_lastHunterHp = -1.0f;
static uint8_t* g_lastHunter = nullptr;
static bool g_weaponHidden = false;

static void stand_in(uint8_t* pl, const MhmcControl* c, bool active, uint8_t* mgr) {
    (void)mgr;
    if (pl != g_lastHunter) {
        g_lastHunter = pl;
        g_lastHunterHp = -1.0f;
        g_weaponHidden = false;
    }
    if (!pl) return;
    uint8_t* hm = *(uint8_t**)(pl + kPlayerHealth);
    bool hmOk = hm && mem_readable(hm, 0x68) && *(uintptr_t*)hm == addr::kHealthVt;

    if (active) {
        // Face the way the Minecraft player faces (MHW models look down +Z).
        float half = -c->hunterYawDeg * 3.14159265f / 360.0f;
        float quat[4] = {0.0f, sinf(half), 0.0f, cosf(half)};
        ((SetPosition_t)addr::kSetPosition)(pl, c->hunterPos, quat);

        if (hmOk) {
            float hp = *(float*)(hm + 0x64), mx = *(float*)(hm + 0x60);
            float lost = g_lastHunterHp > 0.0f ? g_lastHunterHp - hp : 0.0f;
            if (lost > 0.01f && lost < 1.0f) {
                // Poison, fire and other status damage drain less than 1 HP per frame; attacks
                // take whole HP. Reported separately so Minecraft doesn't treat it as blows.
                MhmcHunterEvents* ev = shm_hunter();
                ev->seq = ev->seq + 1;
                __asm__ __volatile__("" ::: "memory");
                ev->totalStatusDamage = ev->totalStatusDamage + lost;
                ev->hunterMaxHp = mx;
                __asm__ __volatile__("" ::: "memory");
                ev->seq = ev->seq + 1;
            } else if (lost >= 1.0f) {
                float dmg = lost;
                // Direction of the hit: the monster closest to the hunter.
                uint8_t* ems[MHMC_MAX_ENTITIES];
                int n = collect_enemies(ems, MHMC_MAX_ENTITIES);
                float best = 1e30f, from[3] = {c->hunterPos[0], c->hunterPos[1], c->hunterPos[2]};
                uint32_t kind = MHMC_ENT_LARGE_MONSTER;
                for (int i = 0; i < n; i++) {
                    const float* p = (const float*)(ems[i] + kPlayerPos);
                    float dx = p[0] - c->hunterPos[0], dz = p[2] - c->hunterPos[2];
                    float d = dx * dx + dz * dz;
                    if (d < best) {
                        best = d;
                        memcpy(from, p, 12);
                        MhmcEntity tmp;
                        memset(&tmp, 0, sizeof(tmp));
                        describe_enemy(ems[i], &tmp);
                        kind = tmp.kind;
                    }
                }
                MhmcHunterEvents* ev = shm_hunter();
                ev->seq = ev->seq + 1;
                __asm__ __volatile__("" ::: "memory");
                ev->hitCount = ev->hitCount + 1;
                ev->totalDamage = ev->totalDamage + dmg;
                ev->lastDamage = dmg;
                memcpy(ev->lastHitFrom, from, 12);
                ev->hunterMaxHp = mx;
                ev->lastHitFrame = shm_state()->frame;
                ev->lastHitKind = kind;
                __asm__ __volatile__("" ::: "memory");
                ev->seq = ev->seq + 1;
                log("stand-in: hunter took %.0f damage -> Minecraft", dmg);
            }
            *(float*)(hm + 0x64) = mx;  // never faint while standing in
            g_lastHunterHp = mx;
        }
    } else {
        g_lastHunterHp = hmOk ? *(float*)(hm + 0x64) : -1.0f;
    }

    // Hide the weapon along with the hunter.
    bool hideWeapon = active && (c->flags & MHMC_CTRL_HIDE_HUNTER);
    uint8_t* wpn = *(uint8_t**)(pl + kPlayerWeapon);
    if (wpn && mem_readable(wpn + kWeaponHide, 1)) {
        if (hideWeapon) {
            wpn[kWeaponHide] = 1;
            g_weaponHidden = true;
        } else if (g_weaponHidden) {
            wpn[kWeaponHide] = 0;
            g_weaponHidden = false;
        }
    }
}

// F8 in MHW hands control back to Minecraft; Minecraft asks us to take the focus when the
// player switches to MHW.
static bool g_f8Down = false;
static uint32_t g_focusSeen = 0;
static bool g_focusSeenInit = false;

static void handle_switching() {
    HWND hwnd = game_hwnd();
    if (!hwnd) return;
    bool focused = GetForegroundWindow() == hwnd;
    bool down = focused && (GetAsyncKeyState(VK_F8) & 0x8000);
    if (down && !g_f8Down) {
        MhmcHeader* h = shm_header();
        h->mcSwitchReq = h->mcSwitchReq + 1;
        log("switch: F8 in MHW -> back to Minecraft");
    }
    g_f8Down = down;

    uint32_t req = shm_header()->mhwFocusReq;
    if (!g_focusSeenInit) {
        g_focusSeen = req;
        g_focusSeenInit = true;
    }
    if (req != g_focusSeen) {
        g_focusSeen = req;
        ShowWindow(hwnd, SW_RESTORE);
        SetForegroundWindow(hwnd);
        SetActiveWindow(hwnd);
        SetFocus(hwnd);
        log("switch: Minecraft handed control to MHW (foreground %s)", GetForegroundWindow() == hwnd ? "ok" : "not yet");
    }
}

static void game_tick(uint8_t* mgr) {
    handle_switching();
    void* sp = *(void**)addr::kSPlayerPtr;
    uint8_t* pl = nullptr;
    if (sp && *(uintptr_t*)sp == addr::kSPlayerVt) pl = (uint8_t*)((FindMasterPlayer_t)addr::kFindMasterPlayer)(sp);
    g_player = (uintptr_t)pl;

    MhmcControl c;
    memset(&c, 0, sizeof(c));
    bool ctrl = control_active(&c);

    uint8_t* cam = *(uint8_t**)(mgr + kVp0Camera);
    bool overriding = ctrl && (c.flags & MHMC_CTRL_OVERRIDE_CAMERA) && cam;
    compositor_note_applied_pose(overriding ? c.mcFrame : 0);
    if (ctrl) compositor_set_pose_lag((int)c.poseLag);
    // Apply exactly the pose recorded above: a fresh read could already be the next one.
    if (overriding) apply_camera_override(cam, mgr, &c);

    stand_in(pl, &c, ctrl && (c.flags & MHMC_CTRL_MOVE_HUNTER), mgr);

    bool wantHidden = ctrl && (c.flags & MHMC_CTRL_HIDE_HUNTER) && pl;
    g_hideEntity = wantHidden ? (uintptr_t)pl : 0;
    if (wantHidden) *(float*)(pl + kOpacity) = 0.0f;
    g_hunterHidden = wantHidden;

    service_rays();
    service_debug_shell();
    service_debug_monster();
    float eye[3];
    if (ctrl && (c.flags & MHMC_CTRL_OVERRIDE_CAMERA)) {
        memcpy(eye, c.camPos, 12);
    } else if (pl) {
        const float* p = (const float*)(pl + kPlayerPos);
        eye[0] = p[0], eye[1] = p[1] + 150.0f, eye[2] = p[2];
    }
    apply_damage(pl ? eye : nullptr);
    publish_entities();
    publish_hazards();
}

// Writes Minecraft's camera into a uCamera (and clears the shake if the manager is given).
// Uses `snapshot` if given, else the current control block.
static bool apply_camera_override(uint8_t* cam, uint8_t* mgr, const MhmcControl* snapshot) {
    MhmcControl c;
    if (snapshot) c = *snapshot;
    else if (!control_active(&c)) return false;
    if (!cam || !(c.flags & MHMC_CTRL_OVERRIDE_CAMERA)) return false;
    memcpy(cam + kCamPos, c.camPos, 12);
    memcpy(cam + kCamTarget, c.camTarget, 12);
    memcpy(cam + kCamUp, c.camUp, 12);
    if (c.fovYDeg > 5.0f && c.fovYDeg < 170.0f) *(float*)(cam + kCamFov) = c.fovYDeg;
    if (mgr) {
        // No camera shake: Minecraft's camera doesn't shake with MHW's.
        float* shake = (float*)(mgr + kShakeMatrix);
        for (int i = 0; i < 16; i++) shake[i] = (i % 5 == 0) ? 1.0f : 0.0f;
    }
    InterlockedExchange(&g_overrideApplied, 1);
    return true;
}

static void __fastcall hkCalcCamera(void* cam) {
    InflightGuard guard;
    oCalcCamera(cam);
    if (cam && *(uintptr_t*)cam == addr::kUMhCameraVt) apply_camera_override((uint8_t*)cam, nullptr, nullptr);
}

static void __fastcall hkCamMove(void* self) {
    InflightGuard guard;
    if (!g_gameThread) {
        g_gameThread = GetCurrentThreadId();
        log("game: sMhCamera::move runs on thread %lu", g_gameThread);
    }
    if (self && *(uintptr_t*)self == addr::kSMhCameraVt) {
        LARGE_INTEGER t0, t1, fq;
        QueryPerformanceCounter(&t0);
        game_tick((uint8_t*)self);
        QueryPerformanceCounter(&t1);
        QueryPerformanceFrequency(&fq);
        perf_add_tick((double)(t1.QuadPart - t0.QuadPart) * 1000.0 / fq.QuadPart);
    }
    oCamMove(self);
}

// ---------------------------------------------------------------------------------------

bool game_init() {
    log("game: exe base %p size 0x%zx", (void*)main_module_base(), main_module_size());
    g_sigsOk = true;
    for (const CodeSig& s : kSigs) {
        if (!bytes_match(s.addr, s.sig)) {
            log("game: signature mismatch for %s at %p; unsupported MHW build", s.name, (void*)s.addr);
            g_sigsOk = false;
        }
    }
    if (!g_sigsOk) return false;
    clear_hazards();
    MH_STATUS st = MH_CreateHook((void*)addr::kSMhCameraMove, (void*)hkCamMove, (void**)&oCamMove);
    if (st == MH_OK) st = MH_EnableHook((void*)addr::kSMhCameraMove);
    g_hooked = (st == MH_OK);
    MH_STATUS rs = MH_CreateHook((void*)addr::kRefreshEntity, (void*)hkRefresh, (void**)&oRefresh);
    if (rs == MH_OK) rs = MH_EnableHook((void*)addr::kRefreshEntity);
    log("game: RefreshEntityParams hook %s (%d)", rs == MH_OK ? "installed" : "FAILED", rs);
    MH_STATUS cs = MH_CreateHook((void*)addr::kCalcCamera, (void*)hkCalcCamera, (void**)&oCalcCamera);
    if (cs == MH_OK) cs = MH_EnableHook((void*)addr::kCalcCamera);
    log("game: CalculateCamera hook %s (%d)", cs == MH_OK ? "installed" : "FAILED", cs);
    MH_STATUS ds = MH_CreateHook((void*)addr::kShowDamage, (void*)hkShowDamage, (void**)&oShowDamage);
    if (ds == MH_OK) ds = MH_EnableHook((void*)addr::kShowDamage);
    log("game: damage-number observer %s (%d)", ds == MH_OK ? "installed" : "FAILED", ds);
    log("game: sMhCamera::move hook %s (%d)", g_hooked ? "installed" : "FAILED", st);
    return g_hooked;
}

void game_shutdown() {
    // Give the hunter back its visibility if we hid it (the game recomputes opacity itself).
    uint8_t* pl = (uint8_t*)g_player;
    g_hideEntity = 0;
    if (g_hunterHidden && pl && mem_readable(pl + kOpacity, 4)) *(float*)(pl + kOpacity) = 1.0f;
    g_hunterHidden = false;
    g_hooked = false;
    clear_hazards();
}

static uint8_t* read_ptr_checked(uintptr_t at, uintptr_t expectVt, size_t minSize) {
    uintptr_t p = 0;
    if (!mem_read((void*)at, &p, 8) || !p) return nullptr;
    if (!mem_readable((void*)p, minSize)) return nullptr;
    if (expectVt && *(uintptr_t*)p != expectVt) return nullptr;
    return (uint8_t*)p;
}

void game_fill_state(MhmcGameState* st) {
    st->unitsPerMeter = 100.0f;  // MHW works in centimetres
    if (!g_sigsOk) return;

    uint8_t* mgr = read_ptr_checked(addr::kSMhCameraPtr, addr::kSMhCameraVt, 0x1EB0);
    if (mgr) {
        uint8_t* vp = mgr + kViewport0;
        uint8_t* cam = read_ptr_checked((uintptr_t)(mgr + kVp0Camera), 0, 0x200);
        if (cam) {
            memcpy(st->camPos, cam + kCamPos, 12);
            memcpy(st->camTarget, cam + kCamTarget, 12);
            memcpy(st->camUp, cam + kCamUp, 12);
            st->fovYDeg = *(float*)(cam + kCamFov);
            st->nearZ = *(float*)(cam + kCamNear);
            st->farZ = *(float*)(cam + kCamFar);
            st->aspect = *(float*)(cam + kCamAspect);
            memcpy(st->view, vp + kVpView, 64);
            memcpy(st->proj, vp + kVpProj, 64);
            st->flags |= MHMC_STATE_CAMERA_VALID | MHMC_STATE_MATRICES_VALID;
        }
    }
    if (InterlockedExchange(&g_overrideApplied, 0)) st->flags |= MHMC_STATE_CAM_OVERRIDDEN;

    uint8_t* sp = read_ptr_checked(addr::kSPlayerPtr, addr::kSPlayerVt, kSPlayerZone + 4);
    if (sp) st->stageId = *(uint32_t*)(sp + kSPlayerZone);

    uint8_t* pl = (uint8_t*)g_player;
    if (pl && mem_readable(pl, 0x200)) {
        memcpy(st->playerPos, pl + kPlayerPos, 12);
        memcpy(st->playerQuat, pl + kPlayerQuat, 16);
        if (!isnan(st->playerPos[0])) st->flags |= MHMC_STATE_PLAYER_VALID;
    }
}

}  // namespace mb
