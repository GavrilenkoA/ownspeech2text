#!/usr/bin/env bash
# One run of the matrix, end to end and idempotent (every step is skipped when its output exists, an
# interrupted training resumes from its newest checkpoint):
#   train (ml/train/configs/RUN.yaml + overrides) -> merge (LoRA/DoRA) -> GGUF BF16 + Q4_0
#   -> eval of the Q4_0 file on the dev sets with llama.cpp CUDA (greedy, as on the phone)
#   -> delete the merged HF copy (re-creatable from the adapter in seconds)
#   bash ml/pod/run_one.sh lfm3-r16
#   bash ml/pod/run_one.sh q08-lora lora.r=64 lora.alpha=128      # overrides (from ml.train.select)
# Env: EVAL_DEV (contract JSONL files; default $MURMUR_DATA/dev.jsonl + prompts/eval/sets/dev.v1.jsonl
#      + smoke), QUANTS (default Q4_0), KEEP_MERGED=1, CONFIG_DIR (default ml/train/configs).
set -euo pipefail
cd "$(dirname "$0")/../.."
source ml/pod/env.sh
PY="$VENV_TRAIN/bin/python"
run=$1; shift
dir="$MURMUR_RUNS/$run"
CFG="${CONFIG_DIR:-ml/train/configs}/$run.yaml"
[[ -f "$CFG" ]] || { log "no config $CFG"; exit 2; }
SMOKE="$MURMUR_DATA/smoke.test.jsonl"
[[ -f "$SMOKE" ]] || "$PY" -m ml.eval.smoke_to_contract --split test --out "$SMOKE" >/dev/null
read -r -a DEV <<<"${EVAL_DEV:-$MURMUR_DATA/dev.jsonl prompts/eval/sets/dev.v1.jsonl $SMOKE}"
QUANTS="${QUANTS:-Q4_0}"

if [[ -f "$dir/train_info.json" ]]; then log "train $run: done, skip"; else
  log "train $run $*"
  timed "train:$run" "$PY" -m ml.train.sft --config "$CFG" --set "output_dir=$dir" "$@" \
    >>"$MURMUR_LOGS/train-$run.log" 2>&1 || { log "train $run FAILED (logs/train-$run.log)"; exit 1; }
fi
method=$(jq -r .method "$dir/train_info.json"); base=$(jq -r .base_model "$dir/train_info.json")
name="murmur-$(basename "$base" | tr '[:upper:]' '[:lower:]')-$run"
if [[ ! -f "$dir/gguf/manifest.json" ]]; then
  if [[ "$method" != full && ! -f "$dir/merged/config.json" ]]; then
    log "merge $run"; timed "merge:$run" "$PY" -m ml.train.merge --run "$dir" >>"$MURMUR_LOGS/train-$run.log" 2>&1
  fi
  log "export $run ($QUANTS)"
  # shellcheck disable=SC2086
  timed "export:$run" "$PY" -m ml.train.export --run "$dir" --name "$name" --quants $QUANTS --keep-bf16 \
    >>"$MURMUR_LOGS/export-$run.log" 2>&1
fi
[[ -n "${KEEP_MERGED:-}" ]] || rm -rf "$dir/merged"
if [[ ! -f "$dir/eval/Q4_0-dev/report.json" ]]; then
  log "eval $run Q4_0 on dev (llama.cpp CUDA)"
  timed "eval:$run:Q4_0-dev" "$PY" -m ml.eval.run_eval --model "$dir/gguf/$name-Q4_0.gguf" --data "${DEV[@]}" \
    --out "$dir/eval/Q4_0-dev" --system finetuned --label "$run Q4_0 dev" --llama-bin "$MURMUR_LLAMA_BIN" --ngl "$MURMUR_LLAMA_NGL" \
    >>"$MURMUR_LOGS/eval-$run.log" 2>&1
fi
jq -c '{run: "'"$run"'", verdict: .gates.verdict, em: .summary.overall.em, nwer: .summary.overall.nwer,
        noop: .summary.overall.noop_exact}' "$dir/eval/Q4_0-dev/report.json" | tee -a "$MURMUR_LOGS/runs.jsonl"
