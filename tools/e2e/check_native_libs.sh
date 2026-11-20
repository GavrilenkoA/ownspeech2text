#!/usr/bin/env bash
# Checks the installed native libraries an APK needs for dictation (:asr and :llm engines).
#   tools/e2e/check_native_libs.sh [<jniLibs dir>]
# Exit: 0 all present; 1 something is missing.
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
libs="${1:-$root/.cache/native/jniLibs}"

arm64_llama=(libmurmur_llm_jni.so libmurmur_llm.so libllama.so libggml.so libggml-base.so libc++_shared.so)
arm64_llama_cpu=(libggml-cpu-android_armv8.0_1.so)
x86_64_llama=(libmurmur_llm_jni.so libmurmur_llm.so libllama.so libggml.so libggml-base.so libggml-cpu-x64.so libc++_shared.so)

missing=()
check() { [ -f "$1" ] || missing+=("${1#$libs/}"); }
for f in "${arm64_llama[@]}"; do check "$libs/llama/arm64-v8a/$f"; done
for f in "${arm64_llama_cpu[@]}"; do check "$libs/llama/arm64-v8a/$f"; done
for f in "${x86_64_llama[@]}"; do check "$libs/llama/x86_64/$f"; done
check "$libs/sherpa/arm64-v8a/libsherpa-onnx-jni.so"
check "$libs/sherpa/x86_64/libsherpa-onnx-jni.so"
check "$libs/qnn-probe/arm64-v8a/libmurmur-qnn-probe.so"

# A dictionary stub of a few bytes would satisfy the checks above but not the runtime.
for f in "$libs/llama/arm64-v8a/libmurmur_llm_jni.so" "$libs/sherpa/arm64-v8a/libsherpa-onnx-jni.so"; do
  [ -f "$f" ] || continue
  size=$(stat -c %s "$f")
  [ "$size" -gt 1000000 ] || missing+=("${f#$libs/} ($size bytes, too small)")
done

if [ ${#missing[@]} -gt 0 ]; then
  echo "missing native libraries in $libs:" >&2
  printf '  %s\n' "${missing[@]}" >&2
  echo "run native/sherpa/build-android.sh all and native/llama/build_android.sh cpu-arm64 cpu-x86_64" >&2
  exit 1
fi
echo "native libraries OK: $(find "$libs" -name '*.so' | wc -l) files in $libs"
