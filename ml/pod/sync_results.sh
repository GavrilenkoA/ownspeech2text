#!/usr/bin/env bash
# Run ON THE DEV SERVER: pull results from the pod volume (the pod never needs credentials for the dev
# server). Small files by default: reports, predictions, cards, manifests, logs, timings, train_info.
#   POD=root@1.2.3.4 POD_PORT=22022 bash ml/pod/sync_results.sh
#   WITH_GGUF=1     + finalists' Q4_0 GGUFs (for the CPU ship-gate eval and upload; ~0.5-1.3 GB each)
#   WITH_ADAPTERS=1 + LoRA adapters (tens of MB each)
#   WITH_PROBE=1    + the round-trip probe outputs under $W/roundtrip (owned by ml/roundtrip)
# Destination: .cache/ml/train/pod-results/ (gitignored). Incremental: safe to re-run.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${POD:?set POD=user@host}"
PORT="${POD_PORT:-22}"; RW="${POD_WORKSPACE:-/workspace}"
SSH="ssh -p $PORT ${POD_KEY:+-i $POD_KEY} -o StrictHostKeyChecking=accept-new"
DST="${DST:-.cache/ml/train/pod-results}"; mkdir -p "$DST"
# rsync: first matching rule wins -> big-dir excludes, then includes, then exclude everything else
exc=(--exclude='checkpoint-*/' --exclude='merged/' --exclude='hf/' --exclude='stock/' --exclude='train.jsonl')
if [[ -n "${WITH_ADAPTERS:-}" ]]; then exc+=(--exclude='final/*.safetensors'); else exc+=(--exclude='final/'); fi
inc=(--include='*/' --include='*.json' --include='*.jsonl' --include='*.md' --include='*.log' --include='*.txt'
     --include='*.yaml')
if [[ -n "${WITH_GGUF:-}" ]]; then
  while read -r _ run; do inc+=(--include="runs/$run/gguf/*-Q4_0.gguf"); done \
    < <($SSH "$POD" "cat $RW/runs/finalists.txt 2>/dev/null" || true)
fi
[[ -n "${WITH_ADAPTERS:-}" ]] && inc+=(--include='final/adapter/**')
rsync -az --prune-empty-dirs -e "$SSH" "${exc[@]}" "${inc[@]}" --exclude='*' \
  "$POD:$RW/runs" "$POD:$RW/eval" "$POD:$RW/logs" "$POD:$RW/data" "$DST/"
[[ -n "${WITH_PROBE:-}" ]] && rsync -az -e "$SSH" "$POD:$RW/roundtrip" "$DST/"
du -sh "$DST"
