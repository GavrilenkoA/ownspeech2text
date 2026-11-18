#!/usr/bin/env bash
# Small REAL probe on an x86_64 emulator (task step 6): real apps, Russian and English locale, cover-size
# and inner-size display; screenshots + uiautomator ground truth -> .cache/ml/ocr/real/emu/.
# Owns exactly one emulator: AVD murmur-ocr-probe on console port 5580 (adb serial emulator-5580). Never
# touches other emulators. Usage:
#   ml/ocr/emu_probe.sh up        # create AVD (if missing) + boot through tools/heavy
#   ml/ocr/emu_probe.sh shots     # drive apps, capture (ru + en, cover + inner)
#   ml/ocr/emu_probe.sh down      # kill the emulator and delete the AVD
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"; export ANDROID_HOME
AVD=murmur-ocr-probe; PORT=5580; S=emulator-$PORT
ADB="$ANDROID_HOME/platform-tools/adb"; A() { "$ADB" -s "$S" "$@"; }
OUT="${OUT:-$ROOT/.cache/ml/ocr/real/emu}"; LOG="$ROOT/.cache/ml/ocr/real/emulator.log"
PY="$ROOT/.cache/ml/ocr/venv/bin/python"
IMAGE="system-images;android-36;google_apis;x86_64"
mkdir -p "$OUT"

up() {
  if ! "$ANDROID_HOME/emulator/emulator" -list-avds | grep -qx "$AVD"; then
    echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" -s create avd -n "$AVD" -k "$IMAGE" -d pixel_7 --force
    cfg="$HOME/.android/avd/$AVD.avd/config.ini"
    for kv in hw.lcd.width=1080 hw.lcd.height=2520 hw.lcd.density=420 hw.ramSize=3072M hw.cpu.ncore=4 \
              hw.keyboard=yes hw.gpu.enabled=yes hw.gpu.mode=swiftshader disk.dataPartition.size=4G; do
      k=${kv%%=*}; grep -q "^$k" "$cfg" && sed -i "s|^$k.*|$kv|" "$cfg" || echo "$kv" >> "$cfg"
    done
  fi
  setsid nohup sg kvm -c "MURMUR_MEM=6G $ROOT/tools/heavy $ANDROID_HOME/emulator/emulator -avd $AVD -port $PORT \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -accel on" > "$LOG" 2>&1 < /dev/null &
  for _ in $(seq 1 180); do
    [ "$(A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 3
  done
  [ "$(A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || { echo "boot failed, see $LOG"; exit 1; }
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do A shell settings put global $s 0; done
  A shell input keyevent 82; A shell svc power stayon true; A shell settings put system screen_off_timeout 1800000
  echo "booted $S"
}

locale() {  # ru-RU | en-US (root on google_apis images)
  A root >/dev/null; sleep 2; A wait-for-device
  A shell "setprop persist.sys.locale $1; setprop ctl.restart zygote"; sleep 8
  for _ in $(seq 1 60); do [ "$(A shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
  sleep 6; A shell input keyevent 82; A shell wm dismiss-keyguard 2>/dev/null
}

cap() { "$PY" -m ml.ocr.real_probe capture --serial "$S" --out "$OUT" --name "$1" >/dev/null && echo "  shot $1"; }
start() { A shell am start -W "$@" >/dev/null 2>&1; sleep 4; }
home() { A shell input keyevent 3; sleep 1; }
type_text() { A shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')"; }   # ASCII only (adb input limitation)

seed_data() {  # contacts, SMS conversation, calendar is left to the app
  for c in "Lesha Kozlov:+15550101" "Ingrid Sorensen:+15550102" "Timur Galiev:+15550103" "Priya Raghunathan:+15550104" \
           "Мама:+15550105" "Марина Ефимова:+15550106"; do
    n=${c%%:*}; p=${c##*:}
    A shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n: >/dev/null
    id=$(A shell content query --uri content://com.android.contacts/raw_contacts --projection _id | tail -1 | grep -oE '_id=[0-9]+' | cut -d= -f2)
    A shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id \
      --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"'$n'" >/dev/null
    A shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id \
      --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:$p --bind data2:i:2 >/dev/null
  done
  "$ADB" -s "$S" emu sms send 15550101 "Привет! Скинь ссылку на дашборд в Grafana по kube-prometheus" >/dev/null
  "$ADB" -s "$S" emu sms send 15550101 "Ещё нужен доступ к ClickHouse кластеру prod-eu-2?" >/dev/null
  "$ADB" -s "$S" emu sms send 15550102 "Did the Terraform plan for eu-west-1 go through? Ping Jurgen about the IAM roles" >/dev/null
  "$ADB" -s "$S" emu sms send 15550104 "Heads up: p99 latency on checkout-api is 840 ms since the 14:05 deploy" >/dev/null
  "$ADB" -s "$S" emu sms send 15550106 "Кто посмотрит flaky тест в CI? Опять упал test_payment_retry" >/dev/null
  sleep 3
}

round() {  # $1 = tag (ru_cover, en_inner, ...)
  local t=$1
  start -a android.settings.SETTINGS; cap "${t}_settings"
  start -a android.settings.WIFI_SETTINGS; cap "${t}_wifi"
  start -a android.settings.DISPLAY_SETTINGS; cap "${t}_display"
  start -a android.settings.DEVICE_INFO_SETTINGS; cap "${t}_about"
  start -a android.settings.APPLICATION_SETTINGS; cap "${t}_apps"
  start -a android.settings.LOCALE_SETTINGS; cap "${t}_languages"
  start -a android.intent.action.VIEW -d content://contacts/people; cap "${t}_contacts"
  start -n com.google.android.apps.messaging/.ui.ConversationListActivity; cap "${t}_messages_list"
  start -a android.intent.action.SENDTO -d sms:15550101; sleep 1; cap "${t}_messages_thread"
  A shell input tap 540 2300; type_text "ok, I will send the link to Timur tonight"; sleep 1; cap "${t}_messages_typing"
  A shell input keyevent 111; A shell input keyevent 111
  start -a android.intent.action.SENDTO -d sms:15550106; cap "${t}_messages_thread2"
  start -a android.intent.action.VIEW -d content://com.android.calendar/time; cap "${t}_calendar"
  start -n com.google.android.documentsui/com.android.documentsui.files.FilesActivity; cap "${t}_files"
  start -a android.intent.action.VIEW -d "https://en.wikipedia.org/wiki/Optical_character_recognition"; sleep 6; cap "${t}_web_en"
  start -a android.intent.action.VIEW -d "https://ru.wikipedia.org/wiki/Оптическое_распознавание_символов"; sleep 6; cap "${t}_web_ru"
  home; cap "${t}_home"
}

shots() {
  A shell pm list packages | grep -E "messaging|chrome|calendar|documentsui|contacts" | sort > "$OUT/packages.txt"
  seed_data
  for loc in ru-RU en-US; do
    locale $loc; tag=${loc%%-*}
    A shell wm size reset; A shell wm density reset; sleep 3; round "${tag}_cover"
    A shell wm size 1968x2184; A shell wm density 420; sleep 4; round "${tag}_inner"
    A shell wm size reset; A shell wm density reset; sleep 2
  done
  ls "$OUT"/*.png | wc -l
}

down() {
  "$ADB" -s "$S" emu kill >/dev/null 2>&1; sleep 5
  pkill -f -- "-avd $AVD -port $PORT" 2>/dev/null
  "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" -s delete avd -n "$AVD" && echo "deleted $AVD"
}

"${1:?up|shots|down}"
