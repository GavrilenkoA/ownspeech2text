#!/usr/bin/env bash
# CONTEXT_V2 probe (docs/ml/CONTEXT_V2.md): what the accessibility side must deliver to OCR + prompt.
#  1. ctx2 on each field surface with the soft keyboard visible: app, field{kind,hint,before_cursor,password},
#     windows with bounds, exclude_rects (IME, own overlay, system bars)
#  2. screenshot minimum interval sweep (display / window / cross) + full pipeline cost
#  3. window vs display capture with IME visible (pulled PNGs show what OCR would see)
# Evidence: evidence/ctx2_*.log, evidence/shot_interval.log, evidence/ctx2_shots/*.png
set -uo pipefail
source "$(dirname "$0")/env.sh"
T=app.murmur.spike.target
OUT="$EVD/ctx2_shots"; mkdir -p "$OUT"
q() { local a out=""; for a in "$@"; do out+="'${a//\'/\'\\\'\'}' "; done; printf '%s' "$out"; }  # quote for the remote sh
svc_on() {
  "$A" shell settings put secure show_ime_with_hard_keyboard 1
  "$A" shell settings put secure enabled_accessibility_services app.murmur.spike/.SpikeA11yService
  "$A" shell settings put secure accessibility_enabled 1; sleep 3
}
grab() {  # $1 = log name, $2.. = am start args
  local name="$1"; shift
  "$A" shell am force-stop $T; "$A" logcat -c
  "$A" shell "am start -W -n $(q "$@")" >/dev/null; sleep 3
  # tap into the field so the IME shows (centre-ish of the first editable from uiautomator)
  local xy; xy=$("$A" shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; "$A" shell cat /sdcard/u.xml | python3 -c '
import sys,re
for n in re.findall(r"<node [^>]*>", sys.stdin.read()):
    if "EditText" in n or "focusable=\"true\" focused=\"true\"" in n:
        b=list(map(int,re.findall(r"\d+",re.search(r"bounds=\"([^\"]*)\"",n).group(1))))
        print((b[0]+b[2])//2,(b[1]+b[3])//2); break')
  [ -n "$xy" ] && "$A" shell input tap $xy; sleep 2
  cmd ctx2; sleep 2
  evlog | grep -E " ctx2 " | tail -1 > "$EVD/ctx2_$name.log"
  echo "== $name"; python3 - "$EVD/ctx2_$name.log" <<'EOF'
import json,sys
l=open(sys.argv[1]).read().strip()
if not l: print("  (no ctx2 line)"); sys.exit()
d=json.loads(l.split(" ctx2 ",1)[1])["data"]
print("  app  ", json.dumps(d.get("app"), ensure_ascii=False))
f=d.get("field"); p=(f or {}).pop("_probe",None) if f else None
print("  field", json.dumps(f, ensure_ascii=False))
if p: print("  probe", json.dumps(p, ensure_ascii=False)[:600])
print("  excl ", json.dumps(d.get("exclude_rects")))
print("  wins ", [(w["type"],w.get("title"),w["bounds"]) for w in d["windows"]])
print("  ms   ", d.get("ms"), "screen", d.get("screen_size"))
EOF
}
svc_on
grab plain_prefill   $T/.PlainActivity --es prefill "Hello world from the spike" --ei cursor 11 --es hint "Message"
grab plain_empty     $T/.PlainActivity --es hint "Сообщение"
grab multiline       $T/.PlainActivity --ez multiline true --es prefill "Line one
line two" --ei cursor 8
grab password        $T/.PlainActivity --ez password true --es prefill "hunter2" --es hint "Password"
grab compose_prefill $T/.ComposeActivity --es prefill "Compose text before|after" --ei cursor 20
grab webview_input   $T/.WebActivity --es kind input
grab webview_ce      $T/.WebActivity --es kind contenteditable
"$A" shell am force-stop $T; "$A" shell input keyevent KEYCODE_HOME; sleep 2; "$A" logcat -c; cmd ctx2; sleep 2
evlog | grep -E " ctx2 " | tail -1 > "$EVD/ctx2_launcher_no_field.log"; echo "== launcher"; cut -c1-400 "$EVD/ctx2_launcher_no_field.log"

# 2+3: interval sweep and captures with the IME up
"$A" shell "am start -W -n $(q $T/.PlainActivity --es prefill 'Invoice 4471 for Anna')" >/dev/null; sleep 3
xy=$("$A" shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; "$A" shell cat /sdcard/u.xml | grep -o 'class="android.widget.EditText"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | paste -sd' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}')
"$A" shell input tap $xy; sleep 2
"$A" logcat -c; "$A" shell run-as app.murmur.spike sh -c 'rm -f files/shots/*' 2>/dev/null
cmd shot_interval --es gaps "0,50,100,200,300,320,330,340,350,400,500"
sleep 100
evlog | grep -E "shot_interval|shot_pipeline" > "$EVD/shot_interval.log"
python3 - "$EVD/shot_interval.log" <<'EOF'
import json,sys
for l in open(sys.argv[1]):
    if " shot_" not in l: continue
    tag="shot_interval" if " shot_interval " in l else "shot_pipeline"
    d=json.loads(l.split(" "+tag+" ",1)[1])
    if tag=="shot_interval": print(f'{d["a"]:>7}->{d["b"]:<7} gap={d["gap"]:>4} actual={d["actualGap"]:>4} b_ok={d["bOk"]} err={d.get("bErr")} aMs={d["aMs"]} bMs={d["bMs"]}')
    else: print("pipeline", d["kind"], {k:d["r"].get(k) for k in ("ok","ms","w","h","file","error")}, "total", d["totalMsInclPng"])
EOF
for f in $("$A" shell run-as app.murmur.spike ls files/shots | tr -d '\r'); do
  "$A" exec-out run-as app.murmur.spike cat files/shots/$f > "$OUT/$f"; done
ls -la "$OUT"
