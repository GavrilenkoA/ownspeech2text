#!/usr/bin/env bash
# Run ON THE DEV SERVER after a training session (the pod may already be stopped once step 1 is done):
#   1. pull results + the finalists' Q4_0 GGUFs from the pod            (skipped with NO_SYNC=1)
#   2. CPU ship-gate eval of each finalist's Q4_0 on the test sets       (llama.cpp CPU, 6 threads, greedy)
#   3. LLM judge (teacher, Responses API) on those reports -> judge_mean / drift gates, then rescore
#   4. stock baselines (B2) on the dev server CPU: vendor Q4_0 GGUFs x prompted-model profiles
#   5. model cards + catalog entries from the CPU report (the ship measurement), leaderboard
# Steps 2-4 are resumable (predictions/judgements are cached per example). The judge costs one
# teacher request per example (~400 per finalist); JUDGE_LIMIT caps it per eval dir.
#   POD=root@1.2.3.4 POD_PORT=22022 bash ml/pod/after_pod.sh
#   NO_SYNC=1 STEPS="judge cards" bash ml/pod/after_pod.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
R="${MURMUR_POD_RESULTS:-.cache/ml/train/pod-results}"; export DST="$R"   # sync_results.sh writes here
PY=.cache/ml/train/venv/bin/python
export PYTHONPATH="$PWD" OMP_NUM_THREADS="${OMP_NUM_THREADS:-6}"
LLAMA_BIN="${MURMUR_LLAMA_BIN:-.cache/ml/llm/bin/llama-b12503}"
STEPS="${STEPS:-sync ship judge stock cards}"
PROFILES="${PROFILES:-default.v4 default.v4s}"   # what the app ships for stock models; reports carry raw + guarded
STOCK_P="${PROFILES%% *}"
read -r -a TEST <<<"${EVAL_TEST:-$R/data/test.jsonl prompts/eval/sets/test.v2.jsonl}"
has() { [[ " $STEPS " == *" $1 "* ]]; }
log() { echo "[$(date -u +%FT%TZ)] $*" >&2; }
heavy_eval() { tools/heavy "$PY" -m ml.eval.run_eval "$@"; }

if has sync && [[ -z "${NO_SYNC:-}" ]]; then WITH_GGUF=1 bash ml/pod/sync_results.sh; fi
[[ -f "$R/runs/finalists.txt" ]] || { log "no $R/runs/finalists.txt (did export_eval.sh run on the pod?)"; exit 2; }
mapfile -t FIN < <(awk '{print $2}' "$R/runs/finalists.txt" | sort -u)

if has ship; then
  for run in "${FIN[@]}"; do
    g=$(ls "$R/runs/$run/gguf/"*-Q4_0.gguf 2>/dev/null | head -1) || true
    [[ -n "$g" ]] || { log "no Q4_0 GGUF for $run (sync with WITH_GGUF=1)"; continue; }
    out="$R/runs/$run/eval/Q4_0-test-cpu"
    ftsys=$("$PY" -c "import json;print(json.load(open('$R/runs/$run/config.resolved.json')).get('prompt',{}).get('system','finetuned.v2'))")
    [[ -f "$out/report.json" ]] && { log "ship eval $run: done"; continue; }
    log "ship eval $run on CPU -> $out"
    heavy_eval --model "$g" --data "${TEST[@]}" --out "$out" --system "$ftsys" --label "$run Q4_0 test cpu" \
      --llama-bin "$LLAMA_BIN" --ngl 0 --threads 6 >/dev/null
  done
fi

if has judge; then
  dirs=()
  for run in "${FIN[@]}"; do for e in Q4_0-test-cpu Q4_0-test; do
    [[ -f "$R/runs/$run/eval/$e/predictions.jsonl" ]] && dirs+=("$R/runs/$run/eval/$e"); done; done
  if ((${#dirs[@]})); then
    tools/with-teacher "$PY" -m ml.eval.judge "${dirs[@]}" --data-root "$R/data" ${JUDGE_LIMIT:+--limit $JUDGE_LIMIT}
    for d in "${dirs[@]}"; do "$PY" -m ml.eval.run_eval --rescore "$d" --data-root "$R/data" >/dev/null; done
  fi
fi

if has stock; then
  for f in .cache/ml/llm/LFM3-1.7B-QAD-Q4_0.gguf .cache/ml/llm/Qwen3.5-0.8B-Q4_0.gguf .cache/ml/llm/Qwen3.5-2B-Q4_0.gguf; do
    [[ -f "$f" ]] || continue
    for p in $PROFILES; do
      out="$R/eval/stock/$(basename "$f" .gguf)/$p-test-cpu"
      [[ -f "$out/report.json" ]] && continue
      log "stock $f $p -> $out"
      heavy_eval --model "$f" --data "${TEST[@]}" --out "$out" --system "$p" --label "stock $(basename "$f" .gguf) $p test cpu" \
        --llama-bin "$LLAMA_BIN" --ngl 0 --threads 6 >/dev/null
    done
  done
fi

if has cards; then
  for run in "${FIN[@]}"; do
    dir="$R/runs/$run"; man="$dir/gguf/manifest.json"; [[ -f "$man" ]] || continue
    name=$("$PY" -c "import json;print(json.load(open('$man'))['name'])")
    ev="$dir/eval/Q4_0-test-cpu/report.json"; [[ -f "$ev" ]] || ev="$dir/eval/Q4_0-test/report.json"
    case "$run" in lfm3*|full*) st=LFM3-1.7B-QAD-Q4_0;; q08*) st=Qwen3.5-0.8B-Q4_0;; *) st=Qwen3.5-2B-Q4_0;; esac
    stock="$R/eval/stock/$st/$STOCK_P-test-cpu/report.json"
    "$PY" -m ml.train.card --run "$dir" --name "$name" --repo "${HF_OWNER:-OWNER}/$name-gguf" --eval "$ev" \
      $([[ -f "$dir/eval/quant_loss.json" ]] && echo --qloss-json "$dir/eval/quant_loss.json") \
      $([[ -f "$stock" ]] && echo --stock-eval "$stock") >/dev/null && log "card: $dir/gguf/README.md"
  done
  "$PY" -m ml.eval.leaderboard "$R/eval" "$R/runs" --out "$R/leaderboard.md" && log "leaderboard: $R/leaderboard.md"
fi
