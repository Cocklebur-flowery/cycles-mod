cmake_minimum_required(VERSION 3.25)

project(cyclesrenderer_native LANGUAGES CXX)

add_library(cyclesrenderer_native SHARED
    src/cycles_bridge.cpp
)

target_compile_features(cyclesrenderer_native PRIVATE cxx_std_20)
target_compile_definitions(cyclesrenderer_native PRIVATE CYCLES_BRIDGE_BUILD)
target_include_directories(cyclesrenderer_native PUBLIC include)

if(MSVC)
    target_compile_options(cyclesrenderer_native PRIVATE /W4 /permissive- /EHsc /utf-8)
endif()

set_target_properties(cyclesrenderer_native PROPERTIES
    CXX_EXTENSIONS OFF
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}/bin"
)
