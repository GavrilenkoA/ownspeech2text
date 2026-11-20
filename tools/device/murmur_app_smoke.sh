#!/usr/bin/env bash
# DEVICE_CHECKLIST DC8: the app end to end on the Galaxy Z Fold7 with the NPU build (WP-20). One pass, ~30 min.
#
#   tools/device/murmur_app_smoke.sh [--apk <app-debug.apk>] [--pack recommended]
#
#   env ANDROID_SERIAL   the phone (needed when more than one device is attached)
#       NONINTERACTIVE=1 no one speaks: every case plays a FLEURS WAV through the debug capture (emulator dry run)
#       SKELETON=1       print the report skeleton (every row and assert this pass fills) and exit; no device needed
#       DC8_ASSUME_PHONE=1  treat an emulator as the phone (only to exercise the NPU gate's refusal on this server)
#
# What it does: refuses an APK without the Qualcomm NPU libraries (prints the build recipe); installs the app, its
# androidTest APK (the on-device steps, app/src/androidTest/.../e2e/DeviceSmokeSteps.kt) and the test target; enables
# the accessibility service through adb; records device and probe facts; downloads the recommended pack in the app
# (Preparing proves every NPU load); then the scripted dictations, all in one run so that the idle exit is real:
#   ru10 (≈10 s Russian), en10 (≈10 s English), mixed60 (≈60 s mixed), cold3 (first dictation after 70 s idle, 3 s),
#   edit10 (right after a prompt edit: a sentence added to "My preferences", restored afterwards; the report asserts the
#   cache prefix's sha256 changed), npu10 (LLM pinned to NPU while :asr holds its QNN context),
#   tmo10 (timeouts forced short, debugTimeoutScale 0.1: the dictation must still end, and the client's
#   "process still alive ... killing" lines are counted),
# then checks that :asr and :llm exit within 70 s and MemAvailable comes back, then presses once more and cancels while
# :asr is still being bound or loaded (:asr must be gone idleUnloadSec + 2 s later). It also runs WP-04's prompt/guard
# goldens on ART (core/prompt/tools/run_on_art.sh, when this tree has it) and records mic start times, the history codec
# probe and the client kill lines from logcat. Per dictation it asserts ASR deviceName
# = qnn, LLM deviceName = HTP0, no fallback in UnitHealth, no process death, the history audio stored as Opus, and
# records timings from history, PSS of the three processes (/proc/<pid>/smaps_rollup, every 2 s) and MemAvailable.
# After en10, with :asr and :llm warm, it probes (WP-09) three capture stop() calls with keepRecordings on (< 200 ms,
# Opus) and (WP-06) en10's recording replayed into a new session of the loaded ASR: finish() within 3 s of the end of
# audio, Transcript.unit = the loaded unit, and NPU on the phone.
# On an emulator (dry run: NONINTERACTIVE=1, CPU build, GigaAM + Qwen3.5 pushed from the shared model cache and then
# prepared in the app, VAD + OCR downloaded in the app) the NPU asserts are SKIPPED and npu10 is skipped. The dry run's
# audio is FLEURS dev speech (CC-BY-4.0): one ru and one en clip, and 60 s of distinct ru/en utterances for mixed60.
# Report: .cache/device-reports/dc8-<serial>-<time>/report.md + report.json (+ the step JSONs, meminfo.csv, logcat).
# Exit 0 = every assert that applies passed; 3 = APK without the NPU libraries; 1 = an assert failed.
set -uo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
export ANDROID_HOME
APK="$root/app/build/outputs/apk/debug/app-debug.apk"
PACK="recommended"
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --pack) PACK="$2"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
TEST_APK="$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
TARGET_APK="$root/testapps/target/build/outputs/apk/debug/target-debug.apk"
PKG=app.murmur
# MurmurTestRunner = AndroidJUnitRunner + the shell tests' -e murmurFake switch (not passed here: the real container).
RUNNER="$PKG.test/app.murmur.shell.MurmurTestRunner"
STEPS=app.murmur.e2e.DeviceSmokeSteps
DEVICE_DIR=/data/local/tmp/murmur-e2e
NPU_LIBS=(lib/arm64-v8a/libQnnHtp.so lib/arm64-v8a/libggml-htp-v79.so)
CASES_PHONE="ru10:10,en10:10:probe,mixed60:60,cold3:3:idle70,edit10:10:edit,npu10:10:pinnpu,tmo10:10:scale0.1"
CASES_EMULATOR="ru10:10,en10:10:probe,mixed60:60,cold3:3:idle70,edit10:10:edit,tmo10:10:scale0.1"
EMU_PUSHED=(gigaam-v3-e2e-rnnt@onnx-int8 qwen3.5-0.8b@q4_0)
EMU_DOWNLOADED=silero-vad-v5@onnx,pp-ocrv6-tiny-det@onnx,pp-ocrv5-cyrillic-mobile-rec@onnx

# The scripted texts the owner reads (the inserted text is compared by eye in the report).
declare -A SAY=(
  [ru10]="Привет, Лёша. Давай созвонимся завтра в десять утра и обсудим новый план по проекту."
  [en10]="Hi Anna, can we move the review to Friday afternoon? I still need to finish the budget slides."
  [mixed60]="Speak for a minute about your week, switching between Russian and English, e.g.: Сегодня утром был созвон по roadmap, потом я писал release notes, а вечером review с командой про deadline на следующей неделе."
  [cold3]="Короткое сообщение: уже еду."
  [edit10]="Напомни мне, пожалуйста, купить молоко, хлеб и кофе по дороге домой."
  [npu10]="This is the forced NPU run: the cleanup model and the speech model share the NPU."
  [tmo10]="Короткие тайм-ауты: этот текст может вставиться без обработки, это нормально."
)

render_report() { # <out dir> <mode: skeleton|emulator|phone>
  python3 -I - "$1" "$2" <<'PY'
import json, os, sys, csv
out, mode = sys.argv[1], sys.argv[2]
cases = ["ru10", "en10", "mixed60", "cold3", "edit10", "npu10", "tmo10"]
def load(n):
    p = os.path.join(out, n)
    return json.load(open(p)) if os.path.isfile(p) else None
facts, prep, idle, cancel = load("facts.json"), load("prepare.json"), load("idle.json"), load("cancel.json")
logs = load("logcat-facts.json") or {}
pss = {}
if os.path.isfile(os.path.join(out, "meminfo.csv")):
    for r in csv.DictReader(open(os.path.join(out, "meminfo.csv"))):
        c = pss.setdefault(r["case"], {"main": 0, "asr": 0, "llm": 0, "memAvailMin": 10**9})
        for k in ("main", "asr", "llm"):
            c[k] = max(c[k], int(r[k] or 0))
        c["memAvailMin"] = min(c["memAvailMin"], int(r["memAvailableKb"] or 10**9))
rows, failed = [], 0
def check(case, name, ok, value):
    global failed
    status = "pending" if mode == "skeleton" else ("SKIPPED" if ok is None else ("PASS" if ok else "FAIL"))
    if status == "FAIL":
        failed += 1
    rows.append({"case": case, "check": name, "status": status, "value": value})
npu = mode == "phone"
if facts is None and mode != "skeleton":
    check("facts", "facts step ran", False, "no facts.json (see step-facts.txt)")
else:
    f = facts or {}
    units = (f.get("profile") or {}).get("units", [])
    check("facts", "NPU unit available (profile)", ("NPU" in units) if npu else None, units)
    check("facts", "probe facts (HTP arch, QNN build, CPU variant, native.build)", True if facts else None,
          {"asr": (f.get("asrProbe") or {}).get("facts"), "llm": (f.get("llmProbe") or {}).get("facts")})
if prep is None and mode != "skeleton":
    check("prepare", "prepare step ran", False, "no prepare.json (see step-prepare.txt)")
else:
    for a in (prep or {}).get("artifacts", []) or [{"artifact": "<each pack artifact>"}]:
        st = a.get("state")
        check("prepare", f"{a['artifact']} installed after Preparing", None if st is None else st == "Installed",
              {k: a.get(k) for k in ("state", "error", "downloadMs", "preparingMs", "health")})
for c in cases:
    d = load(f"dictate-{c}.json")
    probe = load(f"probe-{c}.json")
    skipped_case = mode == "emulator" and c == "npu10"
    if d is None and mode != "skeleton":
        check(c, "dictation ran", None if skipped_case else False, "no result")
        continue
    d = d or {}
    asr, llm = d.get("asr") or {}, d.get("llm") or {}
    if c == "tmo10":
        # Forced short timeouts: any end is right (raw text, Copied, or Failed with the audio kept); a hang is not.
        check(c, "forced timeouts: the dictation ended (no hang)", None if mode == "skeleton" else d.get("outcome") is not None,
              {"outcome": d.get("outcome"), "cleanup": d.get("cleanupOutcome"), "skip": d.get("skipReason"), "error": d.get("error"),
               "timeoutScale": d.get("timeoutScale"), "clientKills": logs.get("clientKills")})
        continue
    # The pipeline check: the text was inserted and the cleanup model ran to a guard verdict. "accept" and "fallback:*"
    # (the guard kept the raw text: a judgement of the model's output, recorded for the owner) both pass; "skipped:*"
    # means cleanup never got to run (LLM not ready, died, failed, timed out) and fails.
    verdict = d.get("cleanupOutcome") or ""
    check(c, "outcome Inserted, cleanup ran (guard verdict)", None if mode == "skeleton" else
          (d.get("outcome") == "Inserted" and verdict.split(":")[0] in ("accept", "fallback")),
          {"outcome": d.get("outcome"), "cleanup": d.get("cleanupOutcome"), "skip": d.get("skipReason"), "error": d.get("error")})
    check(c, "ASR deviceName = qnn", (asr.get("deviceName") == "qnn") if npu else None, asr.get("deviceName"))
    check(c, "LLM deviceName = HTP0", (llm.get("deviceName") == "HTP0") if npu else None, llm.get("deviceName"))
    check(c, "no fallback, no failed unit, no process death", None if mode == "skeleton" else
          (not asr.get("fellBack") and not llm.get("fellBack") and not d.get("unitHealthFailed") and d.get("asrCrashes", 0) == 0 and d.get("llmCrashes", 0) == 0),
          {"fellBack": [asr.get("fellBack"), llm.get("fellBack")], "failedUnits": d.get("unitHealthFailed"), "crashes": [d.get("asrCrashes"), d.get("llmCrashes")]})
    if c == "cold3":
        check(c, "cold: :asr and :llm had exited after 70 s idle", None if mode == "skeleton" else
              (d.get("asrRunningBefore") is False and d.get("llmRunningBefore") is False), [d.get("asrRunningBefore"), d.get("llmRunningBefore")])
    check(c, "history audio stored as Opus (WP-09 detected_codec=OPUS)", None if mode == "skeleton" else d.get("audioCodec") == "opus",
          d.get("audioCodec"))
    if c == "edit10":
        e = d.get("edit") or {}
        check(c, "the prompt edit changed the cache prefix (sha256 before != after)", None if mode == "skeleton" else e.get("prefixChanged") is True,
              {"before": e.get("prefixShaBefore"), "after": e.get("prefixShaAfter"),
               "llmReadyAfterPressMs": d.get("llmReadyAfterPressMs"), "previousWarmLlmReadyAfterPressMs": e.get("previousWarmLlmReadyAfterPressMs")})
    if c == "en10":
        if probe is None and mode != "skeleton":
            check(c, "probe ran (capture stop, ASR session)", False, "no probe-en10.json (see step-dictate.txt)")
        else:
            cs, a = (probe or {}).get("captureStop") or {}, (probe or {}).get("asrSession") or {}
            stops = cs.get("stopMs") or []
            check(c, "capture stop() < 200 ms, keepRecordings on, :asr and :llm running (WP-09)", None if mode == "skeleton" else
                  (bool(stops) and max(stops) < 200 and cs.get("asrRunning") is True and cs.get("llmRunning") is True and not cs.get("error")),
                  {k: cs.get(k) for k in ("stopMs", "asrRunning", "llmRunning", "error")})
            check(c, "capture probe files stored as Opus", None if mode == "skeleton" else cs.get("codecs") == ["opus"], cs.get("codecs"))
            check(c, "ASR session: Transcript.unit = the loaded unit", None if mode == "skeleton" else
                  (a.get("transcriptUnit") is not None and a.get("transcriptUnit") == a.get("loadedUnit")),
                  {k: a.get(k) for k in ("loadedUnit", "transcriptUnit", "asrArtifact", "error")})
            # ADR-076: the emulator's ASR timing counts only with the host CPU model, which the dry run uses.
            check(c, "ASR session: finish() <= 3 s after the end of audio (WP-06)", None if mode == "skeleton" else
                  (a.get("transcriptUnit") is not None and (a.get("finishAfterEofMs") or 10**9) <= 3000),
                  {k: a.get(k) for k in ("finishAfterEofMs", "tailDecodeMs", "audioMs", "words", "historyWords")})
            check(c, "ASR on the NPU: Transcript.unit = NPU (Parakeet through AsrService, WP-06)", (a.get("transcriptUnit") == "NPU") if npu else None,
                  a.get("transcriptUnit"))
    if c == "npu10":
        check(c, "LLM on NPU and ASR on QNN at the same time", (asr.get("deviceName") == "qnn" and llm.get("deviceName") == "HTP0") if npu else None,
              [asr.get("deviceName"), llm.get("deviceName")])
    t = d.get("timings") or {}
    p = pss.get(c, {})
    rows.append({"case": c, "check": "measured", "status": "info", "value": {
        "stopToDoneMs": t.get("stopToDoneMs"), "asrTailMs": t.get("asrTailMs"), "llmMs": t.get("llmMs"), "ocrMs": t.get("ocrMs"),
        "asrLoadMs": asr.get("loadMs"), "llmLoadMs": llm.get("loadMs"), "prefixTokens": llm.get("prefixTokens"),
        "llmWarmAtPress": d.get("llmWarmAtPress"), "llmReadyAfterPressMs": d.get("llmReadyAfterPressMs"),
        "pssPeakKb": {k: p.get(k) for k in ("main", "asr", "llm")}, "memAvailableMinKb": p.get("memAvailMin"),
        "memAvailableBeforeMb": d.get("memAvailableBeforeMb"), "insertMethod": d.get("insertMethod"),
        "promptVersion": d.get("promptVersion"), "cleanupTone": d.get("cleanupTone"), "inserted": d.get("insertedText")}})
if idle is None and mode != "skeleton":
    check("idle", "idle check ran", False, "no idle.json (see step-dictate.txt)")
else:
    i = idle or {}
    check("idle", ":asr and :llm exited within 70 s of the last Done", None if mode == "skeleton" else
          (i.get("asrRunning") is False and i.get("llmRunning") is False),
          {"asrExitMs": i.get("asrExitAfterDoneMs"), "llmExitMs": i.get("llmExitAfterDoneMs"), "memAvailableMb": i.get("memAvailableMb")})
if cancel is None and mode != "skeleton":
    check("cancel", "cancel check ran", False, "no cancel.json (see step-dictate.txt)")
else:
    k = cancel or {}
    check("cancel", ":asr gone idleUnloadSec + 2 s after a cancel during Arming / ASR load", None if mode == "skeleton" else
          k.get("asrRunningAfter") is False,
          {k2: k.get(k2) for k2 in ("cancelledIn", "idleUnloadSec", "asrExitAfterCancelMs", "asrRunningAfter", "llmRunningAfter", "historyRows")})
art = load("art.json")
if art is None and mode != "skeleton":
    check("art", "prompt + guard goldens on ART ran", False, "no art.json")
else:
    a = art or {}
    check("art", "prompt + guard goldens on ART (core/prompt/tools/run_on_art.sh)", None if mode == "skeleton" or a.get("skipped") else a.get("ok"),
          a.get("result") or a.get("skipped"))
rows.append({"case": "logs", "check": "measured", "status": "info", "value": {
    "micStartedMs": logs.get("micStartedMs"), "codecProbe": logs.get("codecProbe"), "clientKills": logs.get("clientKills")}})
report = {"mode": mode, "facts": facts, "rows": rows, "failed": failed}
json.dump(report, open(os.path.join(out, "report.json"), "w"), ensure_ascii=False, indent=2)
with open(os.path.join(out, "report.md"), "w") as md:
    md.write(f"# DC8 app smoke ({mode})\n\n")
    if facts:
        md.write(f"- device: {facts.get('model')} / {facts.get('soc')} / SDK {facts.get('sdk')} / {facts.get('abi')}; app {facts.get('appVersion')}\n")
        md.write(f"- fingerprint: {facts.get('fingerprint')}\n\n")
    md.write("| Case | Check | Status | Value |\n|---|---|---|---|\n")
    for r in rows:
        v = json.dumps(r["value"], ensure_ascii=False) if not isinstance(r["value"], str) else r["value"]
        md.write(f"| {r['case']} | {r['check']} | {r['status']} | {v} |\n")
    md.write(f"\nFailed asserts: {failed}\n")
print(open(os.path.join(out, "report.md")).read())
sys.exit(1 if failed else 0)
PY
}

stamp="$(date +%Y%m%d-%H%M%S)"
if [ "${SKELETON:-0}" = 1 ]; then
  out="$root/.cache/device-reports/dc8-skeleton-$stamp"; mkdir -p "$out"
  render_report "$out" skeleton
  exit $?
fi

ADB_BIN="$ANDROID_HOME/platform-tools/adb"
if [ -z "${ANDROID_SERIAL:-}" ]; then
  mapfile -t devs < <("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1}')
  [ "${#devs[@]}" -eq 1 ] || { echo "need exactly one device (found ${#devs[@]}); set ANDROID_SERIAL" >&2; exit 2; }
  ANDROID_SERIAL="${devs[0]}"
fi
ADB=("$ADB_BIN" -s "$ANDROID_SERIAL")
[ "$("${ADB[@]}" get-state 2>/dev/null)" = device ] || { echo "$ANDROID_SERIAL is not online" >&2; exit 2; }
emulator=0
[ "$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$("${ADB[@]}" shell getprop ro.boot.qemu | tr -d '\r')" = 1 ] && emulator=1
[ "${DC8_ASSUME_PHONE:-0}" = 1 ] && emulator=0
mode=phone; [ "$emulator" = 1 ] && mode=emulator
out="$root/.cache/device-reports/dc8-$ANDROID_SERIAL-$stamp"; mkdir -p "$out"
echo "DC8 on $ANDROID_SERIAL ($mode); report in $out"

# --- the NPU gate -------------------------------------------------------------------------------------------------
for f in "$APK" "$TEST_APK" "$TARGET_APK"; do
  [ -f "$f" ] || { echo "missing $f: tools/gw --max-workers=6 :app:assembleDebug :app:assembleDebugAndroidTest :testapps:target:assembleDebug" >&2; exit 2; }
done
listing="$(unzip -l "$APK")"
missing=()
for lib in "${NPU_LIBS[@]}"; do grep -q " $lib\$" <<<"$listing" || missing+=("$lib"); done
if [ "${#missing[@]}" -gt 0 ]; then
  if [ "$emulator" = 0 ]; then
    cat >&2 <<EOF
This APK has no Qualcomm NPU libraries (${missing[*]}): DC8 needs the local, non-redistributed NPU build (ADR-048):
  HEXAGON_SDK_DIR=.cache/spikes/llm-npu/work/hexagon-sdk tools/heavy native/llama/build_android.sh npu jnilibs
  tools/heavy native/sherpa/build-android.sh all .cache/native/jniLibs/sherpa
  tools/gw --max-workers=6 :app:assembleDebug :app:assembleDebugAndroidTest :testapps:target:assembleDebug \\
      -Pmurmur.qnn=true -Pmurmur.htp=true -Pmurmur.nativeLibsDir=\$PWD/.cache/native/jniLibs
then run this script again.
EOF
    exit 3
  fi
  echo "NPU libraries: SKIPPED on the emulator (CPU build; missing ${missing[*]})"
else
  echo "NPU libraries: present (${NPU_LIBS[*]})"
fi
if [ "$emulator" = 1 ] && [ "${NONINTERACTIVE:-0}" != 1 ]; then
  echo "an emulator cannot be spoken to: run the dry run with NONINTERACTIVE=1" >&2; exit 2
fi

# --- install + enable ---------------------------------------------------------------------------------------------
for f in "$APK" "$TEST_APK" "$TARGET_APK"; do "${ADB[@]}" install -r -t "$f" > /dev/null || { echo "install $f failed" >&2; exit 2; }; done
if [ "${#missing[@]}" -eq 0 ]; then
  libdir="$("${ADB[@]}" shell pm dump "$PKG" | sed -n 's/^ *legacyNativeLibraryDir=//p' | head -1 | tr -d '\r')"
  for lib in "${NPU_LIBS[@]}"; do
    "${ADB[@]}" shell ls "$libdir/arm64/$(basename "$lib")" > /dev/null 2>&1 || { echo "installed app lacks $(basename "$lib") in $libdir/arm64" >&2; exit 3; }
  done
fi
"${ADB[@]}" shell pm grant "$PKG" android.permission.RECORD_AUDIO
"${ADB[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
"${ADB[@]}" shell settings put secure enabled_accessibility_services "$PKG/app.murmur.platform.a11y.MurmurAccessibilityService"
"${ADB[@]}" shell settings put secure accessibility_enabled 1
"${ADB[@]}" shell mkdir -p "$DEVICE_DIR/dc8"
"${ADB[@]}" logcat -G 16M > /dev/null 2>&1 || true

step() { # <name> <am instrument args...>: runs one DeviceSmokeSteps step, pulls its JSON files
  local name="$1"; shift
  echo "== step $name"
  "${ADB[@]}" shell am instrument -w -e class "$STEPS" "$@" "$RUNNER" > "$out/step-$name.txt" 2>&1
  local rc=0
  grep -q '^OK (1 test)' "$out/step-$name.txt" || { rc=1; tail -20 "$out/step-$name.txt"; }
  for j in $("${ADB[@]}" shell run-as "$PKG" ls files/dc8 2>/dev/null | tr -d '\r'); do
    "${ADB[@]}" shell run-as "$PKG" cat "files/dc8/$j" > "$out/$j"
  done
  return $rc
}

"${ADB[@]}" shell run-as "$PKG" rm -rf files/dc8 files/debug-fake-engines
step facts -e dc8.step facts || echo "facts step failed (report continues)"

# --- models -------------------------------------------------------------------------------------------------------
dictate_args=()
if [ "$emulator" = 1 ]; then
  # ADR-063: the 3 GB AVD runs GigaAM + Qwen3.5 on CPU. The big files come from the shared cache (no 1 GB download per
  # run); the small ones go through the in-app download job + Preparing, as on the phone.
  python3 -I "$root/tools/e2e/push_models.py" "$ANDROID_SERIAL" "${EMU_PUSHED[@]}" | tee "$out/push_models.txt" || exit 2
  step prepare -e dc8.step prepare -e dc8.artifacts "$EMU_DOWNLOADED" -e dc8.prepareInstalled "$(IFS=,; echo "${EMU_PUSHED[*]}")" \
    -e dc8.emulator 1 || echo "prepare step failed (report continues)"
  dictate_args+=(-e dc8.models gigaam-v3-e2e-rnnt,qwen3.5-0.8b -e dc8.emulator 1)
  cases="$CASES_EMULATOR"
else
  step prepare -e dc8.step prepare -e dc8.pack "$PACK" -e dc8.timeoutSec 3600 || echo "prepare step failed (report continues)"
  cases="$CASES_PHONE"
fi

# --- audio for the non-interactive run --------------------------------------------------------------------------
if [ "${NONINTERACTIVE:-0}" = 1 ]; then
  wav="$out/wav"; mkdir -p "$wav"
  for l in ru en mixed60; do python3 -I "$root/tools/e2e/fleurs_clip.py" "$wav" "$l" > /dev/null || exit 2; done
  python3 -I - "$wav" <<'PY'
import struct, sys, pathlib
d = pathlib.Path(sys.argv[1])
def pcm(name): return (d / name).read_bytes()[44:]
def wav(path, data):
    path.write_bytes(b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
                     + b"data" + struct.pack("<I", len(data)) + data)
ru, en, mixed = pcm("fleurs-ru.wav"), pcm("fleurs-en.wav"), pcm("fleurs-mixed60.wav")
for name, data in {"ru10": ru, "en10": en, "mixed60": mixed, "cold3": ru[:3 * 32000], "edit10": ru, "npu10": en, "tmo10": ru}.items():
    wav(d / f"{name}.wav", data)
PY
  for c in ru10 en10 mixed60 cold3 edit10 npu10 tmo10; do "${ADB[@]}" push "$wav/$c.wav" "$DEVICE_DIR/dc8/$c.wav" > /dev/null 2>&1; done
  "${ADB[@]}" shell chmod -R a+r "$DEVICE_DIR/dc8"
  dictate_args+=(-e dc8.wavDir "$DEVICE_DIR/dc8")
fi

# --- dictations + memory sampling ----------------------------------------------------------------------------------
"${ADB[@]}" logcat -c
echo "time_ms,case,main,asr,llm,memAvailableKb" > "$out/meminfo.csv"
current="-"
# PSS in kB from /proc/<pid>/smaps_rollup read as the app's uid: `dumpsys meminfo` would force a GC in the process
# every sample and disturb what is measured.
pss_of() {
  local pid; pid="$("${ADB[@]}" shell pidof "$1" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  [ -n "$pid" ] || return 0
  "${ADB[@]}" shell run-as "$PKG" cat "/proc/$pid/smaps_rollup" 2>/dev/null | awk '/^Pss:/{print $2; exit}'
}
sampler() {
  while :; do
    c="$(cat "$out/.case" 2>/dev/null || echo -)"
    m="$("${ADB[@]}" shell cat /proc/meminfo | awk '/MemAvailable/{print $2}' | tr -d '\r')"
    echo "$(date +%s%3N),$c,$(pss_of $PKG),$(pss_of $PKG:asr),$(pss_of $PKG:llm),$m" >> "$out/meminfo.csv"
    sleep 2
  done
}
echo "$current" > "$out/.case"
sampler & sampler_pid=$!
"${ADB[@]}" logcat -v raw -s E2E:I > "$out/steps-e2e.log" 2>/dev/null & log_pid=$!
"${ADB[@]}" shell am instrument -w -e class "$STEPS" -e dc8.step dictate -e dc8.cases "$cases" -e dc8.idleCheckSec 70 -e dc8.cancelCheck 1 \
  "${dictate_args[@]}" "$RUNNER" > "$out/step-dictate.txt" 2>&1 & inst_pid=$!
seen=0
while kill -0 "$inst_pid" 2>/dev/null; do
  while IFS= read -r line; do
    case "$line" in
      "dc8 speak case="*)
        c="${line#dc8 speak case=}"; c="${c%% *}"; echo "$c" > "$out/.case"
        [ "${NONINTERACTIVE:-0}" = 1 ] || printf '\n>>> %s: get ready to say (%s):\n    %s\n' "$c" "${line##*seconds=} s" "${SAY[$c]:-}" ;;
      "dc8 listening case="*) [ "${NONINTERACTIVE:-0}" = 1 ] || echo ">>> SPEAK NOW" ;;
      "dc8 done case="*) echo "    done: ${line#dc8 done case=}" ;;
    esac
  done < <(tail -n +$((seen + 1)) "$out/steps-e2e.log")
  seen=$(wc -l < "$out/steps-e2e.log")
  sleep 0.5
done
wait "$inst_pid"
kill "$sampler_pid" "$log_pid" 2>/dev/null; wait 2>/dev/null
rm -f "$out/.case"
grep -q '^OK (1 test)' "$out/step-dictate.txt" || { echo "dictate step failed:"; tail -20 "$out/step-dictate.txt"; }
for j in $("${ADB[@]}" shell run-as "$PKG" ls files/dc8 2>/dev/null | tr -d '\r'); do
  "${ADB[@]}" shell run-as "$PKG" cat "files/dc8/$j" > "$out/$j"
done
"${ADB[@]}" logcat -d -v threadtime > "$out/logcat.txt" 2>&1
# Codes and numbers from the app's own log lines (WP-05 client kills, WP-09 mic start and codec probe).
python3 -I - "$out" <<'PY'
import json, re, sys, os
out = sys.argv[1]
text = open(os.path.join(out, "logcat.txt"), errors="replace").read()
json.dump({
    "micStartedMs": [int(m) for m in re.findall(r"MurmurCapture *: mic_started ms=(\d+)", text)],
    "codecProbe": sorted(set(re.findall(r"codec_lag (\S+) samples=(\d+)", text))) or "not logged in this run (probed once per install)",
    "clientKills": len(re.findall(r"process still alive after \d+ ms; killing", text)),
}, open(os.path.join(out, "logcat-facts.json"), "w"))
PY

# --- WP-04: the prompt renderer and guard goldens on this device's ART/ICU ---------------------------------------
art="$root/core/prompt/tools/run_on_art.sh"
if [ -x "$art" ]; then
  echo "== step art"
  "$art" "$ANDROID_SERIAL" > "$out/art.txt" 2>&1
  ok="$(grep -E '^OK \(' "$out/art.txt" | tail -1)"
  if [ -n "$ok" ]; then python3 -I -c 'import json,sys; json.dump({"ok": True, "result": sys.argv[2]}, open(sys.argv[1], "w"))' "$out/art.json" "$ok"
  else python3 -I -c 'import json,sys; json.dump({"ok": False, "result": sys.argv[2]}, open(sys.argv[1], "w"))' "$out/art.json" "$(grep -E '^Tests run:|FAIL' "$out/art.txt" | tail -1)"; fi
else
  python3 -I -c 'import json,sys; json.dump({"skipped": "core/prompt/tools/run_on_art.sh is not in this tree (WP-04 not merged)"}, open(sys.argv[1], "w"))' "$out/art.json"
fi
render_report "$out" "$mode"
