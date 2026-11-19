#!/usr/bin/env bash
# WP-09 audio-focus decision with another app: builds and installs the focus-player helper (own package and UID),
# runs FocusOtherAppTest from the installed :platform:audio androidTest APK, then uninstalls the helper.
# Usage: platform/audio/scripts/emulator_focus_other_app.sh <serial> [scratch-dir]
set -euo pipefail
SERIAL="${1:?usage: emulator_focus_other_app.sh <serial> [scratch-dir]}"
SCRATCH="${2:-$(mktemp -d)}"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
export ANDROID_HOME
ADB="$ANDROID_HOME/platform-tools/adb"
apk="$("$(dirname "$0")/build_focus_player.sh" "$SCRATCH")"
trap '"$ADB" -s "$SERIAL" uninstall app.murmur.focusplayer >/dev/null 2>&1 || true' EXIT
"$ADB" -s "$SERIAL" install -r "$apk" | tail -n 1
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell am instrument -w -e class app.murmur.platform.audio.FocusOtherAppTest -e wp09.player 1 \
  app.murmur.platform.audio.test/androidx.test.runner.AndroidJUnitRunner | tee "$SCRATCH/instrument.txt" | tail -n 3
"$ADB" -s "$SERIAL" logcat -d -s WP09:I WP09P:I | grep -E "focus_other_app|WP09P_" | sed -E 's/^.* (WP09P?) +: //'
grep -q "^OK (1 test)" "$SCRATCH/instrument.txt"
