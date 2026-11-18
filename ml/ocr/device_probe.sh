#!/usr/bin/env bash
# DEVICE probe for the Galaxy Z Fold7 (docs/ml/OCR_CONTEXT.md "Re-check on the Fold7"). One run, ~10 min.
#   ml/ocr/device_probe.sh [SERIAL]          (phone on USB with adb debugging allowed)
# What it does, on the cover display first and then (after you unfold the phone and press Enter) on the inner
# display: opens stock apps (Settings, Messages, Contacts, Calendar, Samsung Notes / Keep, My Files, Chrome on
# two public pages; whatever is installed), takes a screenshot + uiautomator dump of each, records the real
# display metrics (wm size / density / status bar / IME frames), then runs the reference OCR pipeline on the
# screenshots and writes the error profile next to the rendered-screen profile.
# Output: .cache/ml/ocr/real/fold7/{*.png,*.xml,*.meta.json,*.ocr.json,report.json,metrics.txt}
# Expected: report.json "aggregate" with cer, item_exact, unmatched_ocr_line_share for ~30 screenshots, and
# metrics.txt with the two physical sizes (1080x2520 and 1968x2184 if the spec sheets are right) and densities.
# Nothing personal leaves the machine; delete the folder after the numbers are copied into OCR_CONTEXT.md.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
S="${1:-$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')}"
[ -n "$S" ] || { echo "no physical device found (adb devices)"; exit 1; }
A() { "$ADB" -s "$S" "$@"; }
OUT="$ROOT/.cache/ml/ocr/real/fold7"; mkdir -p "$OUT"
PY="$ROOT/.cache/ml/ocr/venv/bin/python"
cap() { (cd "$ROOT" && "$PY" -m ml.ocr.real_probe capture --serial "$S" --out "$OUT" --name "$1" >/dev/null) && echo "  shot $1"; }
start() { A shell am start -W "$@" >/dev/null 2>&1; sleep 4; }
has() { A shell pm list packages | grep -q "package:$1\$"; }

{ echo "model: $(A shell getprop ro.product.model)"; echo "android: $(A shell getprop ro.build.version.release)";
  echo "locale: $(A shell getprop persist.sys.locale)"; } > "$OUT/metrics.txt"

round() {
  local t=$1
  { echo "== $t"; A shell wm size; A shell wm density; A shell dumpsys display | grep -m2 -E "mBaseDisplayInfo|DisplayDeviceInfo"; } >> "$OUT/metrics.txt"
  start -a android.settings.SETTINGS; cap "${t}_settings"
  start -a android.settings.DISPLAY_SETTINGS; cap "${t}_display"
  start -a android.intent.action.VIEW -d content://contacts/people; cap "${t}_contacts"
  has com.samsung.android.messaging && { start -n com.samsung.android.messaging/com.android.mms.ui.ConversationComposer; cap "${t}_messages"; }
  has com.google.android.apps.messaging && { start -n com.google.android.apps.messaging/.ui.ConversationListActivity; cap "${t}_gmessages"; }
  start -a android.intent.action.VIEW -d content://com.android.calendar/time; cap "${t}_calendar"
  has com.samsung.android.app.notes && { start -n com.samsung.android.app.notes/.memolist.MemoListActivity; cap "${t}_notes"; }
  has com.sec.android.app.myfiles && { start -n com.sec.android.app.myfiles/.external.ui.MainActivity; cap "${t}_files"; }
  start -a android.intent.action.VIEW -d "https://en.wikipedia.org/wiki/Optical_character_recognition"; sleep 5; cap "${t}_web_en"
  start -a android.intent.action.VIEW -d "https://ru.wikipedia.org/wiki/Оптическое_распознавание_символов"; sleep 5; cap "${t}_web_ru"
  echo "Now open YOUR messenger (Telegram/WhatsApp) on a work chat, tap the message field so the keyboard shows, press Enter"; read -r
  cap "${t}_messenger_kb"
  echo "Open your mail app on a thread, press Enter"; read -r; cap "${t}_mail"
}

echo "Fold the phone (cover display), unlock it, press Enter"; read -r
round cover
echo "Unfold the phone (inner display), unlock it, press Enter"; read -r
round inner
(cd "$ROOT" && "$PY" -m ml.ocr.real_probe report "$OUT") | tee "$OUT/summary.txt"
echo "Done: $OUT (report.json, metrics.txt). Compare with .cache/ml/ocr/real/emu/report.json and OCR_CONTEXT.md."
