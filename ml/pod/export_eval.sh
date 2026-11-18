#!/usr/bin/env bash
# After train_matrix.sh: baselines + finalists, with ONE eval implementation (ml/eval/run_eval.py),
# llama.cpp CUDA on the pod (greedy). Idempotent: every eval dir with a report.json is skipped.
#   1. stock baselines (B2), only with STOCK_ON_POD=1 (default: they run free on the dev server CPU in
#      ml/pod/after_pod.sh, the vendor GGUFs are already there): vendor Q4_0 GGUFs with the
#      prompted-model profiles and the short fine-tune prompt; STOCK_OURS=1 adds the stock weights
#      through OUR conversion (plain Q4_0: isolates the fine-tuning effect from vendor imatrix/QAD)
#   2. finalists = best run per base model on the dev Q4_0 eval (ml.train.select):
#      BF16 GGUF on dev (-> measured quantisation loss), Q4_0 on the test sets, Q4_0 with the long
#      default prompt (robustness to prompt edits), model card + catalog entry
#   3. leaderboard over everything
# The judge (teacher, dev server) and the CPU ship-gate eval run on the dev server afterwards:
# ml/pod/after_pod.sh. Env: EVAL_DEV, EVAL_TEST, PROFILES (prompted-model profiles, default
# "default default.v2"), HF_OWNER (for the card's repo id).
set -euo pipefail
cd "$(dirname "$0")/../.."
source ml/pod/env.sh
PY="$VENV_TRAIN/bin/python"
SMOKE="$MURMUR_DATA/smoke.test.jsonl"
[[ -f "$SMOKE" ]] || "$PY" -m ml.eval.smoke_to_contract --split test --out "$SMOKE" >/dev/null
read -r -a DEV <<<"${EVAL_DEV:-$MURMUR_DATA/dev.jsonl prompts/eval/sets/dev.v1.jsonl $SMOKE}"
read -r -a TEST <<<"${EVAL_TEST:-$MURMUR_DATA/test.jsonl prompts/eval/sets/test.v1.jsonl}"
PROFILES="${PROFILES:-default default.v2}"

evaluate() {  # $1=model file  $2=out dir  $3=system/profile  $4=label  $5=dev|test
  [[ -f "$2/report.json" ]] && { log "eval $4: done, skip"; return 0; }
  local sets=("${DEV[@]}"); [[ "$5" == test ]] && sets=("${TEST[@]}")
  [[ -f "prompts/profiles/$3.json" || -f "prompts/system/$3.md" || -f "prompts/system/$3.v1.md" ]] \
    || { log "eval $4: profile $3 not found, skip"; return 0; }
  log "eval $4 ($3, $5)"
  timed "eval:$4" "$PY" -m ml.eval.run_eval --model "$1" --data "${sets[@]}" --out "$2" --system "$3" --label "$4" \
    --llama-bin "$MURMUR_LLAMA_BIN" --ngl "$MURMUR_LLAMA_NGL" >>"$MURMUR_LOGS/eval-final.log" 2>&1
}

# ---- 1. stock baselines (optional on the pod)
if [[ -n "${STOCK_ON_POD:-}" ]]; then
mkdir -p "$W/stock"
dl() { [[ -f "$W/stock/$2" ]] || curl -fL --retry 5 -o "$W/stock/$2" "https://huggingface.co/$1/resolve/main/$2"; }
dl LiquidAI/LFM3-1.7B-GGUF LFM3-1.7B-QAD-Q4_0.gguf
dl unsloth/Qwen3.5-0.8B-GGUF Qwen3.5-0.8B-Q4_0.gguf
dl unsloth/Qwen3.5-2B-GGUF Qwen3.5-2B-Q4_0.gguf
for f in LFM3-1.7B-QAD-Q4_0 Qwen3.5-0.8B-Q4_0 Qwen3.5-2B-Q4_0; do
  for p in $PROFILES finetuned; do
    for s in dev test; do evaluate "$W/stock/$f.gguf" "$MURMUR_EVAL/stock/$f/$p-$s" "$p" "stock $f $p $s" "$s"; done
  done
done
fi
for base in ${STOCK_OURS:+LiquidAI/LFM3-1.7B Qwen/Qwen3.5-0.8B Qwen/Qwen3.5-2B}; do
  sname="stock-$(basename "$base" | tr '[:upper:]' '[:lower:]')"; sdir="$W/stock/$sname"
  if [[ ! -f "$sdir/manifest.json" ]]; then
    snap=$("$PY" -c "from huggingface_hub import snapshot_download as s; print(s('$base'))")
    timed "export:$sname" "$PY" -m ml.train.export --hf "$snap" --name "$sname" --out "$sdir" --quants Q4_0 \
      >>"$MURMUR_LOGS/export-$sname.log" 2>&1
  fi
  evaluate "$sdir/$sname-Q4_0.gguf" "$MURMUR_EVAL/stock/$sname/default-dev" default "$sname ours-Q4_0 default dev" dev
done

# ---- 2. finalists: best run per base model
declare -A FAM=()
for d in "$MURMUR_RUNS"/*/train_info.json; do
  [[ -f "$d" ]] || continue
  r=$(basename "$(dirname "$d")"); b=$(jq -r .base_model "$d"); FAM[$b]+="$r "
done
for b in "${!FAM[@]}"; do
  # shellcheck disable=SC2086
  run=$("$PY" -m ml.train.select --runs-dir "$MURMUR_RUNS" ${FAM[$b]} | sed -n 1p) || continue
  echo "$b $run" >>"$MURMUR_RUNS/finalists.txt"; log "finalist for $b: $run"
  dir="$MURMUR_RUNS/$run"; name=$(jq -r .name "$dir/gguf/manifest.json")
  evaluate "$dir/gguf/$name-BF16.gguf" "$dir/eval/BF16-dev" finetuned "$run BF16 dev" dev
  evaluate "$dir/gguf/$name-Q4_0.gguf" "$dir/eval/Q4_0-test" finetuned "$run Q4_0 test" test
  evaluate "$dir/gguf/$name-Q4_0.gguf" "$dir/eval/Q4_0-dev-defaultprompt" default "$run Q4_0 dev default-prompt" dev
  "$PY" -m ml.eval.compare "$dir/eval/BF16-dev/report.json" "$dir/eval/Q4_0-dev/report.json" \
    --json "$dir/eval/quant_loss.json" --md "$dir/eval/quant_loss.md" >/dev/null
  case "$b" in *LFM3*) st="LFM3-1.7B-QAD-Q4_0";; *0.8B*) st="Qwen3.5-0.8B-Q4_0";; *) st="Qwen3.5-2B-Q4_0";; esac
  "$PY" -m ml.train.card --run "$dir" --name "$name" --repo "${HF_OWNER:-OWNER}/$name-gguf" \
    --eval "$dir/eval/Q4_0-test/report.json" --qloss-json "$dir/eval/quant_loss.json" \
    $([[ -f "$MURMUR_EVAL/stock/$st/default-test/report.json" ]] && echo --stock-eval "$MURMUR_EVAL/stock/$st/default-test/report.json") \
    >/dev/null
done
sort -u -o "$MURMUR_RUNS/finalists.txt" "$MURMUR_RUNS/finalists.txt" 2>/dev/null || true

# ---- 3. leaderboard
"$PY" -m ml.eval.leaderboard "$MURMUR_EVAL/stock" "$MURMUR_RUNS" --out "$MURMUR_EVAL/leaderboard.md"
log "export_eval finished; leaderboard: $MURMUR_EVAL/leaderboard.md"
