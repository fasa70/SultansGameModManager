cmake_minimum_required(VERSION 3.22)
project(sultans_merge_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(sultans_merge_native SHARED
    merge_native_jni.cpp
    upstream/json_cleaner.cpp
)

target_include_directories(sultans_merge_native PRIVATE
    upstream
)

target_compile_options(sultans_merge_native PRIVATE
    -Wall -Wextra -Wpedantic -Werror
    -fvisibility=hidden -fvisibility-inlines-hidden
)

target_link_libraries(sultans_merge_native PRIVATE log)

target_link_options(sultans_merge_native PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
