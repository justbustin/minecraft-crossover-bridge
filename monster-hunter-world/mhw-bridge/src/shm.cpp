#include "common.h"
#include <string.h>

namespace mb {

static uint8_t* g_base = nullptr;

bool shm_open() {
    if (g_base) return true;
    CreateDirectoryA("Z:\\tmp\\mhwmc", nullptr);
    HANDLE f = CreateFileA("Z:\\tmp\\mhwmc\\bridge.shm", GENERIC_READ | GENERIC_WRITE,
                           FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) {
        log("shm: CreateFile failed: %lu", GetLastError());
        return false;
    }
    HANDLE m = CreateFileMappingA(f, nullptr, PAGE_READWRITE, 0, MHMC_SHM_SIZE, nullptr);
    if (!m) {
        log("shm: CreateFileMapping failed: %lu", GetLastError());
        CloseHandle(f);
        return false;
    }
    g_base = (uint8_t*)MapViewOfFile(m, FILE_MAP_ALL_ACCESS, 0, 0, MHMC_SHM_SIZE);
    if (!g_base) {
        log("shm: MapViewOfFile failed: %lu", GetLastError());
        return false;
    }
    MhmcHeader* h = shm_header();
    if (h->magic != MHMC_MAGIC || h->version != MHMC_VERSION) {
        // Fresh or stale file: initialise everything we own. Minecraft does the same for
        // its blocks when it attaches, so either side may start first.
        memset(g_base, 0, MHMC_OFF_RAYS);
        h->version = MHMC_VERSION;
        h->size = MHMC_SHM_SIZE;
        MemoryBarrier();
        h->magic = MHMC_MAGIC;
    }
    // The previous session may have left a request that was never answered.
    shm_cmd()->respSeq = shm_cmd()->reqSeq;
    if (h->mhwPid != GetCurrentProcessId()) {
        h->mhwPid = GetCurrentProcessId();
        h->mhwStartMs = now_ms();
        h->coreGeneration = 0;
        h->coreStatus = 0;
    }
    log("shm: mapped %u bytes at %p", MHMC_SHM_SIZE, g_base);
    return true;
}

void shm_close() {
    if (g_base) UnmapViewOfFile(g_base);
    g_base = nullptr;
}

MhmcHeader* shm_header() { return (MhmcHeader*)(g_base + MHMC_OFF_HEADER); }
MhmcGameState* shm_state() { return (MhmcGameState*)(g_base + MHMC_OFF_STATE); }
MhmcControl* shm_control() { return (MhmcControl*)(g_base + MHMC_OFF_CONTROL); }
MhmcCmdBlock* shm_cmd() { return (MhmcCmdBlock*)(g_base + MHMC_OFF_CMD); }
uint8_t* shm_cmd_resp() { return g_base + MHMC_OFF_CMD_RESP; }
MhmcRayHeader* shm_rays() { return (MhmcRayHeader*)(g_base + MHMC_OFF_RAYS); }
MhmcHunterEvents* shm_hunter() { return (MhmcHunterEvents*)(g_base + MHMC_OFF_HUNTER); }
MhmcEntityTable* shm_entities() { return (MhmcEntityTable*)(g_base + MHMC_OFF_ENTITIES); }
MhmcDamageQueue* shm_damage() { return (MhmcDamageQueue*)(g_base + MHMC_OFF_DAMAGE); }
MhmcHazardTable* shm_hazards() { return (MhmcHazardTable*)(g_base + MHMC_OFF_HAZARDS); }

// x86 (and Rosetta's emulation of it) is TSO: stores are not reordered with other stores
// and loads are not reordered with other loads, so compiler barriers are sufficient here.
// The Java side uses explicit acquire/release fences.
void state_begin_write() {
    MhmcGameState* s = shm_state();
    s->seq = s->seq + 1;  // odd: write in progress
    __asm__ __volatile__("" ::: "memory");
}

void state_end_write() {
    MhmcGameState* s = shm_state();
    __asm__ __volatile__("" ::: "memory");
    s->seq = s->seq + 1;  // even: stable
}

bool control_snapshot(MhmcControl* out) {
    MhmcControl* c = shm_control();
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
