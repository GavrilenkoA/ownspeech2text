#!/usr/bin/env bash
# Build sherpa-onnx native libraries for Murmur.
#   arm64-v8a : CPU + QNN (HTP) enabled  -> JNI .so + CLI binaries for tools/device/asr_npu_smoke.sh
#   x86_64    : CPU only (emulator)      -> JNI .so
#
# Usage (from the repo root, ~5-15 min per ABI on 6 threads):
#   tools/heavy native/sherpa/build-android.sh [arm64|x86_64|all] [workdir]
# App configuration (one libonnxruntime.so per APK, shared by sherpa-onnx and the OCR code):
#   ORT_AAR=<path>/onnxruntime-android-1.32.0.aar tools/heavy native/sherpa/build-android.sh all .cache/spikes/asr-ocr/build-ort132
#   (Maven: https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.32.0/onnxruntime-android-1.32.0.aar)
# Verified 2026-11-18 (docs/research/verify/asr-ocr.md): arm64-v8a with QNN and x86_64 CPU both build against the 1.32.0 AAR;
#   out/<abi>/lib/libonnxruntime.so is byte-identical to the AAR's (cmp); libsherpa-onnx-jni.so NEEDED = libonnxruntime.so;
#   the x86_64 CLI decodes GigaAM v3 and Parakeet v3 correctly on the API-36 emulator. The official sherpa-onnx-2.0.1.aar
#   has no QNN code ("Please rebuild sherpa-onnx with -DSHERPA_ONNX_ENABLE_QNN=ON"), hence this build.
# Output: <workdir>/out/<abi>/{lib,bin}
#
# Inputs (all public, no account):
#   - sherpa-onnx source, tag $SHERPA_TAG (GitHub)
#   - QNN headers only: qnn-include-<ver>.tar.bz2 from the sherpa-onnx "asr-models-qnn" GitHub release
#     (QNN libraries are dlopen()ed at run time; they are NOT linked, so the app may ship any
#      libQnnHtp.so >= the version the context binaries were generated with)
#   - onnxruntime-android-<ORT_VER>.zip from github.com/csukuangfj/onnxruntime-libs (fetched by sherpa's script)
#   - Android NDK + CMake from the SDK (see docs/dev/TOOLCHAIN.md)
set -euo pipefail

SHERPA_TAG=${SHERPA_TAG:-v2.0.1}
QNN_HDR_VER=${QNN_HDR_VER:-2.40.0.251030}
ORT_VER=${ORT_VER:-1.28.2}          # sherpa-onnx v2.0.1 default; must equal the ORT inside the APK
JOBS=${JOBS:-6}
what=${1:-all}
root=$(cd "$(dirname "$0")/../.." && pwd)
work=${2:-$root/.cache/spikes/asr-ocr/build}

SDK=${ANDROID_HOME:-/home/private/.local/share/android-sdk}
export ANDROID_NDK=${ANDROID_NDK:-$SDK/ndk/30.0.16248370}
export PATH="$SDK/cmake/4.1.2/bin:$PATH"
# CMake 4 refuses cmake_minimum_required(<3.5) in some FetchContent deps
export CMAKE_POLICY_VERSION_MINIMUM=3.5

mkdir -p "$work" && cd "$work"
if [ ! -d sherpa-onnx ]; then
  git clone -q --depth 1 --branch "$SHERPA_TAG" https://github.com/k2-fsa/sherpa-onnx sherpa-onnx
fi
cd sherpa-onnx
# make -jN instead of the hard-coded -j4; pass the CMake 4 policy floor through
sed -i "s/^make -j4$/make -j${JOBS}/" build-android-arm64-v8a.sh build-android-x86-64.sh
grep -q CMAKE_POLICY_VERSION_MINIMUM build-android-arm64-v8a.sh || \
  sed -i 's|^cmake -DCMAKE_TOOLCHAIN_FILE|cmake -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_TOOLCHAIN_FILE|' \
    build-android-arm64-v8a.sh build-android-x86-64.sh
# x86-64 script hard-codes android-21; ORT 1.28 libonnxruntime.so needs API>=24 symbols (__write_chk@LIBC_N)
sed -i 's/-DANDROID_PLATFORM=android-21 /-DANDROID_PLATFORM=android-26 /' build-android-x86-64.sh

export SHERPA_ONNX_ONNXRUNTIME_VERSION=$ORT_VER
# Optional: link against the app's own ONNX Runtime (one libonnxruntime.so per APK), e.g.
#   ORT_AAR=~/.m2/.../onnxruntime-android-1.32.0.aar  (Maven: com.microsoft.onnxruntime:onnxruntime-android)
if [ -n "${ORT_AAR:-}" ]; then
  rm -rf "$work/ort-aar" && mkdir -p "$work/ort-aar" && unzip -q "$ORT_AAR" 'headers/*' 'jni/*' -d "$work/ort-aar"
  export SHERPA_ONNX_ONNXRUNTIME_ROOT="$work/ort-aar"
fi
export SHERPA_ONNX_ENABLE_TTS=OFF
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_C_API=ON
export SHERPA_ONNX_ANDROID_PLATFORM=android-26

if [[ $what == arm64 || $what == all ]]; then
  if [ ! -d qnn-include-$QNN_HDR_VER ]; then
    curl -fsSL -O "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-include-$QNN_HDR_VER.tar.bz2"
    tar xf qnn-include-$QNN_HDR_VER.tar.bz2 && rm qnn-include-$QNN_HDR_VER.tar.bz2
  fi
  export QNN_SDK_ROOT=$PWD/qnn-include-$QNN_HDR_VER
  SHERPA_ONNX_ENABLE_QNN=ON SHERPA_ONNX_ENABLE_BINARY=ON ./build-android-arm64-v8a.sh
  mkdir -p "$work/out/arm64-v8a"
  cp -a build-android-arm64-v8a/install/lib build-android-arm64-v8a/install/bin "$work/out/arm64-v8a/"
fi

if [[ $what == x86_64 || $what == all ]]; then
  SHERPA_ONNX_ENABLE_QNN=OFF SHERPA_ONNX_ENABLE_BINARY=ON ./build-android-x86-64.sh
  mkdir -p "$work/out/x86_64"
  cp -a build-android-x86-64/install/lib build-android-x86-64/install/bin "$work/out/x86_64/"
fi
ls -la "$work"/out/*/lib
