#!/usr/bin/env bash
# Complete device build: CPU + Qualcomm NPU paths (llama.cpp Hexagon, QNN runtime, sherpa QNN, qnn-probe).
# Not for redistribution: the Qualcomm runtime and Hexagon skel stay off the public APKs until the
# licences in docs/DECISIONS.md (ADR-048) are read.
#   tools/device/build_full_apk.sh [--no-native]     output: dist/murmur-full-<timestamp>[-release|debug].apk
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
export ANDROID_HOME
JOBS="${JOBS:-$(nproc)}"
NATIVE=1
[ "${1:-}" = "--no-native" ] && NATIVE=0

if [ "$NATIVE" = 1 ]; then
  JOBS="$JOBS" bash native/sherpa/build-android.sh all
  NO_HEAVY=1 JOBS="$JOBS" bash native/llama/build_android.sh npu cpu-x86_64
fi
bash tools/e2e/check_native_libs.sh

./gradlew assembleDebug assembleRelease -Pmurmur.qnn=true -Pmurmur.htp=true
bash tools/e2e/check_apk_engines.sh --npu app/build/outputs/apk/debug/app-debug.apk arm64-v8a x86_64
bash tools/e2e/check_apk_engines.sh --npu app/build/outputs/apk/release/app-release-unsigned.apk arm64-v8a

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p dist
cp app/build/outputs/apk/release/app-release-unsigned.apk "dist/murmur-full-$stamp-release-unsigned.apk"
cp app/build/outputs/apk/debug/app-debug.apk "dist/murmur-full-$stamp-debug.apk"
ls -la dist/murmur-full-"$stamp"*
