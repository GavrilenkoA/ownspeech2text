#!/usr/bin/env bash
# Emulator end-to-end suites of WP-20 (ARCHITECTURE 16; docs/wp/WP-20-integration-e2e-device.md, Deliver 4).
#
#   tools/e2e/run_emulator_e2e.sh [--serial emulator-5588] [--suite harness,fast,matrix,events,real,privacy | all]
#                                 [--boot <avd>] [--keep-emulator] [--no-build] [--timeout-scale 4]
#
# Suites (each one `am instrument` of the app's androidTest APK, plus host-side checks):
#   harness  the harness itself: target app logging (its own TargetAppTest), host bridge, fold/rotation/call/shade,
#            SIGKILL of :asr/:llm, service on/off (EmulatorHarnessTest), PrivacyManifestTest, no app network traffic
#   fast     FastE2eTest on the :testing fake engines (debug fake mode): tap, listen, tap -> text in the target,
#            history row, bubble Listening -> Polishing -> Inserted. Both AVDs.
#   matrix   TargetMatrixE2eTest: every field kind of the target, password and FLAG_SECURE (fakes)
#   events   PlatformEventsE2eTest (a)-(g) during Listening (fakes; murmur_fold7_inner; needs the host bridge)
#   real     RealE2eTest: GigaAM RNN-T + VAD + OCR + Qwen3.5 0.8B on CPU, FLEURS ru clip, then the privacy checks
#            (no app network bytes after the model push, no transcript word in the app's log lines)
#   privacy  PrivacyManifestTest + the network check, without a dictation
#   all      harness fast matrix events (the real suite needs --boot with the CPU-host emulator; run it on its own)
#
# --boot <avd> starts the emulator through tools/heavy tools/emulator/boot.sh with MURMUR_EMU_READ_ONLY=1 (shared AVDs;
# MURMUR_EMU_CPU_HOST=1 for the real suite, ADR-038) once `free -g` shows >= 14 GB available, and stops it at the end
# unless --keep-emulator. Without --boot the emulator must already run on --serial.
# Results: .cache/e2e-reports/<time>-<serial>/ (instrument outputs, logcat, summary.md); exit 0 only if every suite passed.
set -uo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
export ANDROID_HOME
SERIAL="${ANDROID_SERIAL:-emulator-5588}"
SUITES="harness"
BOOT_AVD=""
KEEP=0
BUILD=1
TIMEOUT_SCALE=4
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --suite) SUITES="$2"; shift 2 ;;
    --boot) BOOT_AVD="$2"; shift 2 ;;
    --keep-emulator) KEEP=1; shift ;;
    --no-build) BUILD=0; shift ;;
    --timeout-scale) TIMEOUT_SCALE="$2"; shift 2 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[ "$SUITES" = "all" ] && SUITES="harness,fast,matrix,events"
IFS=',' read -r -a suites <<<"$SUITES"
for s in "${suites[@]}"; do
  case "$s" in harness|fast|matrix|events|real|privacy) ;; *) echo "unknown suite $s" >&2; exit 2 ;; esac
done
has_suite() { [[ ",$SUITES," == *",$1,"* ]]; }

ADB=("$ANDROID_HOME/platform-tools/adb" -s "$SERIAL")
PKG=app.murmur
TARGET_PKG=app.murmur.testtarget
DEVICE_DIR=/data/local/tmp/murmur-e2e
APP_APK="$root/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
TARGET_APK="$root/testapps/target/build/outputs/apk/debug/target-debug.apk"
TARGET_TEST_APK="$root/testapps/target/build/outputs/apk/androidTest/debug/target-debug-androidTest.apk"
NATIVE_LIBS="${MURMUR_NATIVE_LIBS:-$(cd "$root" && git rev-parse --path-format=absolute --git-common-dir | xargs dirname)/.cache/native/jniLibs}"
REAL_ARTIFACTS=(gigaam-v3-e2e-rnnt@onnx-int8 silero-vad-v5@onnx pp-ocrv6-tiny-det@onnx pp-ocrv5-cyrillic-mobile-rec@onnx qwen3.5-0.8b@q4_0)

out="$root/.cache/e2e-reports/$(date +%Y%m%d-%H%M%S)-$SERIAL"
mkdir -p "$out"
summary="$out/summary.md"
log() { echo "[e2e] $*"; }
die() { echo "[e2e] ERROR: $*" >&2; exit 1; }

bridge_pid=""
booted=0
cleanup() {
  if [ -n "$bridge_pid" ]; then kill -TERM "$bridge_pid" 2>/dev/null; wait "$bridge_pid" 2>/dev/null; fi
  if [ "$booted" = 1 ] && [ "$KEEP" = 0 ]; then "${ADB[@]}" emu kill >/dev/null 2>&1 && log "stopped $SERIAL"; fi
}
trap cleanup EXIT

# --- emulator -----------------------------------------------------------------------------------------------------
if [ -n "$BOOT_AVD" ]; then
  "$ANDROID_HOME/platform-tools/adb" devices | grep -q "^$SERIAL\s" && die "$SERIAL is already running; drop --boot"
  for _ in $(seq 1 60); do
    avail=$(free -g | awk '/^Mem:/{print $7}')
    [ "$avail" -ge 14 ] && break
    log "only ${avail} GB available, waiting for >= 14 GB"; sleep 30
  done
  [ "$(free -g | awk '/^Mem:/{print $7}')" -ge 14 ] || die "not enough free memory to boot an emulator"
  port="${SERIAL#emulator-}"
  cpu_host=0; has_suite real && cpu_host=1
  # The brief asks for 4 GB guest RAM for the real suite (-memory 4096); boot.sh has no switch for it yet (toolchain
  # owner), so the AVD's own hw.ramSize applies; the report records MemTotal.
  MURMUR_EMU_READ_ONLY=1 MURMUR_EMU_CPU_HOST=$cpu_host "$root/tools/heavy" "$root/tools/emulator/boot.sh" "$BOOT_AVD" "$port" \
    || die "emulator boot failed"
  booted=1
fi
[ "$("${ADB[@]}" get-state 2>/dev/null)" = "device" ] || die "$SERIAL is not online (use --boot <avd>)"

# --- build + install ----------------------------------------------------------------------------------------------
if [ "$BUILD" = 1 ]; then
  gradle_args=(--max-workers=6 :app:assembleDebug :app:assembleDebugAndroidTest :testapps:target:assembleDebug :testapps:target:assembleDebugAndroidTest)
  has_suite real && gradle_args+=("-Pmurmur.nativeLibsDir=$NATIVE_LIBS")
  log "building: ${gradle_args[*]}"
  "$root/tools/gw" "${gradle_args[@]}" > "$out/build.txt" 2>&1 || { tail -30 "$out/build.txt"; die "build failed"; }
  tail -1 "$out/build.txt"
fi
for apk in "$APP_APK" "$TEST_APK" "$TARGET_APK" "$TARGET_TEST_APK"; do [ -f "$apk" ] || die "missing $apk"; done
log "installing on $SERIAL"
for apk in "$APP_APK" "$TEST_APK" "$TARGET_APK" "$TARGET_TEST_APK"; do
  "${ADB[@]}" install -r -t "$apk" >/dev/null || die "install $apk failed"
done
"${ADB[@]}" shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
"${ADB[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
"${ADB[@]}" shell settings put secure show_ime_with_hard_keyboard 1
# Crash/ANR dialogs (System UI ANRs under heavy host load) would steal the focus from the target app.
"${ADB[@]}" shell settings put global hide_error_dialogs 1
"${ADB[@]}" shell mkdir -p "$DEVICE_DIR"
"${ADB[@]}" shell "rm -f $DEVICE_DIR/ack-* $DEVICE_DIR/bridge"
"${ADB[@]}" logcat -G 16M >/dev/null 2>&1 || true

# --- facts --------------------------------------------------------------------------------------------------------
app_uid="$("${ADB[@]}" shell cmd package list packages -U "$PKG" | sed -n "s/^package:$PKG uid:\([0-9]*\).*/\1/p" | tr -d '\r')"
{
  echo "# Emulator end-to-end run"
  echo
  echo "- serial: $SERIAL ($("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'), SDK $("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "- avd: $("${ADB[@]}" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  echo "- wm size: $("${ADB[@]}" shell wm size | tr -d '\r' | paste -sd ' ')"
  echo "- MemTotal: $("${ADB[@]}" shell cat /proc/meminfo | awk '/MemTotal/{print $2" kB"}')"
  echo "- cpu: $("${ADB[@]}" shell cat /proc/cpuinfo | grep -m1 'model name' | cut -d: -f2- | tr -d '\r')"
  echo "- app uid: $app_uid; commit: $(git -C "$root" rev-parse --short HEAD)$(git -C "$root" diff --quiet || echo ' (+ local changes)')"
  echo "- suites: $SUITES"
  echo
  echo "| Suite | Result | Tests |"
  echo "|---|---|---|"
} > "$summary"

# --- helpers ------------------------------------------------------------------------------------------------------
start_bridge() {
  [ -n "$bridge_pid" ] && return
  "$root/tools/e2e/host_bridge.sh" "$SERIAL" > "$out/host_bridge.txt" 2>&1 &
  bridge_pid=$!
  for _ in $(seq 1 50); do
    [ "$("${ADB[@]}" shell ls "$DEVICE_DIR/bridge" 2>/dev/null | tr -d '\r')" = "$DEVICE_DIR/bridge" ] && return
    sleep 0.2
  done
  die "host bridge did not come up (see $out/host_bridge.txt)"
}

app_files() { "${ADB[@]}" shell run-as "$PKG" "$@"; }

fake_mode() { # on|off
  "${ADB[@]}" shell am force-stop "$PKG"
  if [ "$1" = on ]; then
    app_files mkdir -p files
    app_files touch files/debug-fake-engines
    # The WAV capture must exist before :main builds its graph (the tests rewrite it, too).
    python3 -I - "$out/tone.wav" <<'PY'
import math, struct, sys
n, s0, s1 = 4000 * 16, 500 * 16, 3500 * 16
pcm = b"".join(struct.pack("<h", int(3000 * math.sin(i * 2 * math.pi * 220 / 16000)) if s0 <= i < s1 else 0) for i in range(n))
hdr = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16) + b"data" + struct.pack("<I", len(pcm))
open(sys.argv[1], "wb").write(hdr + pcm)
PY
    "${ADB[@]}" push "$out/tone.wav" "$DEVICE_DIR/tone.wav" >/dev/null
    "${ADB[@]}" shell chmod 644 "$DEVICE_DIR/tone.wav"
    app_files cp "$DEVICE_DIR/tone.wav" files/debug-capture.wav
  else
    app_files rm -f files/debug-fake-engines files/debug-capture.wav
  fi
}

# Total rx+tx bytes of the app uid in `dumpsys netstats detail` (every interface, every bucket).
net_bytes() {
  "${ADB[@]}" shell dumpsys netstats --poll >/dev/null 2>&1
  "${ADB[@]}" shell dumpsys netstats detail | tr -d '\r' | awk -v uid="$app_uid" '
    /uid=/ { inside = ($0 ~ ("uid=" uid "[^0-9]")) || ($0 ~ ("uid=" uid "$")) }
    inside && /rb=/ { for (i = 1; i <= NF; i++) { if ($i ~ /^rb=/) { sub("rb=", "", $i); t += $i } if ($i ~ /^tb=/) { sub("tb=", "", $i); t += $i } } }
    END { print t + 0 }'
}

result_row() { # suite status detail
  echo "| $1 | $2 | $3 |" >> "$summary"
  log "$1: $2 ($3)"
}

overall=0
instrument() { # suite runner-pkg class[,class] [extra am args...]
  local suite="$1" runner="$2" classes="$3"; shift 3
  local file="$out/$suite.instrument.txt"
  "${ADB[@]}" logcat -c
  log "$suite: am instrument -e class $classes"
  "${ADB[@]}" shell am instrument -w -e class "$classes" "$@" "$runner/androidx.test.runner.AndroidJUnitRunner" > "$file" 2>&1
  "${ADB[@]}" logcat -d -v threadtime > "$out/$suite.logcat.txt" 2>&1
  grep -h ' E2E \| E2E-HOST \| TGT ' "$out/$suite.logcat.txt" | grep -v 'text=' > "$out/$suite.e2e-log.txt" || true
  grep -o 'E2E *: report .*' "$out/$suite.logcat.txt" | sed 's/^E2E *: report /- /' >> "$out/reports.md" || true
  local ok tests
  ok="$(grep -E '^OK \(' "$file" | tail -1)"
  tests="$(grep -E '^(OK \(|Tests run:)' "$file" | tail -1)"
  if [ -n "$ok" ]; then result_row "$suite" PASS "$ok"; return 0; fi
  result_row "$suite" FAIL "${tests:-no result} (see $suite.instrument.txt)"
  grep -E '^[0-9]+\) |^(java|junit|org\.junit)[^ ]*(Error|Exception)' "$file" | head -20 | sed 's/^/    /'
  overall=1
  return 1
}

# --- suites -------------------------------------------------------------------------------------------------------
if has_suite harness; then
  start_bridge
  instrument harness-target "$TARGET_PKG.test" "app.murmur.testtarget.TargetAppTest"
  fake_mode off
  before=$(net_bytes)
  instrument harness "$PKG.test" "app.murmur.e2e.EmulatorHarnessTest,app.murmur.e2e.PrivacyManifestTest" -e e2e.bridge 1
  after=$(net_bytes)
  if [ "$after" -eq "$before" ]; then result_row harness-network PASS "app uid $app_uid: $before -> $after bytes"
  else result_row harness-network FAIL "app uid $app_uid: $before -> $after bytes"; overall=1; fi
fi

if has_suite fast; then
  fake_mode on
  instrument fast "$PKG.test" "app.murmur.e2e.FastE2eTest"
fi

if has_suite matrix; then
  fake_mode on
  instrument matrix "$PKG.test" "app.murmur.e2e.TargetMatrixE2eTest"
fi

if has_suite events; then
  start_bridge
  fake_mode on
  instrument events "$PKG.test" "app.murmur.e2e.PlatformEventsE2eTest" -e e2e.bridge 1
fi

if has_suite privacy; then
  fake_mode off
  before=$(net_bytes)
  instrument privacy "$PKG.test" "app.murmur.e2e.PrivacyManifestTest"
  after=$(net_bytes)
  if [ "$after" -eq "$before" ]; then result_row privacy-network PASS "app uid $app_uid: $before -> $after bytes"
  else result_row privacy-network FAIL "app uid $app_uid: $before -> $after bytes"; overall=1; fi
fi

if has_suite real; then
  start_bridge
  fake_mode off
  python3 -I "$root/tools/e2e/fleurs_clip.py" "$out/fleurs" || die "FLEURS clip"
  "${ADB[@]}" push "$out/fleurs/fleurs-ru.wav" "$DEVICE_DIR/fleurs-ru.wav" >/dev/null
  "${ADB[@]}" push "$out/fleurs/fleurs-ru.txt" "$DEVICE_DIR/fleurs-ru.txt" >/dev/null
  "${ADB[@]}" shell chmod 644 "$DEVICE_DIR/fleurs-ru.wav" "$DEVICE_DIR/fleurs-ru.txt"
  python3 -I "$root/tools/e2e/push_models.py" "$SERIAL" "${REAL_ARTIFACTS[@]}" | tee "$out/push_models.txt" || die "model push"
  before=$(net_bytes)
  instrument real "$PKG.test" "app.murmur.e2e.RealE2eTest" -e e2e.timeoutScale "$TIMEOUT_SCALE"
  after=$(net_bytes)
  if [ "$after" -eq "$before" ]; then result_row real-network PASS "app uid $app_uid: $before -> $after bytes after the model push"
  else result_row real-network FAIL "app uid $app_uid: $before -> $after bytes after the model push"; overall=1; fi
  # D9: no app log line may contain the transcript. The target app logs its own field (other uid): excluded by uid.
  "${ADB[@]}" logcat -d -b main,system,crash -v uid > "$out/real.logcat-uid.txt" 2>&1
  words=$(tr ' ' '\n' < "$out/fleurs/fleurs-ru.txt" | awk 'length($0) >= 5')
  app_user="u0_a$((app_uid % 100000 - 10000))"
  leaks=0
  for w in $words; do
    if grep -E "^[^ ]+ +[^ ]+ +($app_user|$app_uid) " "$out/real.logcat-uid.txt" | grep -qiF -- "$w"; then
      leaks=$((leaks + 1)); echo "transcript word in an app log line: ${#w} letters" >> "$out/privacy-leaks.txt"
    fi
  done
  nwords=$(echo "$words" | wc -w)
  if [ "$leaks" -eq 0 ]; then result_row real-log-privacy PASS "0 of $nwords reference words in app log lines"
  else result_row real-log-privacy FAIL "$leaks of $nwords reference words in app log lines"; overall=1; fi
fi

{ echo; echo "## Reported values"; echo; cat "$out/reports.md" 2>/dev/null; } >> "$summary"
log "report: $summary"
cat "$summary"
exit "$overall"
