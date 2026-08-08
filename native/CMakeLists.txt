cmake_minimum_required(VERSION 3.22)
project(sultans_modloader LANGUAGES CXX)

option(MODLOADER_BUILD_HOST_TESTS "Build host-side core tests" OFF)

set(MODLOADER_BACKEND_MODE "0" CACHE STRING
    "Backend mode: 0=staged native, 1=official canary")
set_property(CACHE MODLOADER_BACKEND_MODE PROPERTY STRINGS 0 1)
if(NOT MODLOADER_BACKEND_MODE MATCHES "^[01]$")
    message(FATAL_ERROR "MODLOADER_BACKEND_MODE must be 0 or 1")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(modloader_core STATIC
    src/backend_route.cpp
    src/canonical_dictionary.cpp
    src/config_catalog.cpp
    src/game_profile.cpp
    src/mod_file_index.cpp
    src/mod_lifecycle.cpp
    src/mod_path.cpp
    src/mod_root.cpp
    src/official_canary.cpp
    src/official_observer_validation.cpp
    src/resource_overrides.cpp
    src/runtime_state.cpp
)
target_include_directories(modloader_core PUBLIC include)
target_compile_definitions(modloader_core PUBLIC
    MODLOADER_BACKEND_MODE=${MODLOADER_BACKEND_MODE}
)
target_compile_options(modloader_core PRIVATE -Wall -Wextra -Wpedantic -Werror)
set_target_properties(modloader_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    if(NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        message(FATAL_ERROR "libmodloader currently supports only arm64-v8a")
    endif()
    if(DEFINED ANDROID_PLATFORM AND ANDROID_PLATFORM MATCHES "^android-([0-9]+)$")
        if(CMAKE_MATCH_1 LESS 21)
            message(FATAL_ERROR "libmodloader requires Android API 21 or newer")
        endif()
    endif()

    target_compile_options(modloader_core PRIVATE
        -fvisibility=hidden -fvisibility-inlines-hidden
    )

    set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
    set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
    set(DOBBY_BUILD_TEST OFF CACHE BOOL "" FORCE)
    set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)
    add_subdirectory(third_party/dobby EXCLUDE_FROM_ALL)

    # Dobby 0c29f64 has a circular include between platform.h and common.h.
    # Some Android sources include platform.h first, before OSMemory is declared.
    # Preloading common.h makes the cycle resolve in the supported order without
    # modifying the pinned third-party source.
    set(DOBBY_COMMON_HEADER
        "${CMAKE_CURRENT_SOURCE_DIR}/third_party/dobby/source/dobby/common.h")
    target_compile_options(dobby PRIVATE
        "$<$<COMPILE_LANGUAGE:CXX>:-include${DOBBY_COMMON_HEADER}>")
    target_compile_options(dobby_static PRIVATE
        "$<$<COMPILE_LANGUAGE:CXX>:-include${DOBBY_COMMON_HEADER}>")

    add_library(modloader SHARED
        src/android_log.cpp
        src/bootstrap_jni.cpp
        src/config_pipeline.cpp
        src/hook_engine.cpp
        src/il2cpp_resolver_android.cpp
        src/il2cpp_runtime.cpp
        src/json_bridge.cpp
        src/managed_dictionary.cpp
        src/mod_hooks.cpp
        src/native_mod_loader.cpp
        src/node_post_process.cpp
        src/resource_hooks.cpp
    )
    target_include_directories(modloader PRIVATE include)
    target_compile_definitions(modloader PRIVATE
        MODLOADER_BACKEND_MODE=${MODLOADER_BACKEND_MODE}
    )
    target_compile_options(modloader PRIVATE
        -Wall -Wextra -Wpedantic -Werror
        -fvisibility=hidden -fvisibility-inlines-hidden
    )
    target_link_libraries(modloader PRIVATE modloader_core dobby_static log dl)
    target_link_options(modloader PRIVATE
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/modloader.map"
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
    set_target_properties(modloader PROPERTIES OUTPUT_NAME modloader)
endif()

if(MODLOADER_BUILD_HOST_TESTS)
    if(ANDROID)
        message(FATAL_ERROR "Host tests cannot be built with the Android toolchain")
    endif()
    enable_testing()
    find_package(Threads REQUIRED)
    add_executable(modloader_core_tests tests/core_tests.cpp)
    target_link_libraries(modloader_core_tests PRIVATE modloader_core Threads::Threads)
    target_compile_options(modloader_core_tests PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME modloader_core_tests COMMAND modloader_core_tests)
endif()
