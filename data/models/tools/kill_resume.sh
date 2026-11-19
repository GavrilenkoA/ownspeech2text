#!/usr/bin/env bash
# WP-13 acceptance: kill the app process in the middle of a model download; the UIDT job must resume with HTTP 206
# from the .part offset and the sha256 must match.
#   data/models/tools/kill_resume.sh <serial> [test-apk]
#   MODE=stop-app (default): `am kill`, and when that leaves the process alive (it does while a user-initiated job
#     runs: the process is perceptible), `am stop-app` (an ActivityManager kill that keeps the app's jobs).
#   MODE=sigkill: SIGKILL from the app uid, like a low-memory kill.
#   In both modes: if the job has not come back in the background within 90 s, the app is opened (no extras; on the
#   phone the bound accessibility service brings :main back by itself) and the resume is checked from there.
# Needs: a booted emulator with network, the test APK built by
#   tools/gw --max-workers=6 :data:models:assembleDebugAndroidTest
# The test APK plays the app (TestModelsApp hosts ModelDownloads); the download is t-one@onnx (144 MB, Hugging Face).
set -euo pipefail
SERIAL="${1:?usage: kill_resume.sh <serial> [apk]}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
APK="${2:-$ROOT/data/models/build/outputs/apk/androidTest/debug/models-debug-androidTest.apk}"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB=("$ANDROID_HOME/platform-tools/adb" -s "$SERIAL")
MODE="${MODE:-stop-app}"
PKG=app.murmur.data.models.test
ART=t-one@onnx
PART="no_backup/models/$ART/model.onnx.part"
MIN=$((20 * 1024 * 1024))

say() { echo "[kill_resume] $*"; }
size() { "${ADB[@]}" shell run-as "$PKG" stat -c %s "$PART" 2>/dev/null | tr -d '\r' || true; }

"${ADB[@]}" install -r -t "$APK" >/dev/null
"${ADB[@]}" shell am force-stop "$PKG"           # clean start: no old process, no old job
"${ADB[@]}" logcat -c
say "start the app and queue $ART"
"${ADB[@]}" shell am start -W -n "$PKG/app.murmur.data.models.VisibleActivity" --es enqueue "$ART" --ez wifiOnly true >/dev/null

for _ in $(seq 1 300); do s=$(size); [ -n "$s" ] && [ "$s" -ge "$MIN" ] && break; sleep 1; done
s=$(size); [ -n "$s" ] && [ "$s" -ge "$MIN" ] || { say "FAIL: part did not reach $MIN bytes (have '${s}')"; exit 1; }
PID1=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r')
say "part=$s bytes, pid=$PID1; app to background, am kill"
"${ADB[@]}" shell input keyevent KEYCODE_HOME
sleep 1
"${ADB[@]}" shell am kill "$PKG"
sleep 1
PID_AFTER=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r' || true)
if [ "$PID_AFTER" = "$PID1" ]; then
  # `am kill` only ends processes that are safe to kill; a running user-initiated job keeps the process perceptible.
  say "am kill left pid $PID1 alive ($("${ADB[@]}" shell dumpsys activity processes "$PKG" | grep -m1 -oE 'curProcState=[0-9]+'), oom_score_adj $("${ADB[@]}" shell cat /proc/$PID1/oom_score_adj | tr -d '\r'))"
  if [ "$MODE" = sigkill ]; then
    say "SIGKILL from the app uid"; "${ADB[@]}" shell run-as "$PKG" kill -9 "$PID1"
  else
    say "am stop-app (kills the process, keeps its jobs)"; "${ADB[@]}" shell am stop-app "$PKG"
  fi
  sleep 1
  PID_AFTER=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r' || true)
  [ "$PID_AFTER" = "$PID1" ] && { say "FAIL: pid $PID1 still alive"; exit 1; }
fi
say "killed: pid $PID1 gone (now '${PID_AFTER}'); part on disk $(size) bytes"
# Evidence for the device check: the job is still scheduled and ready after the kill (JobScheduler re-evaluates it
# only when the uid's state changes, e.g. a bound accessibility service or the app opening).
jobstate() {
  "${ADB[@]}" shell dumpsys jobscheduler "$PKG" | grep -E "Ready: |Backoff:|Last failed run|started as UIJ" | head -4 | sed 's/^ */    /' || true
}
sleep 20
say "jobscheduler 20 s after the kill:"; jobstate

T0=$(date +%s); OPENED=0
for _ in $(seq 1 600); do
  "${ADB[@]}" shell run-as "$PKG" test -f "no_backup/models/$ART/installed.json" 2>/dev/null && break
  if [ "$OPENED" = 0 ] && [ $(( $(date +%s) - T0 )) -ge 90 ] && [ -z "$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r')" ]; then
    say "no background restart within 90 s: opening the app"; OPENED=1
    "${ADB[@]}" shell am start -W -n "$PKG/app.murmur.data.models.VisibleActivity" >/dev/null
  fi
  sleep 1
done
say "restart: $("${ADB[@]}" logcat -d -b events | grep -E "am_proc_start.*$PKG" | tail -1 | sed 's/.*am_proc_start: //')"
"${ADB[@]}" shell run-as "$PKG" test -f "no_backup/models/$ART/installed.json" || { say "FAIL: not installed after 10 min"; exit 1; }
PID2=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r' || true)
say "installed by the restarted job (pid ${PID2:-exited})"
"${ADB[@]}" logcat -d -s MurmurModels:I | grep -E "job_start|job_stopped|dl_http|dl_verified|install_done" | grep -E "$ART|model.onnx|tokens.txt" || true
"${ADB[@]}" shell am instrument -w -e class app.murmur.data.models.KillResumeScenario "$PKG/androidx.test.runner.AndroidJUnitRunner" | tail -3
"${ADB[@]}" logcat -d -s MurmurModelsTest:I | grep -E "kill_resume_verified" || true
