cmake_minimum_required(VERSION 3.10)

# Always build native code optimised: an unoptimised ggml is ~10-50x slower, even in debug APKs.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Get whisper.cpp version
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1} PARENT_SCOPE)
else()
    set(WHISPER_VERSION "unknown" PARENT_SCOPE)
endif()

message(STATUS " Whisper version: ${WHISPER_VERSION}")

# Path to external GGML, otherwise uses the copy in whisper.cpp.
option(GGML_HOME "whisper: Path to external GGML source" OFF)

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${WHISPER_LIB_DIR}/src/aneforge/whisper-aneforge.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
    )

find_library(LOG_LIB log)

include(FetchContent)

# Link ggml statically into libwhisper*.so instead of producing standalone libggml*.so files.
# The app also depends on the GenieX AAR, which bundles its own (differently built, non-ABI-
# compatible — confirmed by a ggml_abort() crash in WhisperEngine.load() when the two were mixed
# via packaging excludes) copies of libggml.so/libggml-base.so/libggml-cpu.so under the same
# names. Static linking means whisper.cpp carries a private copy with no shared filename, so the
# two coexist with zero risk of symbol/version cross-talk, and don't require an APK-packaging
# pick between them. Our own add_library(${target_name} SHARED ...) calls below are unaffected
# since they pass SHARED explicitly.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
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
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)


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

# ---- NPU daemon (Whisper-Small on the Hexagon NPU through the Qualcomm AI Runtime) ----
# Regular Android apps cannot reach the NPU on QIDK builds (libcdsprpc.so is not a public library), so
# the engine is built as an executable that runs outside the app sandbox and serves the app over a
# local socket. Built only on arm64 and only when the QAIRT headers are present; they are Qualcomm's
# and are not in this repository (copy include/QNN from the SDK into whisper/third_party/qairt/include/QNN).
set(QAIRT_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/qairt/include/QNN)
if (${ANDROID_ABI} STREQUAL "arm64-v8a" AND EXISTS ${QAIRT_INCLUDE}/QnnInterface.h)
    add_executable(
        whisper_npud
        npu/qnn_runtime.cpp
        npu/mel.cpp
        npu/whisper_npu.cpp
        npu/daemon_main.cpp
    )
    target_include_directories(whisper_npud PRIVATE ${QAIRT_INCLUDE} ${QAIRT_INCLUDE}/System npu)
    target_compile_options(whisper_npud PRIVATE -O3 -ffunction-sections -fdata-sections)
    target_link_options(whisper_npud PRIVATE -Wl,--gc-sections)
    target_link_libraries(whisper_npud ${LOG_LIB} dl)
else ()
    message(STATUS "QAIRT headers not found; skipping the NPU daemon (CPU transcription only)")
endif ()

