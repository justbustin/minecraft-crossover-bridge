// Hooks IDXGISwapChain::Present/Present1/ResizeBuffers. The addresses come from a
// throwaway device + swapchain; every swapchain of the same implementation (DXMT here)
// shares these functions, so inline-hooking them catches the game's swapchain too.
#include "common.h"
#include <d3d11.h>
#include <dxgi1_2.h>
#include "MinHook.h"

namespace mb {

typedef HRESULT(STDMETHODCALLTYPE* Present_t)(IDXGISwapChain*, UINT, UINT);
typedef HRESULT(STDMETHODCALLTYPE* Present1_t)(IDXGISwapChain1*, UINT, UINT,
                                               const DXGI_PRESENT_PARAMETERS*);
typedef HRESULT(STDMETHODCALLTYPE* ResizeBuffers_t)(IDXGISwapChain*, UINT, UINT, UINT, DXGI_FORMAT,
                                                    UINT);

static Present_t oPresent = nullptr;
static Present1_t oPresent1 = nullptr;
static ResizeBuffers_t oResizeBuffers = nullptr;
static volatile LONG g_inPresent = 0;

static HRESULT STDMETHODCALLTYPE hkPresent(IDXGISwapChain* sc, UINT sync, UINT flags) {
    InflightGuard guard;
    // Present1 may be implemented on top of Present (or vice versa); only tick once.
    if (InterlockedIncrement(&g_inPresent) == 1) on_present(sc);
    HRESULT hr = oPresent(sc, sync, flags);
    InterlockedDecrement(&g_inPresent);
    return hr;
}

static HRESULT STDMETHODCALLTYPE hkPresent1(IDXGISwapChain1* sc, UINT sync, UINT flags,
                                            const DXGI_PRESENT_PARAMETERS* params) {
    InflightGuard guard;
    if (InterlockedIncrement(&g_inPresent) == 1) on_present(sc);
    HRESULT hr = oPresent1(sc, sync, flags, params);
    InterlockedDecrement(&g_inPresent);
    return hr;
}

static HRESULT STDMETHODCALLTYPE hkResizeBuffers(IDXGISwapChain* sc, UINT count, UINT w, UINT h,
                                                 DXGI_FORMAT fmt, UINT flags) {
    InflightGuard guard;
    on_resize();
    return oResizeBuffers(sc, count, w, h, fmt, flags);
}

static const IID kIID_IDXGISwapChain1 = {
    0x790a45f7, 0x0d42, 0x4876, {0x98, 0x3a, 0x0a, 0x55, 0xcf, 0xe6, 0xf4, 0xaa}};

// The game's own swapchain: sMhRender (static 0x1451C4480) -> renderer (+0x78) -> +0x1488.
// Reading the vtable from it avoids creating a throwaway device/swapchain, which DXMT
// doesn't always tolerate.
static bool game_swapchain_vtable(void*** outVt) {
    uintptr_t srender = 0, renderer = 0, sc = 0;
    if (!mem_read((void*)0x1451C4480, &srender, 8) || !srender) return false;
    if (!mem_read((void*)(srender + 0x78), &renderer, 8) || !renderer) return false;
    if (!mem_read((void*)(renderer + 0x1488), &sc, 8) || !sc) return false;
    void** vt = nullptr;
    if (!mem_read((void*)sc, &vt, 8) || !vt || !mem_readable(vt, 23 * sizeof(void*))) return false;
    *outVt = vt;
    return true;
}

static bool install(void* pPresent, void* pPresent1, void* pResize) {
    log("d3d11: Present=%p Present1=%p ResizeBuffers=%p", pPresent, pPresent1, pResize);
    MH_STATUS st;
    if ((st = MH_CreateHook(pPresent, (void*)hkPresent, (void**)&oPresent)) != MH_OK) {
        log("d3d11: hook Present failed %d", st);
        return false;
    }
    if (pPresent1 && pPresent1 != pPresent &&
        (st = MH_CreateHook(pPresent1, (void*)hkPresent1, (void**)&oPresent1)) != MH_OK) {
        log("d3d11: hook Present1 failed %d (continuing)", st);
    }
    if ((st = MH_CreateHook(pResize, (void*)hkResizeBuffers, (void**)&oResizeBuffers)) != MH_OK)
        log("d3d11: hook ResizeBuffers failed %d (continuing)", st);
    if ((st = MH_EnableHook(pPresent)) != MH_OK) {
        log("d3d11: enable Present failed %d", st);
        return false;
    }
    if (oPresent1) MH_EnableHook(pPresent1);
    if (oResizeBuffers) MH_EnableHook(pResize);
    log("d3d11: hooks installed");
    return true;
}

bool d3d11_install_hooks() {
    void** gvt = nullptr;
    if (game_swapchain_vtable(&gvt)) {
        log("d3d11: using the game's swapchain vtable");
        return install(gvt[8], gvt[22], gvt[13]);
    }
    log("d3d11: game swapchain not found; falling back to a probe swapchain");

    HMODULE d3d11 = GetModuleHandleA("d3d11.dll");
    if (!d3d11) d3d11 = LoadLibraryA("d3d11.dll");
    PFN_D3D11_CREATE_DEVICE_AND_SWAP_CHAIN create =
        d3d11 ? (PFN_D3D11_CREATE_DEVICE_AND_SWAP_CHAIN)GetProcAddress(
                    d3d11, "D3D11CreateDeviceAndSwapChain")
              : nullptr;
    if (!create) {
        log("d3d11: D3D11CreateDeviceAndSwapChain not found");
        return false;
    }

    WNDCLASSEXA wc = {};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = DefWindowProcA;
    wc.hInstance = GetModuleHandleA(nullptr);
    wc.lpszClassName = "mhwbridge_dummy";
    RegisterClassExA(&wc);
    HWND hwnd = CreateWindowExA(0, wc.lpszClassName, "mhwbridge", WS_OVERLAPPEDWINDOW, 0, 0, 64, 64,
                                nullptr, nullptr, wc.hInstance, nullptr);
    if (!hwnd) {
        log("d3d11: dummy window failed %lu", GetLastError());
        return false;
    }

    DXGI_SWAP_CHAIN_DESC sd = {};
    sd.BufferCount = 2;
    sd.BufferDesc.Width = 64;
    sd.BufferDesc.Height = 64;
    sd.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.OutputWindow = hwnd;
    sd.SampleDesc.Count = 1;
    sd.Windowed = TRUE;
    sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

    D3D_FEATURE_LEVEL fl = D3D_FEATURE_LEVEL_11_0;
    IDXGISwapChain* sc = nullptr;
    ID3D11Device* dev = nullptr;
    ID3D11DeviceContext* ctx = nullptr;
    HRESULT hr = create(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, &fl, 1, D3D11_SDK_VERSION, &sd,
                        &sc, &dev, nullptr, &ctx);
    if (FAILED(hr) || !sc) {
        log("d3d11: dummy device/swapchain failed hr=0x%08lx", (unsigned long)hr);
        DestroyWindow(hwnd);
        return false;
    }

    void** vt = *(void***)sc;
    void* pPresent = vt[8];
    void* pResize = vt[13];
    void* pPresent1 = nullptr;
    IDXGISwapChain1* sc1 = nullptr;
    if (SUCCEEDED(sc->QueryInterface(kIID_IDXGISwapChain1, (void**)&sc1)) && sc1) {
        pPresent1 = (*(void***)sc1)[22];
        sc1->Release();
    }
    sc->Release();
    ctx->Release();
    dev->Release();
    DestroyWindow(hwnd);
    return install(pPresent, pPresent1, pResize);
}

}  // namespace mb
