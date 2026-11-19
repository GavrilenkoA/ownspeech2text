#!/usr/bin/env bash
# Host AOT compile of the ORT QNN plugin EP contexts (WP-21): PP-OCR det/rec and GigaAM v3 e2e CTC for SM8750 (HTP v79),
# fp16, 100 % on the HTP. Public downloads only (PyPI onnxruntime-qnn 2.8.0 = QAIRT 2.52, Hugging Face), no Qualcomm account.
#   MURMUR_MEM=24G tools/heavy native/qnn-contexts/compile.sh [all|ocr-det|ocr-rec|gigaam ...]
#   OUT=<dir> to write elsewhere (default <main checkout>/.cache/spikes/wp-21/ctx)
# Then, for the record and the simulator suites:
#   GIGAAM: $VENV/bin/python -I native/qnn-contexts/gigaam_eval.py native/qnn-contexts/fixtures/fleurs-ru \
#             $WORK/src/gigaam/v3_e2e_ctc.onnx $OUT/gigaam-v3-e2e-ctc@qnn-sm8750-20s $OUT/gigaam_eval.json   (~30 min, simulator)
#   OCR:    tools/gw :infer:asr:testDebugUnitTest --tests '*SimulatorTest' -Pmurmur.qnnSim=$VENV/lib/python3.12/site-packages/onnxruntime_qnn \
#             -Pmurmur.qnnContexts=$OUT
# Output: $OUT/<artifactId>/ (what the app loads), $OUT/{det,rec}_contexts.zip, $OUT/manifest.json. QNN compiles are not
# bit-reproducible: after a new compile copy $OUT/manifest.json to native/qnn-contexts/manifest.json, regenerate the catalog
# (core/catalog/tools/build_catalog.py) and upload exactly these files (hosting: owner question, WP-21).
# Measured 2026-11-20 on this server (load ~10-16): det 5 graphs 9 s, rec 10 graphs 15 s, GigaAM 65 s; peak RSS < 8 GB.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
common=$(git -C "$root" rev-parse --path-format=absolute --git-common-dir)
main=$(dirname "$common")
WORK=${WORK:-$main/.cache/spikes/wp-21}
OUT=${OUT:-$WORK/ctx}
VENV=$WORK/venv
targets=("$@"); [ ${#targets[@]} -gt 0 ] || targets=(all)

# GigaAM v3 e2e CTC, fp32 ONNX export for onnx-asr (MIT), pinned
GIGAAM_REPO=istupakov/gigaam-v3-onnx
GIGAAM_REVISION=322c3b29492673eb7d0b434bfa9dfb8653e34d02
GIGAAM_SHA256=377701bd33568f4733feec2db5b2dc12544fd09a5a5dfa69ccf55d161f84027a
GIGAAM_VOCAB_SHA256=142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd

mkdir -p "$WORK" "$OUT"
command -v uv >/dev/null || { echo "uv not found (https://docs.astral.sh/uv/)" >&2; exit 2; }
if [ ! -x "$VENV/bin/python" ]; then uv venv -q -p 3.12 "$VENV"; fi
VIRTUAL_ENV=$VENV uv pip install -q -r "$here/requirements.txt"

want() { local t; for t in "${targets[@]}"; do [ "$t" = all ] || [ "$t" = "$1" ] && return 0; done; return 1; }

if want ocr-det || want ocr-rec; then
  "$root/tools/models/fetch" pp-ocrv6-tiny-det@onnx pp-ocrv5-cyrillic-mobile-rec@onnx
fi

fetch_hf() { # <path in repo> <dest> <sha256>
  local url="https://huggingface.co/$GIGAAM_REPO/resolve/$GIGAAM_REVISION/$1"
  if [ ! -f "$2" ] || [ "$(sha256sum "$2" | cut -d' ' -f1)" != "$3" ]; then
    echo "fetch $1" >&2
    curl -fsSL -4 --retry 5 -C - -o "$2.part" "$url"
    [ "$(sha256sum "$2.part" | cut -d' ' -f1)" = "$3" ] || { echo "sha256 mismatch: $1" >&2; rm -f "$2.part"; exit 1; }
    mv "$2.part" "$2"
  fi
}
if want gigaam; then
  mkdir -p "$WORK/src/gigaam"
  fetch_hf v3_e2e_ctc.onnx "$WORK/src/gigaam/v3_e2e_ctc.onnx" "$GIGAAM_SHA256"
  fetch_hf v3_e2e_ctc_vocab.txt "$WORK/src/gigaam/v3_e2e_ctc_vocab.txt" "$GIGAAM_VOCAB_SHA256"
fi

args=()
for t in "${targets[@]}"; do args+=("$t"); done
GIGAAM_ONNX=$WORK/src/gigaam/v3_e2e_ctc.onnx GIGAAM_REVISION=$GIGAAM_REVISION \
  nice -n 10 "$VENV/bin/python" -I "$here/compile.py" "$OUT" "${args[@]}"
echo "contexts in $OUT; manifest $OUT/manifest.json"
