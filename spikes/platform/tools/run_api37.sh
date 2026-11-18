#!/usr/bin/env bash
# Re-run the core spike checks on an Android 17 (API 37) emulator; evidence goes to evidence/api37/.
set -uo pipefail
export EVD_DIR="$(cd "$(dirname "$0")/.." && pwd)/evidence/api37${API37_RUN:+/$API37_RUN}"; mkdir -p "$EVD_DIR"
source "$(dirname "$0")/env.sh"
T=app.murmur.spike.target
ONLY=${ONLY:-abcefg}
has() { [[ "$ONLY" == *"$1"* ]]; }
# a) bubble over another app + rotation
if has a; then
"$A" shell am start -W -n $T/.PlainActivity >/dev/null; sleep 2; "$A" logcat -c
"$A" shell settings put system accelerometer_rotation 0; "$A" shell settings put system user_rotation 1; sleep 3
cmd where; cmd ping; sleep 1; "$A" exec-out screencap -p > "$EVD/a_bubble_landscape.png"
"$A" shell settings put system user_rotation 0; sleep 3; cmd where; sleep 1
"$A" shell wm size 1968x2184; sleep 3; cmd where; cmd ping; sleep 1; "$A" exec-out screencap -p > "$EVD/a_bubble_inner_size.png"
"$A" shell wm size reset; sleep 3; save a_bubble_rotation_resize >/dev/null
"$A" shell dumpsys window windows | grep -A3 "MurmurBubble" | head -8 >> "$EVD/a_bubble_rotation_resize.log"
echo "a: $(grep -cE 'bubble_reclamped' "$EVD/a_bubble_rotation_resize.log") reclamps, $(grep -c '"connected":true' "$EVD/a_bubble_rotation_resize.log") pongs, type: $(grep -oE 'ty=[A-Z_]+' "$EVD/a_bubble_rotation_resize.log" | head -1)"
fi
# b) mic: bubble tap (FGS microphone) with the target app in front; reason codes
if has b; then
"$A" shell am start -W -n $T/.PlainActivity >/dev/null; sleep 2
cmd config --es mic fgs --es insert ladder --es text Murmur_ok; read -r X Y < <(bubble_xy); "$A" logcat -c
"$A" shell input tap 540 300; sleep 1; "$A" shell input tap "$X" "$Y"; sleep 2.5
"$A" shell dumpsys activity services app.murmur.spike | grep -oE 'ServiceRecord\{[^ ]+ [^ ]+ [^ }]+|code:[A-Z_]+|allowWiu:[0-9]+|isForeground=true[^\n]*types=0x[0-9a-f]+' > "$EVD/b_fgs_dumpsys_bubble_visible.txt"
"$A" shell dumpsys audio | grep -E "rec start.*app.murmur.spike" | tail -1 >> "$EVD/b_fgs_dumpsys_bubble_visible.txt"
sleep 4; "$A" shell input tap "$X" "$Y"; sleep 3; save b_mic_fgs_bubble_tap >/dev/null
echo "b fgs: $(paste -sd' ' "$EVD/b_fgs_dumpsys_bubble_visible.txt" | cut -c1-300)"
echo "   $(grep -oE 'EV (fgs_started|fgs_start_failed|dictation_stop|insert_result) .*' "$EVD/b_mic_fgs_bubble_tap.log" | cut -c1-230 | paste -sd'|')"
"$A" logcat -c; cmd fgs_probe_hidden; sleep 2.5
"$A" shell dumpsys activity services app.murmur.spike | grep -oE 'ServiceRecord\{[^ ]+ [^ ]+ [^ }]+|code:[A-Z_]+|allowWiu:[0-9]+' > "$EVD/b_fgs_dumpsys_bubble_detached.txt"
sleep 4; save b_fgs_start_no_overlay >/dev/null
echo "b hidden: $(paste -sd' ' "$EVD/b_fgs_dumpsys_bubble_detached.txt") | $(grep -oE 'EV (fgs_started|fgs_start_failed) .*' "$EVD/b_fgs_start_no_overlay.log" | cut -c1-150)"
cmd config --es mic nofgs --es insert none; "$A" logcat -c; "$A" shell input tap "$X" "$Y"; sleep 6; "$A" shell input tap "$X" "$Y"; sleep 2; save b_mic_nofgs_bubble_tap >/dev/null
echo "b nofgs: $(grep -oE 'EV dictation_stop .*' "$EVD/b_mic_nofgs_bubble_tap.log" | cut -c1-230)"
# control: recording started in a visible activity, then the activity goes to the background, service OFF
"$A" shell settings put secure enabled_accessibility_services null; sleep 3; "$A" logcat -c
"$A" shell am start -W -n app.murmur.spike/.MainActivity --es cmd rec_bg --el ms 14000 >/dev/null; sleep 2
"$A" shell am start -W -n $T/.PlainActivity >/dev/null; sleep 14; save b_mic_control_activity_to_background_service_off >/dev/null
echo "control off: $(grep -oE '"t":[0-9]+,"nonZeroFrac":"[0-9.]+","silenced":[a-z]+' "$EVD/b_mic_control_activity_to_background_service_off.log" | sed -E 's/"t":([0-9]+),"nonZeroFrac":"([0-9.]+)","silenced":([a-z]+)/\1:\3/' | paste -sd' ')"
"$A" shell settings put secure enabled_accessibility_services app.murmur.spike/app.murmur.spike.SpikeA11yService; sleep 4
fi
# c) screenshots
if has c; then
for s in PlainActivity SecureActivity; do
  "$A" shell am start -S -W -n $T/.$s >/dev/null; sleep 2; "$A" logcat -c; cmd shot_suite; sleep 4; save "c_shot_suite_$s" >/dev/null
  echo "c $s: $(grep -oE '"step":"[^"]+","r":\{"kind":"[a-z]+","id":-?[0-9]+,"ok":(true|false)(,"error":[0-9]+)?' "$EVD/c_shot_suite_$s.log" | sed -E 's/"step":"([^"]+)".*"ok":(true|false)(,"error":([0-9]+))?/\1=\2\/\4/' | paste -sd' ')"
done
fi
# e) clipboard write from the service, paste by the user path
if has e; then
"$A" shell am start -S -W -n $T/.PlainActivity >/dev/null; sleep 2; "$A" shell input tap 540 300; "$A" logcat -c
cmd clip --es text clip_from_service_37; sleep 1; "$A" shell input keyevent KEYCODE_PASTE; sleep 1.5; save e_clipboard_write >/dev/null
echo "e clip: $(grep -oE 'EV clip .*|TGT .*' "$EVD/e_clipboard_write.log" | cut -c1-120 | paste -sd' ')"
fi
# f) :infer
if has f; then
"$A" logcat -c; cmd infer_idle --es ms 6000; cmd infer_load --es mb 512; sleep 4
echo "f load: $("$A" shell dumpsys meminfo app.murmur.spike:infer | grep -E 'TOTAL PSS' | tr -s ' ')"
sleep 9; echo "f after idle: $("$A" shell pidof app.murmur.spike:infer || echo gone)"
cmd infer_load --es mb 64; sleep 3; cmd infer_native_crash; sleep 5; cmd ping; sleep 1; save f_infer_idle_and_native_crash >/dev/null
echo "f crash: $(grep -oE 'EV (infer_disconnected|infer_created|pong) .*' "$EVD/f_infer_idle_and_native_crash.log" | cut -c1-90 | paste -sd'|')"
fi
# g) UIDT from the background service
if has g; then
"$A" shell am start -W -n $T/.PlainActivity >/dev/null; "$A" logcat -c; cmd uidt_bg; sleep 6; save g_uidt_schedule_from_service >/dev/null
echo "g: $(grep -oE 'EV (uidt_schedule|uidt_start|uidt_http) .*' "$EVD/g_uidt_schedule_from_service.log" | cut -c1-160 | paste -sd'|')"
"$A" shell cmd jobscheduler cancel app.murmur.spike 4711 >/dev/null 2>&1
fi
