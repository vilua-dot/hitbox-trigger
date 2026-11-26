// language: C++20, target: Windows 11, MinGW-w64
// manual-mapped DLL — no CRT startup, no msvcrt, only KERNEL32

#include <windows.h>
#include "jvm_bridge.h"

static HMODULE g_self = nullptr;
static bool g_mapped = false;
static SIZE_T g_image_size = 0;

struct InjectionCtx {
    uint64_t marker;      // 0x4D4D
    uint64_t jar_addr;
    uint64_t jar_size;
    volatile uint64_t status;  // progress code written by DLL, read by injector
};

static InjectionCtx g_ctx{};
static volatile uint64_t* g_status = nullptr;

void report(uint64_t code) {
    if (g_status) *g_status = code;
}

static void wipe_mapped_image() {
    if (!g_mapped || !g_self) return;
    auto base = reinterpret_cast<uint8_t*>(g_self);
    auto dos = reinterpret_cast<IMAGE_DOS_HEADER*>(base);
    SIZE_T region_size = g_image_size;
    if (region_size == 0 && dos->e_magic == IMAGE_DOS_SIGNATURE) {
        auto nt = reinterpret_cast<IMAGE_NT_HEADERS64*>(base + dos->e_lfanew);
        region_size = nt->OptionalHeader.SizeOfImage;
    }
    DWORD old_prot;
    if (VirtualProtect(base, 4096, PAGE_READWRITE, &old_prot))
        SecureZeroMemory(base, 4096);
    if (region_size > 0)
        VirtualProtect(base, region_size, PAGE_READWRITE, &old_prot);
}

static DWORD WINAPI worker(LPVOID param) {
    report(0x10);  // worker started
    report(0x1A);  // about to call GetTickCount
    DWORD tick = GetTickCount();
    report(0x1B);  // GetTickCount ok
    Sleep(500 + (tick % 300));
    report(0x1C);  // Sleep ok

    report(0x11);  // reading jar bytes
    auto jar = reinterpret_cast<const uint8_t*>(g_ctx.jar_addr);
    auto jar_len = static_cast<int>(g_ctx.jar_size);

    if (!jar || jar_len <= 0) {
        report(0xE1);  // bad jar pointer or size
        wipe_mapped_image();
        if (!g_mapped) FreeLibraryAndExitThread(g_self, 0);
        return 0;
    }

    report(0x12);  // calling jvm_load_agent
    bool ok = jvm_load_agent(jar, jar_len);

    if (!ok) {
        report(0xE2);  // jvm_load_agent failed
        wipe_mapped_image();
        if (!g_mapped) FreeLibraryAndExitThread(g_self, 0);
        return 0;
    }

    report(0xFF);  // success
    wipe_mapped_image();

    Sleep(50);
    if (!g_mapped) FreeLibraryAndExitThread(g_self, 0);
    return 0;
}

extern "C" BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_self = hModule;

        auto rval = reinterpret_cast<uintptr_t>(reserved);
        g_mapped = (rval > 0xFFFF);

        if (g_mapped) {
            auto ctx = reinterpret_cast<InjectionCtx*>(reserved);
            g_status = &ctx->status;
            report(0x01);  // DllMain entered — first possible signal
            g_ctx = *ctx;

            auto base = reinterpret_cast<uint8_t*>(hModule);
            auto dos = reinterpret_cast<IMAGE_DOS_HEADER*>(base);
            if (dos->e_magic == IMAGE_DOS_SIGNATURE) {
                auto nt = reinterpret_cast<IMAGE_NT_HEADERS64*>(base + dos->e_lfanew);
                g_image_size = nt->OptionalHeader.SizeOfImage;
            }
        }

        report(0x02);  // about to create worker thread
        HANDLE t = CreateThread(nullptr, 0, worker, nullptr, 0, nullptr);
        if (t) CloseHandle(t);
        else report(0xE0);  // CreateThread failed
    }
    return TRUE;
}
