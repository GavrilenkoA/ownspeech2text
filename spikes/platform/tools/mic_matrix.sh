#!/usr/bin/env bash
# Mic legality matrix on the emulator with a known tone injected into the virtual mic.
# Needs an emulator from tools/boot_audio.sh, the spike installed + service enabled, and
# V=<venv python> S=<stub dir> (see inject_tone.py).  Writes evidence/b_mic_tone_<case>.log
set -uo pipefail
source "$(dirname "$0")/env.sh"
SECS=${SECS:-12}; GRPC=${GRPC:-8560}
inject() { "$V" -I "$(dirname "$0")/inject_tone.py" "$S" "$GRPC" "$1" 440 8000 >/dev/null 2>&1 & }
ticks() { evlog | grep rec_tick | sed -E 's/.*"t":([0-9]+),"nonZeroFrac":"([0-9.]+)","silenced":([a-z]+).*/\1:\2:\3/' | paste -sd' '; }
summary() { evlog | grep -oE '"(samples|nonZero|peak|firstBufferMs|silencedAtStop|silencedCallback)":[a-z0-9.]+' | paste -sd' '; }
a11y_case() {  # $1 = fgs|nofgs ; bubble tap while the launcher is in front
  "$A" shell input keyevent KEYCODE_HOME; sleep 1.5
  cmd config --es mic "$1" --es insert none; read -r X Y < <(bubble_xy); "$A" logcat -c
  inject $((SECS + 2)); sleep 0.3
  "$A" shell input tap "$X" "$Y"; sleep "$SECS"
  "$A" shell dumpsys audio | grep -E "rec start.*app.murmur.spike" | tail -1 > /tmp/.mm_audio 2>/dev/null
  "$A" shell input tap "$X" "$Y"; sleep 2
  save "b_mic_tone_a11y_$1" >/dev/null; cat /tmp/.mm_audio >> "$EVD/b_mic_tone_a11y_$1.log"
  echo "a11y-$1: $(ticks)"; echo "   $(summary)"
}
control_case() {  # recording started by a visible activity, then HOME; $1 = on|off (a11y service)
  if [ "$1" = off ]; then "$A" shell settings put secure enabled_accessibility_services null; else
    "$A" shell settings put secure enabled_accessibility_services app.murmur.spike/app.murmur.spike.SpikeA11yService; fi
  sleep 3; "$A" logcat -c; inject $((SECS + 4))
  "$A" shell am start -W -n app.murmur.spike/.MainActivity --es cmd rec_bg --el ms $((SECS * 1000)) >/dev/null
  sleep 2; "$A" shell input keyevent KEYCODE_HOME; sleep $((SECS + 1))
  save "b_mic_tone_control_activity_then_home_service_$1" >/dev/null
  echo "activity->HOME, service $1: $(ticks)"; echo "   $(summary)"
}
case "${1:-all}" in
  a11y) a11y_case fgs; a11y_case nofgs ;;
  control) control_case off; control_case on ;;
  all) a11y_case fgs; a11y_case nofgs; a11y_case fgs; control_case off; control_case on ;;
esac
