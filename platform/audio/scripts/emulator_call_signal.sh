#!/usr/bin/env bash
# WP-09 acceptance: an incoming emulator call while recording -> CaptureSignal.CallStarted within 1 s.
# Runs GsmCallSignalTest from the installed :platform:audio androidTest APK and drives the call from the host console.
# Usage: platform/audio/scripts/emulator_call_signal.sh <serial> [number]
#   e.g. platform/audio/scripts/emulator_call_signal.sh emulator-5576
# Prints the host-side delay from `gsm call` to the signal's logcat line (an upper bound: it includes logcat latency)
# and the AudioManager modes the test saw during the call.
set -euo pipefail
SERIAL="${1:?usage: emulator_call_signal.sh <serial> [number]}"
NUMBER="${2:-5551234}"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
TEST_PKG="app.murmur.platform.audio.test"
CLASS="app.murmur.platform.audio.GsmCallSignalTest"
out="$(mktemp -d)"
trap 'kill "${inst_pid:-}" "${log_pid:-}" 2>/dev/null || true; rm -rf "$out"' EXIT

now_ms() { date +%s%3N; }

"$ADB" -s "$SERIAL" logcat -c
# Line-buffered logcat with a host timestamp per line.
( "$ADB" -s "$SERIAL" logcat -v brief -s WP09:I | while IFS= read -r line; do echo "$(now_ms) $line"; done ) > "$out/log" &
log_pid=$!
"$ADB" -s "$SERIAL" shell am instrument -w -e class "$CLASS" -e wp09.gsm 1 \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" > "$out/instrument" 2>&1 &
inst_pid=$!

for _ in $(seq 1 300); do grep -q WP09_CALL_READY "$out/log" && break; sleep 0.1; done
grep -q WP09_CALL_READY "$out/log" || { echo "test never became ready"; cat "$out/instrument"; exit 1; }
sleep 1
call_at=$(now_ms)
"$ADB" -s "$SERIAL" emu gsm call "$NUMBER" >/dev/null
for _ in $(seq 1 100); do grep -q WP09_CALL_SIGNAL "$out/log" && break; sleep 0.02; done
sig_line="$(grep WP09_CALL_SIGNAL "$out/log" || true)"
sleep 2
"$ADB" -s "$SERIAL" emu gsm accept "$NUMBER" >/dev/null
sleep 3
"$ADB" -s "$SERIAL" emu gsm cancel "$NUMBER" >/dev/null
wait "$inst_pid" || true
grep WP09_CALL "$out/log" | cut -d' ' -f2-
if [ -n "$sig_line" ]; then
  echo "host_delay_ms_call_to_signal=$(( ${sig_line%% *} - call_at ))"
else
  echo "host_delay_ms_call_to_signal=none"
fi
tail -n 3 "$out/instrument"
grep -q "^OK (1 test)" "$out/instrument"
