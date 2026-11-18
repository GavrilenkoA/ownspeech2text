#!/usr/bin/env bash
# Pod entry point. PLAN_V2: the pod is STOPPED between sessions, so the stages are split by session:
#   session 1 (probe):  STAGES="bootstrap probe"        then stop the pod; dev server mines errors and
#                                                       generates the ~3K synthetic set with the teacher
#   session 2 (train):  STAGES="bootstrap train eval"   after push_code.sh WITH_DATA=1; then stop
# Every stage is idempotent: after any failure or stop, fix the cause and re-run the same command.
# Run inside tmux so an SSH drop does not kill it:
#   tmux new -s murmur "STAGES='bootstrap train eval' bash ml/pod/run_all.sh 2>&1 | tee -a /workspace/logs/run_all.log"
# Budget guard: MAX_GPU_HOURS (default 5, the owner's hard cap) is checked against the summed stage
# timings in $MURMUR_LOGS/timings.jsonl before each stage; the script stops instead of exceeding it.
set -euo pipefail
cd "$(dirname "$0")/../.."
source ml/pod/env.sh
STAGES="${STAGES:-bootstrap train eval}"
spent() { [[ -f "$MURMUR_LOGS/timings.jsonl" ]] && jq -s '[.[] | select(.stage | startswith("stage:")) | .seconds] | add // 0' \
  "$MURMUR_LOGS/timings.jsonl" || echo 0; }
for s in $STAGES; do
  used=$(spent); cap=$(awk "BEGIN{print int(${MAX_GPU_HOURS:-5}*3600)}")
  if (( used >= cap )); then log "budget: ${used}s of stage time used >= cap ${cap}s; stopping before $s"; exit 5; fi
  log "=== stage $s (stage time used so far: ${used}s)"
  case $s in
    bootstrap)  # base weights only when this session trains
      if [[ " $STAGES " == *" train "* ]]; then timed stage:bootstrap bash ml/pod/bootstrap.sh
      else MODELS="" timed stage:bootstrap bash ml/pod/bootstrap.sh; fi ;;
    probe) timed stage:probe bash ml/pod/probe.sh ;;
    train)
      [[ -f "$MURMUR_DATA/train.jsonl" && -f "$MURMUR_DATA/dev.jsonl" ]] \
        || { log "no $MURMUR_DATA/{train,dev}.jsonl: push it with ml/pod/push_code.sh WITH_DATA=1"; exit 2; }
      timed stage:train bash ml/pod/train_matrix.sh ;;
    eval) timed stage:eval bash ml/pod/export_eval.sh ;;
    *) log "unknown stage $s"; exit 2 ;;
  esac
done
log "run_all done ($STAGES); stage time used: $(spent)s"
[[ -f "$MURMUR_EVAL/leaderboard.md" ]] && cat "$MURMUR_EVAL/leaderboard.md" || true
