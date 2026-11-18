#!/usr/bin/env bash
# Tiny uiautomator helpers.  ui.sh dump            -> "text|desc  [bounds]" lines
#                            ui.sh tap "<text>"    -> taps centre of first node whose text/desc contains <text>
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
ADB="${ANDROID_HOME:-$HOME/.local/share/android-sdk}/platform-tools/adb"
dump() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" shell cat /sdcard/ui.xml; }
case "$1" in
  dump) dump | python3 -c '
import sys,re
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    n=m.group(0); g=lambda k:(re.search(k+r"=\"([^\"]*)\"",n) or [None,""])[1]
    t=g("text") or g("content-desc")
    if t: print(t, g("bounds"), g("class"), g("resource-id"))' ;;
  tap) b=$(dump | python3 -c '
import sys,re
want=sys.argv[1]; nodes=re.findall(r"<node [^>]*>", sys.stdin.read())
lab=lambda n: [re.search(k+r"=\"([^\"]*)\"",n).group(1) for k in ("text","content-desc","resource-id")]
hit=[n for n in nodes if want in lab(n)] or [n for n in nodes if any(want in l for l in lab(n))]
if hit:
    x1,y1,x2,y2=map(int,re.findall(r"\d+",re.search(r"bounds=\"([^\"]*)\"",hit[0]).group(1))); print((x1+x2)//2,(y1+y2)//2)' "$2")
     [ -n "$b" ] || { echo "not found: $2" >&2; exit 1; }
     "$ADB" shell input tap $b; echo "tapped $2 at $b" ;;
esac
