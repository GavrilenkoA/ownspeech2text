#!/usr/bin/env bash
# Insertion into Chrome (real browser, not WebView): <input> and contenteditable pages served from the
# host (python -m http.server 18731 in .cache/spikes/platform/www, adb reverse). Reads back both the
# accessibility node and the page's own DOM 'input' event echo (uiautomator dump of the echo line).
set -uo pipefail
source "$(dirname "$0")/env.sh"; U="$(dirname "$0")/ui.sh"
"$A" reverse tcp:18731 tcp:18731 >/dev/null
out="$EVD/e_insert_chrome.log"; : > "$out"
for page in index ce; do for m in commit paste settext; do
  "$A" shell am start -W -a android.intent.action.VIEW -d "http://127.0.0.1:18731/$page.html?m=$m" -p com.android.chrome >/dev/null; sleep 4
  "$A" shell input tap 540 600; sleep 1; "$A" shell input text "abc%s"; sleep 1; "$A" logcat -c
  cmd insert --es method $m --es text Murmur_ok; sleep 2.5
  echo_line=$("$U" dump | grep -oE '^echo:[^[]*' | head -1)
  { echo "### chrome-$page $m"; evlog; echo "PAGE_ECHO: $echo_line"; } >> "$out"
  echo "chrome-$page $m: $(evlog | grep insert_try | grep -oE '"cls":"[^"]*"|"(called|verified)":(true|false)' | paste -sd' ') | page $echo_line"
done; done
