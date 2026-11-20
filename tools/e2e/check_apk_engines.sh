#!/usr/bin/env bash
# Checks that an APK ships the native engines dictation needs, per ABI it claims.
#   tools/e2e/check_apk_engines.sh [--npu] <apk> [abi ...]   (default ABIs: every one found in the APK)
# --npu also requires the Qualcomm runtime, the Hexagon skel and the ONNX Runtime QNN plugin EP.
# Exit: 0 present; 1 missing.
set -uo pipefail
npu=0
if [ "${1:-}" = "--npu" ]; then npu=1; shift; fi
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
npu_required=(libQnnHtp.so libQnnSystem.so libQnnHtpV79Stub.so libQnnHtpV79Skel.so
              libonnxruntime_providers_qnn.so libggml-hexagon.so libggml-htp-v79.so libmurmur-qnn-probe.so)
entries=$(unzip -Z1 "$apk")
missing=()
for abi in "${abis[@]}"; do
  libs=("${required[@]}")
  [ "$npu" = 1 ] && [ "$abi" = arm64-v8a ] && libs+=("${npu_required[@]}")
  for lib in "${libs[@]}"; do
    grep -qx "lib/$abi/$lib" <<<"$entries" || missing+=("lib/$abi/$lib")
  done
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "$apk cannot dictate: missing engine libraries" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi
echo "engine libraries OK in $apk (${abis[*]})"
