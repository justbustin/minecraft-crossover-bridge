#include "common.h"
#include <string.h>

namespace mb {

static uint8_t* g_base = nullptr;

bool shm_open() {
    if (g_base) return true;
    CreateDirectoryA("Z:\\tmp\\ermc", nullptr);
    HANDLE f = CreateFileA("Z:\\tmp\\ermc\\bridge.shm", GENERIC_READ | GENERIC_WRITE,
                           FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) {
        log("shm: CreateFile failed: %lu", GetLastError());
        return false;
    }
    HANDLE m = CreateFileMappingA(f, nullptr, PAGE_READWRITE, 0, ERMC_SHM_SIZE, nullptr);
    if (!m) {
        log("shm: CreateFileMapping failed: %lu", GetLastError());
        CloseHandle(f);
        return false;
    }
    g_base = (uint8_t*)MapViewOfFile(m, FILE_MAP_ALL_ACCESS, 0, 0, ERMC_SHM_SIZE);
    if (!g_base) {
        log("shm: MapViewOfFile failed: %lu", GetLastError());
        return false;
    }
    ErmcHeader* h = shm_header();
    if (h->magic != ERMC_MAGIC || h->version != ERMC_VERSION) {
        // Fresh or stale file: initialise everything we own. Minecraft does the same for
        // its blocks when it attaches, so either side may start first.
        memset(g_base, 0, ERMC_OFF_RAYS);
        h->version = ERMC_VERSION;
        h->size = ERMC_SHM_SIZE;
        MemoryBarrier();
        h->magic = ERMC_MAGIC;
    }
    // The previous session may have left a request that was never answered.
    shm_cmd()->respSeq = shm_cmd()->reqSeq;
    if (h->hostPid != GetCurrentProcessId()) {
        h->hostPid = GetCurrentProcessId();
        h->hostStartMs = now_ms();
        h->coreGeneration = 0;
        h->coreStatus = 0;
    }
    log("shm: mapped %u bytes at %p", ERMC_SHM_SIZE, g_base);
    return true;
}

void shm_close() {
    if (g_base) UnmapViewOfFile(g_base);
    g_base = nullptr;
}

ErmcHeader* shm_header() { return (ErmcHeader*)(g_base + ERMC_OFF_HEADER); }
ErmcGameState* shm_state() { return (ErmcGameState*)(g_base + ERMC_OFF_STATE); }
ErmcControl* shm_control() { return (ErmcControl*)(g_base + ERMC_OFF_CONTROL); }
ErmcCmdBlock* shm_cmd() { return (ErmcCmdBlock*)(g_base + ERMC_OFF_CMD); }
uint8_t* shm_cmd_resp() { return g_base + ERMC_OFF_CMD_RESP; }
ErmcRayHeader* shm_rays() { return (ErmcRayHeader*)(g_base + ERMC_OFF_RAYS); }
ErmcHunterEvents* shm_hunter() { return (ErmcHunterEvents*)(g_base + ERMC_OFF_HUNTER); }
ErmcEntityTable* shm_entities() { return (ErmcEntityTable*)(g_base + ERMC_OFF_ENTITIES); }
ErmcPassageTable* shm_passages() { return (ErmcPassageTable*)(g_base + ERMC_OFF_PASSAGES); }
ErmcDamageQueue* shm_damage() { return (ErmcDamageQueue*)(g_base + ERMC_OFF_DAMAGE); }

// x86 (and Rosetta's emulation of it) is TSO: stores are not reordered with other stores
// and loads are not reordered with other loads, so compiler barriers are sufficient here.
// The Java side uses explicit acquire/release fences.
void state_begin_write() {
    ErmcGameState* s = shm_state();
    s->seq = s->seq + 1;  // odd: write in progress
    __asm__ __volatile__("" ::: "memory");
}

void state_end_write() {
    ErmcGameState* s = shm_state();
    __asm__ __volatile__("" ::: "memory");
    s->seq = s->seq + 1;  // even: stable
}

bool control_snapshot(ErmcControl* out) {
    ErmcControl* c = shm_control();
    for (int tries = 0; tries < 64; tries++) {
        uint32_t s1 = c->seq;
        __asm__ __volatile__("" ::: "memory");
        if (s1 & 1) {
            YieldProcessor();
            continue;
        }
        memcpy(out, (const void*)c, sizeof(*out));
        __asm__ __volatile__("" ::: "memory");
        if (c->seq == s1) return s1 != 0;
    }
    return false;
}

}  // namespace mb
