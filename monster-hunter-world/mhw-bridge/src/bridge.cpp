// Per-frame glue, called on the game's render thread from the Present hook.
#include "common.h"
#include <d3d11.h>
#include <string.h>

namespace mb {

static uint64_t g_frame = 0;

// Frame-time accounting: what our code costs MHW per frame, logged every ~5 s.
static double g_compositeMs = 0, g_tickMs = 0, g_frameMsSum = 0, g_frameMsMax = 0;
static uint32_t g_perfFrames = 0;
static LARGE_INTEGER g_lastPresent = {};

void perf_add_composite(double ms) { g_compositeMs += ms; }
void perf_add_tick(double ms) { g_tickMs += ms; }

static void perf_frame() {
    LARGE_INTEGER now, fq;
    QueryPerformanceCounter(&now);
    QueryPerformanceFrequency(&fq);
    if (g_lastPresent.QuadPart) {
        double ms = (double)(now.QuadPart - g_lastPresent.QuadPart) * 1000.0 / fq.QuadPart;
        g_frameMsSum += ms;
        if (ms > g_frameMsMax) g_frameMsMax = ms;
        if (++g_perfFrames >= 300) {
            log("perf: %.1f fps (worst frame %.1f ms); per frame: composite %.2f ms, game-thread tick %.2f ms",
                1000.0 * g_perfFrames / g_frameMsSum, g_frameMsMax, g_compositeMs / g_perfFrames, g_tickMs / g_perfFrames);
            g_perfFrames = 0;
            g_frameMsSum = g_frameMsMax = g_compositeMs = g_tickMs = 0;
        }
    }
    g_lastPresent = now;
}
static HWND g_hwnd = nullptr;
static UINT g_bbW = 0, g_bbH = 0;
static volatile LONG g_resized = 1;

HWND game_hwnd() { return g_hwnd; }

void on_resize() {
    // ResizeBuffers fails while anyone still holds a back buffer reference.
    compositor_release_rtv();
    InterlockedExchange(&g_resized, 1);
}

void on_present(IDXGISwapChain* sc) {
    g_frame++;
    perf_frame();
    if (g_frame == 1) log("present: running on thread %lu", GetCurrentThreadId());
    if (g_resized || !g_hwnd) {
        DXGI_SWAP_CHAIN_DESC d;
        if (SUCCEEDED(sc->GetDesc(&d))) {
            if (d.OutputWindow != g_hwnd || d.BufferDesc.Width != g_bbW || d.BufferDesc.Height != g_bbH)
                log("present: window %p backbuffer %ux%u", d.OutputWindow, d.BufferDesc.Width,
                    d.BufferDesc.Height);
            g_hwnd = d.OutputWindow;
            g_bbW = d.BufferDesc.Width;
            g_bbH = d.BufferDesc.Height;
        }
        InterlockedExchange(&g_resized, 0);
    }

    // Gather everything first (the memory checks are slow under Wine), then publish it
    // with a short memcpy so readers practically never see a write in progress.
    MhmcGameState local;
    memset(&local, 0, sizeof(local));
    local.frame = g_frame;
    local.bbW = g_bbW;
    local.bbH = g_bbH;
    uint32_t flags = 0;
    RECT rc;
    POINT tl = {0, 0};
    if (g_hwnd && GetClientRect(g_hwnd, &rc) && ClientToScreen(g_hwnd, &tl)) {
        local.winX = tl.x;
        local.winY = tl.y;
        local.winW = rc.right - rc.left;
        local.winH = rc.bottom - rc.top;
        flags |= MHMC_STATE_WINDOW_VALID;
        if (GetForegroundWindow() == g_hwnd) flags |= MHMC_STATE_WINDOW_FOCUSED;
    }
    local.flags = flags;
    game_fill_state(&local);  // ORs in camera/player flags

    LARGE_INTEGER t0, t1, fq;
    QueryPerformanceCounter(&t0);
    compositor_on_present(sc, g_bbW, g_bbH);
    QueryPerformanceCounter(&t1);
    QueryPerformanceFrequency(&fq);
    perf_add_composite((double)(t1.QuadPart - t0.QuadPart) * 1000.0 / fq.QuadPart);
    if (compositor_active()) local.flags |= MHMC_STATE_COMPOSITING;

    MhmcGameState* st = shm_state();
    state_begin_write();
    memcpy((uint8_t*)st + 4, (const uint8_t*)&local + 4, sizeof(local) - 4);  // everything but seq
    state_end_write();

    shm_header()->mhwHeartbeat = g_frame;
    if (g_frame == 1 || g_frame % 3600 == 0) log("present: frame %llu", (unsigned long long)g_frame);
}

}  // namespace mb
