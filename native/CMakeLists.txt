cmake_minimum_required(VERSION 3.25)

project(cyclesrenderer_native LANGUAGES CXX)

get_filename_component(CYCLESRENDERER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
set(CYCLES_SOURCE_DIR "${CYCLESRENDERER_ROOT}/.deps/cycles" CACHE PATH "Cycles source checkout")
set(CYCLES_BUILD_DIR "${CYCLESRENDERER_ROOT}/.deps/cycles-build" CACHE PATH "Cycles build tree")
set(CYCLES_INSTALL_DIR "${CYCLESRENDERER_ROOT}/.deps/cycles-install" CACHE PATH "Cycles install tree")
set(CYCLES_WINDOWS_LIB_DIR "${CYCLES_SOURCE_DIR}/lib/windows_x64" CACHE PATH "Blender Windows dependency tree")
set(OIDN_ROOT_DIR "${CYCLES_WINDOWS_LIB_DIR}/openimagedenoise")

set(CYCLES_RELEASE_LIB_DIR "${CYCLES_BUILD_DIR}/lib/Release")

function(require_file path description)
    if(NOT EXISTS "${path}")
        message(FATAL_ERROR "Missing ${description}: ${path}. Run scripts/setup-cycles.ps1 first.")
    endif()
endfunction()

require_file("${CYCLES_SOURCE_DIR}/src/session/session.h" "Cycles headers")
require_file("${CYCLES_RELEASE_LIB_DIR}/cycles_session.lib" "Cycles Release libraries")
require_file("${CYCLES_INSTALL_DIR}/lib/kernel_optix.ptx.zst" "Cycles OptiX kernel")
require_file("${OIDN_ROOT_DIR}/lib/OpenImageDenoise.lib" "OpenImageDenoise import library")
require_file("${CYCLES_INSTALL_DIR}/color/ocio/config.ocio" "Blender OpenColorIO configuration")

set(OIDN_RUNTIME_DLLS
    "${OIDN_ROOT_DIR}/bin/OpenImageDenoise.dll"
    "${OIDN_ROOT_DIR}/bin/OpenImageDenoise_core.dll"
    "${OIDN_ROOT_DIR}/bin/OpenImageDenoise_device_cpu.dll"
    "${OIDN_ROOT_DIR}/bin/OpenImageDenoise_device_cuda.dll"
)
foreach(runtime_dll IN LISTS OIDN_RUNTIME_DLLS)
    require_file("${runtime_dll}" "OpenImageDenoise runtime")
endforeach()

add_library(cyclesrenderer_native SHARED
    src/color_management.cpp
    src/cycles_bridge.cpp
    src/cycles_engine.cpp
    src/scene_update.cpp
)

target_compile_features(cyclesrenderer_native PRIVATE cxx_std_20)
target_compile_definitions(cyclesrenderer_native PRIVATE
    CYCLES_BRIDGE_BUILD
    _CRT_SECURE_NO_WARNINGS
    _USE_MATH_DEFINES
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    WITH_KERNEL_SSE42
    WITH_KERNEL_AVX2
    "CCL_NAMESPACE_BEGIN=namespace ccl {"
    "CCL_NAMESPACE_END=}"
    WITH_CUDA
    WITH_CUDA_DYNLOAD
    WITH_OPTIX
    WITH_OPENIMAGEDENOISE
    WITH_OCIO
    WITH_PUGIXML
    WITH_SYSTEM_PUGIXML
    WITH_TBB
    OPENEXR_DLL
    IMATH_DLL
)

target_include_directories(cyclesrenderer_native
    PUBLIC
        include
    PRIVATE
        "${CYCLES_SOURCE_DIR}/src"
        "${CYCLES_SOURCE_DIR}/third_party/atomic"
        "${CYCLES_SOURCE_DIR}/third_party/cuew/include"
        "${CYCLES_SOURCE_DIR}/third_party/sky/include"
        "${CYCLES_WINDOWS_LIB_DIR}/pthreads/include"
        "${CYCLES_WINDOWS_LIB_DIR}/OpenImageIO/include"
        "${CYCLES_WINDOWS_LIB_DIR}/tbb/include"
        "${CYCLES_WINDOWS_LIB_DIR}/pugixml/include"
        "${CYCLES_WINDOWS_LIB_DIR}/zlib/include"
        "${CYCLES_WINDOWS_LIB_DIR}/opencolorio/include"
        "${CYCLES_WINDOWS_LIB_DIR}/openexr/include"
        "${CYCLES_WINDOWS_LIB_DIR}/openexr/include/OpenEXR"
        "${CYCLES_WINDOWS_LIB_DIR}/imath/include"
        "${CYCLES_WINDOWS_LIB_DIR}/imath/include/Imath"
        "${OIDN_ROOT_DIR}/include"
)

target_link_libraries(cyclesrenderer_native PRIVATE
    "${CYCLES_RELEASE_LIB_DIR}/cycles_device.lib"
    "${CYCLES_RELEASE_LIB_DIR}/cycles_scene.lib"
    "${CYCLES_RELEASE_LIB_DIR}/cycles_session.lib"
    "${CYCLES_RELEASE_LIB_DIR}/cycles_bvh.lib"
    "${CYCLES_RELEASE_LIB_DIR}/cycles_subd.lib"
    "${CYCLES_RELEASE_LIB_DIR}/cycles_graph.lib"
    "${CYCLES_RELEASE_LIB_DIR}/cycles_integrator.lib"
    "${CYCLES_RELEASE_LIB_DIR}/cycles_kernel_cpu.lib"
    "${CYCLES_RELEASE_LIB_DIR}/cycles_util.lib"
    "${CYCLES_RELEASE_LIB_DIR}/extern_sky.lib"
    "${CYCLES_RELEASE_LIB_DIR}/extern_cuew.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/OpenImageIO/lib/OpenImageIO.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/OpenImageIO/lib/OpenImageIO_Util.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/opencolorio/lib/OpenColorIO.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/openexr/lib/OpenEXR.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/openexr/lib/IlmThread.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/openexr/lib/Iex.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/openexr/lib/OpenEXRCore.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/imath/lib/Imath.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/tbb/lib/tbb12.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/zstd/lib/zstd_static.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/pugixml/lib/pugixml.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/zlib/lib/libz_st.lib"
    "${CYCLES_WINDOWS_LIB_DIR}/pthreads/lib/pthreadVC3.lib"
    "${OIDN_ROOT_DIR}/lib/OpenImageDenoise.lib"
    psapi
    Version
    Dbghelp
    Shlwapi
)

if(MSVC)
    target_compile_options(cyclesrenderer_native PRIVATE
        /W4
        /permissive-
        /EHsc
        /utf-8
        /bigobj
        /J
        /Zc:__cplusplus
    )
endif()

set_target_properties(cyclesrenderer_native PROPERTIES
    CXX_EXTENSIONS OFF
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}/bin"
)

file(GLOB CYCLES_RUNTIME_DLLS CONFIGURE_DEPENDS "${CYCLES_INSTALL_DIR}/*.dll")
list(FILTER CYCLES_RUNTIME_DLLS EXCLUDE REGEX "[/\\\\]OpenImageDenoise[^/\\\\]*\\.dll$")
foreach(runtime_dll IN LISTS CYCLES_RUNTIME_DLLS)
    add_custom_command(TARGET cyclesrenderer_native POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different
            "${runtime_dll}"
            "$<TARGET_FILE_DIR:cyclesrenderer_native>"
        VERBATIM
    )
endforeach()
foreach(runtime_dll IN LISTS OIDN_RUNTIME_DLLS)
    add_custom_command(TARGET cyclesrenderer_native POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different
            "${runtime_dll}"
            "$<TARGET_FILE_DIR:cyclesrenderer_native>"
        VERBATIM
    )
endforeach()
add_custom_command(TARGET cyclesrenderer_native POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_directory
        "${CYCLES_INSTALL_DIR}/lib"
        "$<TARGET_FILE_DIR:cyclesrenderer_native>/lib"
    VERBATIM
)
add_custom_command(TARGET cyclesrenderer_native POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_directory
        "${CYCLES_INSTALL_DIR}/color"
        "$<TARGET_FILE_DIR:cyclesrenderer_native>/color"
    VERBATIM
)

add_executable(cyclesrenderer_smoke
    tests/cycles_bridge_smoke.cpp
)
target_compile_features(cyclesrenderer_smoke PRIVATE cxx_std_20)
target_link_libraries(cyclesrenderer_smoke PRIVATE cyclesrenderer_native)
set_target_properties(cyclesrenderer_smoke PROPERTIES
    CXX_EXTENSIONS OFF
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}/bin"
)

add_executable(cyclesrenderer_scene_update_test
    src/scene_update.cpp
    tests/scene_update_test.cpp
)
target_compile_features(cyclesrenderer_scene_update_test PRIVATE cxx_std_20)
target_include_directories(cyclesrenderer_scene_update_test PRIVATE include src)
target_link_libraries(cyclesrenderer_scene_update_test PRIVATE cyclesrenderer_native)
set_target_properties(cyclesrenderer_scene_update_test PROPERTIES
    CXX_EXTENSIONS OFF
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}/bin"
)
add_dependencies(cyclesrenderer_smoke cyclesrenderer_scene_update_test)

enable_testing()
add_test(NAME cyclesrenderer_native_smoke COMMAND cyclesrenderer_smoke)
add_test(
    NAME cyclesrenderer_scene_update
    COMMAND cyclesrenderer_scene_update_test
)
