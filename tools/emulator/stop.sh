#!/usr/bin/env bash
# Stop a Murmur emulator by adb serial (default: all murmur emulators on ports 5560-5589).
set -uo pipefail
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
if [ $# -gt 0 ]; then serials=("$@"); else
  mapfile -t serials < <("$ADB" devices | awk '/^emulator-55(6|7|8)[0-9]/{print $1}')
fi
for s in "${serials[@]}"; do "$ADB" -s "$s" emu kill && echo "stopped $s"; done
