#!/usr/bin/env bash
# Copies the models of :infer:asr's instrumented tests from the shared model cache into the test APK's files dir
# (run-as; the APK must be installed first). Models come from tools/models/fetch, never from the network here.
#   infer/asr/tools/push-test-models.sh <adb serial> [artifactId ...]
# Default set: silero-vad-v5@onnx gigaam-v3-e2e-rnnt@onnx-int8 parakeet-tdt-0.6b-v3@onnx-int8
#              nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms
set -euo pipefail
serial=${1:?usage: push-test-models.sh <serial> [artifactId ...]}; shift
pkg=app.murmur.infer.asr.test
adb="${ANDROID_HOME:-/home/private/.local/share/android-sdk}/platform-tools/adb"
here=$(cd "$(dirname "$0")" && pwd)
common=$(git -C "$here" rev-parse --path-format=absolute --git-common-dir)
models=${MURMUR_MODELS:-$(dirname "$common")/.cache/models}
ids=("$@")
[ ${#ids[@]} -gt 0 ] || ids=(silero-vad-v5@onnx gigaam-v3-e2e-rnnt@onnx-int8 parakeet-tdt-0.6b-v3@onnx-int8
                              nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms)
for id in "${ids[@]}"; do
  src="$models/$id"
  [ -f "$src/fetch.ok" ] || { echo "not fetched: $id (tools/models/fetch $id)" >&2; exit 2; }
  "$adb" -s "$serial" shell "run-as $pkg mkdir -p files/models/$id"
  (cd "$src" && find . -type f ! -name fetch.ok ! -name '*.part' -printf '%P\n') | while read -r rel; do
    size=$(stat -c %s "$src/$rel")
    have=$("$adb" -s "$serial" shell "run-as $pkg stat -c %s 'files/models/$id/$rel' 2>/dev/null" | tr -d '\r' || true)
    if [ "$have" = "$size" ]; then continue; fi
    "$adb" -s "$serial" shell "run-as $pkg mkdir -p \"\$(dirname 'files/models/$id/$rel')\""
    "$adb" -s "$serial" exec-in run-as "$pkg" sh -c "cat > 'files/models/$id/$rel'" < "$src/$rel"
    have=$("$adb" -s "$serial" shell "run-as $pkg stat -c %s 'files/models/$id/$rel'" | tr -d '\r')
    [ "$have" = "$size" ] || { echo "copy failed: $id/$rel ($have != $size bytes)" >&2; exit 1; }
    echo "pushed $id/$rel ($size bytes)"
  done
done
