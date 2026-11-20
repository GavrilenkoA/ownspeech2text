#!/usr/bin/env bash
# Release APK content checks (WP-20 acceptance, ADR-048, D6):
#   - native libraries only for arm64-v8a;
#   - no Hexagon DSP skel (libggml-htp-*.so), no QNN library (libQnn*.so) and no ONNX Runtime QNN plugin EP
#     (libonnxruntime_providers_qnn.so, WP-21) without -Pmurmur.htp / -Pmurmur.qnn;
#   - no class of the :testing fakes (app/murmur/testing) in any dex.
#
#   tools/e2e/check_release_apk.sh [--no-build] [--native-libs <dir>] [--probe-libs] [--control] [<apk>]
#
# --native-libs adds a native library directory (default: the shared .cache/native/jniLibs of the main checkout, if it
# exists), so that the exclusion is checked against real llama/sherpa outputs; with --probe-libs it creates a probe dir
# holding a placeholder libggml-hexagon.so + libggml-htp-v79.so (arm64 and x86_64) to show the skel is dropped while
# the hexagon backend stays.
# --control first builds the same release APK with -Pmurmur.qnn=true -Pmurmur.htp=true and requires the checks to see
# what the flags add: libQnnHtp.so (the qnn-runtime AAR, Maven Central), libonnxruntime_providers_qnn.so (the
# onnxruntime-android-qnn AAR) and, when the native dir holds one,
# libggml-htp-v79.so. Without that positive control a PASS of the two exclusion checks proves nothing. The control
# APK is checked and then rebuilt without the flags.
# Exit: 0 every check passed; 1 a check failed; 2 a build failed or no APK; 4 the positive control failed.
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
export ANDROID_HOME
BUILD=1
NATIVE=""
PROBE=0
CONTROL=0
APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0; shift ;;
    --native-libs) NATIVE="$2"; shift 2 ;;
    --probe-libs) PROBE=1; shift ;;
    --control) CONTROL=1; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) APK="$1"; shift ;;
  esac
done
[ "$CONTROL" = 1 ] && [ "$BUILD" = 0 ] && { echo "--control builds the APK twice; it cannot be combined with --no-build" >&2; exit 2; }
[ "$CONTROL" = 1 ] && [ -n "$APK" ] && { echo "--control checks the APK it builds; do not pass one" >&2; exit 2; }
if [ "$PROBE" = 1 ]; then
  NATIVE="$root/.cache/spikes/wp-20/probe-jnilibs"
  for abi in arm64-v8a x86_64; do
    mkdir -p "$NATIVE/llama/$abi"
    printf 'probe' > "$NATIVE/llama/$abi/libggml-hexagon.so"
    printf 'probe' > "$NATIVE/llama/$abi/libggml-htp-v79.so"
  done
fi
if [ -z "$NATIVE" ]; then
  main="$(git -C "$root" rev-parse --path-format=absolute --git-common-dir | xargs dirname)"
  [ -d "$main/.cache/native/jniLibs" ] && NATIVE="$main/.cache/native/jniLibs"
fi

build() { # [extra gradle args...]: assembles the release APK; exits 2 when the build fails
  local args=(--max-workers=6 :app:assembleRelease "$@")
  [ -n "$NATIVE" ] && args+=("-Pmurmur.nativeLibsDir=$NATIVE")
  echo "build: tools/gw ${args[*]}"
  local log; log="$(mktemp)"
  "$root/tools/gw" "${args[@]}" > "$log" 2>&1
  local rc=$?
  grep -E "^BUILD|^e: |^FAILURE|^\* What went wrong" -A2 "$log" | head -20
  rm -f "$log"
  if [ "$rc" -ne 0 ]; then echo "FAIL build (exit $rc): no APK is checked" >&2; exit 2; fi
}

release_apk() { ls "$root"/app/build/outputs/apk/release/*.apk 2>/dev/null | head -1; }
libs_of() { unzip -l "$1" | awk '{print $4}' | grep '^lib/' || true; }

if [ "$CONTROL" = 1 ]; then
  echo "== positive control: the same build with -Pmurmur.qnn=true -Pmurmur.htp=true"
  build -Pmurmur.qnn=true -Pmurmur.htp=true
  capk="$(release_apk)"
  [ -f "$capk" ] || { echo "no release APK after the control build" >&2; exit 2; }
  clibs="$(libs_of "$capk")"
  cfail=0
  if grep -q '^lib/arm64-v8a/libQnnHtp.so$' <<<"$clibs"; then echo "CONTROL PASS libQnnHtp.so packaged with -Pmurmur.qnn=true"
  else echo "CONTROL FAIL -Pmurmur.qnn=true packaged no lib/arm64-v8a/libQnnHtp.so: the libQnn check has no positive control on this tree"; cfail=1; fi
  if grep -q '^lib/arm64-v8a/libonnxruntime_providers_qnn.so$' <<<"$clibs"; then echo "CONTROL PASS libonnxruntime_providers_qnn.so (QNN plugin EP) packaged with -Pmurmur.qnn=true"
  else echo "CONTROL FAIL -Pmurmur.qnn=true packaged no lib/arm64-v8a/libonnxruntime_providers_qnn.so: the plugin EP check has no positive control on this tree"; cfail=1; fi
  if [ -n "$NATIVE" ] && ls "$NATIVE"/llama/arm64-v8a/libggml-htp-*.so > /dev/null 2>&1; then
    if grep -q '^lib/arm64-v8a/libggml-htp-' <<<"$clibs"; then echo "CONTROL PASS libggml-htp-*.so packaged with -Pmurmur.htp=true"
    else echo "CONTROL FAIL -Pmurmur.htp=true packaged no libggml-htp-*.so although $NATIVE/llama/arm64-v8a has one"; cfail=1; fi
  else
    echo "CONTROL SKIPPED libggml-htp: no libggml-htp-*.so under ${NATIVE:-<no native dir>}/llama/arm64-v8a (use --probe-libs)"
  fi
  [ "$cfail" = 0 ] || exit 4
  echo "== the release build without the flags"
fi
[ "$BUILD" = 1 ] && build
[ -n "$APK" ] || APK="$(release_apk)"
[ -f "$APK" ] || { echo "no release APK" >&2; exit 2; }
echo "apk: $APK ($(stat -c %s "$APK") bytes)"
fail=0
libs="$(libs_of "$APK")"
echo "native libraries:"; sed 's/^/  /' <<<"$libs"
abis="$(sed -n 's#^lib/\([^/]*\)/.*#\1#p' <<<"$libs" | sort -u | paste -sd ' ')"
if [ "$abis" = "arm64-v8a" ]; then echo "PASS abi: $abis"; else echo "FAIL abi: '$abis' (expected arm64-v8a only)"; fail=1; fi
if grep -q 'libggml-htp-' <<<"$libs"; then echo "FAIL libggml-htp-*.so present"; fail=1; else echo "PASS no libggml-htp-*.so"; fi
if grep -q 'libQnn' <<<"$libs"; then echo "FAIL QNN libraries present"; fail=1; else echo "PASS no libQnn*.so"; fi
if grep -q 'libonnxruntime_providers_qnn' <<<"$libs"; then echo "FAIL QNN plugin EP present"; fail=1; else echo "PASS no libonnxruntime_providers_qnn.so"; fi
dexdump="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)dexdump"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$APK" 'classes*.dex' -d "$tmp"
hits=0
for d in "$tmp"/classes*.dex; do
  n=$("$dexdump" "$d" 2>/dev/null | grep -c "Lapp/murmur/testing/" || true)
  hits=$((hits + n))
done
echo "dex files: $(ls "$tmp" | paste -sd ' '); references to app/murmur/testing: $hits"
if [ "$hits" -eq 0 ]; then echo "PASS no :testing classes"; else echo "FAIL :testing classes in the release APK"; fail=1; fi
exit $fail
