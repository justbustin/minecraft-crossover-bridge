#pragma once
#include <windows.h>
#include <stdint.h>
#include "bridge_protocol.h"

struct IDXGISwapChain;

namespace mb {

// log.cpp
void log(const char* fmt, ...) __attribute__((format(gnu_printf, 1, 2)));
void log_close();
uint64_t now_ms();

// shm.cpp
bool shm_open();
void shm_close();
MhmcHeader* shm_header();
MhmcGameState* shm_state();
MhmcControl* shm_control();
MhmcCmdBlock* shm_cmd();
uint8_t* shm_cmd_resp();
MhmcRayHeader* shm_rays();
MhmcEntityTable* shm_entities();
MhmcHunterEvents* shm_hunter();
MhmcDamageQueue* shm_damage();
MhmcHazardTable* shm_hazards();

bool core_stopping();  // the core is shutting down (hot reload): long waits give up

// Seqlock writer for the state block (MHW is the only writer).
void state_begin_write();
void state_end_write();
// Consistent snapshot of the control block written by Minecraft. Returns false if the
// writer never published anything.
bool control_snapshot(MhmcControl* out);

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

// d3d11hook.cpp (MinHook must already be initialised)
bool d3d11_install_hooks();

// bridge.cpp: per-frame glue called from the Present hook.
void on_present(IDXGISwapChain* swapchain);
void on_resize();
HWND game_hwnd();
void perf_add_composite(double ms);
void perf_add_tick(double ms);

// compositor.cpp: draws Minecraft's frames into MHW's frame (game thread, at Present).
void compositor_on_present(IDXGISwapChain* sc, UINT bbW, UINT bbH);
void compositor_note_applied_pose(uint64_t poseId);
void compositor_set_pose_lag(int lag);
void compositor_release_rtv();
bool compositor_active();
void compositor_shutdown();

// game.cpp: MHW-specific memory access and hooks.
bool game_init();
void game_shutdown();
void game_fill_state(MhmcGameState* st);

// core.cpp: number of threads currently inside one of our detours. The loader only
// unloads the core once this drops to zero.
extern volatile LONG g_inflight;
struct InflightGuard {
    InflightGuard() { InterlockedIncrement(&g_inflight); }
    ~InflightGuard() { InterlockedDecrement(&g_inflight); }
};

}  // namespace mb
