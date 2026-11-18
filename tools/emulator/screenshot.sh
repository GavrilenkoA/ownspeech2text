#!/usr/bin/env bash
# Install the debug APK on a running emulator, launch Murmur and save a PNG screenshot.
# Usage: tools/emulator/screenshot.sh <adb-serial> <out.png>
set -euo pipefail

SERIAL="${1:?usage: screenshot.sh <serial> <out.png>}"
OUT="${2:?usage: screenshot.sh <serial> <out.png>}"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

[ -f "$APK" ] || { echo "missing $APK -- run ./gradlew assembleDebug first" >&2; exit 1; }
"$ADB" -s "$SERIAL" install -r -t "$APK"
"$ADB" -s "$SERIAL" shell am start -W -n app.murmur/.MainActivity
sleep 2
mkdir -p "$(dirname "$OUT")"
"$ADB" -s "$SERIAL" exec-out screencap -p >"$OUT"
echo "saved $OUT"
