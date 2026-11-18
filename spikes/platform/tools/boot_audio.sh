#!/usr/bin/env bash
# Same as tools/emulator/boot.sh, but WITH the audio device and a localhost gRPC port, so
# tools/inject_tone.py can feed the emulator microphone.  Start it through tools/heavy:
#   tools/heavy spikes/platform/tools/boot_audio.sh murmur_fold7_cover 5560 8560
# Feed the mic: PULSE_SERVER=unix:<socket> of a PulseAudio with module-sine-source as default source
# (see .cache/spikes/platform/pulse/img). MURMUR_EMU_EXTRA=-read-only when another instance of the AVD runs.
set -euo pipefail
AVD="${1:?avd}"; PORT="${2:-5560}"; GRPC="${3:-8560}"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"; export ANDROID_HOME
[ -n "${MURMUR_EMU_XDG:-}" ] && export XDG_RUNTIME_DIR="$MURMUR_EMU_XDG"   # private dir holding pulse/native
SERIAL="emulator-$PORT"; ADB="$ANDROID_HOME/platform-tools/adb"
LOG_DIR="${MURMUR_EMU_LOG_DIR:-${TMPDIR:-/tmp}/murmur-emulator}"; mkdir -p "$LOG_DIR"
cmd=("$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT" -grpc "$GRPC" -grpc-use-token
     -no-window -no-boot-anim -no-snapshot-save -gpu "${MURMUR_EMU_GPU:-swiftshader}" -accel on ${MURMUR_EMU_EXTRA:-})
if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
  setsid nohup "${cmd[@]}" >"$LOG_DIR/$AVD.log" 2>&1 < /dev/null &
else
  setsid nohup sg kvm -c "$(printf '%q ' "${cmd[@]}")" >"$LOG_DIR/$AVD.log" 2>&1 < /dev/null &
fi
EMU_PID=$!; echo "emulator starting: $SERIAL grpc $GRPC pid $EMU_PID (log: $LOG_DIR/$AVD.log)"
for _ in $(seq 1 240); do
  kill -0 "$EMU_PID" 2>/dev/null || { echo "emulator exited early, see $LOG_DIR/$AVD.log" >&2; exit 1; }
  if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do
      "$ADB" -s "$SERIAL" shell settings put global $k 0; done
    "$ADB" -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
    echo "booted: $SERIAL"; exit 0
  fi
  sleep 2
done
echo "boot timed out" >&2; exit 1
