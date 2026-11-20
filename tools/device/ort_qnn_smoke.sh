#!/usr/bin/env bash
# DEVICE_CHECKLIST DC4, WP-21 part: the app's ORT QNN plugin EP path (OCR det/rec contexts, GigaAM v3 CTC 20 s context)
# on the phone (Galaxy Z Fold7, SM8750), NPU vs CPU, as one compact table.
#   tools/device/ort_qnn_smoke.sh [serial]        build (QNN), install, push, run, print the table
#   tools/device/ort_qnn_smoke.sh --dry-run       no device: check the host inputs and print the table skeleton
#   SKIP_BUILD=1 / KEEP_ON_DEVICE=1 / CTX=<native/qnn-contexts/compile.sh output dir> / GOLDEN_DIR=<pushed ml/ocr/golden>
# What runs: :infer:asr's instrumented OrtQnnDeviceTest#deviceSmoke (the app's own Kotlin code: QnnEp, QnnDetector/
# QnnRecognizer, GigaAmFrontEnd + GigaAmCtcModel behind AsrService), from a local -Pmurmur.qnn=true test APK (QNN 2.52
# libraries + com.qualcomm.qti:onnxruntime-android-qnn 2.7.0; ADR-048: never redistributed).
#   OCR: the 4 bundled goldens (or GOLDEN_DIR): per page QNN ms vs CPU ms (det ms, rec ms per line), binary-map (prob > 0.2)
#        and argmax agreement of every QNN call with the CPU call, README tolerance of the QNN output.
#   GigaAM: the 12 FLEURS ru clips through AsrService with the VAD: NPU artifact vs the CPU artifact (sherpa int8 CTC),
#        ms from the first byte to the transcript, WER.
# Pass criteria (DC4): binmap_agree and argmax_agree 1.000000, qnn_ok true (or the one-stage row for the stage to adopt),
# GigaAM NPU WER = CPU WER +- 1 pp; adopt only if clearly faster (ADR-059). On the x86_64 HTP simulator the fp16 detector
# failed this (binmap 0.99988; 3 of 26 pages outside the README tolerance): expect the det rows to fail here too. Needs on the host: adb, python3, the contexts (default <main>/.cache/spikes/wp-21/ctx) and the
# shared model cache (tools/models/fetch). Device files live in the test APK's files dir (run-as) and go with uninstall.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
common=$(git -C "$root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || echo "$root/.git")
main=$(dirname "$common")
CTX=${CTX:-$main/.cache/spikes/wp-21/ctx}
MODELS=${MURMUR_MODELS:-$main/.cache/models}
FLEURS=$root/native/qnn-contexts/fixtures/fleurs-ru
PKG=app.murmur.infer.asr.test
RUNNER=$PKG/androidx.test.runner.AndroidJUnitRunner
APK=$root/infer/asr/build/outputs/apk/androidTest/debug/asr-debug-androidTest.apk
ADB=${ADB:-${ANDROID_HOME:-$HOME/.local/share/android-sdk}/platform-tools/adb}
CPU_ARTIFACTS=(silero-vad-v5@onnx gigaam-v3-e2e-ctc@onnx-int8 pp-ocrv6-tiny-det@onnx pp-ocrv5-cyrillic-mobile-rec@onnx)
NPU_ARTIFACTS=(pp-ocrv6-tiny-det@qnn-sm8750 pp-ocrv5-cyrillic-mobile-rec@qnn-sm8750 gigaam-v3-e2e-ctc@qnn-sm8750-20s)
DRY=0
if [ "${1:-}" = --dry-run ]; then DRY=1; shift; fi
SERIAL=${1:-}

# Turns the `INSTRUMENTATION_STATUS: ortqnn={json}` lines on stdin into the table (no rows = the skeleton).
TABLE_PY=$(cat <<'EOF'
import json, sys
rows = []
key = 'INSTRUMENTATION_STATUS: ortqnn='
for line in sys.stdin:
    if line.startswith(key):
        rows.append(json.loads(line[len(key):]))
def by(kind, placeholder):
    return [r for r in rows if r['kind'] == kind] or ([placeholder] if placeholder is not None else [])
def f(r, k):
    return str(r.get(k, ''))
out = ['| stage | case | NPU (QNN) | CPU | agreement / accuracy |', '|---|---|---|---|---|']
for r in by('probe', None):
    out.append('| probe | units %s | qnnep %s %s | htp %s | qnn %s |' % (f(r, 'units'), f(r, 'qnnep'), f(r, 'qnnep_version'), f(r, 'htp'), f(r, 'qnn_build')))
for r in by('ocr-load', None):
    out.append('| ocr load | det+rec | %s ms | %s ms | |' % (f(r, 'qnn_ms'), f(r, 'cpu_ms')))
for r in by('ocr-page', {'page': '<page>', 'det_in': '<w x h>'}):
    out.append('| ocr page | %s %s (%s lines) | %s ms (det %s, rec/line %s) | %s ms (det %s, rec/line %s) | binmap %s argmax %s exact %s |' % (
        f(r, 'page'), f(r, 'det_in'), f(r, 'lines'), f(r, 'qnn_ms'), f(r, 'qnn_det_ms'), f(r, 'qnn_rec_ms_per_line'),
        f(r, 'cpu_ms'), f(r, 'cpu_det_ms'), f(r, 'cpu_rec_ms_per_line'), f(r, 'binmap_agree'), f(r, 'argmax_agree'), f(r, 'qnn_exact')))
for r in by('ocr-total', {'pages': '<n>'}):
    out.append('| ocr all | %s pages | tolerance %s, exact %s | tolerance %s, exact %s | binmap %s argmax %s |' % (
        f(r, 'pages'), f(r, 'qnn_ok'), f(r, 'qnn_exact'), f(r, 'cpu_ok'), f(r, 'cpu_exact'), f(r, 'binmap_agree'), f(r, 'argmax_agree')))
    out.append('| ocr one stage on NPU | det NPU + rec CPU / det CPU + rec NPU | tolerance %s, exact %s / tolerance %s, exact %s | | |' % (
        f(r, 'det_only_ok'), f(r, 'det_only_exact'), f(r, 'rec_only_ok'), f(r, 'rec_only_exact')))
for r in by('gigaam-load', None):
    out.append('| gigaam load | %s | %s %s | %s ms | |' % (f(r, 'artifact'), f(r, 'device'), f(r, 'unit'), f(r, 'ms')))
for r in by('gigaam-clip', {'clip': '<clip>'}):
    out.append('| gigaam | %s (%s frames) | %s ms | %s ms | errors NPU %s / CPU %s of %s words |' % (
        f(r, 'clip'), f(r, 'frames'), f(r, 'qnn_ms'), f(r, 'cpu_ms'), f(r, 'qnn_wer_err'), f(r, 'cpu_wer_err'), f(r, 'words')))
for r in by('gigaam-total', {'clips': '<n>'}):
    out.append('| gigaam all | %s clips, %s words | %s ms | %s ms | WER NPU %s %% / CPU %s %% |' % (
        f(r, 'clips'), f(r, 'words'), f(r, 'qnn_ms'), f(r, 'cpu_ms'), f(r, 'qnn_wer'), f(r, 'cpu_wer')))
print('\n'.join(out))
EOF
)
table() { python3 -I -c "$TABLE_PY"; }

check_inputs() { # prints one line per missing input; returns the count
  local missing=0 a
  for a in "${NPU_ARTIFACTS[@]}"; do
    [ -d "$CTX/$a" ] || { echo "missing: $CTX/$a (run: MURMUR_MEM=24G tools/heavy native/qnn-contexts/compile.sh)"; missing=$((missing + 1)); }
  done
  for a in "${CPU_ARTIFACTS[@]}"; do
    [ -f "$MODELS/$a/fetch.ok" ] || { echo "missing: $MODELS/$a (run: tools/models/fetch $a)"; missing=$((missing + 1)); }
  done
  [ -f "$FLEURS/mel_ref.json" ] || { echo "missing: $FLEURS"; missing=$((missing + 1)); }
  return $missing
}

if [ $DRY = 1 ]; then
  echo "ort_qnn_smoke dry run: no device; host inputs:"
  check_inputs && echo "all inputs present" || true
  echo "device steps: build $APK (-Pmurmur.qnn=true), install, push ${#CPU_ARTIFACTS[@]} CPU + ${#NPU_ARTIFACTS[@]} NPU artifacts and 12 clips (run-as),"
  echo "  am instrument -w -r -e class app.murmur.infer.asr.qnnep.OrtQnnDeviceTest#deviceSmoke -e ortQnnSmoke 1 $RUNNER"
  echo
  table < /dev/null
  exit 0
fi

check_inputs || { echo "inputs missing (above)" >&2; exit 2; }
A=("$ADB"); [ -n "$SERIAL" ] && A+=(-s "$SERIAL")
"${A[@]}" get-state >/dev/null 2>&1 || { echo "no device (adb ${SERIAL:-default})" >&2; exit 2; }
soc=$("${A[@]}" shell -n getprop ro.soc.model | tr -d '\r')
[ "$soc" = SM8750 ] || echo "warning: SoC '$soc' is not SM8750; the contexts are compiled for SM8750 (expect qnn errors)" >&2

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  "$root/tools/gw" --max-workers=6 :infer:asr:assembleDebugAndroidTest -Pmurmur.qnn=true \
    -Pmurmur.nativeLibsDir="${NATIVE_LIBS:-$main/.cache/native/jniLibs}"
fi
unzip -l "$APK" | grep -q 'lib/arm64-v8a/libonnxruntime_providers_qnn.so' || { echo "the test APK has no QNN EP plugin (build with -Pmurmur.qnn=true)" >&2; exit 2; }
"${A[@]}" install -r -t "$APK" >/dev/null

push_dir() { # <host dir> <device path relative to files/>
  local src=$1 dst=$2 rel size have
  "${A[@]}" shell -n "run-as $PKG mkdir -p files/$dst"
  while IFS= read -r rel; do
    size=$(stat -c %s "$src/$rel")
    have=$("${A[@]}" shell -n "run-as $PKG stat -c %s 'files/$dst/$rel' 2>/dev/null" | tr -d '\r' || true)
    [ "$have" = "$size" ] && continue
    "${A[@]}" shell "run-as $PKG sh -c 'cat > files/$dst/$rel'" < "$src/$rel"
    have=$("${A[@]}" shell -n "run-as $PKG stat -c %s 'files/$dst/$rel'" | tr -d '\r')
    [ "$have" = "$size" ] || { echo "copy failed: $dst/$rel ($have != $size bytes)" >&2; exit 1; }
    echo "pushed $dst/$rel ($size bytes)" >&2
  done < <(cd "$src" && find . -maxdepth 1 -type f ! -name fetch.ok ! -name '*.part' -printf '%P\n' | sort)
}
for a in "${CPU_ARTIFACTS[@]}"; do push_dir "$MODELS/$a" "models/$a"; done
for a in "${NPU_ARTIFACTS[@]}"; do push_dir "$CTX/$a" "models/$a"; done
push_dir "$FLEURS" ortqnn/fleurs-ru

extra=()
[ -n "${GOLDEN_DIR:-}" ] && extra+=(-e goldenDir "$GOLDEN_DIR")
ts=$(date +%Y%m%d-%H%M%S)
out=$main/.cache/device/ort_qnn_${soc:-unknown}_$ts.txt
mkdir -p "$(dirname "$out")"
"${A[@]}" shell -n am instrument -w -r -e class app.murmur.infer.asr.qnnep.OrtQnnDeviceTest#deviceSmoke -e ortQnnSmoke 1 \
  "${extra[@]}" "$RUNNER" | tr -d '\r' > "$out" || true
grep -E 'INSTRUMENTATION_(RESULT|CODE)|FAILURES|OK \(' "$out" | tail -3 >&2 || true
table < "$out" | tee "${out%.txt}.md"
if [ "${KEEP_ON_DEVICE:-0}" != 1 ]; then "${A[@]}" shell -n "run-as $PKG rm -rf files/models files/ortqnn"; fi
grep -q '^OK (' "$out" || { echo "deviceSmoke failed: see $out" >&2; exit 1; }
