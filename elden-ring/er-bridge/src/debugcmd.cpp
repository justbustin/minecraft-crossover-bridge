// Debug/RE mailbox: lets scripts on the macOS side (elden-ring/scripts/erctl.py) read, write and
// scan the game's memory through the shared-memory file, without rebuilding the DLL.
#include "common.h"
#include <tlhelp32.h>
#include <string.h>
#include <stdio.h>
#include <math.h>

namespace mb {

bool game_debug_raycast(const float* start, const float* end, uint32_t flags, const uint32_t* filter, ErmcRayHit* out);
bool game_debug_fire_shell(uint32_t index, const float* origin, const float* target, uint64_t* result);

// ---------------------------------------------------------------------------------------

template <typename T>
static T arg(const uint8_t* a, uint32_t off) {
    T v;
    memcpy(&v, a + off, sizeof(T));
    return v;
}

void debugcmd_poll() {
    ErmcCmdBlock* c = shm_cmd();
    uint32_t req = c->reqSeq;
    if (req == c->respSeq) return;
    __asm__ __volatile__("" ::: "memory");

    const uint8_t* a = c->args;
    uint8_t* out = shm_cmd_resp();
    uint32_t outLen = 0;
    int status = 0;

    switch (c->cmd) {
        case ERMC_CMD_PING: {
            outLen = (uint32_t)snprintf((char*)out, 256, "er-bridge pid=%lu base=%p size=0x%zx",
                                        GetCurrentProcessId(), (void*)main_module_base(),
                                        main_module_size());
            break;
        }
        case ERMC_CMD_READ: {
            uintptr_t addr = arg<uint64_t>(a, 0);
            uint32_t len = arg<uint32_t>(a, 8);
            if (len > ERMC_CMD_RESP_MAX) len = ERMC_CMD_RESP_MAX;
            if (mem_read((void*)addr, out, len)) outLen = len;
            else status = -1;
            break;
        }
        case ERMC_CMD_WRITE: {
            uintptr_t addr = arg<uint64_t>(a, 0);
            uint32_t len = arg<uint32_t>(a, 8);
            if (len > sizeof(c->args) - 12 || !mem_write((void*)addr, a + 12, len)) status = -1;
            break;
        }
        case ERMC_CMD_READ_MANY: {
            uint32_t count = arg<uint32_t>(a, 0), len = arg<uint32_t>(a, 4);
            if (count > (sizeof(c->args) - 8) / 8 || (uint64_t)count * (len + 1) > ERMC_CMD_RESP_MAX) {
                status = -2;
                break;
            }
            uint8_t* flags = out;
            uint8_t* data = out + count;
            for (uint32_t i = 0; i < count; i++) {
                uintptr_t addr = arg<uint64_t>(a, 8 + i * 8);
                flags[i] = mem_read((void*)addr, data + (size_t)i * len, len) ? 1 : 0;
                if (!flags[i]) memset(data + (size_t)i * len, 0, len);
            }
            outLen = count + count * len;
            break;
        }
        case ERMC_CMD_SCAN: {
            uintptr_t start = arg<uint64_t>(a, 0), end = arg<uint64_t>(a, 8);
            uint32_t patLen = arg<uint32_t>(a, 16), maxResults = arg<uint32_t>(a, 20);
            uint32_t flags = arg<uint32_t>(a, 24);
            if (patLen == 0 || 28 + 2 * patLen > sizeof(c->args)) {
                status = -2;
                break;
            }
            if (maxResults > ERMC_CMD_RESP_MAX / 8) maxResults = ERMC_CMD_RESP_MAX / 8;
            size_t n = mem_scan(start, end, a + 28, a + 28 + patLen, patLen, flags,
                                (uintptr_t*)out, maxResults);
            outLen = (uint32_t)(n * 8);
            break;
        }
        case ERMC_CMD_SCAN_FLOAT: {
            uintptr_t start = arg<uint64_t>(a, 0), end = arg<uint64_t>(a, 8);
            float lo = arg<float>(a, 16), hi = arg<float>(a, 20);
            uint32_t align = arg<uint32_t>(a, 24), maxResults = arg<uint32_t>(a, 28);
            uint32_t flags = arg<uint32_t>(a, 32);
            if (align == 0) align = 4;
            if (maxResults > ERMC_CMD_RESP_MAX / 8) maxResults = ERMC_CMD_RESP_MAX / 8;
            uintptr_t* res = (uintptr_t*)out;
            uint32_t n = 0;
            uintptr_t p = start < 0x10000 ? 0x10000 : start;
            while (p < end && n < maxResults) {
                MEMORY_BASIC_INFORMATION mbi;
                if (!VirtualQuery((void*)p, &mbi, sizeof(mbi))) break;
                uintptr_t rb = (uintptr_t)mbi.BaseAddress, re = rb + mbi.RegionSize;
                if (re <= p) break;
                bool want = mbi.State == MEM_COMMIT && !(mbi.Protect & (PAGE_GUARD | PAGE_NOACCESS)) &&
                            (mbi.Protect & (PAGE_READWRITE | PAGE_EXECUTE_READWRITE | PAGE_READONLY |
                                            PAGE_WRITECOPY)) &&
                            ((mbi.Type == MEM_IMAGE && (flags & ERMC_SCAN_IMAGE)) ||
                             (mbi.Type == MEM_PRIVATE && (flags & ERMC_SCAN_PRIVATE)) ||
                             (mbi.Type == MEM_MAPPED && (flags & ERMC_SCAN_MAPPED)));
                if (want) {
                    uintptr_t s = (p > rb ? p : rb), e = (re < end ? re : end);
                    s = (s + align - 1) & ~(uintptr_t)(align - 1);
                    for (uintptr_t q = s; q + 4 <= e && n < maxResults; q += align) {
                        float v = *(const float*)q;
                        if (v >= lo && v <= hi) res[n++] = q;
                    }
                }
                p = re;
            }
            outLen = n * 8;
            break;
        }
        case ERMC_CMD_QUERY: {
            uintptr_t addr = arg<uint64_t>(a, 0);
            MEMORY_BASIC_INFORMATION mbi;
            if (!VirtualQuery((void*)addr, &mbi, sizeof(mbi))) {
                status = -1;
                break;
            }
            uint64_t v[3] = {(uint64_t)mbi.BaseAddress, (uint64_t)mbi.AllocationBase,
                             (uint64_t)mbi.RegionSize};
            uint32_t w[3] = {mbi.State, mbi.Protect, mbi.Type};
            memcpy(out, v, 24);
            memcpy(out + 24, w, 12);
            outLen = 36;
            break;
        }
        case ERMC_CMD_MODULES: {
            HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId());
            if (snap == INVALID_HANDLE_VALUE) {
                status = -1;
                break;
            }
            MODULEENTRY32 me;
            me.dwSize = sizeof(me);
            if (Module32First(snap, &me)) {
                do {
                    uint16_t nl = (uint16_t)strlen(me.szModule);
                    if (outLen + 14 + nl > ERMC_CMD_RESP_MAX) break;
                    uint64_t base = (uint64_t)me.modBaseAddr;
                    uint32_t size = me.modBaseSize;
                    memcpy(out + outLen, &base, 8);
                    memcpy(out + outLen + 8, &size, 4);
                    memcpy(out + outLen + 12, &nl, 2);
                    memcpy(out + outLen + 14, me.szModule, nl);
                    outLen += 14 + nl;
                } while (Module32Next(snap, &me));
            }
            CloseHandle(snap);
            break;
        }
        case ERMC_CMD_RAYCAST: {
            float start[3], end[3];
            memcpy(start, a, 12);
            memcpy(end, a + 12, 12);
            ErmcRayHit hit;
            uint32_t filter[3] = {arg<uint32_t>(a, 28), arg<uint32_t>(a, 32), arg<uint32_t>(a, 36)};
            if (game_debug_raycast(start, end, arg<uint32_t>(a, 24), filter, &hit)) {
                memcpy(out, &hit, sizeof(hit));
                outLen = sizeof(hit);
            } else {
                status = -1;  // game thread not ticking (loading screen?) or hooks missing
            }
            break;
        }
        case ERMC_CMD_FIRE_SHELL: {
            float origin[3], target[3];
            memcpy(origin, a + 4, 12);
            memcpy(target, a + 16, 12);
            uint64_t shell = 0;
            if (game_debug_fire_shell(arg<uint32_t>(a, 0), origin, target, &shell)) {
                memcpy(out, &shell, 8);
                outLen = 8;
            } else {
                status = -1;
            }
            break;
        }
        default:
            status = -100;
    }

    c->status = status;
    c->respLen = outLen;
    __asm__ __volatile__("" ::: "memory");
    c->respSeq = req;
}

}  // namespace mb
