#include "common.h"
#include <string.h>
#include <stdlib.h>
#include <vector>

namespace mb {

static bool protect_readable(DWORD p) {
    if (p & (PAGE_GUARD | PAGE_NOACCESS)) return false;
    return (p & (PAGE_READONLY | PAGE_READWRITE | PAGE_WRITECOPY | PAGE_EXECUTE_READ |
                 PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY)) != 0;
}

bool mem_readable(const void* p, size_t len) {
    uintptr_t a = (uintptr_t)p, end = a + len;
    if (a < 0x10000 || end < a) return false;
    while (a < end) {
        MEMORY_BASIC_INFORMATION mbi;
        if (!VirtualQuery((void*)a, &mbi, sizeof(mbi))) return false;
        if (mbi.State != MEM_COMMIT || !protect_readable(mbi.Protect)) return false;
        a = (uintptr_t)mbi.BaseAddress + mbi.RegionSize;
    }
    return true;
}

bool mem_read(const void* p, void* out, size_t len) {
    if (!mem_readable(p, len)) return false;
    memcpy(out, p, len);
    return true;
}

bool mem_write(void* p, const void* src, size_t len) {
    MEMORY_BASIC_INFORMATION mbi;
    if (!VirtualQuery(p, &mbi, sizeof(mbi)) || mbi.State != MEM_COMMIT) return false;
    DWORD old;
    if (!VirtualProtect(p, len, PAGE_EXECUTE_READWRITE, &old)) return false;
    memcpy(p, src, len);
    VirtualProtect(p, len, old, &old);
    FlushInstructionCache(GetCurrentProcess(), p, len);
    return true;
}

static bool region_wanted(const MEMORY_BASIC_INFORMATION& mbi, uint32_t flags) {
    if (mbi.State != MEM_COMMIT || !protect_readable(mbi.Protect)) return false;
    if (flags & ERMC_SCAN_EXEC_ONLY) {
        if (!(mbi.Protect & (PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY)))
            return false;
    }
    if (mbi.Type == MEM_IMAGE) return (flags & ERMC_SCAN_IMAGE) != 0;
    if (mbi.Type == MEM_PRIVATE) return (flags & ERMC_SCAN_PRIVATE) != 0;
    if (mbi.Type == MEM_MAPPED) return (flags & ERMC_SCAN_MAPPED) != 0;
    return false;
}

size_t mem_scan(uintptr_t start, uintptr_t end, const uint8_t* pat, const uint8_t* mask,
                size_t len, uint32_t flags, uintptr_t* out, size_t maxOut) {
    if (len == 0) return 0;
    // Anchor on the first fully-specified byte to use memchr.
    size_t anchor = 0;
    while (anchor < len && mask[anchor] != 0xFF) anchor++;
    size_t found = 0;
    uintptr_t a = start < 0x10000 ? 0x10000 : start;
    while (a < end && found < maxOut) {
        MEMORY_BASIC_INFORMATION mbi;
        if (!VirtualQuery((void*)a, &mbi, sizeof(mbi))) break;
        uintptr_t rbase = (uintptr_t)mbi.BaseAddress, rend = rbase + mbi.RegionSize;
        if (rend <= a) break;
        if (region_wanted(mbi, flags)) {
            uintptr_t s = a > rbase ? a : rbase;
            uintptr_t e = rend < end ? rend : end;
            if (e - s >= len) {
                const uint8_t* p = (const uint8_t*)s;
                const uint8_t* last = (const uint8_t*)(e - len);
                while (p <= last && found < maxOut) {
                    if (anchor < len) {
                        const uint8_t* q = (const uint8_t*)memchr(p + anchor, pat[anchor],
                                                                (size_t)(last - p) + 1);
                        if (!q) break;
                        p = q - anchor;
                    }
                    size_t i = 0;
                    for (; i < len; i++)
                        if ((p[i] & mask[i]) != (pat[i] & mask[i])) break;
                    if (i == len) out[found++] = (uintptr_t)p;
                    p++;
                }
            }
        }
        a = rend;
    }
    return found;
}

uintptr_t main_module_base() { return (uintptr_t)GetModuleHandleA(nullptr); }

size_t main_module_size() {
    uint8_t* base = (uint8_t*)main_module_base();
    IMAGE_DOS_HEADER* dos = (IMAGE_DOS_HEADER*)base;
    IMAGE_NT_HEADERS64* nt = (IMAGE_NT_HEADERS64*)(base + dos->e_lfanew);
    return nt->OptionalHeader.SizeOfImage;
}

static bool parse_sig(const char* sig, std::vector<uint8_t>& pat, std::vector<uint8_t>& mask) {
    const char* s = sig;
    while (*s) {
        while (*s == ' ') s++;
        if (!*s) break;
        if (s[0] == '?') {
            pat.push_back(0);
            mask.push_back(0);
            s += (s[1] == '?') ? 2 : 1;
        } else {
            char hex[3] = {s[0], s[1], 0};
            char* endp;
            long v = strtol(hex, &endp, 16);
            if (endp != hex + 2) return false;
            pat.push_back((uint8_t)v);
            mask.push_back(0xFF);
            s += 2;
        }
    }
    return !pat.empty();
}

uintptr_t find_pattern(const char* sig) {
    std::vector<uint8_t> pat, mask;
    if (!parse_sig(sig, pat, mask)) return 0;
    uintptr_t base = main_module_base();
    uintptr_t hits[2];
    size_t n = mem_scan(base, base + main_module_size(), pat.data(), mask.data(), pat.size(),
                        ERMC_SCAN_IMAGE | ERMC_SCAN_EXEC_ONLY, hits, 2);
    if (n != 1) {
        log("find_pattern: %zu matches for '%s'", n, sig);
        return n ? hits[0] : 0;
    }
    return hits[0];
}

}  // namespace mb
