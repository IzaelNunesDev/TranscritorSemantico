cmake_minimum_required(VERSION 3.10)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../../third_party/whisper.cpp)
set(VULKAN_HEADERS_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/../../../../../third_party/Vulkan-Headers/include)

# Get whisper.cpp version
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()

message(STATUS " Whisper version: ${WHISPER_VERSION}")

# Path to external GGML, otherwise uses the copy in whisper.cpp.
option(GGML_HOME "whisper: Path to external GGML source" OFF)

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan support" FORCE)
    set(WHISPER_VULKAN ON CACHE BOOL "Enable Vulkan support" FORCE)
else()
    set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan support" FORCE)
    set(WHISPER_VULKAN OFF CACHE BOOL "Enable Vulkan support" FORCE)
endif()

if (CMAKE_HOST_WIN32)
    set(GGML_HOST_TAG windows-x86_64)
    file(GLOB GGML_MSVC_CL_CANDIDATES
        "C:/Program Files/Microsoft Visual Studio/*/*/VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe"
        "C:/Program Files (x86)/Microsoft Visual Studio/*/*/VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe"
    )
    if (GGML_MSVC_CL_CANDIDATES)
        list(SORT GGML_MSVC_CL_CANDIDATES)
        list(REVERSE GGML_MSVC_CL_CANDIDATES)
        list(GET GGML_MSVC_CL_CANDIDATES 0 GGML_MSVC_CL)
        get_filename_component(GGML_MSVC_BIN "${GGML_MSVC_CL}" DIRECTORY)
        file(GLOB GGML_WINDOWS_RC_CANDIDATES
            "C:/Program Files (x86)/Windows Kits/10/bin/*/x64/rc.exe"
        )
        if (GGML_WINDOWS_RC_CANDIDATES)
            list(SORT GGML_WINDOWS_RC_CANDIDATES)
            list(REVERSE GGML_WINDOWS_RC_CANDIDATES)
            list(GET GGML_WINDOWS_RC_CANDIDATES 0 GGML_WINDOWS_RC)
            set(HOST_RC_COMPILER "${GGML_WINDOWS_RC}" CACHE FILEPATH "Host resource compiler for Vulkan shader generation" FORCE)
        endif()
        set(HOST_C_COMPILER "${GGML_MSVC_CL}" CACHE FILEPATH "Host C compiler for Vulkan shader generation" FORCE)
        set(HOST_CXX_COMPILER "${GGML_MSVC_CL}" CACHE FILEPATH "Host C++ compiler for Vulkan shader generation" FORCE)
        set(HOST_LINKER "${GGML_MSVC_BIN}/link.exe" CACHE FILEPATH "Host linker for Vulkan shader generation" FORCE)
    endif()
elseif (CMAKE_HOST_APPLE)
    set(GGML_HOST_TAG darwin-x86_64)
else()
    set(GGML_HOST_TAG linux-x86_64)
endif()

find_program(
    Vulkan_GLSLC_EXECUTABLE
    NAMES glslc glslc.exe
    HINTS "${ANDROID_NDK}/shader-tools/${GGML_HOST_TAG}"
)
set(Vulkan_INCLUDE_DIR "${VULKAN_HEADERS_INCLUDE_DIR}" CACHE PATH "Vulkan headers include directory" FORCE)

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
    )

find_library(LOG_LIB log)
find_library(VULKAN_LIB vulkan)

include(FetchContent)

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS                      -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        set(GGML_COMPILE_OPTIONS                      -mfpu=neon-vfpv4)
    endif ()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)
    endif ()

    if (GGML_HOME)
        FetchContent_Declare(ggml SOURCE_DIR ${GGML_HOME})
    else()
        FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    endif()
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    if (GGML_VULKAN)
        target_link_libraries(${target_name} ${LOG_LIB} ${VULKAN_LIB} android ggml)
    else()
        target_link_libraries(${target_name} ${LOG_LIB} android ggml)
    endif()


endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

build_library("whisper") # Default target

include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)

