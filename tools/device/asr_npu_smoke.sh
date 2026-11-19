#!/usr/bin/env bash
# ASR + OCR NPU smoke test for the owner's phone (Galaxy Z Fold7 / SM8750). Plug the phone in (USB debugging on), then:
#   tools/device/asr_npu_smoke.sh               # prepare artifacts on the host (cached), push, run, print report
#   SKIP_PREPARE=1 tools/device/asr_npu_smoke.sh
#   ONLY=asr | ONLY=ocr                          # run one half
# ASR (sherpa-onnx CLI from native/sherpa/build-android.sh, arm64, QNN enabled, linked to the Maven ORT 1.32.0 lib):
#   Parakeet TDT 0.6B v3  : CPU int8 ONNX  vs  NPU (SM8750 context binary, 20 s window)
#   Nemotron 3.5 streaming: CPU int8 ONNX  vs  NPU (SM8750 context binary, 560 ms chunks), language=ru / en
#   GigaAM v3 RNN-T       : CPU int8 ONNX (Russian clips only)
#   Each NPU case with the QNN libs the binaries were built with (QAIRT 2.40.0.251030) and with
#   com.qualcomm.qti:qnn-runtime:2.52.0 (newer runtime loading older binaries = the app's real situation), cold + warm.
# OCR (ort_bench from .cache/spikes/asr-ocr/ortbench, ORT 1.32.0 + QNN plugin EP com.qualcomm.qti:onnxruntime-android-qnn:2.7.0):
#   PP-OCRv6_tiny_det + cyrillic_PP-OCRv5_mobile_rec on real screenshot tensors: CPU EP vs XNNPACK EP vs QNN HTP
#   (context binaries AOT-compiled on the dev host with QAIRT 2.52, fixed shapes 960x416 det / 48x320 rec, fp16).
#   Outputs are pulled back and compared with the host reference (max abs diff, argmax / binary-map agreement).
# Output: one JSON object per run on stdout + markdown summary; also $WORK/report-<ts>.{jsonl,md}
# Needs: adb, curl, unzip, tar, python3 (+numpy for OCR parity) on the host.
# First prepare downloads ~3.0 GB; ~2.3 GB is pushed to /data/local/tmp/murmur-asr (removed at the end unless KEEP_ON_DEVICE=1).
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${WORK:-$root/.cache/device-smoke}
SPIKE=$root/.cache/spikes/asr-ocr
# the app configuration: sherpa-onnx linked against the Maven ORT 1.32.0 libonnxruntime.so (one ORT per APK), i.e. the
# output of native/sherpa/build-android.sh (default workdir .cache/native/build/sherpa; SHERPA_OUT=<workdir>/out/arm64-v8a)
SHERPA_OUT=${SHERPA_OUT:-$root/.cache/native/build/sherpa/out/arm64-v8a}
ORTBENCH=${ORTBENCH:-$SPIKE/ortbench}      # ort_bench.cc, arm64-v8a/ort_bench, data/*.f32 (scripts/ortbench_inputs.py)
OCRQNN=${OCRQNN:-$SPIKE/ocrqnn}            # *_ctx.onnx + *_ctx_qnn.bin from scripts/ocr_qnn.py
OCRM=${OCRM:-$root/.cache/ml/ocr}          # PP-OCRv6_tiny_det_onnx/, cyrillic_PP-OCRv5_mobile_rec_onnx/
D=/data/local/tmp/murmur-asr
THREADS=${THREADS:-4}
ONLY=${ONLY:-all}
# Parakeet NPU binaries have a fixed input window (input padded to it, longer input truncated).
# Extra windows cost ~446 MB download each; e.g. PARAKEET_WINDOWS="5 20" to measure the per-call cost of a short window.
PARAKEET_WINDOWS=${PARAKEET_WINDOWS:-20}
ADB=${ADB:-adb}
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
HF=https://huggingface.co
MVN=https://repo1.maven.org/maven2
mkdir -p "$WORK"; cd "$WORK"

fetch() { [ -s "$2" ] || { echo "fetch $2" >&2; curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"; }; }

prepare() {
  [ -x "$SHERPA_OUT/bin/sherpa-onnx-offline" ] || { echo "missing $SHERPA_OUT; run: tools/heavy native/sherpa/build-android.sh arm64 (or set SHERPA_OUT=<workdir>/out/arm64-v8a)" >&2; exit 2; }
  mkdir -p qnn240 qnn252 models clips ortlib
  # QNN runtime libs, two versions
  fetch $GH/asr-models-qnn/qnn-libs-2.40.0.251030.tar.bz2 qnn-libs-240.tar.bz2
  [ -f qnn240/libQnnHtp.so ] || tar xjf qnn-libs-240.tar.bz2 -C qnn240 --strip-components=1
  fetch $MVN/com/qualcomm/qti/qnn-runtime/2.52.0/qnn-runtime-2.52.0.aar qnn-runtime-2.52.0.aar
  [ -f qnn252/libQnnHtp.so ] || unzip -joq qnn-runtime-2.52.0.aar 'jni/arm64-v8a/libQnnHtp.so' 'jni/arm64-v8a/libQnnSystem.so' \
      'jni/arm64-v8a/libQnnHtpV79Stub.so' 'jni/arm64-v8a/libQnnHtpV79Skel.so' 'jni/arm64-v8a/libQnnHtpPrepare.so' -d qnn252
  # keep only what an SM8750 needs
  find qnn240 -name 'libQnnHtpV*' ! -name '*V79*' -delete
  # ORT QNN plugin EP (for OCR / GigaAM-style ORT graphs on the HTP)
  fetch $MVN/com/qualcomm/qti/onnxruntime-android-qnn/2.7.0/onnxruntime-android-qnn-2.7.0.aar ortqnn-2.7.0.aar
  [ -f ortlib/libonnxruntime_providers_qnn.so ] || unzip -joq ortqnn-2.7.0.aar 'jni/arm64-v8a/libonnxruntime_providers_qnn.so' -d ortlib
  # NPU context binaries (QAIRT 2.40, SM8750)
  if [ "$ONLY" != ocr ]; then
  for t in $(for w in $PARAKEET_WINDOWS; do echo asr-models-qnn-binary-2/sherpa-onnx-qnn-SM8750-binary-parakeet-tdt-0.6b-v3-${w}s-transducer; done) \
           asr-models-qnn-binary-3/sherpa-onnx-qnn-SM8750-binary-nemotron-3.5-asr-streaming-0.6b-560ms; do
    n=$(basename $t); fetch $GH/$t.tar.bz2 $n.tar.bz2; [ -d models/$n ] || tar xjf $n.tar.bz2 -C models
  done
  # CPU int8 packs (Hugging Face, pinned revisions)
  P=csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8; PR=ed5d745623472f97cfc2e6c2916ee248798fc5a8
  mkdir -p models/parakeet-cpu; for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do fetch $HF/$P/resolve/$PR/$f models/parakeet-cpu/$f; done
  N=csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11; NR=ab43d895f5985b1bbab8b6eac8607fcdc05343f3
  mkdir -p models/nemotron-cpu; for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do fetch $HF/$N/resolve/$NR/$f models/nemotron-cpu/$f; done
  G=csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16; GR=a6039be7cee829a9044a69ac0ebaf1c191217c97
  mkdir -p models/gigaam-cpu; for f in encoder.int8.onnx decoder.onnx joiner.onnx tokens.txt; do fetch $HF/$G/resolve/$GR/$f models/gigaam-cpu/$f; done
  # clips: en (LibriSpeech, from the Parakeet pack), ru (Qwen3-ASR pack; GigaAM pack)
  fetch $HF/$P/resolve/$PR/test_wavs/en.wav clips/en.wav
  fetch $HF/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/test_wavs/ru1.wav clips/ru1.wav
  fetch $HF/$G/resolve/main/test_wavs/example.wav clips/ru2.wav
  fi
  if [ "$ONLY" != asr ]; then
    [ -x "$ORTBENCH/arm64-v8a/ort_bench" ] && [ -s "$ORTBENCH/data/det960cover.in.f32" ] || {
      echo "missing $ORTBENCH (build: see header of $ORTBENCH/ort_bench.cc and docs/research/verify/asr-ocr.md §OCR)" >&2; exit 2; }
    [ -s "$OCRQNN/det_tiny_416x960_ctx_qnn.bin" ] || { echo "missing $OCRQNN (scripts/ocr_qnn.py in the venv-qnn)" >&2; exit 2; }
  fi
}

push() {
  $ADB shell mkdir -p $D/bin $D/lib $D/qnn240 $D/qnn252 $D/models $D/clips $D/ocr/data $D/ocr/out
  $ADB push --sync "$SHERPA_OUT"/lib/*.so $D/lib/ >/dev/null
  $ADB push --sync qnn240/. $D/qnn240/ >/dev/null; $ADB push --sync qnn252/. $D/qnn252/ >/dev/null
  $ADB push --sync ortlib/libonnxruntime_providers_qnn.so $D/lib/ >/dev/null
  if [ "$ONLY" != ocr ]; then
    $ADB push --sync "$SHERPA_OUT/bin/sherpa-onnx-offline" "$SHERPA_OUT/bin/sherpa-onnx" $D/bin/ >/dev/null
    $ADB push --sync models/. $D/models/ >/dev/null; $ADB push --sync clips/. $D/clips/ >/dev/null
  fi
  if [ "$ONLY" != asr ]; then
    $ADB push --sync "$ORTBENCH/arm64-v8a/ort_bench" $D/bin/ >/dev/null
    $ADB push --sync "$OCRM/PP-OCRv6_tiny_det_onnx/inference.onnx" $D/ocr/det.onnx >/dev/null
    $ADB push --sync "$OCRM/cyrillic_PP-OCRv5_mobile_rec_onnx/inference.onnx" $D/ocr/rec.onnx >/dev/null
    for f in det_tiny_416x960_ctx.onnx det_tiny_416x960_ctx_qnn.bin rec_cyr_48x320_ctx.onnx rec_cyr_48x320_ctx_qnn.bin det_tiny_416x960.onnx rec_cyr_48x320.onnx; do
      $ADB push --sync "$OCRQNN/$f" $D/ocr/ >/dev/null; done
    for f in det960cover det960 detnative rec320 rec640; do $ADB push --sync "$ORTBENCH/data/$f.in.f32" $D/ocr/data/ >/dev/null; done
  fi
  $ADB shell chmod 755 $D/bin/*
}

# run <label> <qnn-dir|-> <command...> : runs on the phone, samples VmHWM (peak RSS), returns raw log
run() {
  local label=$1 q=$2; shift 2
  local libp="$D/lib" adsp=""
  # the app's string (WP-06: OfflineRecognizer.prependAdspLibraryPath(nativeLibraryDir) keeps these vendor paths;
  # the Samsung "snap" path matters for QNN, llm spike 3.4)
  if [ "$q" != - ]; then libp="$D/lib:$D/$q"; adsp="export ADSP_LIBRARY_PATH='$D/$q;/vendor/lib64/rfs/dsp/snap;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp';"; fi
  $ADB shell "cd $D && export LD_LIBRARY_PATH=$libp; $adsp t0=\$(date +%s%N); ( $* ) > out.txt 2>&1 & p=\$!; hwm=0; \
    while kill -0 \$p 2>/dev/null; do v=\$(grep VmHWM /proc/\$p/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2); [ -n \"\$v\" ] && hwm=\$v; sleep 0.05; done; \
    wait \$p; rc=\$?; t1=\$(date +%s%N); cat out.txt; echo \"@@rc=\$rc hwm_kb=\$hwm wall_ms=\$(( (t1-t0)/1000000 ))\"" > "$WORK/run.log" 2>&1 || true
  python3 - "$label" "$q" "$WORK/run.log" <<'PY'
import json, re, sys
label, q, f = sys.argv[1:4]; s = open(f, errors='replace').read()
def g(p, cast=float):
    m = re.search(p, s); return cast(m.group(1)) if m else None
tail = re.search(r'@@rc=(\d+) hwm_kb=(\d+) wall_ms=(\d+)', s)
text = None
m = re.search(r'"text": ?"([^"]*)"', s)
if m: text = m.group(1)
else:
    m = re.search(r'Real time factor \(RTF\) = [^\n]*\n([^\n]*)\n', s); text = m.group(1) if m else None
r = dict(label=label, qnn=q, rc=int(tail.group(1)) if tail else None, peak_rss_mb=round(int(tail.group(2)) / 1024) if tail else None,
         wall_ms=int(tail.group(3)) if tail else None,
         create_s=g(r'[Rr]ecognizer created in ([0-9.]+) s'), elapsed_s=g(r'Elapsed seconds: ([0-9.]+)'),
         rtf=g(r'(?m)RTF\)[^\n]*= *([0-9.]+(?:e-?[0-9]+)?) *$'), text=text,
         errors=[l for l in s.splitlines() if re.search(r'(?i)error|fail|unable|not supported|segmentation', l)][:4])
bench = re.search(r'^\{"model".*\}$', s, re.M)
if bench: r['bench'] = json.loads(bench.group(0))
print(json.dumps(r, ensure_ascii=False))
PY
}

device_info() {
  $ADB get-state >/dev/null || { echo "no device (adb get-state failed)" >&2; exit 3; }
  local props; props=$($ADB shell 'for p in ro.product.model ro.soc.model ro.soc.manufacturer ro.board.platform ro.build.version.release ro.build.version.sdk ro.build.fingerprint; do echo "$p=$(getprop $p)"; done; ls /vendor/lib64/libcdsprpc.so 2>&1 | head -1')
  python3 -c "import json,sys; kv=dict(l.split('=',1) for l in sys.argv[1].splitlines() if '=' in l); kv['libcdsprpc']=[l for l in sys.argv[1].splitlines() if 'cdsprpc' in l][0] if 'cdsprpc' in sys.argv[1] else None; print(json.dumps({'device': kv}))" "$props"
}

asr() {
  local out=$1
  NQ=models/sherpa-onnx-qnn-SM8750-binary-nemotron-3.5-asr-streaming-0.6b-560ms
  PC=models/parakeet-cpu; NC=models/nemotron-cpu; GC=models/gigaam-cpu
  OFF="./bin/sherpa-onnx-offline --num-threads=$THREADS"
  ON="./bin/sherpa-onnx --num-threads=$THREADS"
  for clip in en ru1 ru2; do
    run "parakeet-cpu-int8/$clip" - $OFF --encoder=$PC/encoder.int8.onnx --decoder=$PC/decoder.int8.onnx --joiner=$PC/joiner.int8.onnx --tokens=$PC/tokens.txt --model-type=nemo_transducer clips/$clip.wav | tee -a "$out"
    for w in $PARAKEET_WINDOWS; do PQ=models/sherpa-onnx-qnn-SM8750-binary-parakeet-tdt-0.6b-v3-${w}s-transducer
    for q in qnn240 qnn252; do for pass in cold warm; do
      run "parakeet-npu-${w}s/$clip/$pass" $q $OFF --provider=qnn --model-type=nemo_transducer --tokens=$PQ/tokens.txt \
        --transducer.qnn-backend-lib=$D/$q/libQnnHtp.so --transducer.qnn-system-lib=$D/$q/libQnnSystem.so \
        --transducer.qnn-context-binary=$PQ/encoder.bin,$PQ/decoder.bin,$PQ/joiner.bin clips/$clip.wav | tee -a "$out"
    done; done; done
    lang=en; [ $clip = en ] || lang=ru
    if [ $lang = ru ]; then
      run "gigaam-rnnt-cpu-int8/$clip" - $OFF --encoder=$GC/encoder.int8.onnx --decoder=$GC/decoder.onnx --joiner=$GC/joiner.onnx --tokens=$GC/tokens.txt --model-type=nemo_transducer clips/$clip.wav | tee -a "$out"
    fi
    run "nemotron-cpu-int8-560ms/$clip" - $ON --encoder=$NC/encoder.int8.onnx --decoder=$NC/decoder.int8.onnx --joiner=$NC/joiner.int8.onnx --tokens=$NC/tokens.txt --language=$lang clips/$clip.wav | tee -a "$out"
    for q in qnn240 qnn252; do for pass in cold warm; do
      run "nemotron-npu-560ms/$clip/$pass" $q $ON --provider=qnn --model-type=nemo_transducer --tokens=$NQ/tokens.txt --language=$lang \
        --transducer.qnn-backend-lib=$D/$q/libQnnHtp.so --transducer.qnn-system-lib=$D/$q/libQnnSystem.so \
        --transducer.qnn-context-binary=$NQ/encoder.bin,$NQ/decoder.bin,$NQ/joiner.bin clips/$clip.wav | tee -a "$out"
    done; done
  done
}

ocr() {
  local out=$1 B="./bin/ort_bench"
  local QENV="ORT_QNN_EP_LIB=$D/lib/libonnxruntime_providers_qnn.so ORT_QNN_BACKEND=$D/qnn252/libQnnHtp.so ORT_QNN_STRICT=1"
  for ep in cpu xnnpack; do
    run "ocr-det-960x416(cover)/$ep" - $B ocr/det.onnx ocr/data/det960cover.in.f32 1,3,960,416 $ep $THREADS 10 ocr/out/det960cover.$ep.f32 | tee -a "$out"
    run "ocr-det-960x864(inner,limit960)/$ep" - $B ocr/det.onnx ocr/data/det960.in.f32 1,3,960,864 $ep $THREADS 10 ocr/out/det960.$ep.f32 | tee -a "$out"
    run "ocr-det-2176x1984(inner,native)/$ep" - $B ocr/det.onnx ocr/data/detnative.in.f32 1,3,2176,1984 $ep $THREADS 5 ocr/out/detnative.$ep.f32 | tee -a "$out"
    run "ocr-rec-48x320/$ep" - $B ocr/rec.onnx ocr/data/rec320.in.f32 1,3,48,320 $ep $THREADS 20 ocr/out/rec320.$ep.f32 | tee -a "$out"
    run "ocr-rec-48x640/$ep" - $B ocr/rec.onnx ocr/data/rec640.in.f32 1,3,48,640 $ep $THREADS 20 ocr/out/rec640.$ep.f32 | tee -a "$out"
  done
  # NPU: precompiled context binaries (QAIRT 2.52 = the qnn-runtime 2.52.0 libs), strict = no CPU fallback
  run "ocr-det-960x416(cover)/qnn-ctx" qnn252 env $QENV $B ocr/det_tiny_416x960_ctx.onnx ocr/data/det960cover.in.f32 1,3,960,416 qnn $THREADS 20 ocr/out/det960cover.qnn.f32 | tee -a "$out"
  run "ocr-rec-48x320/qnn-ctx" qnn252 env $QENV $B ocr/rec_cyr_48x320_ctx.onnx ocr/data/rec320.in.f32 1,3,48,320 qnn $THREADS 50 ocr/out/rec320.qnn.f32 | tee -a "$out"
  # NPU: on-device compile from the fixed-shape ONNX (measures first-use compile time with libQnnHtpPrepare.so)
  run "ocr-det-960x416(cover)/qnn-jit" qnn252 env $QENV $B ocr/det_tiny_416x960.onnx ocr/data/det960cover.in.f32 1,3,960,416 qnn $THREADS 5 | tee -a "$out"
  mkdir -p "$WORK/ocr_out"; $ADB pull $D/ocr/out/. "$WORK/ocr_out/" >/dev/null 2>&1 || true
  python3 - "$WORK/ocr_out" "$ORTBENCH/data" <<'PY' | tee -a "$out"
import json, glob, os, sys
try:
    import numpy as np
except ImportError:
    print(json.dumps({'ocr_parity': 'numpy missing on host'})); sys.exit()
o, ref = sys.argv[1:3]; man = json.load(open(f'{ref}/manifest.json'))
for f in sorted(glob.glob(f'{o}/*.f32')):
    n, ep = os.path.basename(f).split('.')[:2]; y = np.fromfile(f, np.float32); r = np.fromfile(f'{ref}/{n}.ref.f32', np.float32)
    if y.size != r.size: print(json.dumps({'ocr_parity': n, 'ep': ep, 'error': f'size {y.size} != {r.size}'})); continue
    d = {'ocr_parity': n, 'ep': ep, 'max_abs': float(abs(y - r).max())}
    if n.startswith('rec'): sh = man[n]['out']; d['argmax_agree'] = float((y.reshape(sh).argmax(-1) == r.reshape(sh).argmax(-1)).mean())
    else: d['binmap_agree@0.2'] = float(((y > 0.2) == (r > 0.2)).mean())
    print(json.dumps(d))
PY
}

main() {
  [ -n "${SKIP_PREPARE:-}" ] || prepare
  ts=$(date +%Y%m%d-%H%M%S); out="$WORK/report-$ts.jsonl"
  device_info | tee "$out"
  push
  [ "$ONLY" = ocr ] || asr "$out"
  [ "$ONLY" = asr ] || ocr "$out"
  python3 - "$out" <<'PY' | tee "${out%.jsonl}.md"
import json, sys
L = [json.loads(l) for l in open(sys.argv[1]) if l.startswith('{')]
rows = [r for r in L if 'label' in r]; dev = [r for r in L if 'device' in r]; par = [r for r in L if 'ocr_parity' in r]
print('## ASR/OCR NPU smoke —', dev[0]['device'].get('ro.product.model') if dev else '?', dev[0]['device'].get('ro.soc.model') if dev else '')
print('\n| run | qnn libs | rc | create s | decode s | RTF | bench median ms | load ms | peak RSS MB | text / errors |\n|---|---|---|---|---|---|---|---|---|---|')
for r in rows:
    b = r.get('bench') or {}
    print(f"| {r['label']} | {r['qnn']} | {r['rc']} | {r['create_s']} | {r['elapsed_s']} | {r['rtf']} | {b.get('median_ms')} | {b.get('load_ms')} | {r['peak_rss_mb']} | {(r['text'] or '; '.join(r['errors']))[:70]} |")
if par:
    print('\n| OCR output | EP | max abs diff vs host | agreement |\n|---|---|---|---|')
    for p in par: print(f"| {p['ocr_parity']} | {p.get('ep')} | {p.get('max_abs')} | {p.get('argmax_agree', p.get('binmap_agree@0.2', p.get('error')))} |")
PY
  echo "report: $out" >&2
  [ -n "${KEEP_ON_DEVICE:-}" ] || $ADB shell rm -rf $D
}
main "$@"
