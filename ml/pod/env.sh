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

# ---- GPU-hour budget, enforced where the time is spent (run_all per stage, train_matrix per run,
# run_one per step, export_eval per eval). Billed time = sum over pod sessions of (last check - container
# start), container start = now - elapsed time of PID 1 (counts setup and idle time, not only stages),
# plus BUDGET_SESSION_OVERHEAD_S per session for provisioning / image pull before the container runs.
# Persisted on the volume in $W/.budget/sessions.tsv ("start<TAB>last" per session).
#   MAX_GPU_HOURS     hard cap (owner: 5): nothing starts that would end past it
#   TARGET_GPU_HOURS  plan (3): optional runs (sweep extras, DoRA, full FT) start only if they fit
# MURMUR_SESSION_START (epoch seconds) overrides the container start (local simulation, odd runtimes).
export MAX_GPU_HOURS="${MAX_GPU_HOURS:-5}" TARGET_GPU_HOURS="${TARGET_GPU_HOURS:-3}"
export BUDGET_SESSION_OVERHEAD_S="${BUDGET_SESSION_OVERHEAD_S:-300}"
export BUDGET_DIR="$W/.budget"; mkdir -p "$BUDGET_DIR"
_session_start() {
  if [[ -n "${MURMUR_SESSION_START:-}" ]]; then echo "$MURMUR_SESSION_START"; return; fi
  local now et; now=$(date +%s); et=$(ps -o etimes= -p 1 2>/dev/null | tr -d ' ')
  if [[ -n "$et" && "$et" -lt 43200 ]]; then echo $(( now - et )); return; fi
  # PID 1 older than 12 h: not a fresh pod container; fall back to the first check seen on this boot
  local boot; boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo noboot)
  [[ -f "$BUDGET_DIR/boot-$boot" ]] || echo "$now" >"$BUDGET_DIR/boot-$boot"
  cat "$BUDGET_DIR/boot-$boot"
}
# prints the billed seconds so far (and records this session's heartbeat)
budget_used() {
  local now st; now=$(date +%s); st=$(_session_start)
  touch "$BUDGET_DIR/sessions.tsv"
  awk -v st="$st" -v now="$now" -v ov="$BUDGET_SESSION_OVERHEAD_S" -v f="$BUDGET_DIR/sessions.tsv.new" '
    BEGIN { OFS = "\t" }
    { if ($1 - st < 90 && st - $1 < 90) { $2 = now; hit = 1 } print > f; used += $2 - $1 + ov }
    END { if (!hit) { print st, now > f; used += now - st + ov } print used }' "$BUDGET_DIR/sessions.tsv"
  mv "$BUDGET_DIR/sessions.tsv.new" "$BUDGET_DIR/sessions.tsv"
}
# budget_check WHAT RESERVE_S [hard|target]: rc 5 (and a log line) if WHAT would end past the limit
budget_check() {
  local what=$1 reserve=${2:-0} kind=${3:-hard} used lim h
  used=$(budget_used)
  if [[ "$kind" == target ]]; then h=$TARGET_GPU_HOURS; else h=$MAX_GPU_HOURS; fi
  lim=$(awk "BEGIN{print int($h*3600)}")
  if (( used + reserve > lim )); then
    log "budget: $what needs ~${reserve}s, used ${used}s, $kind limit ${lim}s (${h} h): NOT started"
    return 5
  fi
  log "budget: used ${used}s of ${lim}s ($kind); next $what (~${reserve}s)"
}
# median seconds of finished timed steps whose name starts with $1 (e.g. "eval:"), else $2
median_seconds() {
  [[ -f "$MURMUR_LOGS/timings.jsonl" ]] || { echo "$2"; return; }
  jq -s --arg p "$1" --argjson d "$2" '[.[] | select(.stage | startswith($p)) | select(.rc == 0) | .seconds] | sort
    | if length == 0 then $d else .[(length / 2 | floor)] end' "$MURMUR_LOGS/timings.jsonl"
}
