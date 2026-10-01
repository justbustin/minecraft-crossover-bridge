// Per-frame glue: publishes the game state block (window rect, frame counter, whatever
// game.cpp knows about the camera and player) and the heartbeat Minecraft watches.
//
// Graphics-agnostic on purpose: Elden Ring renders with D3D12 through CrossOver's D3DMetal,
// where a Present hook may not be available. Until a frame source hooks in (a Present
// hook or a game-thread hook calling on_frame()), the core's pacer thread calls it at
// ~60 Hz so the overlay window can still find and follow the game window.
#include "common.h"
#include <string.h>

namespace mb {

static uint64_t g_frame = 0;
static HWND g_hwnd = nullptr;
static volatile LONG g_frameSource = 0;  // 0 = pacer thread, 1 = a real per-frame hook
// on_frame() may be entered from the pacer thread and from the game's own task thread.
// Never block here: a thread that finds it busy just skips that frame. (A blocking lock
// deadlocked the game once: the pacer held it while waiting on the game's main thread.)
static volatile LONG g_inFrame = 0;

// Frame-time accounting, logged every 300 frames.
static double g_tickMs = 0, g_frameMsSum = 0, g_frameMsMax = 0;
static uint32_t g_perfFrames = 0;
static LARGE_INTEGER g_lastFrame = {};

void perf_add_tick(double ms) { g_tickMs += ms; }

static void perf_frame() {
    LARGE_INTEGER now, fq;
    QueryPerformanceCounter(&now);
    QueryPerformanceFrequency(&fq);
    if (g_lastFrame.QuadPart) {
        double ms = (double)(now.QuadPart - g_lastFrame.QuadPart) * 1000.0 / fq.QuadPart;
        g_frameMsSum += ms;
        if (ms > g_frameMsMax) g_frameMsMax = ms;
        if (++g_perfFrames >= 300) {
            log("perf: %.1f fps (worst frame %.1f ms), game-thread tick %.2f ms, source %s",
                1000.0 * g_perfFrames / g_frameMsSum, g_frameMsMax, g_tickMs / g_perfFrames,
                g_frameSource ? "game" : "pacer");
            g_perfFrames = 0;
            g_frameMsSum = g_frameMsMax = g_tickMs = 0;
        }
    }
    g_lastFrame = now;
}

static BOOL CALLBACK find_game_window(HWND hwnd, LPARAM lp) {
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid != GetCurrentProcessId() || !IsWindowVisible(hwnd)) return TRUE;
    RECT r;
    GetClientRect(hwnd, &r);
    if (r.right - r.left < 320 || r.bottom - r.top < 200) return TRUE;
    *(HWND*)lp = hwnd;
    return FALSE;
}

// Never call anything that sends a message to the game's window (GetWindowText,
// SendMessage...) from here: the window belongs to the game's main thread, which also runs
// our per-frame task, so a cross-thread message can deadlock it.
HWND game_hwnd() {
    if (!g_hwnd || !IsWindow(g_hwnd)) {
        HWND h = nullptr;
        EnumWindows(find_game_window, (LPARAM)&h);
        if (h != g_hwnd && h) log("frame: game window %p", (void*)h);
        g_hwnd = h;
    }
    return g_hwnd;
}

void frame_claim_source() {
    if (InterlockedExchange(&g_frameSource, 1) == 0) log("frame: a per-frame game hook now drives the bridge");
}

bool frame_source_is_pacer() { return g_frameSource == 0; }

void on_frame() {
    if (InterlockedCompareExchange(&g_inFrame, 1, 0) != 0) return;  // busy: skip, never wait
    g_frame++;
    perf_frame();

    // Gather everything first (memory checks are slow under Wine), then publish it with a
    // short memcpy so readers practically never see a write in progress.
    ErmcGameState local;
    memset(&local, 0, sizeof(local));
    local.frame = g_frame;
    uint32_t flags = 0;
    HWND hwnd = game_hwnd();
    RECT rc;
    POINT tl = {0, 0};
    if (hwnd && GetClientRect(hwnd, &rc) && ClientToScreen(hwnd, &tl)) {
        local.winX = tl.x;
        local.winY = tl.y;
        local.winW = rc.right - rc.left;
        local.winH = rc.bottom - rc.top;
        local.bbW = (uint32_t)local.winW;
        local.bbH = (uint32_t)local.winH;
        flags |= ERMC_STATE_WINDOW_VALID;
        if (GetForegroundWindow() == hwnd) flags |= ERMC_STATE_WINDOW_FOCUSED;
    }
    local.flags = flags;
    game_fill_state(&local);  // ORs in camera/player flags

    ErmcGameState* st = shm_state();
    state_begin_write();
    memcpy((uint8_t*)st + 4, (const uint8_t*)&local + 4, sizeof(local) - 4);  // everything but seq
    state_end_write();

    shm_header()->hostHeartbeat = g_frame;
    if (g_frame == 1 || g_frame % 3600 == 0) log("frame: %llu", (unsigned long long)g_frame);
    InterlockedExchange(&g_inFrame, 0);
}

}  // namespace mb
