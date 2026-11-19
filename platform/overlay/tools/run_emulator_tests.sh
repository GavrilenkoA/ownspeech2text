#!/usr/bin/env bash
# WP-03 instrumented tests on a running murmur_fold7_inner emulator, without Gradle on the device side
# (docs/wp/README.md rule 11). Installs :testapps:target and the overlay test APK, runs the tests, answers the
# posture requests of OverlayWindowTest.survivesPostureChanges with the emulator console (hinge sensor), and pulls
# screenshots + position logs into .cache/spikes/wp-03/.
#
# Build first:  tools/gw --max-workers=6 :platform:overlay:assembleDebugAndroidTest :testapps:target:assembleDebug
# Usage:        platform/overlay/tools/run_emulator_tests.sh emulator-5564 [test class or class#method]
set -euo pipefail

SERIAL="${1:?usage: run_emulator_tests.sh <serial> [class]}"
CLASS="${2:-}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
OUT="$ROOT/.cache/spikes/wp-03"
TEST_PKG="app.murmur.platform.overlay.test"
mkdir -p "$OUT"

"$ADB" -s "$SERIAL" install -r -t "$ROOT/testapps/target/build/outputs/apk/debug/target-debug.apk" >/dev/null
"$ADB" -s "$SERIAL" install -r -t "$ROOT/platform/overlay/build/outputs/apk/androidTest/debug/overlay-debug-androidTest.apk" >/dev/null
"$ADB" -s "$SERIAL" logcat -c

# Posture driver: OverlayWindowTest logs "WP03_HINGE <angle>"; the console command exists only on the host.
# Polls the log (no long-lived logcat child), answers every new request once, stops with the script.
( seen=0
  while kill -0 $$ 2>/dev/null; do
    mapfile -t reqs < <("$ADB" -s "$SERIAL" logcat -d -v brief -s WP03:I 2>/dev/null | grep -o 'WP03_HINGE [0-9]*' || true)
    while [ "$seen" -lt "${#reqs[@]}" ]; do
      angle="${reqs[$seen]##* }"; seen=$((seen + 1))
      "$ADB" -s "$SERIAL" emu sensor set hinge-angle0 "$angle" >/dev/null && echo "hinge-angle0 -> $angle"
    done
    sleep 0.5
  done ) &
DRIVER=$!
trap 'kill $DRIVER 2>/dev/null || true; "$ADB" -s "$SERIAL" emu sensor set hinge-angle0 180 >/dev/null 2>&1 || true' EXIT

args=(-w -r -e hinge host)
[ -n "$CLASS" ] && args+=(-e class "$CLASS")
set +e
"$ADB" -s "$SERIAL" shell am instrument "${args[@]}" "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt" \
  | grep -E "^INSTRUMENTATION_STATUS: (class|test)=|INSTRUMENTATION_STATUS_CODE|^OK|^FAILURES|Tests run|INSTRUMENTATION_FAILED|shortMsg" || true
set -e

"$ADB" -s "$SERIAL" logcat -d -v time -s WP03:I MurmurBubble:I > "$OUT/overlay.log" || true
rm -rf "$OUT/shots" && mkdir -p "$OUT/shots"
"$ADB" -s "$SERIAL" pull "/sdcard/Android/data/$TEST_PKG/files/wp03/." "$OUT/shots" >/dev/null 2>&1 || true
ls "$OUT/shots" | wc -l | xargs echo "screenshots pulled:"
tail -3 "$OUT/instrument.txt"
