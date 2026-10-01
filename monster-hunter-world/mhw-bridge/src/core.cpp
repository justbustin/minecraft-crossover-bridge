// mhwbridge_core.dll entry points, called by the dinput8 loader. Must be able to shut down
// cleanly: every hook is removed and no game thread may still be executing our code when
// the loader calls FreeLibrary.
#include "common.h"
#include "MinHook.h"

namespace mb {

volatile LONG g_inflight = 0;
static HANDLE g_worker = nullptr;
static volatile LONG g_stop = 0;

bool core_stopping() { return g_stop != 0; }

static DWORD WINAPI worker(LPVOID) {
    while (!g_stop) {
        debugcmd_poll();
        Sleep(1);
    }
    return 0;
}

}  // namespace mb

extern "C" __declspec(dllexport) bool mhwb_core_init() {
    using namespace mb;
    if (!shm_open()) return false;
    log("core: init");
    MH_STATUS st = MH_Initialize();
    if (st != MH_OK && st != MH_ERROR_ALREADY_INITIALIZED) {
        log("core: MH_Initialize failed %d", st);
        return false;
    }
    bool ok = d3d11_install_hooks();
    if (!ok) log("core: D3D11 hooks FAILED");
    game_init();
    g_stop = 0;
    g_worker = CreateThread(nullptr, 0, worker, nullptr, 0, nullptr);
    return ok;
}

extern "C" __declspec(dllexport) void mhwb_core_shutdown() {
    using namespace mb;
    log("core: shutdown");
    g_stop = 1;
    if (g_worker) {
        // Mailbox calls and scans give up as soon as they see g_stop. Never unload under a
        // running worker: this runs on the loader's own thread, so waiting doesn't stall MHW.
        while (WaitForSingleObject(g_worker, 5000) != WAIT_OBJECT_0) log("core: waiting for the mailbox worker to stop");
        CloseHandle(g_worker);
        g_worker = nullptr;
    }
    MH_DisableHook(MH_ALL_HOOKS);
    // A thread may have jumped into a detour just before the prologue was restored; give
    // it time to register itself, then wait for every detour to return.
    Sleep(100);
    for (int i = 0; i < 400 && g_inflight > 0; i++) Sleep(5);
    if (g_inflight > 0) log("core: %ld hooks still in flight at shutdown", g_inflight);
    compositor_shutdown();
    MH_Uninitialize();
    game_shutdown();
    shm_close();
    log("core: shutdown complete");
    log_close();
}
