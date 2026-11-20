#!/usr/bin/env bash
# Bootstrap step 6b: llama.cpp GPU KERNEL SMOKE TEST, on every start (a restarted pod can land on another
# host/driver). `--list-devices` runs no kernel: a build without sm_120 device code, a failing or slow PTX JIT or a
# wrong kernel would only show at the anchor's parity/eval, after its training time. ml/train/llama_smoke.py
# decodes 4 fixed prompts with every layer on the GPU and on the CPU build, and compares (PASS: all agree, all
# layers offloaded, < 120 s).
#   bash ml/pod/kernel_smoke.sh          # called by bootstrap.sh; safe to re-run on its own
# Two separate phases, so a problem with the TEST MODEL is never mistaken for a broken GPU build:
#   A. prepare the smoke models: download real base weights (pinned revision; step 7 then skips them) and convert
#      them to Q4_0 with the CPU build, once (cached on the volume). A failure is retried once (after
#      LLAMA_SMOKE_RETRY_S, default 20 s); if it fails again: marker llama-smoke-unprepared, "ERROR ... smoke model
#      could not be prepared (not a CUDA build problem)", NO build is tested, NO fallback build is fetched or
#      compiled, exit 7 (bootstrap stops; train_matrix.sh preflight refuses while the marker exists, rc 6,
#      unless ALLOW_UNTESTED_LLAMA=1).
#   B. test the builds on the prepared models. Measured on the dev server CPU: GGUF build 16.5 s for
#      Qwen3.5-0.8B, decode 2.5 s per build [M]; on the pod ~1-3 min the first time (two models), ~30 s on a
#      restart [A]. Candidates, first PASS wins (each one has its own log: $W/llama/smoke/*.<label>.gpu.log):
#   1. the release CUDA 12.8 build (its default arch list includes 120a-real, i.e. sm_120 device code [V source])
#   2. the release CUDA 13.5 build of the same tag (~670 MB, ~1 min to fetch) [A: needs the driver's 13.x minor-
#      version compatibility]
#   3. a source build of the same tag for sm_120 only (needs nvcc >= 12.8 in the image; ~10-15 min [A];
#      LLAMA_SRC_BUILD=0 skips it)
#   4. none: marker llama-cpu-fallback -> train_matrix.sh preflight aborts (rc 6) unless ALLOW_CPU_EVAL=1
#      (CPU evals: ~10-40 min per ~420-item dev eval, ~10 evals per session: over the budget; runbook section 3)
# LLAMA_SMOKE_MODELS="" skips the test (legacy --list-devices verdict only).
# Exit: 0 decided (marker llama-cuda-bin or llama-cpu-fallback), 7 a smoke model could not be prepared.
set -euo pipefail
cd "$(dirname "$0")/../.."
source ml/pod/env.sh
PY="$VENV_TRAIN/bin/python"
LLAMA_SMOKE_MODELS="${LLAMA_SMOKE_MODELS-Qwen/Qwen3.5-0.8B LiquidAI/LFM3-1.7B}"

if [[ -z "$LLAMA_SMOKE_MODELS" ]]; then
  rm -f "$MARK_VOL/llama-smoke-unprepared"
  if LD_LIBRARY_PATH="$LLAMA_BIN_CUDA" "$LLAMA_BIN_CUDA/llama-server" --list-devices 2>&1 | grep -q CUDA0; then
    rm -f "$MARK_VOL/llama-cpu-fallback"
  else
    touch "$MARK_VOL/llama-cpu-fallback"
  fi
  exit 0
fi
mkdir -p "$W/llama/smoke"

# ---- A. the smoke models (no build under test involved: only the CPU build's llama-quantize)
prepare() {  # $1 model; rc 0 = its Q4_0 smoke GGUF is ready
  local m=$1 i j="$W/llama/smoke/prepare-$(basename "$1").json"
  for i in 1 2; do
    log "llama.cpp kernel smoke: preparing the smoke model $m (download + Q4_0 conversion, attempt $i of 2)"
    if timeout 1800 "$PY" -m ml.train.llama_smoke --prepare-only --hf "$m" --work "$W/llama/smoke" \
        --cpu-bin "$LLAMA_BIN_CPU" --json "$j" >>"$MURMUR_LOGS/llama-smoke.log" 2>&1; then return 0; fi
    jq -c '{status, reason: (.reason // "" | .[0:300])}' "$j" 2>/dev/null >&2 || true
    (( i == 2 )) || sleep "${LLAMA_SMOKE_RETRY_S:-20}"
  done
  return 1
}
for m in $LLAMA_SMOKE_MODELS; do
  if ! prepare "$m"; then
    echo "$m" >"$MARK_VOL/llama-smoke-unprepared"
    log "ERROR: the kernel smoke model $m could not be prepared (download or GGUF conversion; NOT a CUDA build problem)."
    log "  No llama.cpp build was tested and no fallback build was tried. See $W/llama/smoke/prepare-$(basename "$m").json"
    log "  and $MURMUR_LOGS/llama-smoke.log; fix the cause and re-run bootstrap.sh (TRAINING_RUNBOOK 3.2). train_matrix.sh"
    log "  refuses to start while $MARK_VOL/llama-smoke-unprepared exists (rc 6)."
    exit 7
  fi
done
rm -f "$MARK_VOL/llama-smoke-unprepared"

# ---- B. the builds under test
smoke() {  # $1 label  $2 llama.cpp bin dir under test; rc 0 = PASS on every smoke model
  local m rc=0; [[ -x "$2/llama-server" ]] || return 1
  for m in $LLAMA_SMOKE_MODELS; do
    log "llama.cpp kernel smoke: $1 build on $m"
    timeout 1800 "$PY" -m ml.train.llama_smoke --hf "$m" --work "$W/llama/smoke" --gpu-bin "$2" --label "$1" \
      --cpu-bin "$LLAMA_BIN_CPU" --require-gpu --timeout "${LLAMA_SMOKE_TIMEOUT:-120}" \
      --json "$W/llama/smoke/$1-$(basename "$m").json" >>"$MURMUR_LOGS/llama-smoke.log" 2>&1 || rc=1
    jq -c '{status, reason: (.reason // "" | .[0:300]), gpu}' "$W/llama/smoke/$1-$(basename "$m").json" 2>/dev/null >&2 || true
    (( rc == 0 )) || return 1
  done
}
fetch_cuda135() {
  local d="$W/llama/cuda13.5/llama-$LLAMA_TAG" base="https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_TAG"
  [[ -x "$d/llama-server" ]] && { echo "$d"; return; }
  mkdir -p "$d"
  for f in "llama-$LLAMA_TAG-bin-ubuntu-cuda-13.5-x64.tar.gz" "cudart-llama-$LLAMA_TAG-bin-ubuntu-cuda-13.5-x64.tar.gz"; do
    curl -fsSL "$base/$f" | tar xz -C "$d" --strip-components=1 || return 1
  done
  echo "$d"
}
build_cuda_src() {  # sm_120 only, server + quantize; the CPU backend is built in as well
  local b="$W/llama/build-cuda" t0; t0=$(date +%s)
  [[ "${LLAMA_SRC_BUILD:-1}" != 0 ]] && command -v nvcc >/dev/null && command -v cmake >/dev/null \
    || { log "llama.cpp source build skipped (LLAMA_SRC_BUILD=0, or no nvcc/cmake in the image)"; return 1; }
  log "llama.cpp source build for sm_120 (~10-15 min, log $MURMUR_LOGS/llama-build.log)"
  [[ -x "$b/bin/llama-server" ]] || {
    cmake -S "$LLAMA_SRC" -B "$b" -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=ON -DCMAKE_CUDA_ARCHITECTURES=120a-real \
      -DGGML_NATIVE=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_OPENSSL=OFF >>"$MURMUR_LOGS/llama-build.log" 2>&1 &&
    cmake --build "$b" --config Release -j "$(nproc)" --target llama-server llama-quantize >>"$MURMUR_LOGS/llama-build.log" 2>&1 \
      || return 1
    log "llama.cpp source build: $(( $(date +%s) - t0 ))s"; }
  echo "$b/bin"
}
pick=""
prev=$(cat "$MARK_VOL/llama-cuda-bin" 2>/dev/null || true)
if [[ -n "$prev" ]] && smoke previous "$prev"; then pick=$prev
elif smoke cuda12.8 "$W/llama/cuda/llama-$LLAMA_TAG"; then pick="$W/llama/cuda/llama-$LLAMA_TAG"
elif d=$(fetch_cuda135) && smoke cuda13.5 "$d"; then pick=$d
elif d=$(build_cuda_src) && smoke source "$d"; then pick=$d
fi
if [[ -n "$pick" ]]; then
  echo "$pick" >"$MARK_VOL/llama-cuda-bin"; rm -f "$MARK_VOL/llama-cpu-fallback"
  log "llama.cpp kernel smoke PASS: pod evals use $pick"
else
  rm -f "$MARK_VOL/llama-cuda-bin"; touch "$MARK_VOL/llama-cpu-fallback"
  log "WARNING: no llama.cpp CUDA build passed the kernel smoke test ($MURMUR_LOGS/llama-smoke.log, $W/llama/smoke/*.json):"
  log "  train_matrix.sh will refuse to start (rc 6); see TRAINING_RUNBOOK 'llama.cpp CUDA fallback'"
fi
