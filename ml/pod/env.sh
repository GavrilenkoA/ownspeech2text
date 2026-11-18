# Common environment for every ml/pod script. Source it: `source ml/pod/env.sh`.
# PLAN_V2 pod: 1x RTX 5090, stopped between stages. The container disk is wiped on stop; only the
# volume ($W, /workspace) persists. So EVERYTHING that must survive a stop lives under $W, including
# uv, uv's managed Python (a venv symlinks its interpreter: if the interpreter were on the container
# disk the venv would break after a stop) and the uv / HF caches.
# No teacher and no teacher credentials on the pod (the teacher runs on the dev server).
export W="${MURMUR_WORKSPACE:-/workspace}"
export REPO="${MURMUR_REPO:-$W/murmur}"
export LLAMA_TAG="${MURMUR_LLAMA_TAG:-b12503}"
export LLAMA_SRC="$W/llama/llama.cpp-$LLAMA_TAG"            # convert_hf_to_gguf.py + gguf-py
export LLAMA_BIN_CPU="$W/llama/cpu/llama-$LLAMA_TAG"         # CPU build (same as the dev server)
export LLAMA_BIN_CUDA="$W/llama/cuda/llama-$LLAMA_TAG"       # CUDA 12.8 build: fast GPU eval on the pod
export VENV_TRAIN="$W/venv-train"
export UV_INSTALL_DIR="$W/.uv/bin"
export UV_PYTHON_INSTALL_DIR="$W/.uv/python"
export UV_CACHE_DIR="$W/.uv/cache"
export UV_LINK_MODE=copy
export PATH="$UV_INSTALL_DIR:$PATH"
export HF_HOME="$W/hf"
export HF_HUB_ENABLE_HF_TRANSFER=0
export MURMUR_DATA="${MURMUR_DATA:-$W/data}"
export MURMUR_RUNS="${MURMUR_RUNS:-$W/runs}"
export MURMUR_EVAL="${MURMUR_EVAL:-$W/eval}"
export MURMUR_LOGS="${MURMUR_LOGS:-$W/logs}"
export MURMUR_LLAMA_SRC="$LLAMA_SRC"
# llama.cpp for pod evals: the CUDA build, unless bootstrap found it cannot see the GPU
if [[ -f "$W/.bootstrap/llama-cpu-fallback" ]]; then _lb="$LLAMA_BIN_CPU"; _ngl=0; else _lb="$LLAMA_BIN_CUDA"; _ngl=99; fi
export MURMUR_LLAMA_BIN="${MURMUR_LLAMA_BIN:-$_lb}"
export MURMUR_LLAMA_NGL="${MURMUR_LLAMA_NGL:-$_ngl}"
export MURMUR_THREADS="${MURMUR_THREADS:-$(( $(nproc) > 16 ? 16 : $(nproc) ))}"
export PYTHONPATH="$REPO:${PYTHONPATH:-}"
export TOKENIZERS_PARALLELISM=false
# markers: volume-backed steps persist; container-disk steps (apt) are re-checked after every start
export MARK_VOL="$W/.bootstrap"
export MARK_CTR="/var/tmp/murmur-bootstrap"
mkdir -p "$MURMUR_DATA" "$MURMUR_RUNS" "$MURMUR_EVAL" "$MURMUR_LOGS" "$HF_HOME" "$MARK_VOL" "$MARK_CTR"
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a "$MURMUR_LOGS/pod.log" >&2; }
# timed STAGE: appends {"stage","seconds","rc","utc"} to $MURMUR_LOGS/timings.jsonl (the time budget
# in ml/pod/REQUEST.md is re-measured from this file after every pod session)
timed() {
  local name=$1; shift; local t0 rc=0; t0=$(date +%s)
  "$@" || rc=$?
  printf '{"stage": "%s", "seconds": %d, "rc": %d, "utc": "%s", "gpu": "%s"}\n' "$name" $(( $(date +%s) - t0 )) "$rc" \
    "$(date -u +%FT%TZ)" "$(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -1)" >>"$MURMUR_LOGS/timings.jsonl"
  return $rc
}
