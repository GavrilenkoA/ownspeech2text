#!/usr/bin/env bash
# Checks that an APK ships the native engines dictation needs, per ABI it claims.
#   tools/e2e/check_apk_engines.sh <apk> [abi ...]     (default: every ABI found in the APK)
# Exit: 0 present; 1 missing.
set -uo pipefail
apk="${1:-}"
shift || true
[ -f "$apk" ] || { echo "usage: $0 <apk> [abi ...]" >&2; exit 2; }

if [ $# -gt 0 ]; then
  abis=("$@")
else
  abis=()
  while IFS= read -r abi; do abis+=("$abi"); done < <(
    unzip -Z1 "$apk" | sed -n 's|^lib/\([^/]*\)/.*|\1|p' | sort -u
  )
fi
[ ${#abis[@]} -gt 0 ] || { echo "no lib/ entries in $apk" >&2; exit 1; }

required=(libsherpa-onnx-jni.so libmurmur_llm_jni.so libllama.so libmurmur_llm.so
          libggml.so libggml-base.so libonnxruntime.so libc++_shared.so)
entries=$(unzip -Z1 "$apk")
missing=()
for abi in "${abis[@]}"; do
  for lib in "${required[@]}"; do
    grep -qx "lib/$abi/$lib" <<<"$entries" || missing+=("lib/$abi/$lib")
  done
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "$apk cannot dictate: missing engine libraries" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi
echo "engine libraries OK in $apk (${abis[*]})"
