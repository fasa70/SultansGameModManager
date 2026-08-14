cmake_minimum_required(VERSION 3.22)
project(sultan_core_android LANGUAGES C CXX)

option(SULTAN_CHAQUOPY_ANDROID "Build against a Chaquopy Android Python target" OFF)
if(SULTAN_CHAQUOPY_ANDROID)
    set(Python_VERSION "3.11.14" CACHE STRING "Chaquopy Python version")
    set(Python_VERSION_MAJOR 3 CACHE STRING "" FORCE)
    set(Python_VERSION_MINOR 11 CACHE STRING "" FORCE)
    set(Python_INCLUDE_DIRS "${CHAQUOPY_PYTHON_INCLUDE_DIR}" CACHE PATH "" FORCE)
    set(Python_SOABI "cpython-311" CACHE STRING "" FORCE)
    add_library(Python::Module UNKNOWN IMPORTED)
    set_target_properties(Python::Module PROPERTIES
        IMPORTED_LOCATION "${CHAQUOPY_PYTHON_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${CHAQUOPY_PYTHON_INCLUDE_DIR}")
    add_executable(Python::Interpreter IMPORTED)
    set_target_properties(Python::Interpreter PROPERTIES
        IMPORTED_LOCATION "${Python_EXECUTABLE}")
else()
    find_package(Python COMPONENTS Interpreter Development.Module REQUIRED)
endif()
find_package(nanobind CONFIG REQUIRED)

add_library(yyjson STATIC yyjson.c)
target_include_directories(yyjson PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

add_library(sultan_core_json STATIC
    diag.cpp
    resource_loader.cpp
    json_cleaner.cpp
    json_doc.cpp
    json_val.cpp
    mut_doc.cpp
    mut_val.cpp
    json_ops.cpp
    overlay.cpp
    state/json_state.cpp
    state/state_formatter.cpp
    state/state_node.cpp
    delta/apply_delta.cpp
    delta/array_match.cpp
    delta/compute_delta.cpp
    delta/delta_node.cpp
    delta/delta_rules.cpp
    delta/similarity.cpp
)
target_include_directories(sultan_core_json PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/state
    ${CMAKE_CURRENT_SOURCE_DIR}/delta
    ${CMAKE_CURRENT_SOURCE_DIR}/rapidfuzz
)
target_link_libraries(sultan_core_json PUBLIC yyjson)
target_compile_features(sultan_core_json PUBLIC cxx_std_17)
set_target_properties(sultan_core_json PROPERTIES POSITION_INDEPENDENT_CODE ON)

nanobind_add_module(_native MODULE module.cpp)
target_link_libraries(_native PRIVATE sultan_core_json)
target_include_directories(_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
if(SULTAN_CHAQUOPY_ANDROID)
    get_filename_component(CHAQUOPY_PYTHON_LIBRARY_DIR
        "${CHAQUOPY_PYTHON_LIBRARY}" DIRECTORY)
    target_link_options(_native PRIVATE
        "-L${CHAQUOPY_PYTHON_LIBRARY_DIR}"
        "-lpython3.11")
    # Chaquopy's Android Python target exposes the CPython SOABI suffix used
    # by its bundled extension modules, such as _bz2.cpython-311.so.
    set_target_properties(_native PROPERTIES
        SUFFIX ".cpython-311.so")
endif()

install(TARGETS _native LIBRARY DESTINATION sultan_core)
install(FILES
    ${CMAKE_CURRENT_SOURCE_DIR}/python/sultan_core/__init__.py
    ${CMAKE_CURRENT_SOURCE_DIR}/python/sultan_core/json.py
    ${CMAKE_CURRENT_SOURCE_DIR}/python/sultan_core/json_ops.py
    DESTINATION sultan_core
)
