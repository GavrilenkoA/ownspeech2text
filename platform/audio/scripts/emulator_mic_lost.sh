#!/usr/bin/env bash
# WP-09: the input stream dies mid-recording. Runs MicLostTest from the installed :platform:audio androidTest APK and
# breaks the audio server from the host while the test records (needs a debuggable emulator image: `adb root`).
# Usage: platform/audio/scripts/emulator_mic_lost.sh <serial> [kill|stall]
#   kill  (default): SIGKILL audioserver; AudioRecord may restore itself (then the test checks that samples resumed
#                    within 1 s) or report the loss (then it must be CaptureSignal.Lost(MicLost)).
#   stall: SIGSTOP audioserver for 2.5 s, then SIGCONT: reads block, and the capture must report Lost(MicLost("stall")).
set -euo pipefail
SERIAL="${1:?usage: emulator_mic_lost.sh <serial> [kill|stall]}"
MODE="${2:-kill}"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
out="$(mktemp -d)"
log_pid=""
inst_pid=""
trap '[ -n "$log_pid" ] && kill "$log_pid" 2>/dev/null; [ -n "$inst_pid" ] && kill "$inst_pid" 2>/dev/null; "$ADB" -s "$SERIAL" unroot >/dev/null 2>&1 || true; rm -rf "$out"' EXIT
# adbd restarts on root/unroot: do it before the test runs, not during.
# adbd may still be restarting from an earlier unroot: retry, then check.
for _ in 1 2 3 4 5; do
  "$ADB" -s "$SERIAL" wait-for-device
  "$ADB" -s "$SERIAL" root >/dev/null 2>&1 && break
  sleep 2
done
"$ADB" -s "$SERIAL" wait-for-device
for _ in 1 2 3 4 5; do [ "$("$ADB" -s "$SERIAL" shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && break; sleep 1; done
[ "$("$ADB" -s "$SERIAL" shell id -u 2>/dev/null | tr -d '\r')" = 0 ] || { echo "adb root failed"; exit 1; }
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" logcat -v brief -s WP09:I MurmurCapture:W > "$out/log" 2>/dev/null &
log_pid=$!
"$ADB" -s "$SERIAL" shell am instrument -w -e class app.murmur.platform.audio.MicLostTest -e wp09.miclost "$MODE" \
  app.murmur.platform.audio.test/androidx.test.runner.AndroidJUnitRunner > "$out/instrument" 2>&1 &
inst_pid=$!
for _ in $(seq 1 300); do grep -q WP09_LOST_READY "$out/log" && break; sleep 0.1; done
grep -q WP09_LOST_READY "$out/log" || { echo "test never became ready"; cat "$out/instrument"; exit 1; }
before="$("$ADB" -s "$SERIAL" shell pidof audioserver || true)"
if [ "$MODE" = stall ]; then
  "$ADB" -s "$SERIAL" shell kill -STOP "$before"
  sleep 2.5
  "$ADB" -s "$SERIAL" shell kill -CONT "$before"
else
  "$ADB" -s "$SERIAL" shell kill -9 "$before"
fi
rc=0
wait "$inst_pid" || rc=$?
inst_pid=""
kill "$log_pid" 2>/dev/null || true
wait "$log_pid" 2>/dev/null || true
log_pid=""
echo "audioserver pid before=$before after=$("$ADB" -s "$SERIAL" shell pidof audioserver || true)"
grep -E "WP09_LOST|mic_lost|read_error" "$out/log" | sed 's/^[^:]*: //'
tail -n 3 "$out/instrument"
grep -q "^OK (1 test)" "$out/instrument"
