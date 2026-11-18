#!/usr/bin/env bash
# WP-02 instrumented tests on a running emulator (README rule 11: install + am instrument, no connectedAndroidTest).
# Build first:  tools/gw --max-workers=6 :platform:a11y:assembleDebugAndroidTest :testapps:target:assembleDebug
# Usage:        platform/a11y/tools/run_emulator_tests.sh emulator-5562
# 1. adb install (packageSource 1) of the test APK + :testapps:target, the whole suite;
# 2. file-session install (pm install-create --package-source 3) and RestrictedSettingsTest -> restrictedLikely true;
# 3. adb install again and RestrictedSettingsTest -> restrictedLikely false.
# Logs and the toggle screenshots go to .cache/spikes/wp-02/.
set -euo pipefail
SERIAL="${1:?usage: run_emulator_tests.sh <serial>}"
root="$(cd "$(dirname "$0")/../../.." && pwd)"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
adb=("$ANDROID_HOME/platform-tools/adb" -s "$SERIAL")
out="$root/.cache/spikes/wp-02"; mkdir -p "$out"
test_apk="$root/platform/a11y/build/outputs/apk/androidTest/debug/a11y-debug-androidTest.apk"
target_apk="$root/testapps/target/build/outputs/apk/debug/target-debug.apk"
pkg=app.murmur.platform.a11y.test
runner="$pkg/androidx.test.runner.AndroidJUnitRunner"

instrument() { # $1 = log name, rest = am instrument args
  local name="$1"; shift
  "${adb[@]}" logcat -c
  "${adb[@]}" logcat -G 16M >/dev/null 2>&1 || true
  "${adb[@]}" shell am instrument -w "$@" "$runner" | tee "$out/$name.txt"
  "${adb[@]}" logcat -d -s A11yTest:I MurmurA11y:I A11yTestHost:I TGT:I > "$out/$name.logcat.txt"
  grep -h 'A11yTest' "$out/$name.logcat.txt" || true
  grep -q '^OK (' "$out/$name.txt"
}

source_of() { "${adb[@]}" shell dumpsys package "$pkg" | grep -m1 -o 'packageSource=[0-9]*'; }

status=0
"${adb[@]}" uninstall "$pkg" >/dev/null 2>&1 || true
"${adb[@]}" install -t "$test_apk" >/dev/null
"${adb[@]}" install -r -t "$target_apk" >/dev/null
echo "adb install: $(source_of)"
instrument suite || status=1

"${adb[@]}" uninstall "$pkg" >/dev/null
"${adb[@]}" push "$test_apk" /data/local/tmp/a11y-test.apk >/dev/null
sid="$("${adb[@]}" shell pm install-create -t --package-source 3 | sed -E 's/.*\[([0-9]+)\].*/\1/' | tr -d '\r')"
"${adb[@]}" shell pm install-write "$sid" base /data/local/tmp/a11y-test.apk >/dev/null
"${adb[@]}" shell pm install-commit "$sid" >/dev/null
echo "file session install (pm install-create --package-source 3): $(source_of)"
instrument restricted_file_install -e class app.murmur.platform.a11y.RestrictedSettingsTest || status=1
"${adb[@]}" pull /sdcard/Download/a11y_toggle_source3.png "$out/" >/dev/null || true

"${adb[@]}" uninstall "$pkg" >/dev/null
"${adb[@]}" install -t "$test_apk" >/dev/null
echo "adb install: $(source_of)"
instrument restricted_adb_install -e class app.murmur.platform.a11y.RestrictedSettingsTest || status=1
"${adb[@]}" pull /sdcard/Download/a11y_toggle_source1.png "$out/" >/dev/null || true
exit $status
