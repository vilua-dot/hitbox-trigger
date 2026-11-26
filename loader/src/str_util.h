#pragma once

// inline string functions — no msvcrt dependency
// javaw doesn't load msvcrt.dll, only ucrtbase.dll

inline int s_len(const char* s) {
    int n = 0;
    while (s[n]) n++;
    return n;
}

inline int s_cmp(const char* a, const char* b) {
    while (*a && *a == *b) { a++; b++; }
    return (unsigned char)*a - (unsigned char)*b;
}

inline void s_ncpy(char* dst, const char* src, int n) {
    int i = 0;
    while (i < n && src[i]) { dst[i] = src[i]; i++; }
    while (i < n) { dst[i] = 0; i++; }
}

inline const char* s_str(const char* hay, const char* needle) {
    if (!*needle) return hay;
    for (; *hay; hay++) {
        const char* h = hay;
        const char* n = needle;
        while (*h && *n && *h == *n) { h++; n++; }
        if (!*n) return hay;
    }
    return nullptr;
}
