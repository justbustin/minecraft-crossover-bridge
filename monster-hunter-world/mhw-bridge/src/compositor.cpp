// Draws Minecraft's frames into MHW's own frame at Present, so both games share one image:
// same camera pose (no swimming between two windows) and Minecraft pixels hidden behind MHW
// geometry (depth test against MHW's scene depth buffer).
//
// DXMT notes: no SwapDeviceContextState, no UpdateSubresource
// into staging, no Map on DEFAULT resources. We only Map DYNAMIC textures with WRITE_DISCARD
// and save/restore the pipeline state by hand.
#include "common.h"
#include <d3d11.h>
#include <d3dcompiler.h>
#include <string.h>
#include <math.h>
#include "MinHook.h"

namespace mb {

// ---------------------------------------------------------------------------------------
// frames.shm

static uint8_t* g_frames = nullptr;
static uint64_t g_lastMapAttempt = 0;

static bool frames_open() {
    if (g_frames) return true;
    uint64_t now = now_ms();
    if (now - g_lastMapAttempt < 2000) return false;
    g_lastMapAttempt = now;
    HANDLE f = CreateFileA("Z:\\tmp\\mhwmc\\frames.shm", GENERIC_READ | GENERIC_WRITE,
                           FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL,
                           nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;  // Minecraft creates it
    LARGE_INTEGER size;
    if (!GetFileSizeEx(f, &size) || (uint64_t)size.QuadPart < MHMC_FRAMES_FILE_SIZE) {
        CloseHandle(f);
        return false;
    }
    HANDLE m = CreateFileMappingA(f, nullptr, PAGE_READWRITE, 0, MHMC_FRAMES_FILE_SIZE, nullptr);
    CloseHandle(f);
    if (!m) return false;
    g_frames = (uint8_t*)MapViewOfFile(m, FILE_MAP_ALL_ACCESS, 0, 0, MHMC_FRAMES_FILE_SIZE);
    CloseHandle(m);
    if (g_frames && ((MhmcFramesHeader*)g_frames)->magic != MHMC_FRAMES_MAGIC) {
        UnmapViewOfFile(g_frames);
        g_frames = nullptr;
        return false;
    }
    if (g_frames) log("compositor: mapped frames.shm");
    return g_frames != nullptr;
}

static MhmcFrameHeader* slot_header(uint32_t i) {
    return (MhmcFrameHeader*)(g_frames + 0x1000 + (size_t)i * MHMC_FRAME_SLOT_SIZE);
}

// ---------------------------------------------------------------------------------------
// Pose bookkeeping: which Minecraft pose MHW used for each game frame. The render thread
// draws one game frame behind the game thread, so the image
// handed to Present was made with the pose applied `g_poseLag` game frames ago.

static uint64_t g_poseHistory[8];
static uint32_t g_poseHistoryPos = 0;
static int g_poseLag = 1;

void compositor_note_applied_pose(uint64_t poseId) {
    g_poseHistory[g_poseHistoryPos++ & 7] = poseId;
}

static uint64_t pose_for_present() {
    if (g_poseHistoryPos == 0) return 0;
    uint32_t lag = (uint32_t)g_poseLag;
    if (lag >= g_poseHistoryPos) lag = g_poseHistoryPos - 1;
    return g_poseHistory[(g_poseHistoryPos - 1 - lag) & 7];
}

void compositor_set_pose_lag(int lag) {
    if (lag >= 0 && lag < 6) g_poseLag = lag;
}

// ---------------------------------------------------------------------------------------
// MHW scene depth: found by watching depth clears.

typedef void(STDMETHODCALLTYPE* ClearDSV_t)(ID3D11DeviceContext*, ID3D11DepthStencilView*, UINT, FLOAT, UINT8);
static ClearDSV_t oClearDSV = nullptr;
static void* g_clearDsvTarget = nullptr;

static CRITICAL_SECTION g_depthLock;
static bool g_depthLockInit = false;
// Full-size, sampleable depth textures seen being cleared (MHW clears two per frame),
// in first-seen order. Which one is the scene depth is selectable (MhmcControl.depthIndex).
static ID3D11Texture2D* g_depthCands[2] = {nullptr, nullptr};  // AddRef'd
static int g_depthIndex = 0;
static float g_depthClearValue = 1.0f;
static UINT g_bbW = 0, g_bbH = 0;
static int g_depthLogBudget = 40;

static void consider_depth(ID3D11DepthStencilView* dsv, float clearValue) {
    ID3D11Resource* res = nullptr;
    dsv->GetResource(&res);
    if (!res) return;
    ID3D11Texture2D* tex = nullptr;
    static const IID kIID_Tex2D = {0x6f15aaf2, 0xd208, 0x4e89, {0x9a, 0xb4, 0x48, 0x95, 0x35, 0xd3, 0x4f, 0x9c}};
    if (SUCCEEDED(res->QueryInterface(kIID_Tex2D, (void**)&tex)) && tex) {
        D3D11_TEXTURE2D_DESC d;
        tex->GetDesc(&d);
        if (g_depthLogBudget > 0) {
            g_depthLogBudget--;
            log("compositor: depth clear %ux%u fmt %u bind 0x%x samples %u clear %.2f (thread %lu)", d.Width, d.Height,
                d.Format, d.BindFlags, d.SampleDesc.Count, clearValue, GetCurrentThreadId());
        }
        // The scene depth: full back buffer size, single-sampled, readable by shaders.
        bool full = g_bbW && d.Width * 10 >= g_bbW * 9 && d.Height * 10 >= g_bbH * 9 && d.Width <= g_bbW * 2;
        if (full && d.SampleDesc.Count == 1 && (d.BindFlags & D3D11_BIND_SHADER_RESOURCE) && d.ArraySize == 1) {
            EnterCriticalSection(&g_depthLock);
            if (g_depthCands[0] != tex && g_depthCands[1] != tex) {
                int slot = !g_depthCands[0] ? 0 : (!g_depthCands[1] ? 1 : -1);
                if (slot < 0) {  // resolution change etc.: start over
                    g_depthCands[0]->Release();
                    g_depthCands[1]->Release();
                    g_depthCands[0] = g_depthCands[1] = nullptr;
                    slot = 0;
                }
                g_depthCands[slot] = tex;
                tex->AddRef();
                log("compositor: depth candidate %d = %p %ux%u fmt %u, cleared to %.1f (%s-Z)", slot, (void*)tex,
                    d.Width, d.Height, d.Format, clearValue, clearValue < 0.5f ? "reversed" : "standard");
            }
            g_depthClearValue = clearValue;
            LeaveCriticalSection(&g_depthLock);
        }
        tex->Release();
    }
    res->Release();
}

static void STDMETHODCALLTYPE hkClearDSV(ID3D11DeviceContext* ctx, ID3D11DepthStencilView* dsv, UINT flags,
                                         FLOAT depth, UINT8 stencil) {
    InflightGuard guard;
    if (dsv && (flags & D3D11_CLEAR_DEPTH)) consider_depth(dsv, depth);
    oClearDSV(ctx, dsv, flags, depth, stencil);
}

// ---------------------------------------------------------------------------------------
// D3D resources

static const char* kShader = R"(
Texture2D<float4> tWorld : register(t0);
Texture2D<float>  tMcDepth : register(t1);
Texture2D<float4> tGui : register(t2);
Texture2D<float>  tMhwDepth : register(t3);
Texture2D<float4> tScene : register(t4);     // MHW's finished frame (with mips)
SamplerState sPoint : register(s0);
SamplerState sLinear : register(s1);
cbuffer Params : register(b0) {
    float mcNear; float mcFar; float mhwNear; float mhwFar;     // metres
    float mhwReversed; float useDepth; float debugView; float bias;
    float relight; float lightGain; float lightMin; float fogStrength;
    float fogStart; float fogEnd; float sceneMip; float pad0;
};
struct VSOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
VSOut VS(uint id : SV_VertexID) {
    VSOut o;
    float2 uv = float2((id << 1) & 2, id & 2);
    o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
    o.uv = uv;
    return o;
}
float linMc(float d) {
    float z = d * 2 - 1;
    return 2 * mcNear * mcFar / (mcFar + mcNear - z * (mcFar - mcNear));
}
float linMhw(float d) {
    return mhwReversed > 0.5 ? mhwFar * mhwNear / (mhwNear + d * (mhwFar - mhwNear))
                             : mhwFar * mhwNear / (mhwFar - d * (mhwFar - mhwNear));
}
float4 PS(VSOut i) : SV_Target {
    float2 uvMc = float2(i.uv.x, 1 - i.uv.y);   // Minecraft rows are bottom-up
    float hd = tMhwDepth.SampleLevel(sPoint, i.uv, 0);
    if (debugView > 0.5) {
        float m = linMhw(hd);
        return float4(frac(m / 10).xxx, 1);    // 10 m depth bands
    }
    float4 world = tWorld.SampleLevel(sPoint, uvMc, 0);
    float md = tMcDepth.SampleLevel(sPoint, uvMc, 0);
    if (world.a > 0 && md < 1.0) {
        float mc = linMc(md);
        if (useDepth > 0.5 && mc > linMhw(hd) + bias + mc * 0.004) world = 0;
        // MHW lighting, estimated from MHW's own image: a heavily blurred copy of the frame
        // is the local ambient light around this pixel (night, caves, tree cover...).
        if (relight > 0.5) {
            float3 env = tScene.SampleLevel(sLinear, i.uv, sceneMip).rgb;
            float lum = dot(env, float3(0.299, 0.587, 0.114));
            world.rgb *= clamp(lightMin + lum * lightGain, 0.0, 1.15);
        }
        // Distance haze: fade toward the MHW scenery behind the block.
        float f = saturate((mc - fogStart) / max(fogEnd - fogStart, 1.0)) * fogStrength;
        if (f > 0) {
            float3 behind = tScene.SampleLevel(sLinear, i.uv, 2).rgb;
            world.rgb = lerp(world.rgb, behind * world.a, f);
        }
    }
    float4 gui = tGui.SampleLevel(sPoint, uvMc, 0);
    return gui + world * (1 - gui.a);
}
)";

struct Params {
    float mcNear, mcFar, mhwNear, mhwFar;
    float mhwReversed, useDepth, debugView, bias;
    float relight, lightGain, lightMin, fogStrength;
    float fogStart, fogEnd, sceneMip, pad0;
};

static ID3D11Device* g_dev = nullptr;
static ID3D11VertexShader* g_vs = nullptr;
static ID3D11PixelShader* g_ps = nullptr;
static ID3D11SamplerState* g_sampler = nullptr;
static ID3D11SamplerState* g_samplerLinear = nullptr;
static ID3D11Texture2D* g_sceneTex = nullptr;          // copy of MHW's frame, full mip chain
static ID3D11ShaderResourceView* g_srvScene = nullptr;
static UINT g_sceneW = 0, g_sceneH = 0, g_sceneMips = 1;
static DXGI_FORMAT g_sceneFmt = DXGI_FORMAT_UNKNOWN;
static ID3D11BlendState* g_blend = nullptr;
static ID3D11RasterizerState* g_raster = nullptr;
static ID3D11DepthStencilState* g_dss = nullptr;
static ID3D11Buffer* g_cb = nullptr;
static ID3D11Texture2D* g_texWorld = nullptr;
static ID3D11Texture2D* g_texMcDepth = nullptr;
static ID3D11Texture2D* g_texGui = nullptr;
static ID3D11ShaderResourceView* g_srvWorld = nullptr;
static ID3D11ShaderResourceView* g_srvMcDepth = nullptr;
static ID3D11ShaderResourceView* g_srvGui = nullptr;
static UINT g_mcW = 0, g_mcH = 0;
static ID3D11Texture2D* g_srvDepthTexKey = nullptr;
static ID3D11ShaderResourceView* g_srvMhwDepth = nullptr;
static ID3D11Texture2D* g_depthFallbackTex = nullptr;
static ID3D11ShaderResourceView* g_srvDepthFallback = nullptr;
static ID3D11RenderTargetView* g_rtv = nullptr;
static ID3D11Texture2D* g_rtvTex = nullptr;
static bool g_failed = false;
static volatile LONG g_compositing = 0;
static uint64_t g_lastCompositeMs = 0;

bool compositor_active() { return now_ms() - g_lastCompositeMs < 500; }

template <typename T>
static void safe_release(T*& p) {
    if (p) p->Release();
    p = nullptr;
}

static void release_frame_textures() {
    safe_release(g_srvWorld);
    safe_release(g_srvMcDepth);
    safe_release(g_srvGui);
    safe_release(g_texWorld);
    safe_release(g_texMcDepth);
    safe_release(g_texGui);
    g_mcW = g_mcH = 0;
}

void compositor_release_rtv() {
    safe_release(g_rtv);
    safe_release(g_rtvTex);
}

static bool make_dynamic(UINT w, UINT h, DXGI_FORMAT fmt, ID3D11Texture2D** tex, ID3D11ShaderResourceView** srv) {
    D3D11_TEXTURE2D_DESC d = {};
    d.Width = w;
    d.Height = h;
    d.MipLevels = 1;
    d.ArraySize = 1;
    d.Format = fmt;
    d.SampleDesc.Count = 1;
    d.Usage = D3D11_USAGE_DYNAMIC;
    d.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    d.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    if (FAILED(g_dev->CreateTexture2D(&d, nullptr, tex))) return false;
    return SUCCEEDED(g_dev->CreateShaderResourceView(*tex, nullptr, srv));
}

typedef HRESULT(WINAPI* D3DCompile_t)(LPCVOID, SIZE_T, LPCSTR, const D3D_SHADER_MACRO*, ID3DInclude*, LPCSTR, LPCSTR,
                                      UINT, UINT, ID3DBlob**, ID3DBlob**);

static bool init_device_objects(ID3D11Device* dev) {
    g_dev = dev;
    HMODULE dc = LoadLibraryA("d3dcompiler_47.dll");
    D3DCompile_t compile = dc ? (D3DCompile_t)GetProcAddress(dc, "D3DCompile") : nullptr;
    if (!compile) {
        log("compositor: D3DCompile not available");
        return false;
    }
    ID3DBlob *vsb = nullptr, *psb = nullptr, *err = nullptr;
    if (FAILED(compile(kShader, strlen(kShader), "mhwbridge", nullptr, nullptr, "VS", "vs_5_0", 0, 0, &vsb, &err)) ||
        FAILED(compile(kShader, strlen(kShader), "mhwbridge", nullptr, nullptr, "PS", "ps_5_0", 0, 0, &psb, &err))) {
        log("compositor: shader compile failed: %s", err ? (const char*)err->GetBufferPointer() : "?");
        safe_release(err);
        safe_release(vsb);
        return false;
    }
    bool ok = SUCCEEDED(dev->CreateVertexShader(vsb->GetBufferPointer(), vsb->GetBufferSize(), nullptr, &g_vs)) &&
              SUCCEEDED(dev->CreatePixelShader(psb->GetBufferPointer(), psb->GetBufferSize(), nullptr, &g_ps));
    vsb->Release();
    psb->Release();
    if (!ok) return false;

    D3D11_SAMPLER_DESC sd = {};
    sd.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
    sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    sd.MaxLOD = D3D11_FLOAT32_MAX;
    D3D11_BLEND_DESC bd = {};
    bd.RenderTarget[0].BlendEnable = TRUE;
    bd.RenderTarget[0].SrcBlend = D3D11_BLEND_ONE;  // premultiplied "over"
    bd.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
    bd.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    bd.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
    bd.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_INV_SRC_ALPHA;
    bd.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    bd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    D3D11_RASTERIZER_DESC rd = {};
    rd.FillMode = D3D11_FILL_SOLID;
    rd.CullMode = D3D11_CULL_NONE;
    rd.DepthClipEnable = TRUE;
    D3D11_DEPTH_STENCIL_DESC dd = {};
    dd.DepthEnable = FALSE;
    dd.StencilEnable = FALSE;
    D3D11_BUFFER_DESC cbd = {};
    cbd.ByteWidth = sizeof(Params);
    cbd.Usage = D3D11_USAGE_DYNAMIC;
    cbd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    cbd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    D3D11_SAMPLER_DESC sl = sd;
    sl.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
    ok = SUCCEEDED(dev->CreateSamplerState(&sd, &g_sampler)) && SUCCEEDED(dev->CreateSamplerState(&sl, &g_samplerLinear)) &&
         SUCCEEDED(dev->CreateBlendState(&bd, &g_blend)) &&
         SUCCEEDED(dev->CreateRasterizerState(&rd, &g_raster)) &&
         SUCCEEDED(dev->CreateDepthStencilState(&dd, &g_dss)) && SUCCEEDED(dev->CreateBuffer(&cbd, nullptr, &g_cb));
    // 1x1 "infinitely far" depth used until MHW's depth buffer is known.
    if (ok) {
        D3D11_TEXTURE2D_DESC d = {};
        d.Width = d.Height = 1;
        d.MipLevels = d.ArraySize = 1;
        d.Format = DXGI_FORMAT_R32_FLOAT;
        d.SampleDesc.Count = 1;
        d.Usage = D3D11_USAGE_IMMUTABLE;
        d.BindFlags = D3D11_BIND_SHADER_RESOURCE;
        float far1 = 1.0f;
        D3D11_SUBRESOURCE_DATA init = {&far1, 4, 4};
        ok = SUCCEEDED(dev->CreateTexture2D(&d, &init, &g_depthFallbackTex)) &&
             SUCCEEDED(dev->CreateShaderResourceView(g_depthFallbackTex, nullptr, &g_srvDepthFallback));
    }
    log("compositor: device objects %s", ok ? "ready" : "FAILED");
    return ok;
}

static void install_clear_hook(ID3D11DeviceContext* ctx) {
    if (oClearDSV) return;
    void* target = (*(void***)ctx)[53];  // ID3D11DeviceContext::ClearDepthStencilView
    if (MH_CreateHook(target, (void*)hkClearDSV, (void**)&oClearDSV) == MH_OK && MH_EnableHook(target) == MH_OK) {
        g_clearDsvTarget = target;
        log("compositor: ClearDepthStencilView hooked at %p", target);
    } else {
        oClearDSV = nullptr;
        log("compositor: could not hook ClearDepthStencilView");
    }
}

static ID3D11ShaderResourceView* mhw_depth_srv(float* clearValue) {
    EnterCriticalSection(&g_depthLock);
    ID3D11Texture2D* tex = g_depthCands[g_depthIndex & 1] ? g_depthCands[g_depthIndex & 1] : g_depthCands[0];
    if (tex) tex->AddRef();
    *clearValue = g_depthClearValue;
    LeaveCriticalSection(&g_depthLock);
    if (!tex) return nullptr;
    if (tex != g_srvDepthTexKey) {
        safe_release(g_srvMhwDepth);
        safe_release(g_srvDepthTexKey);
        D3D11_TEXTURE2D_DESC d;
        tex->GetDesc(&d);
        D3D11_SHADER_RESOURCE_VIEW_DESC sv = {};
        sv.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
        sv.Texture2D.MipLevels = 1;
        switch (d.Format) {
            case DXGI_FORMAT_R24G8_TYPELESS: sv.Format = DXGI_FORMAT_R24_UNORM_X8_TYPELESS; break;
            case DXGI_FORMAT_R32_TYPELESS: sv.Format = DXGI_FORMAT_R32_FLOAT; break;
            case DXGI_FORMAT_R32G8X24_TYPELESS: sv.Format = DXGI_FORMAT_R32_FLOAT_X8X24_TYPELESS; break;
            case DXGI_FORMAT_R16_TYPELESS: sv.Format = DXGI_FORMAT_R16_UNORM; break;
            default: sv.Format = DXGI_FORMAT_UNKNOWN;
        }
        if (sv.Format == DXGI_FORMAT_UNKNOWN || FAILED(g_dev->CreateShaderResourceView(tex, &sv, &g_srvMhwDepth))) {
            log("compositor: cannot sample MHW depth (format %u)", d.Format);
            g_srvMhwDepth = nullptr;
        }
        g_srvDepthTexKey = tex;
        g_srvDepthTexKey->AddRef();
    }
    tex->Release();
    return g_srvMhwDepth;
}

// Copies a slot's layers into the dynamic textures. Returns false if the slot was being
// rewritten while we copied (then this frame is skipped).
static bool upload_slot(ID3D11DeviceContext* ctx, MhmcFrameHeader* h) {
    uint32_t seq = h->seq;
    if (seq & 1) return false;
    __asm__ __volatile__("" ::: "memory");
    UINT w = h->width, ht = h->height;
    if (!w || !ht || w > MHMC_FRAME_MAX_W || ht > MHMC_FRAME_MAX_H) return false;
    if (w != g_mcW || ht != g_mcH) {
        release_frame_textures();
        if (!make_dynamic(w, ht, DXGI_FORMAT_B8G8R8A8_UNORM, &g_texWorld, &g_srvWorld) ||
            !make_dynamic(w, ht, DXGI_FORMAT_R32_FLOAT, &g_texMcDepth, &g_srvMcDepth) ||
            !make_dynamic(w, ht, DXGI_FORMAT_B8G8R8A8_UNORM, &g_texGui, &g_srvGui)) {
            log("compositor: cannot create %ux%u frame textures", w, ht);
            release_frame_textures();
            g_failed = true;
            return false;
        }
        g_mcW = w;
        g_mcH = ht;
        log("compositor: frame textures %ux%u", w, ht);
    }
    const uint8_t* base = (const uint8_t*)h + MHMC_FRAME_HDR;
    size_t layer = (size_t)w * ht * 4;
    ID3D11Texture2D* texs[3] = {g_texWorld, g_texMcDepth, g_texGui};
    for (int i = 0; i < 3; i++) {
        D3D11_MAPPED_SUBRESOURCE m;
        if (FAILED(ctx->Map(texs[i], 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) return false;
        const uint8_t* src = base + layer * i;
        if (m.RowPitch == w * 4) {
            memcpy(m.pData, src, layer);
        } else {
            for (UINT y = 0; y < ht; y++) memcpy((uint8_t*)m.pData + (size_t)y * m.RowPitch, src + (size_t)y * w * 4, w * 4);
        }
        ctx->Unmap(texs[i], 0);
    }
    __asm__ __volatile__("" ::: "memory");
    return h->seq == seq;
}

// Copies MHW's finished frame (before we draw on it) into a mipmapped texture: mip 0 is the
// scenery behind our pixels, the small mips give the local ambient light.
static bool capture_scene(ID3D11DeviceContext* ctx) {
    if (!g_rtvTex) return false;
    D3D11_TEXTURE2D_DESC bd;
    g_rtvTex->GetDesc(&bd);
    if (!g_sceneTex || bd.Width != g_sceneW || bd.Height != g_sceneH || bd.Format != g_sceneFmt) {
        safe_release(g_srvScene);
        safe_release(g_sceneTex);
        D3D11_TEXTURE2D_DESC d = {};
        d.Width = bd.Width;
        d.Height = bd.Height;
        d.MipLevels = 0;  // full chain
        d.ArraySize = 1;
        d.Format = bd.Format;
        d.SampleDesc.Count = 1;
        d.Usage = D3D11_USAGE_DEFAULT;
        d.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
        d.MiscFlags = D3D11_RESOURCE_MISC_GENERATE_MIPS;
        if (FAILED(g_dev->CreateTexture2D(&d, nullptr, &g_sceneTex)) ||
            FAILED(g_dev->CreateShaderResourceView(g_sceneTex, nullptr, &g_srvScene))) {
            log("compositor: cannot create scene copy (format %u)", bd.Format);
            safe_release(g_sceneTex);
            g_sceneW = 0;
            return false;
        }
        g_sceneTex->GetDesc(&d);
        g_sceneW = bd.Width;
        g_sceneH = bd.Height;
        g_sceneFmt = bd.Format;
        g_sceneMips = d.MipLevels;
        log("compositor: scene copy %ux%u, %u mips", bd.Width, bd.Height, d.MipLevels);
    }
    ctx->CopySubresourceRegion(g_sceneTex, 0, 0, 0, 0, g_rtvTex, 0, nullptr);
    ctx->GenerateMips(g_srvScene);
    return true;
}

// How often the frame for the pose MHW presented was still there (logged every 300 frames).
static uint32_t g_syncExact = 0, g_syncOlder = 0, g_syncMissing = 0;

static void note_sync(MhmcFrameHeader* slot, uint64_t poseId) {
    if (slot && slot->poseId == poseId) g_syncExact++;
    else if (slot) g_syncOlder++;
    else g_syncMissing++;
    if (g_syncExact + g_syncOlder + g_syncMissing >= 300) {
        log("sync: frames with the presented pose: exact %u, older %u, missing %u", g_syncExact, g_syncOlder,
            g_syncMissing);
        g_syncExact = g_syncOlder = g_syncMissing = 0;
    }
}

static MhmcFrameHeader* pick_slot(uint64_t poseId) {
    MhmcFrameHeader* best = nullptr;
    for (uint32_t i = 0; i < MHMC_FRAME_SLOTS; i++) {
        MhmcFrameHeader* h = slot_header(i);
        if (h->seq & 1) continue;
        if (h->poseId == poseId) return h;
        // Fall back to the newest frame that is not newer than the pose MHW rendered.
        if (h->poseId < poseId && (!best || h->poseId > best->poseId)) best = h;
    }
    return best;
}

struct SavedState {
    D3D11_PRIMITIVE_TOPOLOGY topo;
    ID3D11InputLayout* layout;
    ID3D11VertexShader* vs;
    ID3D11HullShader* hs;
    ID3D11DomainShader* ds;
    ID3D11GeometryShader* gs;
    ID3D11PixelShader* ps;
    ID3D11ShaderResourceView* srvs[5];
    ID3D11SamplerState* samplers[2];
    ID3D11Buffer* cb;
    ID3D11RasterizerState* rs;
    D3D11_VIEWPORT vps[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE];
    UINT numVps;
    D3D11_RECT scissors[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE];
    UINT numScissors;
    ID3D11RenderTargetView* rtvs[D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT];
    ID3D11DepthStencilView* dsv;
    ID3D11BlendState* blend;
    FLOAT blendFactor[4];
    UINT sampleMask;
    ID3D11DepthStencilState* dss;
    UINT stencilRef;
};

static void save_state(ID3D11DeviceContext* c, SavedState& s) {
    memset(&s, 0, sizeof(s));
    c->IAGetPrimitiveTopology(&s.topo);
    c->IAGetInputLayout(&s.layout);
    c->VSGetShader(&s.vs, nullptr, nullptr);
    c->HSGetShader(&s.hs, nullptr, nullptr);
    c->DSGetShader(&s.ds, nullptr, nullptr);
    c->GSGetShader(&s.gs, nullptr, nullptr);
    c->PSGetShader(&s.ps, nullptr, nullptr);
    c->PSGetShaderResources(0, 5, s.srvs);
    c->PSGetSamplers(0, 2, s.samplers);
    c->PSGetConstantBuffers(0, 1, &s.cb);
    c->RSGetState(&s.rs);
    s.numVps = D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE;
    c->RSGetViewports(&s.numVps, s.vps);
    s.numScissors = D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE;
    c->RSGetScissorRects(&s.numScissors, s.scissors);
    c->OMGetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, s.rtvs, &s.dsv);
    c->OMGetBlendState(&s.blend, s.blendFactor, &s.sampleMask);
    c->OMGetDepthStencilState(&s.dss, &s.stencilRef);
}

static void restore_state(ID3D11DeviceContext* c, SavedState& s) {
    c->IASetPrimitiveTopology(s.topo);
    c->IASetInputLayout(s.layout);
    c->VSSetShader(s.vs, nullptr, 0);
    c->HSSetShader(s.hs, nullptr, 0);
    c->DSSetShader(s.ds, nullptr, 0);
    c->GSSetShader(s.gs, nullptr, 0);
    c->PSSetShader(s.ps, nullptr, 0);
    c->PSSetShaderResources(0, 5, s.srvs);
    c->PSSetSamplers(0, 2, s.samplers);
    c->PSSetConstantBuffers(0, 1, &s.cb);
    c->RSSetState(s.rs);
    c->RSSetViewports(s.numVps, s.vps);
    c->RSSetScissorRects(s.numScissors, s.scissors);
    c->OMSetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, s.rtvs, s.dsv);
    c->OMSetBlendState(s.blend, s.blendFactor, s.sampleMask);
    c->OMSetDepthStencilState(s.dss, s.stencilRef);
    IUnknown* objs[] = {s.layout, s.vs, s.hs, s.ds, s.gs, s.ps, s.srvs[0], s.srvs[1], s.srvs[2], s.srvs[3], s.srvs[4],
                        s.samplers[0], s.samplers[1], s.cb, s.rs, s.dsv, s.blend, s.dss};
    for (IUnknown* o : objs)
        if (o) o->Release();
    for (ID3D11RenderTargetView* r : s.rtvs)
        if (r) r->Release();
}

void compositor_on_present(IDXGISwapChain* sc, UINT bbW, UINT bbH) {
    if (g_failed) return;
    if (!g_depthLockInit) {
        InitializeCriticalSection(&g_depthLock);
        g_depthLockInit = true;
    }
    g_bbW = bbW;
    g_bbH = bbH;

    static const IID kIID_Device = {0xdb6f6ddb, 0xac77, 0x4e88, {0x82, 0x53, 0x81, 0x9d, 0xf9, 0xbb, 0xf1, 0x40}};
    ID3D11Device* dev = nullptr;
    if (FAILED(sc->GetDevice(kIID_Device, (void**)&dev)) || !dev) return;
    ID3D11DeviceContext* ctx = nullptr;
    dev->GetImmediateContext(&ctx);
    if (!g_dev && !init_device_objects(dev)) g_failed = true;
    install_clear_hook(ctx);

    MhmcControl ctrl;
    bool mcDriving = control_snapshot(&ctrl) && (ctrl.flags & MHMC_CTRL_COMPOSITE);
    if (mcDriving) g_depthIndex = (int)ctrl.depthIndex;
    if (g_failed || !mcDriving || !frames_open()) {
        ctx->Release();
        dev->Release();
        return;
    }
    uint64_t poseId = pose_for_present();
    MhmcFrameHeader* slot = pick_slot(poseId);
    note_sync(slot, poseId);
    if (!slot || !upload_slot(ctx, slot)) {
        // Keep showing the last uploaded frame if this one isn't ready.
        if (!g_mcW) {
            ctx->Release();
            dev->Release();
            return;
        }
    }

    if (!g_rtv) {
        static const IID kIID_Tex2D = {0x6f15aaf2, 0xd208, 0x4e89, {0x9a, 0xb4, 0x48, 0x95, 0x35, 0xd3, 0x4f, 0x9c}};
        if (FAILED(sc->GetBuffer(0, kIID_Tex2D, (void**)&g_rtvTex)) ||
            FAILED(dev->CreateRenderTargetView(g_rtvTex, nullptr, &g_rtv))) {
            log("compositor: cannot create back buffer RTV");
            safe_release(g_rtvTex);
            ctx->Release();
            dev->Release();
            return;
        }
    }

    float clearValue = 1.0f;
    ID3D11ShaderResourceView* mhwDepth = mhw_depth_srv(&clearValue);
    MhmcGameState* st = shm_state();
    Params p;
    p.mcNear = slot ? slot->mcNear : 0.05f;
    p.mcFar = slot ? slot->mcFar : 1000.0f;
    p.mhwNear = (st->nearZ > 0 ? st->nearZ : 16.0f) / 100.0f;
    p.mhwFar = (st->farZ > 0 ? st->farZ : 1.5e6f) / 100.0f;
    p.mhwReversed = clearValue < 0.5f ? 1.0f : 0.0f;
    p.useDepth = (mhwDepth && !(ctrl.flags & MHMC_CTRL_NO_DEPTH_TEST)) ? 1.0f : 0.0f;
    p.debugView = (ctrl.flags & MHMC_CTRL_DEBUG_DEPTH) ? 1.0f : 0.0f;
    p.bias = 0.03f;
    bool haveScene = capture_scene(ctx);
    p.relight = (haveScene && !(ctrl.flags & MHMC_CTRL_NO_RELIGHT)) ? 1.0f : 0.0f;
    p.lightGain = ctrl.lightGain > 0 ? ctrl.lightGain : 2.6f;
    p.lightMin = ctrl.lightMin > 0 ? ctrl.lightMin : 0.18f;
    p.fogStrength = haveScene ? (ctrl.fogStrength > 0 ? ctrl.fogStrength : 0.55f) : 0.0f;
    p.fogStart = 40.0f;
    p.fogEnd = 400.0f;
    p.sceneMip = g_sceneMips > 6 ? (float)(g_sceneMips - 4) : (float)(g_sceneMips > 1 ? g_sceneMips - 1 : 0);
    p.pad0 = 0;

    D3D11_MAPPED_SUBRESOURCE m;
    if (SUCCEEDED(ctx->Map(g_cb, 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) {
        memcpy(m.pData, &p, sizeof(p));
        ctx->Unmap(g_cb, 0);
    }

    SavedState saved;
    save_state(ctx, saved);
    D3D11_VIEWPORT vp = {0, 0, (FLOAT)bbW, (FLOAT)bbH, 0, 1};
    ID3D11ShaderResourceView* srvs[5] = {g_srvWorld, g_srvMcDepth, g_srvGui, mhwDepth ? mhwDepth : g_srvDepthFallback,
                                         haveScene ? g_srvScene : g_srvWorld};
    ID3D11SamplerState* samplers[2] = {g_sampler, g_samplerLinear};
    ID3D11RenderTargetView* rtv = g_rtv;
    ctx->OMSetRenderTargets(1, &rtv, nullptr);
    ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ctx->IASetInputLayout(nullptr);
    ctx->VSSetShader(g_vs, nullptr, 0);
    ctx->HSSetShader(nullptr, nullptr, 0);
    ctx->DSSetShader(nullptr, nullptr, 0);
    ctx->GSSetShader(nullptr, nullptr, 0);
    ctx->PSSetShader(g_ps, nullptr, 0);
    ctx->PSSetShaderResources(0, 5, srvs);
    ctx->PSSetSamplers(0, 2, samplers);
    ctx->PSSetConstantBuffers(0, 1, &g_cb);
    ctx->RSSetState(g_raster);
    ctx->RSSetViewports(1, &vp);
    FLOAT bf[4] = {0, 0, 0, 0};
    ctx->OMSetBlendState(g_blend, bf, 0xFFFFFFFF);
    ctx->OMSetDepthStencilState(g_dss, 0);
    ctx->Draw(3, 0);
    ID3D11ShaderResourceView* nulls[5] = {};
    ctx->PSSetShaderResources(0, 5, nulls);
    restore_state(ctx, saved);

    g_lastCompositeMs = now_ms();
    ctx->Release();
    dev->Release();
}

void compositor_shutdown() {
    if (g_clearDsvTarget) {
        MH_DisableHook(g_clearDsvTarget);
        MH_RemoveHook(g_clearDsvTarget);
        g_clearDsvTarget = nullptr;
    }
    oClearDSV = nullptr;
    release_frame_textures();
    compositor_release_rtv();
    safe_release(g_srvMhwDepth);
    safe_release(g_srvDepthTexKey);
    safe_release(g_srvDepthFallback);
    safe_release(g_depthFallbackTex);
    safe_release(g_vs);
    safe_release(g_ps);
    safe_release(g_sampler);
    safe_release(g_samplerLinear);
    safe_release(g_srvScene);
    safe_release(g_sceneTex);
    g_sceneW = 0;
    safe_release(g_blend);
    safe_release(g_raster);
    safe_release(g_dss);
    safe_release(g_cb);
    if (g_depthLockInit) {
        EnterCriticalSection(&g_depthLock);
        safe_release(g_depthCands[0]);
        safe_release(g_depthCands[1]);
        LeaveCriticalSection(&g_depthLock);
    }
    g_dev = nullptr;
    if (g_frames) UnmapViewOfFile(g_frames);
    g_frames = nullptr;
}

}  // namespace mb
