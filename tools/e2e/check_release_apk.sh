#!/usr/bin/env bash
# Release APK content checks (WP-20 acceptance, ADR-048, D6):
#   - native libraries only for arm64-v8a;
#   - no Hexagon DSP skel (libggml-htp-*.so) and no QNN library (libQnn*.so) without -Pmurmur.htp / -Pmurmur.qnn;
#   - no class of the :testing fakes (app/murmur/testing) in any dex.
#
#   tools/e2e/check_release_apk.sh [--no-build] [--native-libs <dir>] [<apk>]
#
# --native-libs adds a native library directory (default: the shared .cache/native/jniLibs of the main checkout, if it
# exists), so that the exclusion is checked against real llama/sherpa outputs; with --probe-libs it creates a probe dir
# holding a placeholder libggml-hexagon.so + libggml-htp-v79.so (arm64 and x86_64) to show the skel is dropped while
# the hexagon backend stays.
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
export ANDROID_HOME
BUILD=1
NATIVE=""
PROBE=0
APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0; shift ;;
    --native-libs) NATIVE="$2"; shift 2 ;;
    --probe-libs) PROBE=1; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) APK="$1"; shift ;;
  esac
done
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
if [ "$BUILD" = 1 ]; then
  args=(--max-workers=6 :app:assembleRelease)
  [ -n "$NATIVE" ] && args+=("-Pmurmur.nativeLibsDir=$NATIVE")
  echo "build: tools/gw ${args[*]}"
  "$root/tools/gw" "${args[@]}" 2>&1 | grep -E "^BUILD|^e: "
fi
[ -n "$APK" ] || APK="$(ls "$root"/app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
[ -f "$APK" ] || { echo "no release APK" >&2; exit 2; }
echo "apk: $APK ($(stat -c %s "$APK") bytes)"
fail=0
libs="$(unzip -l "$APK" | awk '{print $4}' | grep '^lib/' || true)"
echo "native libraries:"; sed 's/^/  /' <<<"$libs"
abis="$(sed -n 's#^lib/\([^/]*\)/.*#\1#p' <<<"$libs" | sort -u | paste -sd ' ')"
if [ "$abis" = "arm64-v8a" ]; then echo "PASS abi: $abis"; else echo "FAIL abi: '$abis' (expected arm64-v8a only)"; fail=1; fi
if grep -q 'libggml-htp-' <<<"$libs"; then echo "FAIL libggml-htp-*.so present"; fail=1; else echo "PASS no libggml-htp-*.so"; fi
if grep -q 'libQnn' <<<"$libs"; then echo "FAIL QNN libraries present"; fail=1; else echo "PASS no libQnn*.so"; fi
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
