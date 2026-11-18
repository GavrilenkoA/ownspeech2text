#!/usr/bin/env bash
# Bubble survival across rotation (90/180/270), and a cover->inner->cover size change
# (wm size/density: same logical display 0, new metrics = what a Samsung fold/unfold looks like
# to apps). Writes evidence/a_display_matrix.log and a_display_*.png.
set -uo pipefail
source "$(dirname "$0")/env.sh"
out="$EVD/a_display_matrix.log"; : > "$out"
step() {  # $1 label
  sleep 2.5; cmd where; cmd ping; sleep 1
  echo "### $1 (wm size: $("$A" shell wm size | tail -1 | tr -d '\r'), density: $("$A" shell wm density | tail -1 | tr -d '\r'))" >> "$out"
  evlog | grep -E "bubble_|config_changed|display_|pong|service_" >> "$out"; "$A" logcat -c
  "$A" exec-out screencap -p > "$EVD/a_display_$1.png"
}
"$A" shell am start -W -n app.murmur.spike.target/.PlainActivity >/dev/null; "$A" logcat -c
"$A" shell settings put system accelerometer_rotation 0
for r in 0 1 2 3 0; do "$A" shell settings put system user_rotation $r; step "rot$r"; done
"$A" shell wm size 1968x2184; step inner_1968x2184_d420
"$A" shell wm density 374; step inner_1968x2184_d374
"$A" shell wm size reset; "$A" shell wm density reset; step cover_reset
grep -E "^###|bubble_reclamped|config_changed|pong|bubble_where" "$out" | sed -E 's/^ *[0-9]+ +[0-9]+ I MurmurSpike: EV //' | cut -c1-200
