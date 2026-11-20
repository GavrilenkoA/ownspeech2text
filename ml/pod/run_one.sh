#!/usr/bin/env bash
# One run of the matrix, end to end and idempotent (every step is skipped when its output exists, an
# interrupted training resumes from its newest checkpoint):
#   train (ml/train/configs/RUN.yaml + overrides) -> merge (LoRA/DoRA) -> GGUF BF16 + Q4_0
#   -> PARITY: merged HF (bf16, torch) vs BF16 GGUF (llama.cpp) greedy on PARITY_N dev items; a broken
#      merge/convert (e.g. Gated DeltaNet tensors on Qwen3.5) stops the run here (exit 3)
#   -> eval of the Q4_0 file on the dev sets with llama.cpp CUDA (greedy, as on the phone)
#   -> delete the merged HF copy (re-creatable from the adapter in seconds)
#   bash ml/pod/run_one.sh lfm3-r16
#   bash ml/pod/run_one.sh q08-lora lora.r=64 lora.alpha=128      # overrides (from ml.train.select)
# Env: EVAL_DEV (contract JSONL files; default: the synthetic ctx-v2 dev split + prompts dev.v2 +
#      smoke v2; v1-context sets are legacy and never used for selection), QUANTS (default Q4_0),
#      KEEP_MERGED=1, CONFIG_DIR (default ml/train/configs), PARITY_N (default 24, 0 = skip),
#      PARITY_MIN_AGREE (default 0.8; only the random-weight local simulation lowers it),
#      RUN_RESERVE_S (projected seconds of the whole run, from train_matrix.sh; budget check),
#      FORCE_RETRAIN=1 (train a run again that diverged earlier, from scratch; see below).
# Exit: 0 done, 1 failed, 2 no config, 3 parity FAIL, 4 training diverged (now or in an earlier attempt:
#      $MURMUR_RUNS/RUN/DIVERGED.json holds step + reason; a re-run skips the run instead of resuming from the
#      last good checkpoint into the same divergence, until FORCE_RETRAIN=1), 5 budget.
set -euo pipefail
cd "$(dirname "$0")/../.."
source ml/pod/env.sh
PY="$VENV_TRAIN/bin/python"
run=$1; shift
dir="$MURMUR_RUNS/$run"
CFG="${CONFIG_DIR:-ml/train/configs}/$run.yaml"
[[ -f "$CFG" ]] || { log "no config $CFG"; exit 2; }
SMOKE="$MURMUR_DATA/smoke.v2.test.jsonl"
[[ -f "$SMOKE" ]] || "$PY" -m ml.eval.smoke_to_contract --split test --out "$SMOKE" >/dev/null
read -r -a DEV <<<"${EVAL_DEV:-$MURMUR_DATA/dev.jsonl prompts/eval/sets/dev.v2.jsonl $SMOKE}"
QUANTS="${QUANTS:-Q4_0}"
PARITY_N="${PARITY_N:-24}"
step_check() { budget_check "$1" "$2" hard || exit 5; }

diverged_why() { jq -r '"step \(.step // "?"): \(.reason // "?")"' "$dir/DIVERGED.json" 2>/dev/null || echo "unreadable marker"; }
if [[ -f "$dir/train_info.json" ]]; then log "train $run: done, skip"; else
  if [[ -f "$dir/DIVERGED.json" && "${FORCE_RETRAIN:-}" != 1 ]]; then
    log "train $run: DIVERGED in an earlier attempt ($(diverged_why)); skipped, not resumed. Fix the config, then FORCE_RETRAIN=1 retrains it from scratch"
    exit 4
  fi
  step_check "train $run" "${RUN_RESERVE_S:-0}"
  # stop rule inside training: the rest of this run (merge + export + parity + dev eval) must still fit the cap
  after_s=$(( $(median_seconds merge: 60) + $(median_seconds export: 120) + $(median_seconds parity: 90) + $(median_seconds eval: 300) ))
  export MURMUR_TRAIN_DEADLINE; MURMUR_TRAIN_DEADLINE=$(budget_deadline "$after_s")
  log "train $run $* (training deadline in $(( MURMUR_TRAIN_DEADLINE - $(date +%s) ))s, reserving ${after_s}s for the rest of the run)"
  rc=0; timed "train:$run" "$PY" -m ml.train.sft --config "$CFG" --set "output_dir=$dir" "$@" \
    >>"$MURMUR_LOGS/train-$run.log" 2>&1 || rc=$?
  if (( rc == 4 )) && [[ -f "$dir/DIVERGED.json" ]]; then
    log "train $run DIVERGED ($(diverged_why); $dir/DIVERGED.json, logs/train-$run.log): not exported or evaluated; re-runs skip it until FORCE_RETRAIN=1"
    exit 4
  fi
  (( rc == 0 )) || { log "train $run FAILED (rc $rc, logs/train-$run.log)"; exit 1; }
  jq -c '{run: .run_name, tokens_per_s: .approx_tokens_per_s, padded_tokens_per_s, train_s: .train_runtime_s,
          peak_gb: .peak_cuda_mem_gb, steps, planned_steps, stopped_by}' "$dir/train_info.json" >&2
  [[ "$(jq -r '.stopped_by // ""' "$dir/train_info.json")" != budget ]] \
    || log "train $run: STOPPED BY THE BUDGET at step $(jq -r .steps "$dir/train_info.json") of $(jq -r .planned_steps "$dir/train_info.json") (partially trained; exported and evaluated anyway)"
fi
method=$(jq -r .method "$dir/train_info.json"); base=$(jq -r .base_model "$dir/train_info.json")
FTSYS=$(jq -r '.prompt.system // "finetuned.v2"' "$dir/config.resolved.json")   # eval with the prompt it trained on
name="murmur-$(basename "$base" | tr '[:upper:]' '[:lower:]')-$run"
parity_ok() { [[ -f "$dir/eval/parity.json" && "$(jq -r .status "$dir/eval/parity.json")" == PASS ]]; }
if [[ ! -f "$dir/gguf/manifest.json" ]] || { (( PARITY_N > 0 )) && ! parity_ok && [[ ! -f "$dir/gguf/$name-BF16.gguf" ]]; }; then
  step_check "export $run" "$(median_seconds export: 120)"
  if [[ "$method" != full && ! -f "$dir/merged/config.json" ]]; then
    log "merge $run"; timed "merge:$run" "$PY" -m ml.train.merge --run "$dir" >>"$MURMUR_LOGS/train-$run.log" 2>&1
  fi
  log "export $run ($QUANTS)"
  # shellcheck disable=SC2086
  timed "export:$run" "$PY" -m ml.train.export --run "$dir" --name "$name" --quants $QUANTS --keep-bf16 \
    >>"$MURMUR_LOGS/export-$run.log" 2>&1
fi
if (( PARITY_N > 0 )) && ! parity_ok; then
  if [[ "$method" != full && ! -f "$dir/merged/config.json" ]]; then  # merged copy was deleted after an earlier export
    log "re-merge $run for parity"; timed "merge:$run" "$PY" -m ml.train.merge --run "$dir" >>"$MURMUR_LOGS/train-$run.log" 2>&1
  fi
  step_check "parity $run" "$(median_seconds parity: 90)"
  log "parity $run: merged HF vs BF16 GGUF, $PARITY_N dev items"
  if ! timed "parity:$run" "$PY" -m ml.train.parity --run "$dir" --gguf "$dir/gguf/$name-BF16.gguf" --data "${DEV[0]}" \
      -n "$PARITY_N" --min-agree "${PARITY_MIN_AGREE:-0.8}" --system "$FTSYS" --llama-bin "$MURMUR_LLAMA_BIN" --ngl "$MURMUR_LLAMA_NGL" >>"$MURMUR_LOGS/export-$run.log" 2>&1; then
    log "PARITY FAILED for $run: the GGUF does not reproduce the trained model ($dir/eval/parity.json); not evaluated"
    exit 3
  fi
fi
[[ -n "${KEEP_MERGED:-}" ]] || rm -rf "$dir/merged"
if [[ ! -f "$dir/eval/Q4_0-dev/report.json" ]]; then
  step_check "eval $run Q4_0 dev" "$(median_seconds eval: 300)"
  log "eval $run Q4_0 on dev (llama.cpp ${MURMUR_LLAMA_NGL} GPU layers)"
  timed "eval:$run:Q4_0-dev" "$PY" -m ml.eval.run_eval --model "$dir/gguf/$name-Q4_0.gguf" --data "${DEV[@]}" \
    --out "$dir/eval/Q4_0-dev" --system "$FTSYS" --label "$run Q4_0 dev" --llama-bin "$MURMUR_LLAMA_BIN" --ngl "$MURMUR_LLAMA_NGL" \
    >>"$MURMUR_LOGS/eval-$run.log" 2>&1
fi
jq -c '{run: "'"$run"'", verdict: .gates.verdict, guarded: .guarded.gates.verdict, em: .summary.overall.em,
        nwer: .summary.overall.nwer, noop: .summary.overall.noop_exact,
        pass_guarded: (.guarded.summary.by_set // {} | map_values(.pass)),
        vs_best_stock_dev_v2: .baseline.sets["dev.v2"].delta_pass_guarded_vs_best}' "$dir/eval/Q4_0-dev/report.json" \
  | tee -a "$MURMUR_LOGS/runs.jsonl"
