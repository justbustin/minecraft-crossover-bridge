// erbridge_core.dll entry points, called by the dinput8 loader. Must be able to shut down
// cleanly: every hook is removed and no game thread may still be executing our code when
// the loader calls FreeLibrary.
#include "common.h"
#include "MinHook.h"

namespace mb {

volatile LONG g_inflight = 0;
static HANDLE g_worker = nullptr;
static volatile LONG g_stop = 0;

static HANDLE g_pacer = nullptr;

// Dev (debugFlags bit4, set by erctl): press Esc in Elden Ring through Wine's own input queue,
// e.g. to close a tutorial popup while testing. Only reaches the game while its window has focus.
static void dev_press_keys() {
    ErmcHeader* h = shm_header();
    if (!(h->debugFlags & 16)) return;
    h->debugFlags = h->debugFlags & ~16u;
    INPUT in[1] = {};
    in[0].type = INPUT_KEYBOARD;
    in[0].ki.wVk = VK_ESCAPE;
    in[0].ki.wScan = 0x01;
    in[0].ki.dwFlags = KEYEVENTF_SCANCODE;
    UINT a = SendInput(1, in, sizeof(INPUT));
    Sleep(150);
    in[0].ki.dwFlags = KEYEVENTF_SCANCODE | KEYEVENTF_KEYUP;
    UINT b = SendInput(1, in, sizeof(INPUT));
    log("dev: pressed Esc (%u/%u)", a, b);
}

static DWORD WINAPI worker(LPVOID) {
    while (!g_stop) {
        dev_press_keys();
        debugcmd_poll();
        compositor_poll();
        compositor_depth_poll();
        game_worker_poll();
        Sleep(1);
    }
    return 0;
}

// Publishes the state block at ~60 Hz until a real per-frame hook takes over.
static DWORD WINAPI pacer(LPVOID) {
    while (!g_stop) {
        if (frame_source_is_pacer()) on_frame();
        Sleep(16);
    }
    return 0;
}

}  // namespace mb

extern "C" __declspec(dllexport) bool erb_core_init() {
    using namespace mb;
    if (!shm_open()) return false;
    log("core: init");
    MH_STATUS st = MH_Initialize();
    if (st != MH_OK && st != MH_ERROR_ALREADY_INITIALIZED) {
        log("core: MH_Initialize failed %d", st);
        return false;
    }
    bool ok = game_init();
    if (ok) compositor_init();  // optional: without it Minecraft shows in its own overlay window
    g_stop = 0;
    g_worker = CreateThread(nullptr, 0, worker, nullptr, 0, nullptr);
    g_pacer = CreateThread(nullptr, 0, pacer, nullptr, 0, nullptr);
    return ok;
}

extern "C" __declspec(dllexport) void erb_core_shutdown() {
    using namespace mb;
    log("core: shutdown");
    g_stop = 1;
    HANDLE* threads[] = {&g_worker, &g_pacer};
    for (HANDLE* t : threads) {
        if (*t) {
            WaitForSingleObject(*t, 2000);
            CloseHandle(*t);
            *t = nullptr;
        }
    }
    // Stop every entry into this core: game tasks, Present stubs, MinHook detours.
    game_detach();
    compositor_detach();
    MH_DisableHook(MH_ALL_HOOKS);
    // A thread may have jumped into a detour just before the prologue was restored; give
    // it time to register itself, then wait for every detour to return.
    Sleep(100);
    for (int i = 0; i < 400 && g_inflight > 0; i++) Sleep(5);
    if (g_inflight > 0) log("core: %ld hooks still in flight at shutdown", g_inflight);
    MH_Uninitialize();
    compositor_shutdown();
    game_shutdown();
    shm_close();
    log("core: shutdown complete");
    log_close();
}
