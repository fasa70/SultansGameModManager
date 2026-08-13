cmake_minimum_required(VERSION 3.22)
project(sultan_core_android LANGUAGES C CXX)

find_package(Python COMPONENTS Interpreter Development.Module REQUIRED)
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
)
target_include_directories(sultan_core_json PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/rapidfuzz
)
target_link_libraries(sultan_core_json PUBLIC yyjson)
target_compile_features(sultan_core_json PUBLIC cxx_std_17)
set_target_properties(sultan_core_json PROPERTIES POSITION_INDEPENDENT_CODE ON)

nanobind_add_module(_native MODULE module.cpp)
target_link_libraries(_native PRIVATE sultan_core_json)
target_include_directories(_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

install(TARGETS _native LIBRARY DESTINATION sultan_core)
install(FILES
    ${CMAKE_CURRENT_SOURCE_DIR}/python/sultan_core/__init__.py
    ${CMAKE_CURRENT_SOURCE_DIR}/python/sultan_core/json.py
    ${CMAKE_CURRENT_SOURCE_DIR}/python/sultan_core/json_ops.py
    DESTINATION sultan_core
)
