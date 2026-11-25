// language: C++20, target: Windows 11, MSVC or MinGW-w64

#include <windows.h>
#include "jvm_bridge.h"

static HMODULE g_self = nullptr;
static bool g_mapped = false;
static SIZE_T g_image_size = 0;

struct InjectionCtx {
    uint64_t marker;
    uint64_t jar_addr;
    uint64_t jar_size;
};

static InjectionCtx g_ctx{};

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
    Sleep(500 + (GetTickCount() % 300));

    auto jar = reinterpret_cast<const uint8_t*>(g_ctx.jar_addr);
    auto jar_len = static_cast<int>(g_ctx.jar_size);

    if (!jar || jar_len <= 0 || !jvm_load_agent(jar, jar_len)) {
        wipe_mapped_image();
        if (!g_mapped) FreeLibraryAndExitThread(g_self, 0);
        return 0;
    }

    // jar bytes are in a separate allocation — injector frees them later
    wipe_mapped_image();

    Sleep(50);
    if (!g_mapped) FreeLibraryAndExitThread(g_self, 0);
    return 0;
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hModule);
        g_self = hModule;

        auto rval = reinterpret_cast<uintptr_t>(reserved);
        g_mapped = (rval > 0xFFFF);

        if (g_mapped) {
            // read context struct passed by injector
            auto ctx = reinterpret_cast<InjectionCtx*>(reserved);
            g_ctx = *ctx;

            auto base = reinterpret_cast<uint8_t*>(hModule);
            auto dos = reinterpret_cast<IMAGE_DOS_HEADER*>(base);
            if (dos->e_magic == IMAGE_DOS_SIGNATURE) {
                auto nt = reinterpret_cast<IMAGE_NT_HEADERS64*>(base + dos->e_lfanew);
                g_image_size = nt->OptionalHeader.SizeOfImage;
            }
        }

        HANDLE t = CreateThread(nullptr, 0, worker, nullptr, 0, nullptr);
        if (t) CloseHandle(t);
    }
    return TRUE;
}
