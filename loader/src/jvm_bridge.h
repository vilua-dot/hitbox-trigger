#pragma once
#include <cstdint>

bool jvm_load_agent(const uint8_t* class_bytes, int length);

// status reporting — implemented in main.cpp
extern void report(uint64_t code);
