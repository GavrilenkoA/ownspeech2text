#!/usr/bin/env bash
# GPU TTS probe on the pod: the hook ml/pod/probe.sh runs when MURMUR_PROBE_CMD is unset.
# UNVERIFIED-ON-GPU: written and dry-run on the CPU dev server only (no GPU there).
#
# What it does (docs/ml/ROUNDTRIP.md, "Full run plan" > "GPU pod probe"):
#   1. own venvs for the two cloning systems (their transformers pins conflict) on the volume
#   2. the 10 CC-BY FLEURS reference clips (fetched on the pod; same seed/revision as the dev server's)
#   3. plans: per system the first N sentences of the CPU full run's order, clean channel, voices rotated
#      over the reference clips of the sentence's language (python -m ml.roundtrip.full_plan --gpu-system)
#   4. SIZING: 100 sentences per system = 200 utterances, synthesis only, timed
#   5. MAIN: up to 300 sentences per system (the 100 are reused), cut to what the remaining budget allows
#      at the speed measured in step 4
#   6. summary: $W/roundtrip/out/pod_probe.summary.json
# Budget: POD_PROBE_BUDGET_MIN (default 30) pod minutes IN TOTAL over all invocations (setup included),
# kept in $W/roundtrip/.pod_probe_used_s. Every step checks it; synthesis runs get --max-wall-h for the
# remainder and a hard `timeout`. Resumable: rerunning continues (existing audio is reused, nothing is
# synthesised twice). Output layout = run_roundtrip's: $W/roundtrip/out/audio/<system>/<voice>/<probe>.clean.flac;
# no ASR here. ASR runs later on the dev server's CPU on the same --out (command printed at the end).
#
#   bash ml/roundtrip/pod_probe.sh            # on the pod (normally via: STAGES=probe bash ml/pod/run_all.sh)
#   bash ml/roundtrip/pod_probe.sh --dry-run  # anywhere: checks, plans, budget arithmetic; runs no GPU step
# Env: POD_PROBE_BUDGET_MIN=30  POD_PROBE_SIZING_N=100  POD_PROBE_MAX_N=300  POD_PROBE_SYSTEMS="qwen3tts-1.7b chatterbox-mtl"
#      RT_REF_DIR=<dir with manifest.jsonl> (skip the FLEURS fetch)  MURMUR_QWEN_ATTN=sdpa|flash_attention_2
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO_DIR="$PWD"

DRY=0
case "${1:-}" in
  --dry-run) DRY=1 ;;
  "") ;;
  -h|--help) awk 'NR > 1 && /^set -euo/ { exit } NR > 1' "$REPO_DIR/ml/roundtrip/pod_probe.sh"; exit 0 ;;
  *) echo "unknown argument: $1 (only --dry-run)" >&2; exit 2 ;;
esac

BUDGET_MIN="${POD_PROBE_BUDGET_MIN:-30}"
SIZING_N="${POD_PROBE_SIZING_N:-100}"
MAX_N="${POD_PROBE_MAX_N:-300}"
SYSTEMS="${POD_PROBE_SYSTEMS:-qwen3tts-1.7b chatterbox-mtl}"
for v in BUDGET_MIN SIZING_N MAX_N; do
  [[ "${!v}" =~ ^[0-9]+$ && "${!v}" -gt 0 ]] || { echo "$v must be a positive integer, got '${!v}'" >&2; exit 2; }
done
(( SIZING_N <= MAX_N )) || { echo "POD_PROBE_SIZING_N ($SIZING_N) > POD_PROBE_MAX_N ($MAX_N)" >&2; exit 2; }
for s in $SYSTEMS; do
  case "$s" in qwen3tts-1.7b|chatterbox-mtl) ;; *) echo "unknown GPU system $s" >&2; exit 2 ;; esac
done

if (( DRY )) && [[ ! -d "${W:-/workspace}" ]]; then
  # dry run off the pod: a scratch workspace inside the project cache, the dev server's venv and clips
  W="$REPO_DIR/.cache/ml/roundtrip/pod_dryrun/workspace"
  RT_REF_DIR="${RT_REF_DIR:-$REPO_DIR/.cache/ml/roundtrip/refvoices}"
  HOST_PY="$REPO_DIR/.cache/ml/roundtrip/venv/bin/python"
else
  W="${W:-/workspace}"
  HOST_PY="$(command -v python3)"
fi
RT="$W/roundtrip"
OUT="$RT/out"
REF="${RT_REF_DIR:-$RT/refvoices}"
USED_F="$RT/.pod_probe_used_s"
LOG="$RT/pod_probe.log"
declare -A VENV=([qwen3tts-1.7b]="$W/venv-rt-qwen3tts" [chatterbox-mtl]="$W/venv-rt-chatterbox")
declare -A REQ=([qwen3tts-1.7b]="ml/roundtrip/tts/requirements-gpu-qwen3tts.txt"
                [chatterbox-mtl]="ml/roundtrip/tts/requirements-gpu-chatterbox.txt")
declare -A BATCH=([qwen3tts-1.7b]=8 [chatterbox-mtl]=1)  # chatterbox has no batch API
mkdir -p "$OUT"
T_START=$(date +%s)
USED_BEFORE=$(cat "$USED_F" 2>/dev/null || echo 0)
[[ "$USED_BEFORE" =~ ^[0-9]+$ ]] || USED_BEFORE=0

say() { echo "[pod_probe $(date -u +%FT%TZ)] $*" | tee -a "$LOG" >&2; }
used_s() { echo $(( USED_BEFORE + $(date +%s) - T_START )); }
remaining_s() { echo $(( BUDGET_MIN * 60 - $(used_s) )); }
save_used() { (( DRY )) || echo "$(used_s)" > "$USED_F"; }
trap save_used EXIT
# run CMD... unless dry; refuse when fewer than NEED seconds remain. Every step runs under `timeout` for the
# remaining budget, so no step (venv install, download, synthesis) can outlive the 30 minutes; a step cut by the
# budget returns 75 like a refused one.
step() {
  local need=$1; shift
  local rem; rem=$(remaining_s)
  if (( rem < need )); then say "budget: ${rem}s left < ${need}s needed for: $*; stopping"; return 75; fi
  if (( DRY )); then say "DRY: $*"; return 0; fi
  say "run (${rem}s left): $*"
  local rc=0
  timeout --signal=TERM --kill-after=20 "$rem" "$@" || rc=$?
  if (( rc == 124 || rc == 137 )); then say "budget: step cut at the budget limit: $*"; return 75; fi
  return "$rc"
}
# after a failed step: budget out (75) -> exit 0 with what exists; a real failure -> exit with its code, so
# ml/pod/probe.sh does not mark the probe done
stop_or_fail() {
  local rc=$1
  if (( rc == 75 )); then exit 0; fi
  say "step failed (rc=$rc); stopping without marking the probe done"; exit "$rc"
}

say "start: budget ${BUDGET_MIN} min, used before $(( USED_BEFORE / 60 )) min, systems: $SYSTEMS, sizing $SIZING_N, max $MAX_N"
(( DRY )) && say "DRY RUN: W=$W (no venv install, no download, no GPU)"

# ---- 0. GPU and uv present (pod bootstrap installs uv on the volume)
if (( ! DRY )); then
  nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader | tee -a "$LOG" >&2 \
    || { say "no GPU visible: this script is for the pod"; exit 3; }
  command -v uv >/dev/null || { say "uv missing: run ml/pod/bootstrap.sh first"; exit 3; }
fi

# ---- 1. venvs (volume; markers so a restart skips them)
for s in $SYSTEMS; do
  v="${VENV[$s]}"
  if [[ -f "$v/.rt-ok" ]]; then continue; fi
  step 120 uv venv -q -p 3.12 "$v" || stop_or_fail $?
  step 120 uv pip install -q -p "$v/bin/python" -r "${REQ[$s]}" numpy soundfile scipy huggingface_hub || stop_or_fail $?
  if (( ! DRY )); then
    # a real kernel launch, not only is_available(): a torch build without this GPU's arch (sm_120 on the
    # RTX 5090) reports the GPU as available and fails at the first kernel ("no kernel image is available")
    if ! "$v/bin/python" -c 'import torch
assert torch.cuda.is_available(), "torch sees no GPU"
cap = "sm_%d%d" % torch.cuda.get_device_capability(0)
print("torch", torch.__version__, "cuda", torch.version.cuda, torch.cuda.get_device_name(0), cap, "archs", torch.cuda.get_arch_list())
x = torch.ones(256, 256, device="cuda"); assert float((x @ x)[0, 0]) == 256.0, "bad matmul"' 2>&1 | tee -a "$LOG" >&2; then
      say "$s: torch in $v cannot run on this GPU; fix its torch build (see the log above), then rerun"
      exit 3
    fi
    touch "$v/.rt-ok"
  fi
done

# ---- 2. reference voices
if [[ ! -f "$REF/manifest.jsonl" ]]; then
  any_py="${VENV[${SYSTEMS%% *}]}/bin/python"; (( DRY )) && any_py="$HOST_PY"
  step 180 "$any_py" -m ml.roundtrip.tts.fetch_refvoices --ru 6 --en 4 --out "$REF" || stop_or_fail $?
fi

# ---- 3. plans and the plan check (soundfile, numpy, scipy: the check imports the runner). On the pod a
# venv's python has all three (installed in step 1); the host python3 is used only when it has them too.
plan_py="$HOST_PY"
"$plan_py" -c 'import soundfile, numpy, scipy' 2>/dev/null || plan_py="${VENV[${SYSTEMS%% *}]}/bin/python"
for s in $SYSTEMS; do
  for n in "$SIZING_N" "$MAX_N"; do
    p="$OUT/pod_plan_${s}_${n}.jsonl"
    [[ -f "$p" ]] || "$plan_py" -m ml.roundtrip.full_plan --gpu-system "$s" --gpu-n "$n" --ref-dir "$REF" --out "$p" \
      | tee -a "$LOG" >&2
  done
done

# check every plan against the runner (voices resolvable, languages, channels) without loading a model
"$plan_py" - "$OUT" "$REF" $SYSTEMS <<'PY'
import os, sys
from pathlib import Path
out, ref, systems = Path(sys.argv[1]), sys.argv[2], sys.argv[3:]
os.environ["MURMUR_REFVOICE_DIR"] = ref
from ml.roundtrip import run_roundtrip as rr
from ml.roundtrip.tts.registry import get_tts
probes = rr.load_probes([Path("ml/roundtrip/probe/seed.jsonl"), Path("ml/roundtrip/probe/gen.jsonl")])
for s in systems:
    voices = {s: get_tts(s, 1).voices()}
    for p in sorted(out.glob(f"pod_plan_{s}_*.jsonl")):
        plan = rr.load_plan(p, probes, voices, 13)
        print(f"plan ok: {p.name}: {len(plan.utts)} clips, {len({u.tts_voice for u in plan.utts})} voices", file=sys.stderr)
PY

# ---- 4. sizing, then 5. main, per system; synthesis only (--skip-asr), outputs in the runner's layout
runner() {  # runner SYSTEM PLAN RUNNAME
  local s=$1 plan=$2 run=$3 rem
  rem=$(remaining_s)
  local py="${VENV[$s]}/bin/python"; (( DRY )) && py="$HOST_PY"
  local hours; hours=$(awk -v r="$rem" 'BEGIN { printf "%.4f", (r - 30) / 3600 }')
  step 90 timeout --signal=TERM --kill-after=30 "$((rem - 10))" "$py" -m ml.roundtrip.run_roundtrip \
    --probe ml/roundtrip/probe/seed.jsonl ml/roundtrip/probe/gen.jsonl --plan "$plan" --out "$OUT" --run "$run" \
    --tts "$s" --asr all --skip-asr --workers 1 --threads 4 --tts-batch "${BATCH[$s]}" --tts-shard 32 \
    --ref-dir "$REF" --max-wall-h "$hours" --stop-file "$RT/STOP" --allow-errors
}

declare -A SIZE_S
for s in $SYSTEMS; do
  t0=$(date +%s)
  runner "$s" "$OUT/pod_plan_${s}_${SIZING_N}.jsonl" "pod_${s}_sizing" || { rc=$?; (( rc == 75 )) && break; say "sizing $s rc=$rc"; }
  SIZE_S[$s]=$(( $(date +%s) - t0 ))
done

# per-sentence wall time from the sizing step (model load included, so the projection errs long)
for s in $SYSTEMS; do
  [[ -n "${SIZE_S[$s]:-}" ]] || continue
  done_n=$( { find "$OUT/audio/$s" -name '*.clean.flac' 2>/dev/null || true; } | wc -l)
  per=$(awk -v t="${SIZE_S[$s]}" -v n="$SIZING_N" 'BEGIN { printf "%.2f", t / n }')
  say "sizing $s: ${SIZE_S[$s]}s for $SIZING_N sentences (${per}s each incl. load); $done_n clips on disk"
done

if (( MAX_N > SIZING_N )); then
  for s in $SYSTEMS; do
    [[ -n "${SIZE_S[$s]:-}" ]] || continue
    rem=$(remaining_s)
    n_sys=$(echo $SYSTEMS | wc -w)
    # split what is left evenly over the systems still to run; never more than MAX_N sentences
    share=$(( rem / n_sys - 60 ))
    n=$(awk -v t="${SIZE_S[$s]}" -v sn="$SIZING_N" -v sh="$share" -v mx="$MAX_N" \
         'BEGIN { per = (t > 0 ? t / sn : 1); k = sn + int(sh / per); if (k > mx) k = mx; if (k < sn) k = sn; print k }')
    (( DRY )) && n=$MAX_N
    if (( n <= SIZING_N )); then say "main $s: no budget beyond the sizing step"; continue; fi
    p="$OUT/pod_plan_${s}_${n}.jsonl"
    [[ -f "$p" ]] || "$plan_py" -m ml.roundtrip.full_plan --gpu-system "$s" --gpu-n "$n" --ref-dir "$REF" --out "$p" >/dev/null
    say "main $s: $n sentences (plan $p)"
    runner "$s" "$p" "pod_${s}" || { rc=$?; (( rc == 75 )) && break; say "main $s rc=$rc"; }
    SYSTEMS=${SYSTEMS#"$s"}
  done
fi

# ---- 6. summary: clips per system/voice, runner reports (x-realtime per system), budget used
"$plan_py" - "$OUT" "$(used_s)" "$BUDGET_MIN" <<'PY' | tee "$OUT/pod_probe.summary.json" >&2
import json, sys
from collections import Counter
from pathlib import Path
out, used, budget = Path(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
clips = Counter(f.parts[-3] + "/" + f.parts[-2] for f in (out / "audio").glob("*/*/*.clean.flac")) if (out / "audio").exists() else {}
reports = {p.name: {k: json.loads(p.read_text()).get(k) for k in ("utterances", "pending", "wall_s", "tts", "errors", "stopped")}
           for p in sorted((out / "roundtrip").glob("pod_*.report.json"))} if (out / "roundtrip").exists() else {}
print(json.dumps({"budget_min": budget, "used_s": used, "clips_by_voice": dict(clips), "reports": reports},
                 ensure_ascii=False, indent=1))
PY
say "done: $(( $(used_s) / 60 )) of $BUDGET_MIN min used. Next, on the dev server after WITH_PROBE=1 ml/pod/sync_results.sh:"
say "  for s in $(echo ${!VENV[@]}); do tools/heavy .cache/ml/roundtrip/venv/bin/python -m ml.roundtrip.run_roundtrip \\"
say "    --probe ml/roundtrip/probe/seed.jsonl ml/roundtrip/probe/gen.jsonl --plan <pulled>/roundtrip/out/pod_plan_\${s}_<N>.jsonl \\"
say "    --out <pulled>/roundtrip/out --run "pod_\$s" --tts "\$s" --ref-dir <pulled>/roundtrip/refvoices --asr all --workers 8 --threads 2 --mem-gb 12; done"
exit 0
