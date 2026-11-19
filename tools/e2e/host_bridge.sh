#!/usr/bin/env bash
# Host side of app/src/androidTest/kotlin/app/murmur/e2e/HostBridge.kt: emulator console commands for the end-to-end
# tests, which cannot reach the console from inside the guest.
#
#   tools/e2e/host_bridge.sh <serial>          (run_emulator_e2e.sh starts and stops it)
#
# Protocol: the test logs `E2E-HOST: req=<id> emu <args>`; this script runs `adb -s <serial> emu <args>` if <args> is on
# the allowlist below and writes `rc=<exit code>` to /data/local/tmp/murmur-e2e/ack-<id>. While it runs,
# /data/local/tmp/murmur-e2e/bridge exists (the tests refuse to start without it).
# Only the allowlisted console commands can run: the bridge never executes free text from the device.
set -uo pipefail
SERIAL="${1:?usage: host_bridge.sh <serial>}"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB=("$ANDROID_HOME/platform-tools/adb" -s "$SERIAL")
DIR=/data/local/tmp/murmur-e2e

allowed() {
  [[ "$1" =~ ^sensor\ set\ hinge-angle0\ [0-9]{1,3}$ ]] ||
  [[ "$1" =~ ^gsm\ (call|accept|cancel)\ [0-9]{3,15}$ ]]
}

cleanup() {
  "${ADB[@]}" shell rm -f "$DIR/bridge" >/dev/null 2>&1
  [ -n "${log_pid:-}" ] && kill "$log_pid" 2>/dev/null
}
trap cleanup EXIT
trap 'exit 0' TERM INT

"${ADB[@]}" shell mkdir -p "$DIR" >/dev/null
fifo="$(mktemp -u)"; mkfifo "$fifo"
# Only lines logged from now on: -T takes the device's current time.
since="$("${ADB[@]}" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
"${ADB[@]}" logcat -v raw -T "$since" -s E2E-HOST:I > "$fifo" 2>/dev/null &
log_pid=$!
exec 3<"$fifo"
rm -f "$fifo"
echo "$$" | "${ADB[@]}" shell "cat > $DIR/bridge"
echo "host bridge up for $SERIAL (pid $$)"

declare -A done_ids=()
while IFS= read -r line <&3; do
  line="${line%$'\r'}"
  [[ "$line" =~ ^req=([0-9]+)\ emu\ (.+)$ ]] || continue
  id="${BASH_REMATCH[1]}"; args="${BASH_REMATCH[2]}"
  [ -n "${done_ids[$id]:-}" ] && continue
  done_ids[$id]=1
  if allowed "$args"; then
    # shellcheck disable=SC2086  # args were matched by the allowlist: words only
    out="$("${ADB[@]}" emu $args 2>&1)"; rc=$?
    # The console answers "OK" or "KO: <reason>" with exit code 0 either way.
    if [ $rc -eq 0 ] && grep -q '^KO' <<<"$out"; then rc=1; fi
  else
    out="refused (not on the allowlist)"; rc=126
  fi
  echo "bridge: emu $args -> rc=$rc ${out//$'\n'/ }"
  "${ADB[@]}" shell "echo rc=$rc > $DIR/ack-$id" >/dev/null
done
