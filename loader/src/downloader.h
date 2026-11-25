#pragma once
#include <vector>
#include <cstdint>

bool download_payload(const wchar_t* host, const wchar_t* path,
                      int port, bool use_tls,
                      std::vector<uint8_t>& out);
