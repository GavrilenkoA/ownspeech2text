# Murmur LLM native module: llama.cpp (pinned) + libmurmur_llm.so (C ABI in include/murmur_llm.h)
# + murmur-llm-bench (CLI used by tools/device/llm_npu_smoke.sh and host measurements).
#
# Used two ways:
#   1. native/llama/build_android.sh   -> prebuilt .so set per ABI (recommended for the app, see README.md)
#   2. Gradle externalNativeBuild      -> path = "../native/llama/CMakeLists.txt" (CPU/OpenCL only; the
#                                        Hexagon backend needs HEXAGON_SDK_ROOT, which Gradle machines lack)
cmake_minimum_required(VERSION 3.22)
project(murmur_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pinned commit lives in versions.env (single source of truth); parse it so FetchContent matches the script.
file(STRINGS "${CMAKE_CURRENT_LIST_DIR}/versions.env" _ver REGEX "^LLAMA_COMMIT=")
string(REGEX REPLACE "^LLAMA_COMMIT=\"?([0-9a-f]+)\"?.*" "\\1" MURMUR_LLAMA_COMMIT "${_ver}")
file(STRINGS "${CMAKE_CURRENT_LIST_DIR}/versions.env" _tag REGEX "^LLAMA_TAG=")
string(REGEX REPLACE "^LLAMA_TAG=\"?([A-Za-z0-9]+)\"?.*" "\\1" MURMUR_LLAMA_TAG "${_tag}")
file(STRINGS "${CMAKE_CURRENT_LIST_DIR}/versions.env" _sha REGEX "^LLAMA_ARCHIVE_SHA256=")
string(REGEX REPLACE "^LLAMA_ARCHIVE_SHA256=\"?([0-9a-f]+)\"?.*" "\\1" MURMUR_LLAMA_ARCHIVE_SHA256 "${_sha}")

set(MURMUR_LLAMA_SRC "" CACHE PATH "llama.cpp checkout at ${MURMUR_LLAMA_COMMIT} (empty = FetchContent)")
option(MURMUR_LLM_BENCH "build murmur-llm-bench" ON)
# Names the build in mllm_build_info() (ProcessProbe fact native.build, part of the prefix cache key, ADR-060).
set(MURMUR_BUILD_VARIANT "custom" CACHE STRING "build_android.sh variant (npu, cpu-arm64, cpu-x86_64, host)")

if (NOT MURMUR_LLAMA_SRC)
    # 39 MB source archive of the pinned commit, sha256-checked (a git clone of llama.cpp is far bigger).
    include(FetchContent)
    FetchContent_Declare(llama_cpp
        URL      https://github.com/ggml-org/llama.cpp/archive/${MURMUR_LLAMA_COMMIT}.tar.gz
        URL_HASH SHA256=${MURMUR_LLAMA_ARCHIVE_SHA256}
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    FetchContent_GetProperties(llama_cpp)
    if (NOT llama_cpp_POPULATED)
        FetchContent_Populate(llama_cpp)
    endif()
    set(MURMUR_LLAMA_SRC ${llama_cpp_SOURCE_DIR})
endif()

# Defaults suitable for an app build; the script overrides per variant.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "")
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "")
set(LLAMA_BUILD_APP      OFF CACHE BOOL "")
set(LLAMA_OPENSSL        OFF CACHE BOOL "")
# Everything (libllama, libggml*, backend modules, binaries) lands in one dir = the jniLibs/<abi> layout.
# Under Gradle, AGP passes CMAKE_LIBRARY_OUTPUT_DIRECTORY (the dir it packages from); keep it and route the
# ggml backend MODULEs (which use CMAKE_RUNTIME_OUTPUT_DIRECTORY) there too.
if (NOT CMAKE_LIBRARY_OUTPUT_DIRECTORY)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
endif()
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})
# NDK r30 clang 21.0.0 (r574158c) aborts ("malloc(): invalid size", AArch64 Assembly Printer, ggml_gemm_q8_0_4x8_q8_0)
# compiling ggml-cpu/arch/arm/repack.cpp for the armv9.0_1 variant (+sve2) with full -g, which AGP's default
# RelWithDebInfo passes. Reproduced 3/3 here; -gline-tables-only and -g0 compile fine. The last -g option wins in clang,
# so append line tables for every config that carries debug info (keeps symbolized native crash stacks).
if (ANDROID AND CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64" AND CMAKE_BUILD_TYPE MATCHES "Deb")
    add_compile_options($<$<COMPILE_LANGUAGE:C,CXX>:-gline-tables-only>)
endif()
add_subdirectory(${MURMUR_LLAMA_SRC} llama.cpp)

add_library(murmur_llm SHARED src/murmur_llm.cpp include/murmur_llm.h)
target_include_directories(murmur_llm PUBLIC include)
target_link_libraries(murmur_llm PRIVATE llama ggml)
target_compile_definitions(murmur_llm PRIVATE
    MURMUR_LLAMA_TAG="${MURMUR_LLAMA_TAG}" MURMUR_LLAMA_COMMIT="${MURMUR_LLAMA_COMMIT}"
    MURMUR_BUILD_VARIANT="${MURMUR_BUILD_VARIANT}")
if (ANDROID)
    target_link_libraries(murmur_llm PRIVATE log)
    # JNI shim of the :llm process (Kotlin: app.murmur.infer.llm.LlmNative). Thin layer over murmur_llm.h.
    add_library(murmur_llm_jni SHARED src/murmur_llm_jni.cpp include/murmur_llm_jni.h)
    target_link_libraries(murmur_llm_jni PRIVATE murmur_llm log)
endif()

if (MURMUR_LLM_BENCH)
    add_executable(murmur-llm-bench src/murmur_llm_bench.cpp)
    target_link_libraries(murmur-llm-bench PRIVATE murmur_llm)
    if (ANDROID)
        # binaries pushed to /data/local/tmp/<dir>/ find their libs next to themselves
        set_target_properties(murmur-llm-bench PROPERTIES BUILD_RPATH "\$ORIGIN" INSTALL_RPATH "\$ORIGIN")
    endif()
endif()
