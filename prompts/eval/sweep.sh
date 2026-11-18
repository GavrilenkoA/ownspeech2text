#!/usr/bin/env bash
# Run every (model, profile, set) combination sequentially (one heavy job at a time) under tools/heavy.
#   prompts/eval/sweep.sh "q2b lfm3 q08" "default" "dev"
set -euo pipefail
cd "$(dirname "$0")/../.."
for m in $1; do for p in $2; do for s in ${3:-dev}; do
  echo "== $m $p $s $(date +%T)"
  tools/heavy python3 prompts/eval/run_eval.py --m "$m" --profile "$p" --set "$s" ${EXTRA:-} | head -1
done; done; done
