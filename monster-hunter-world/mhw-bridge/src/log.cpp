#include "common.h"
#include <stdio.h>
#include <stdarg.h>

namespace mb {

static CRITICAL_SECTION g_logLock;
static FILE* g_logFile = nullptr;
static bool g_logInit = false;

uint64_t now_ms() {
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
    return (t - 116444736000000000ULL) / 10000ULL;  // 100ns since 1601 -> ms since 1970
}

void log(const char* fmt, ...) {
    if (!g_logInit) {
        InitializeCriticalSection(&g_logLock);
        CreateDirectoryA("Z:\\tmp\\mhwmc", nullptr);
        g_logFile = fopen("Z:\\tmp\\mhwmc\\mhw-bridge.log", "a");
        g_logInit = true;
    }
    if (!g_logFile) return;
    EnterCriticalSection(&g_logLock);
    SYSTEMTIME st;
    GetLocalTime(&st);
    fprintf(g_logFile, "[%02d:%02d:%02d.%03d] ", st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(g_logFile, fmt, ap);
    va_end(ap);
    fputc('\n', g_logFile);
    fflush(g_logFile);
    LeaveCriticalSection(&g_logLock);
}

void log_close() {
    if (!g_logInit) return;
    EnterCriticalSection(&g_logLock);
    if (g_logFile) fclose(g_logFile);
    g_logFile = nullptr;
    LeaveCriticalSection(&g_logLock);
}

}  // namespace mb
