#!/usr/bin/env bash
# Boot a Murmur AVD headless and block until Android reports boot completed.
# Usage: tools/emulator/boot.sh <avd-name> [console-port]
#   e.g. tools/emulator/boot.sh murmur_fold7_cover 5560   -> adb serial emulator-5560
#   env: MURMUR_EMU_READ_ONLY=1, MURMUR_EMU_CPU_HOST=1, MURMUR_EMU_GPU
# Ports default to 5560+ to stay away from emulators other users may run on 5554.
set -euo pipefail

AVD="${1:?usage: boot.sh <avd-name> [port]}"
PORT="${2:-5560}"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
export ANDROID_HOME
SERIAL="emulator-$PORT"
ADB="$ANDROID_HOME/platform-tools/adb"
LOG_DIR="${MURMUR_EMU_LOG_DIR:-${TMPDIR:-/tmp}/murmur-emulator}"
mkdir -p "$LOG_DIR"

cmd=("$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT"
     -no-window -no-audio -no-boot-anim -no-snapshot-save
     -gpu "${MURMUR_EMU_GPU:-swiftshader}" -accel on)
# MURMUR_EMU_READ_ONLY=1: second instance of an AVD that is already running.
if [ "${MURMUR_EMU_READ_ONLY:-0}" = "1" ]; then cmd+=(-read-only); fi
# MURMUR_EMU_CPU_HOST=1: expose the host CPU (AVX2) to the guest; LLM tests run 3-4x faster (ADR-038).
# -qemu must stay the last option.
if [ "${MURMUR_EMU_CPU_HOST:-0}" = "1" ]; then cmd+=(-qemu -cpu host); fi

# /dev/kvm is group kvm; a login shell started before `usermod -aG kvm` lacks the group.
if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
  setsid nohup "${cmd[@]}" >"$LOG_DIR/$AVD.log" 2>&1 < /dev/null &
else
  setsid nohup sg kvm -c "$(printf '%q ' "${cmd[@]}")" >"$LOG_DIR/$AVD.log" 2>&1 < /dev/null &
fi
EMU_PID=$!
echo "emulator starting: $SERIAL pid $EMU_PID (log: $LOG_DIR/$AVD.log)"

for _ in $(seq 1 240); do
  if ! kill -0 "$EMU_PID" 2>/dev/null; then
    echo "emulator process exited early -- see $LOG_DIR/$AVD.log (OOM in the 8 GB session cgroup?)" >&2
    exit 1
  fi
  if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    # Make UI tests deterministic.
    "$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0
    "$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0
    "$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0
    "$ADB" -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true   # dismiss keyguard
    echo "booted: $SERIAL"
    exit 0
  fi
  sleep 2
done
echo "boot timed out for $SERIAL" >&2
exit 1
