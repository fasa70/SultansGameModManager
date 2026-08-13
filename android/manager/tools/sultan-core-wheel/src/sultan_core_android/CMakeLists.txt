cmake_minimum_required(VERSION 3.22)
project(sultan_core_android LANGUAGES C CXX)

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
