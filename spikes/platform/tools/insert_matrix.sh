#!/usr/bin/env bash
# Insertion-ladder matrix on the emulator: surfaces x methods, fresh field each time.
# Each field is pre-filled with "abc " by the real keyboard path (input text), then the service inserts.
# Output: one line per case, plus evidence/e_insert_matrix.log with all EV/TGT lines.
set -uo pipefail
source "$(dirname "$0")/env.sh"
TXT="Murmur_ok"
out="$EVD/e_insert_matrix.log"; : > "$out"
surfaces=(plain compose web-input web-textarea web-contenteditable)
methods=(${METHODS:-commit paste settext ladder})
launch() {
  case "$1" in
    plain) "$A" shell am start -S -W -n app.murmur.spike.target/.PlainActivity ;;
    compose) "$A" shell am start -S -W -n app.murmur.spike.target/.ComposeActivity ;;
    web-input) "$A" shell am start -S -W -n app.murmur.spike.target/.WebActivity --es kind input ;;
    web-textarea) "$A" shell am start -S -W -n app.murmur.spike.target/.WebActivity --es kind textarea ;;
    web-contenteditable) "$A" shell am start -S -W -n app.murmur.spike.target/.WebActivity --es kind contenteditable ;;
  esac >/dev/null
}
for s in "${surfaces[@]}"; do for m in "${methods[@]}"; do
  launch "$s"; sleep 3
  "$A" shell input text "abc%s"; sleep 1
  "$A" logcat -c
  cmd insert --es method "$m" --es text "$TXT"; sleep 2.5
  log=$(evlog); echo "### $s $m" >> "$out"; echo "$log" >> "$out"
  res=$(echo "$log" | grep insert_result | tail -1 | sed -E 's/.*"method":"([^"]+)","ok":(true|false).*/\1 ok=\2/')
  tries=$(echo "$log" | grep insert_try | sed -E 's/.*"method":"([a-z]+)".*"called":(true|false).*"verified":(true|false).*/\1:called=\2,verified=\3/' | tr '\n' ' ')
  tgt=$(echo "$log" | grep " TGT " | tail -1 | sed -E 's/.*"text":"([^"]*)".*/\1/')
  printf '%-20s %-8s result=[%s] tries=[%s] target_final="%s"\n' "$s" "$m" "$res" "$tries" "$tgt"
done; done
