#!/usr/bin/env bash
# PLAN_V2 run matrix on ONE GPU, sequential, idempotent (finished steps are skipped; re-run after a
# crash or a pod stop and it continues where it stopped).
#   Phase A  LFM3-1.7B LoRA sweep: rank / learning rate / epochs against the anchor r16, 2e-4, 3 ep
#   select   best phase-A run on the dev Q4_0 eval (ml.train.select) -> its r/alpha/lr/epochs
#   Phase B  Qwen3.5-0.8B and Qwen3.5-2B LoRA with the winner's hyperparameters
#   Fallback (FULL=1 only) the single full fine-tuning config, if LoRA misses the gates
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
fail=0
for r in "${A[@]}"; do bash ml/pod/run_one.sh "$r" || fail=1; done
OV=()
if ((${#A[@]})); then
  "$PY" -m ml.train.select --runs-dir "$MURMUR_RUNS" "${A[@]}" --json "$MURMUR_RUNS/selection-phaseA.json" \
    > "$MURMUR_RUNS/selection-phaseA.txt"
fi
if [[ -s "$MURMUR_RUNS/selection-phaseA.txt" ]]; then  # also reused when phase A ran in an earlier session
  winner=$(sed -n 1p "$MURMUR_RUNS/selection-phaseA.txt"); read -r -a OV <<<"$(sed -n 2p "$MURMUR_RUNS/selection-phaseA.txt")"
  log "phase A winner: $winner (${OV[*]})"
fi
for r in "${B[@]}"; do bash ml/pod/run_one.sh "$r" "${OV[@]}" || fail=1; done
[[ -z "${FULL:-}" ]] || bash ml/pod/run_one.sh full-fallback || fail=1
log "train_matrix finished (fail=$fail)"
exit $fail
