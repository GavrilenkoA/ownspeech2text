#!/usr/bin/env bash
# Platform smoke test on the real phone (Galaxy Z Fold7) with the spike from spikes/platform.
# Answers the UNVERIFIED-ON-DEVICE items of docs/research/verify/platform.md and prints a compact
# markdown report (also saved to .cache/device/platform_<serial>_<time>.md with the raw logcat).
#
# Usage (phone plugged in, USB debugging on, Auto Blocker off, screen unlocked):
#   tools/device/platform_smoke.sh            # builds the spike if needed, installs, runs all checks
#   SERIAL=R5CX... tools/device/platform_smoke.sh
# NONINTERACTIVE=1 skips the prompts (emulator dry run).
# Interactive steps: fold/unfold once, tap the bubble once, tap into 4 fields (keyboard visible).
# Run it twice for the screenshot samples: once unfolded (inner) and once folded (cover screen).
# It changes: installs app.murmur.spike + app.murmur.spike.target, enables their accessibility
# service via adb, grants mic/notification permission. Undo: tools/device/platform_smoke.sh --uninstall
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SP="$ROOT/spikes/platform"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"; command -v "$ADB" >/dev/null || ADB=adb
[ -n "${SERIAL:-}" ] || SERIAL=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')
[ -n "$SERIAL" ] || { echo "no device: plug the phone in and allow USB debugging" >&2; exit 1; }
A=("$ADB" -s "$SERIAL")
SVC=app.murmur.spike/app.murmur.spike.SpikeA11yService
if [ "${1:-}" = "--uninstall" ]; then
  "${A[@]}" shell settings delete secure enabled_accessibility_services
  "${A[@]}" uninstall app.murmur.spike; "${A[@]}" uninstall app.murmur.spike.target; exit 0
fi
OUT="$ROOT/.cache/device"; mkdir -p "$OUT"; TS=$(date +%Y%m%d_%H%M%S)
REP="$OUT/platform_${SERIAL}_$TS.md"; RAW="$OUT/platform_${SERIAL}_$TS.logcat"
cmd() { local c="$1"; shift; "${A[@]}" shell am broadcast -a app.murmur.spike.CMD --es c "$c" "$@" >/dev/null; }
ev() { "${A[@]}" logcat -d -s MurmurSpike:I MurmurTarget:I | grep -E " (EV|TGT) " | tee -a "$RAW"; }
pick() { grep -E "EV $1 " | tail -1 | sed -E "s/.*EV $1 //"; }
say() { echo "$*" | tee -a "$REP"; }
# NONINTERACTIVE=1 (used to dry-run on the emulator): skip prompts, drive the bubble by broadcast.
ask() { [ "${NONINTERACTIVE:-0}" = 1 ] && return 0; echo; read -r -p ">>> $* [Enter when done] " _ </dev/tty; }

# ---- build + install
APK="$SP/app/build/outputs/apk/debug/app-debug.apk"; TAPK="$SP/target/build/outputs/apk/debug/target-debug.apk"
if [ ! -f "$APK" ] || [ ! -f "$TAPK" ]; then (cd "$SP" && "$ROOT/tools/heavy" ./gradlew -q assembleDebug) || exit 1; fi
"${A[@]}" install -r -t "$APK" >/dev/null && "${A[@]}" install -r -t "$TAPK" >/dev/null || { echo "install failed (Auto Blocker on?)" >&2; exit 1; }
for p in RECORD_AUDIO POST_NOTIFICATIONS; do "${A[@]}" shell pm grant app.murmur.spike android.permission.$p; done

say "# Platform smoke: $("${A[@]}" shell getprop ro.product.model | tr -d '\r') ($SERIAL), $TS"
say ""
say "| item | value |"; say "|---|---|"
for kv in "android:ro.build.version.release" "sdk:ro.build.version.sdk" "one_ui:ro.build.version.oneui" \
          "sem_platform:ro.build.version.sem_platform_int" "build:ro.build.display.id" "patch:ro.build.version.security_patch" \
          "soc:ro.soc.model" "hw:ro.hardware"; do
  say "| ${kv%%:*} | $("${A[@]}" shell getprop "${kv#*:}" | tr -d '\r') |"
done
say "| install source (adb) | $("${A[@]}" shell dumpsys package app.murmur.spike | grep -oE 'packageSource=[0-9]+|initiatingPackageName=[^ ]+' | paste -sd' ') |"
say "| ACCESS_RESTRICTED_SETTINGS op | $("${A[@]}" shell appops get app.murmur.spike ACCESS_RESTRICTED_SETTINGS | tr -d '\r' | head -1) |"

# ---- enable the service through adb (test automation path)
"${A[@]}" shell settings put secure enabled_accessibility_services null; sleep 2; "${A[@]}" logcat -c
"${A[@]}" shell settings put secure enabled_accessibility_services "$SVC"
"${A[@]}" shell settings put secure accessibility_enabled 1
sleep 4; L=$(ev)
say "| a11y enabled via adb settings | $(echo "$L" | grep -c 'EV service_connected') service_connected, bubble: $(echo "$L" | pick bubble_attached) |"
say "| main_status (AAPM etc.) | $("${A[@]}" shell am start -W -n app.murmur.spike/.MainActivity >/dev/null; sleep 2; ev | pick main_status) |"
"${A[@]}" shell input keyevent KEYCODE_HOME

# ---- display metrics, folded and unfolded
metrics() { echo "size=$("${A[@]}" shell wm size | tail -1 | tr -d '\r' | sed 's/.*: //') density=$("${A[@]}" shell wm density | tail -1 | tr -d '\r' | sed 's/.*: //') $("${A[@]}" shell dumpsys display | grep -oE 'mDisplayId=[0-9]+|uniqueId=\"[^\"]+\"' | sort -u | paste -sd' ')"; }
ask "UNFOLD the phone (inner screen on), keep it unlocked"
say "| unfolded metrics | $(metrics) |"
"${A[@]}" logcat -c
ask "FOLD the phone and use the cover screen (unlock if needed), then unfold again"
L=$(ev)
say "| fold/unfold events | config_changed=$(echo "$L" | grep -c config_changed) display_added=$(echo "$L" | grep -c display_added) display_removed=$(echo "$L" | grep -c display_removed) last=$(echo "$L" | pick bubble_reclamped) |"
say "| folded metrics (from events) | $(echo "$L" | grep config_changed | sed -E 's/.*EV config_changed //' | sort -u | paste -sd' ') |"

# ---- mic from bubble tap with another app in front, FGS microphone, silenced?
"${A[@]}" shell am start -W -n app.murmur.spike.target/.PlainActivity >/dev/null; sleep 2
cmd config --es mic fgs --es insert ladder --es text "Murmur_ok"; "${A[@]}" logcat -c
fgs_reason() { sleep 2; "${A[@]}" shell dumpsys activity services app.murmur.spike | grep -oE 'RecordingFgs|code:[A-Z_]+|allowWiu:[0-9]+' | paste -sd' ' > "$OUT/.fgs_reason"; }
if [ "${NONINTERACTIVE:-0}" = 1 ]; then
  "${A[@]}" shell input tap 540 300; sleep 1; cmd tap; fgs_reason; sleep 3; cmd tap
else
  echo ">>> Tap the plain EditText, then TAP THE BLUE BUBBLE and keep talking; the script samples the FGS state"
  until ev | grep -q 'EV fgs_started\|EV fgs_start_failed'; do sleep 1; done; fgs_reason
  ask "Now TAP THE BUBBLE again to stop"
fi
say "| FGS reason while recording (dumpsys) | $(cat "$OUT/.fgs_reason" 2>/dev/null) |"
sleep 3; L=$(ev)
say "| FGS start | $(echo "$L" | pick fgs_started)$(echo "$L" | pick fgs_start_failed) |"
say "| recording | $(echo "$L" | pick dictation_stop) |"
say "| rec_config (silenced?) | $(echo "$L" | grep 'EV rec_config' | sed -E 's/.*EV rec_config //' | paste -sd' ') |"
say "| screenshot ~400 ms | $(echo "$L" | grep 'EV shot ' | sed -E 's/.*EV shot //' | paste -sd' ') |"
say "| context | $(echo "$L" | pick context | cut -c1-600) |"
say "| insertion | $(echo "$L" | grep -E 'EV insert_(try|result)' | sed -E 's/.*EV //' | cut -c1-300 | paste -sd' ') |"
say "| target field after | $(echo "$L" | grep ' TGT ' | tail -1 | sed -E 's/.*TGT //') |"

# ---- insertion ladder per method in the spike target (plain, compose, web)
for s in PlainActivity ComposeActivity WebActivity; do for m in commit paste settext; do
  "${A[@]}" shell am start -S -W -n app.murmur.spike.target/.$s >/dev/null; sleep 3
  "${A[@]}" shell input text "abc%s"; sleep 1; "${A[@]}" logcat -c
  cmd insert --es method $m --es text Murmur_ok; sleep 2.5; L=$(ev)
  say "| insert $s $m | $(echo "$L" | grep insert_try | grep -oE '"(called|verified)":(true|false)|"focusPath":"[^"]*"' | paste -sd' ') final=$(echo "$L" | grep ' TGT ' | tail -1 | sed -E 's/.*"text":"([^"]*)".*/\1/') |"
done; done

# ---- screenshot rate limit + FLAG_SECURE
for s in PlainActivity SecureActivity; do
  "${A[@]}" shell am start -S -W -n app.murmur.spike.target/.$s >/dev/null; sleep 2; "${A[@]}" logcat -c
  cmd shot_suite; sleep 3
  say "| shot_suite $s | $(ev | grep 'EV shot_suite' | sed -E 's/.*"step":"([^"]+)","r":\{"kind":"[a-z]+","id":-?[0-9]+,"ok":(true|false)(,"error":([0-9]+))?(,"ms":([0-9]+))?.*/\1:ok=\2 err=\4 ms=\6/' | paste -sd' ') |"
done

# ---- CONTEXT_V2 (docs/ml/CONTEXT_V2.md): app label, field kind/hint/before_cursor/password,
#      IME + own-overlay bounds (OCR exclusion rects), screenshot min interval, Fold7 capture samples
q() { local a o=""; for a in "$@"; do o+="'${a//\'/\'\\\'\'}' "; done; printf '%s' "$o"; }
ctx2_line() { ev | grep -E 'EV ctx2 ' | tail -1 | sed -E 's/.*EV ctx2 //' | python3 -c '
import sys,json
try: d=json.loads(sys.stdin.read())["data"]
except Exception as e: print("no ctx2:",e); sys.exit()
f=d.get("field") or {}; f.pop("_probe",None)
print(json.dumps({"app":d.get("app"),"field":f,"exclude":d.get("exclude_rects"),"screen":d.get("screen_size"),"ms":d.get("ms",{}).get("total")},ensure_ascii=False))'; }
for spec in "PlainActivity --es prefill Привет,world --es hint Сообщение" "PlainActivity --ez password true --es prefill hunter2" \
            "ComposeActivity --es prefill compose_text" "WebActivity --es kind input"; do
  set -- $spec; act=$1; shift
  "${A[@]}" shell "am start -S -W -n app.murmur.spike.target/.$act $*" >/dev/null; sleep 3
  if [ "${NONINTERACTIVE:-0}" != 1 ]; then ask "Tap into the text field so the KEYBOARD is visible ($act)"; fi
  "${A[@]}" logcat -c; cmd ctx2; sleep 2
  say "| ctx2 $act | $(ctx2_line) |"
done
"${A[@]}" shell am start -W -n app.murmur.spike.target/.PlainActivity >/dev/null; sleep 2
"${A[@]}" shell run-as app.murmur.spike sh -c 'rm -f files/shots/*' 2>/dev/null
"${A[@]}" logcat -c; cmd shot_interval --es gaps "0,200,320,330,340,400"; sleep 50; L=$(ev)
say "| shot min interval (b ok per gap) | $(echo "$L" | grep 'EV shot_interval' | sed -E 's/.*"a":"([a-z]+)","b":"([a-z]+)","gap":([0-9]+).*"bOk":(true|false).*/\1>\2@\3=\4/' | paste -sd' ') |"
say "| shot pipeline (capture+copy+png) | $(echo "$L" | grep 'EV shot_pipeline' | sed -E 's/.*"kind":"([a-z]+)".*"ms":([0-9]+),"w":([0-9]+),"h":([0-9]+).*"totalMsInclPng":([0-9]+).*/\1 api=\2ms \3x\4 total=\5ms/' | paste -sd' ') |"
mkdir -p "$OUT/shots_$TS"; for f in $("${A[@]}" shell run-as app.murmur.spike ls files/shots 2>/dev/null | tr -d '\r'); do
  "${A[@]}" exec-out run-as app.murmur.spike cat "files/shots/$f" > "$OUT/shots_$TS/$f"; done
say "| Fold7 capture samples (for ml/ocr realism check) | $OUT/shots_$TS |"

# ---- :infer process: load 512 MB native, idle-exit, crash isolation
"${A[@]}" logcat -c; cmd infer_idle --es ms 8000; cmd infer_load --es mb 512; sleep 4
say "| :infer after load | $("${A[@]}" shell dumpsys meminfo app.murmur.spike:infer | grep -E 'TOTAL PSS' | tr -s ' ') |"
sleep 10
say "| :infer after idle (expect gone) | $("${A[@]}" shell pidof app.murmur.spike:infer || echo gone) |"
cmd infer_load --es mb 64; sleep 3; cmd infer_native_crash; sleep 5; cmd ping; sleep 1; L=$(ev)
say "| native crash in :infer, service alive? | $(echo "$L" | pick pong) |"

# ---- UIDT download from the background service (whisper-tiny 151 MB, resumable)
"${A[@]}" logcat -c; cmd uidt_bg; sleep 6; L=$(ev)
say "| UIDT from service | $(echo "$L" | pick uidt_schedule) $(echo "$L" | pick uidt_http) |"
"${A[@]}" shell cmd jobscheduler cancel app.murmur.spike 4711 >/dev/null 2>&1

# ---- Samsung specifics worth recording
say "| battery/standby bucket | $("${A[@]}" shell am get-standby-bucket app.murmur.spike | tr -d '\r') |"
say "| memory limiter (A17) | $("${A[@]}" shell am memory-limiter status 2>&1 | head -3 | tr '\n' ' ') |"
say ""; say "Raw logcat: $RAW"
echo; echo "Report: $REP"
