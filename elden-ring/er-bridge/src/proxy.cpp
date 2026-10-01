// dinput8.dll proxy + loader for Elden Ring. It forwards the real DirectInput exports to
// Wine's builtin dinput8 and loads the actual bridge (erbridge\erbridge_core.dll), which can
// be hot-swapped while the game runs.
//
// Safety: Elden Ring mods must only run offline, without Easy Anti-Cheat. This DLL is only
// picked up when elden-ring/scripts/launch_er.sh starts eldenring.exe directly with a
// per-launch "dinput8=n,b" override (no registry override is installed), and even then it
// stays passive unless that launcher's ERBRIDGE=1 marker is set and no EAC module is loaded.
#include "common.h"
#include <string.h>
#include <ctype.h>
#include <stdio.h>
#include <tlhelp32.h>

static HMODULE g_real = nullptr;

static FARPROC real_proc(const char* name) {
    if (!g_real) {
        char path[MAX_PATH];
        UINT n = GetSystemDirectoryA(path, MAX_PATH);
        if (n == 0 || n > MAX_PATH - 20) return nullptr;
        strcat(path, "\\dinput8.dll");
        g_real = LoadLibraryA(path);
        if (!g_real) {
            mb::log("proxy: failed to load %s (%lu)", path, GetLastError());
            return nullptr;
        }
    }
    return GetProcAddress(g_real, name);
}

extern "C" {

HRESULT WINAPI Proxy_DirectInput8Create(HINSTANCE hinst, DWORD version, REFIID riid, LPVOID* out,
                                        void* outer) {
    typedef HRESULT(WINAPI * fn_t)(HINSTANCE, DWORD, REFIID, LPVOID*, void*);
    fn_t f = (fn_t)real_proc("DirectInput8Create");
    return f ? f(hinst, version, riid, out, outer) : E_FAIL;
}

HRESULT WINAPI Proxy_DllCanUnloadNow() {
    typedef HRESULT(WINAPI * fn_t)();
    fn_t f = (fn_t)real_proc("DllCanUnloadNow");
    return f ? f() : S_FALSE;
}

HRESULT WINAPI Proxy_DllGetClassObject(REFCLSID clsid, REFIID riid, LPVOID* out) {
    typedef HRESULT(WINAPI * fn_t)(REFCLSID, REFIID, LPVOID*);
    fn_t f = (fn_t)real_proc("DllGetClassObject");
    return f ? f(clsid, riid, out) : CLASS_E_CLASSNOTAVAILABLE;
}

HRESULT WINAPI Proxy_DllRegisterServer() {
    typedef HRESULT(WINAPI * fn_t)();
    fn_t f = (fn_t)real_proc("DllRegisterServer");
    return f ? f() : E_FAIL;
}

HRESULT WINAPI Proxy_DllUnregisterServer() {
    typedef HRESULT(WINAPI * fn_t)();
    fn_t f = (fn_t)real_proc("DllUnregisterServer");
    return f ? f() : E_FAIL;
}

void* WINAPI Proxy_GetdfDIJoystick() {
    typedef void*(WINAPI * fn_t)();
    fn_t f = (fn_t)real_proc("GetdfDIJoystick");
    return f ? f() : nullptr;
}

}  // extern "C"

// ---------------------------------------------------------------------------------------
// Core loader

typedef bool (*core_init_t)();
typedef void (*core_shutdown_t)();

static HMODULE g_self = nullptr;
static HMODULE g_core = nullptr;
static core_shutdown_t g_coreShutdown = nullptr;
static char g_bridgeDir[MAX_PATH];

static bool is_game_process() {
    char exe[MAX_PATH];
    GetModuleFileNameA(nullptr, exe, MAX_PATH);
    for (char* p = exe; *p; ++p) *p = (char)tolower((unsigned char)*p);
    return strstr(exe, "eldenring.exe") != nullptr;
}

// Only when started by our launcher (never in a Steam/EAC launch), and never next to EAC.
static bool launch_allowed() {
    char v[8] = {0};
    if (GetEnvironmentVariableA("ERBRIDGE", v, sizeof(v)) == 0 || strcmp(v, "1") != 0) {
        mb::log("loader: not started by launch_er.sh (ERBRIDGE unset); staying passive");
        return false;
    }
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId());
    if (snap != INVALID_HANDLE_VALUE) {
        MODULEENTRY32 me;
        me.dwSize = sizeof(me);
        bool eac = false;
        if (Module32First(snap, &me)) {
            do {
                char name[MAX_PATH];
                snprintf(name, sizeof(name), "%s", me.szModule);
                for (char* p = name; *p; ++p) *p = (char)tolower((unsigned char)*p);
                if (strstr(name, "easyanticheat") || strstr(name, "eosac")) eac = true;
            } while (!eac && Module32Next(snap, &me));
        }
        CloseHandle(snap);
        if (eac) {
            mb::log("loader: Easy Anti-Cheat is loaded; refusing to run (offline launch only)");
            return false;
        }
    }
    return true;
}

static bool game_window_ready() {
    struct Ctx {
        DWORD pid;
        bool found;
    } ctx = {GetCurrentProcessId(), false};
    EnumWindows(
        [](HWND hwnd, LPARAM lp) -> BOOL {
            Ctx* c = (Ctx*)lp;
            DWORD pid = 0;
            GetWindowThreadProcessId(hwnd, &pid);
            if (pid != c->pid || !IsWindowVisible(hwnd)) return TRUE;
            RECT r;
            GetClientRect(hwnd, &r);
            if (r.right - r.left >= 320 && r.bottom - r.top >= 200) {
                c->found = true;
                return FALSE;
            }
            return TRUE;
        },
        (LPARAM)&ctx);
    return ctx.found;
}

static void unload_core() {
    if (!g_core) return;
    if (g_coreShutdown) g_coreShutdown();
    FreeLibrary(g_core);
    g_core = nullptr;
    g_coreShutdown = nullptr;
    mb::shm_header()->coreStatus = 0;
    mb::log("loader: core unloaded");
}

static bool load_core() {
    ErmcHeader* h = mb::shm_header();
    uint32_t gen = h->coreGeneration + 1;
    char src[MAX_PATH], dir[MAX_PATH], dst[MAX_PATH];
    snprintf(src, sizeof(src), "%s\\erbridge_core.dll", g_bridgeDir);
    // Load a private copy so the build output can be overwritten while the game runs.
    snprintf(dir, sizeof(dir), "%s\\loaded", g_bridgeDir);
    CreateDirectoryA(dir, nullptr);
    snprintf(dst, sizeof(dst), "%s\\core_%lu_%u.dll", dir, GetCurrentProcessId(), gen);
    if (!CopyFileA(src, dst, FALSE)) {
        mb::log("loader: cannot copy %s (%lu)", src, GetLastError());
        h->coreStatus = -1;
        return false;
    }
    HMODULE m = LoadLibraryA(dst);
    if (!m) {
        mb::log("loader: LoadLibrary(%s) failed (%lu)", dst, GetLastError());
        h->coreStatus = -2;
        return false;
    }
    core_init_t init = (core_init_t)GetProcAddress(m, "erb_core_init");
    core_shutdown_t shutdown = (core_shutdown_t)GetProcAddress(m, "erb_core_shutdown");
    if (!init || !shutdown) {
        mb::log("loader: core is missing its entry points");
        FreeLibrary(m);
        h->coreStatus = -3;
        return false;
    }
    g_core = m;
    g_coreShutdown = shutdown;
    bool ok = init();
    h->coreGeneration = gen;
    h->coreStatus = ok ? 1 : -4;
    mb::log("loader: core generation %u loaded (%s)", gen, ok ? "ok" : "init reported errors");
    return ok;
}

static DWORD WINAPI loader_thread(LPVOID) {
    mb::log("loader: attached to pid %lu", GetCurrentProcessId());
    if (!mb::shm_open()) return 1;

    char self[MAX_PATH];
    GetModuleFileNameA(g_self, self, MAX_PATH);
    char* slash = strrchr(self, '\\');
    if (slash) *slash = 0;
    snprintf(g_bridgeDir, sizeof(g_bridgeDir), "%s\\erbridge", self);

    if (!launch_allowed()) return 0;

    // The core hooks the renderer, so wait for the game's window and D3D12 to exist.
    uint64_t start = mb::now_ms();
    while (!(GetModuleHandleA("d3d12.dll") && GetModuleHandleA("dxgi.dll") && game_window_ready())) {
        Sleep(20);
        if (mb::now_ms() - start > 10 * 60 * 1000) {
            mb::log("loader: game window never appeared");
            return 1;
        }
    }
    Sleep(1500);  // let the game finish creating its device and swapchain

    ErmcHeader* h = mb::shm_header();
    h->coreReloadAck = h->coreReloadReq;
    load_core();
    for (;;) {
        Sleep(50);
        uint32_t req = h->coreReloadReq;
        if (req != h->coreReloadAck) {
            mb::log("loader: hot reload requested");
            unload_core();
            load_core();
            h->coreReloadAck = req;
        }
    }
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_self = inst;
        DisableThreadLibraryCalls(inst);
        if (is_game_process()) CreateThread(nullptr, 0, loader_thread, nullptr, 0, nullptr);
    }
    return TRUE;
}
