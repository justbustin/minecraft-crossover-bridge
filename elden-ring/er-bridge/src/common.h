#pragma once
#include <windows.h>
#include <stdint.h>
#include "bridge_protocol.h"

namespace mb {

// log.cpp
void log(const char* fmt, ...) __attribute__((format(gnu_printf, 1, 2)));
void log_close();
uint64_t now_ms();

// shm.cpp
bool shm_open();
void shm_close();
ErmcHeader* shm_header();
ErmcGameState* shm_state();
ErmcControl* shm_control();
ErmcCmdBlock* shm_cmd();
uint8_t* shm_cmd_resp();
ErmcRayHeader* shm_rays();
ErmcEntityTable* shm_entities();
ErmcPassageTable* shm_passages();
ErmcHunterEvents* shm_hunter();
ErmcDamageQueue* shm_damage();

// Seqlock writer for the state block (ER is the only writer).
void state_begin_write();
void state_end_write();
// Consistent snapshot of the control block written by Minecraft. Returns false if the
// writer never published anything.
bool control_snapshot(ErmcControl* out);

// memutil.cpp
bool mem_readable(const void* p, size_t len);
bool mem_read(const void* p, void* out, size_t len);
bool mem_write(void* p, const void* src, size_t len);
// Scan [start, end) for a masked byte pattern. mask byte 0xFF = must match, 0x00 = wildcard.
size_t mem_scan(uintptr_t start, uintptr_t end, const uint8_t* pat, const uint8_t* mask,
                size_t len, uint32_t flags, uintptr_t* out, size_t maxOut);
// Parse an IDA-style signature ("48 8B ?? 05") and scan the main module's executable pages.
uintptr_t find_pattern(const char* sig);
uintptr_t main_module_base();
size_t main_module_size();

// debugcmd.cpp
void debugcmd_poll();

// frame.cpp: per-frame glue. on_frame() publishes the state block and the heartbeat. Until a
// real per-frame hook calls frame_claim_source(), the core's pacer thread calls it at ~60 Hz.
void on_frame();
void frame_claim_source();
bool frame_source_is_pacer();
HWND game_hwnd();
void perf_add_tick(double ms);

// game.cpp: Elden Ring-specific memory access and hooks.
bool game_init();
void game_detach();    // shutdown step 1: our tasks stop calling into this core
void game_shutdown();  // step 2, once nothing runs our code: hand the character back
void game_fill_state(ErmcGameState* st);

// compositor.cpp: draws Minecraft's frames into Elden Ring's at Present (D3D12).
bool compositor_init();
void compositor_poll();       // worker thread: retries compositor_init until the game has a swapchain
void compositor_depth_poll(); // worker thread: runs a requested scan for the game's depth buffers
void game_worker_poll();      // worker thread: lists doors, levers... near the player (reach)
void compositor_detach();    // shutdown step 1: the Present stubs go straight to the originals
void compositor_shutdown();  // step 2: release D3D12 objects
void compositor_note_applied_pose(uint64_t poseId);  // camera task: the pose this game frame uses
bool compositor_active();

// core.cpp: number of threads currently inside one of our detours. The loader only
// unloads the core once this drops to zero.
extern volatile LONG g_inflight;
struct InflightGuard {
    InflightGuard() { InterlockedIncrement(&g_inflight); }
    ~InflightGuard() { InterlockedDecrement(&g_inflight); }
};

}  // namespace mb
