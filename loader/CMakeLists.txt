cmake_minimum_required(VERSION 3.20)
project(loader LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)

add_library(loader SHARED src/main.cpp src/jvm_bridge.cpp src/downloader.cpp)

target_link_libraries(loader PRIVATE winhttp)

# strip all symbols in release
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(loader PRIVATE -s)
endif()

# output name — change this to whatever you want the file to look like
set_target_properties(loader PROPERTIES
    OUTPUT_NAME "msvcp140_codec"
    PREFIX ""
    SUFFIX ".dll"
)
