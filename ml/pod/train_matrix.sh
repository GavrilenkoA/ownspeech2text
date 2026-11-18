#!/usr/bin/env bash
# PLAN_V2 run matrix on ONE GPU, sequential, idempotent (finished steps are skipped; re-run after a
# crash or a pod stop and it continues where it stopped).
#   preflight  abort if a Qwen3.5 run is planned and flash-linear-attention is missing (slow PyTorch
#              path would eat the budget) or if the llama.cpp CUDA build fell back to CPU
#              (ALLOW_SLOW_QWEN=1 / ALLOW_CPU_EVAL=1 override after a conscious decision)
#   anchor     the first phase-A run (lfm3-r16) alone; prints its MEASURED tokens/s and step times and
#              projects every remaining run from them (RUN_COST factors below are assumptions [A])
#   Phase A    the other LFM3 LoRA sweep runs: OPTIONAL, each starts only if its projection fits
#              TARGET_GPU_HOURS (default 3)
#   select     best phase-A run on the dev Q4_0 eval (ml.train.select) -> its r/alpha/lr/epochs
#   Phase B    Qwen3.5-0.8B and Qwen3.5-2B LoRA with the winner's hyperparameters: must fit MAX_GPU_HOURS
#   Fallback   (FULL=1 only) the single full fine-tuning config, OPTIONAL like phase A extras
#   bash ml/pod/train_matrix.sh
#   PHASE_A="lfm3-r16" PHASE_B="q08-lora" bash ml/pod/train_matrix.sh     # a cheaper subset
#   DORA=1 adds lfm3-dora-r16 to phase A; FULL=1 runs full-fallback after phase B.
set -euo pipefail
cd "$(dirname "$0")/../.."
source ml/pod/env.sh
PY="$VENV_TRAIN/bin/python"
read -r -a A <<<"${PHASE_A-lfm3-r16 lfm3-r64 lfm3-r16-lr5e-4 lfm3-r16-e5}"   # PHASE_A="" skips phase A
[[ -z "${DORA:-}" ]] || A+=(lfm3-dora-r16)
read -r -a B <<<"${PHASE_B-q08-lora q2-lora}"

# Cost of a run relative to the anchor (train + merge + export + parity + dev eval), [A] assumptions:
# epochs scale linearly; r64 adds ~10 % optimizer/adapter work; DoRA ~1.3x (weight-norm recompute);
# Qwen3.5-0.8B ~0.6x and Qwen3.5-2B ~1.3x of LFM3-1.7B (params, with fla kernels); full FT ~2.5x.
declare -A RUN_COST=([lfm3-r16]=1.0 [lfm3-r64]=1.1 [lfm3-r16-lr5e-4]=1.0 [lfm3-r16-e5]=1.67 [lfm3-dora-r16]=1.3
                     [q08-lora]=0.6 [q2-lora]=1.3 [full-fallback]=2.5)
ANCHOR_DEFAULT_S="${ANCHOR_DEFAULT_S:-900}"   # [A] before the anchor ran: 15 min

# ---- preflight
qwen=0; for r in "${B[@]}"; do [[ "$r" == q* ]] && qwen=1; done
if (( qwen )) && [[ -f "$MARK_VOL/fla-missing" && -z "${ALLOW_SLOW_QWEN:-}" ]]; then
  log "preflight: flash-linear-attention is missing ($MARK_VOL/fla-missing) and Qwen3.5 runs are planned: abort."
  log "  fix the fla install (bootstrap.sh), or PHASE_B=\"\" for LFM3 only, or ALLOW_SLOW_QWEN=1 to accept the slow path"
  exit 6
fi
if [[ -f "$MARK_VOL/llama-cpu-fallback" && -z "${ALLOW_CPU_EVAL:-}" ]]; then
  log "preflight: the llama.cpp CUDA build does not see the GPU; evals would run on the pod CPU: abort."
  log "  fix it (bootstrap.sh step 4) or ALLOW_CPU_EVAL=1 (see TRAINING_RUNBOOK.md 'CPU eval fallback' timing)"
  exit 6
fi

run_seconds() {  # measured wall seconds of one finished run (all its timed steps)
  jq -s --arg r "$1" '[.[] | select(.stage | test(":" + $r + "(:|$)")) | .seconds] | add // 0' \
    "$MURMUR_LOGS/timings.jsonl" 2>/dev/null || echo 0
}
anchor_s() { local s; s=$(run_seconds "${A[0]:-lfm3-r16}"); (( s > 0 )) && echo "$s" || echo "$ANCHOR_DEFAULT_S"; }
proj() { awk -v a="$(anchor_s)" -v f="${RUN_COST[$1]:-1.5}" 'BEGIN{print int(a*f)}'; }
do_run() {  # $1 run  $2 hard|target  rest: overrides
  local r=$1 kind=$2; shift 2
  if [[ -f "$MURMUR_RUNS/$r/eval/Q4_0-dev/report.json" ]]; then bash ml/pod/run_one.sh "$r" "$@"; return; fi
  local p; p=$(proj "$r")
  budget_check "run $r" "$p" "$kind" || return 5
  RUN_RESERVE_S=$p bash ml/pod/run_one.sh "$r" "$@"
}

fail=0
# ---- anchor first, then report measured throughput and the projections
if ((${#A[@]})); then
  do_run "${A[0]}" hard || fail=1
  if [[ -f "$MURMUR_RUNS/${A[0]}/train_info.json" ]]; then
    jq -c '{anchor: .run_name, tokens_per_s: .approx_tokens_per_s, train_s: .train_runtime_s, steps: .steps,
            peak_gb: .peak_cuda_mem_gb, device: .device}' "$MURMUR_RUNS/${A[0]}/train_info.json" \
      | tee -a "$MURMUR_LOGS/anchor.jsonl" >&2
    log "anchor total ${A[0]}: $(anchor_s)s measured; projections:" \
        "$(for r in "${A[@]:1}" "${B[@]}" ${FULL:+full-fallback}; do printf '%s=%ss ' "$r" "$(proj "$r")"; done)"
  fi
  for r in "${A[@]:1}"; do do_run "$r" target || { [[ $? == 5 ]] && log "skip optional $r (budget)" || fail=1; }; done
fi
OV=()
if ((${#A[@]})); then
  "$PY" -m ml.train.select --runs-dir "$MURMUR_RUNS" "${A[@]}" --json "$MURMUR_RUNS/selection-phaseA.json" \
    > "$MURMUR_RUNS/selection-phaseA.txt" || true
fi
if [[ -s "$MURMUR_RUNS/selection-phaseA.txt" ]]; then  # also reused when phase A ran in an earlier session
  winner=$(sed -n 1p "$MURMUR_RUNS/selection-phaseA.txt"); read -r -a OV <<<"$(sed -n 2p "$MURMUR_RUNS/selection-phaseA.txt")"
  log "phase A winner: $winner (${OV[*]})"
fi
for r in "${B[@]}"; do do_run "$r" hard "${OV[@]}" || fail=1; done
if [[ -n "${FULL:-}" ]]; then
  do_run full-fallback target || { [[ $? == 5 ]] && log "skip optional full-fallback (budget)" || fail=1; }
fi
log "train_matrix finished (fail=$fail); billed pod time $(budget_used)s"
exit $fail
