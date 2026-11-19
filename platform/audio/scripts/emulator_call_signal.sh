#!/usr/bin/env bash
# WP-09 acceptance: an incoming emulator call while recording -> CaptureSignal.CallStarted within 1 s.
# Runs GsmCallSignalTest from the installed :platform:audio androidTest APK and drives the call from the host console.
# Usage: platform/audio/scripts/emulator_call_signal.sh <serial> [number]
#   e.g. platform/audio/scripts/emulator_call_signal.sh emulator-5576
# Pass/fail (exit 1 on a miss):
#   - the test passes (CallStarted arrived; the system's mode change -> our signal <= 1000 ms, asserted in the test);
#   - device clock: the modem's ring (RILJ UNSOL_CALL_RING) -> our WP09_CALL_SIGNAL line <= 1000 ms. This includes the
#     system's own delay from the ring to AudioManager's mode change.
# Also printed: the host-side delay from `gsm call` to the signal line (includes the console and logcat latency) and
# the AudioManager modes the test saw during the call.
set -euo pipefail
SERIAL="${1:?usage: emulator_call_signal.sh <serial> [number]}"
NUMBER="${2:-5551234}"
LIMIT_MS=1000
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
TEST_PKG="app.murmur.platform.audio.test"
CLASS="app.murmur.platform.audio.GsmCallSignalTest"
out="$(mktemp -d)"
log_pid=""
inst_pid=""
# Both background jobs are adb processes themselves (no subshell in between), so killing their PIDs leaves nothing.
trap '[ -n "$log_pid" ] && kill "$log_pid" 2>/dev/null; [ -n "$inst_pid" ] && kill "$inst_pid" 2>/dev/null; rm -rf "$out"' EXIT

now_ms() { date +%s%3N; }
# Epoch seconds with millis ("1795047498.500") -> milliseconds.
epoch_ms() { awk '{ printf "%d\n", $1 * 1000 + 0.5 }'; }

"$ADB" -s "$SERIAL" logcat -b all -c
"$ADB" -s "$SERIAL" logcat -v epoch -s WP09:I > "$out/log" 2>/dev/null &
log_pid=$!
"$ADB" -s "$SERIAL" shell am instrument -w -e class "$CLASS" -e wp09.gsm 1 \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" > "$out/instrument" 2>&1 &
inst_pid=$!

for _ in $(seq 1 300); do grep -q WP09_CALL_READY "$out/log" && break; sleep 0.1; done
grep -q WP09_CALL_READY "$out/log" || { echo "test never became ready"; cat "$out/instrument"; exit 1; }
sleep 1
call_at=$(now_ms)
"$ADB" -s "$SERIAL" emu gsm call "$NUMBER" >/dev/null
sig_host=""
for _ in $(seq 1 250); do
  if grep -q WP09_CALL_SIGNAL "$out/log"; then sig_host=$(now_ms); break; fi
  sleep 0.02
done
sleep 2
"$ADB" -s "$SERIAL" emu gsm accept "$NUMBER" >/dev/null
sleep 3
"$ADB" -s "$SERIAL" emu gsm cancel "$NUMBER" >/dev/null
rc=0
wait "$inst_pid" || rc=$?
inst_pid=""
kill "$log_pid" 2>/dev/null || true
wait "$log_pid" 2>/dev/null || true
log_pid=""

grep WP09_CALL "$out/log" | sed 's/^ *[0-9.]* *[0-9]* *[0-9]* I WP09 *: //'
if [ -n "$sig_host" ]; then echo "host_delay_ms_call_to_signal=$(( sig_host - call_at ))"; else echo "host_delay_ms_call_to_signal=none"; fi

ring_ms="$("$ADB" -s "$SERIAL" logcat -b all -d -v epoch | grep -m1 'UNSOL_CALL_RING' | epoch_ms || true)"
signal_ms="$(grep -m1 WP09_CALL_SIGNAL "$out/log" | epoch_ms || true)"
verdict=0
if [ -n "$ring_ms" ] && [ -n "$signal_ms" ]; then
  d=$(( signal_ms - ring_ms ))
  echo "device_ms_ring_to_signal=$d (limit $LIMIT_MS)"
  [ "$d" -le "$LIMIT_MS" ] || { echo "MISS: CallStarted came $d ms after the ring"; verdict=1; }
else
  echo "device_ms_ring_to_signal=unknown (ring='${ring_ms}' signal='${signal_ms}')"
  verdict=1
fi
tail -n 3 "$out/instrument"
grep -q "^OK (1 test)" "$out/instrument" || { echo "MISS: instrumented test failed (rc=$rc)"; verdict=1; }
exit "$verdict"
