#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

// compile-time XOR string encryption — strings exist only as encrypted
// bytes in the binary. decrypted on the stack at runtime, wiped after use.

namespace cx {

constexpr uint8_t seed(const char* file, int line) {
    uint8_t h = static_cast<uint8_t>(line);
    for (int i = 0; file[i]; i++)
        h ^= static_cast<uint8_t>(file[i]) + static_cast<uint8_t>(i * 37);
    return h ? h : 0x55;
}

template<size_t N, uint8_t Key>
struct encrypted_string {
    std::array<char, N> data{};

    consteval encrypted_string(const char (&str)[N]) {
        for (size_t i = 0; i < N; i++)
            data[i] = static_cast<char>(str[i] ^ (Key + static_cast<uint8_t>(i * 13)));
    }

    // decrypt into a stack buffer — caller must wipe after use
    void decrypt(char* out) const {
        for (size_t i = 0; i < N; i++)
            out[i] = static_cast<char>(data[i] ^ (Key + static_cast<uint8_t>(i * 13)));
    }

    static constexpr size_t size() { return N; }
};

// RAII wrapper that decrypts on construction, wipes on destruction
template<size_t N, uint8_t Key>
struct decrypted {
    char buf[N]{};
    explicit decrypted(const encrypted_string<N, Key>& enc) { enc.decrypt(buf); }
    ~decrypted() { volatile char* p = buf; for (size_t i = 0; i < N; i++) p[i] = 0; }
    operator const char*() const { return buf; }
    const char* c_str() const { return buf; }
};

template<size_t N, uint8_t Key>
struct encrypted_wstring {
    std::array<wchar_t, N> data{};

    consteval encrypted_wstring(const wchar_t (&str)[N]) {
        for (size_t i = 0; i < N; i++)
            data[i] = static_cast<wchar_t>(str[i] ^ (Key + static_cast<uint8_t>(i * 13)));
    }

    void decrypt(wchar_t* out) const {
        for (size_t i = 0; i < N; i++)
            out[i] = static_cast<wchar_t>(data[i] ^ (Key + static_cast<uint8_t>(i * 13)));
    }

    static constexpr size_t size() { return N; }
};

template<size_t N, uint8_t Key>
struct wdecrypted {
    wchar_t buf[N]{};
    explicit wdecrypted(const encrypted_wstring<N, Key>& enc) { enc.decrypt(buf); }
    ~wdecrypted() { volatile wchar_t* p = buf; for (size_t i = 0; i < N; i++) p[i] = 0; }
    operator const wchar_t*() const { return buf; }
};

} // namespace cx

// usage: ENC("string") produces an encrypted_string, DEC() decrypts it into RAII scope
#define ENC(s)  (::cx::encrypted_string<sizeof(s), ::cx::seed(__FILE__, __LINE__)>{s})
#define DEC(e)  (::cx::decrypted{e})
#define WENC(s) (::cx::encrypted_wstring<sizeof(s)/sizeof(wchar_t), ::cx::seed(__FILE__, __LINE__)>{s})
#define WDEC(e) (::cx::wdecrypted{e})
