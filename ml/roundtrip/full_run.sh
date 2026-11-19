#!/usr/bin/env bash
# Full CPU round trip (docs/ml/ROUNDTRIP.md, "Full run plan"), detached from the calling shell.
#   ml/roundtrip/full_run.sh start    # build the plan if missing, launch detached (also = resume)
#   ml/roundtrip/full_run.sh status   # done/total jobs, rows, errors, rate, ETA
#   ml/roundtrip/full_run.sh stop     # clean stop: no new shards, running shards finish, DONE written
# Output: .cache/ml/roundtrip/out/full/ (plan.jsonl, roundtrip/full.jsonl rows, run.log, DONE = exit code).
# The run goes through tools/heavy (MURMUR_MEM, default 16G here) at nice 15, 8 workers x 2 threads,
# --mem-gb 12, --max-wall-h ${MAX_WALL_H:-4} per invocation. Rerunning `start` continues without duplicates.
# History and how to watch/stop/mine: .cache/ml/roundtrip/out/full/RUN.md
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
SELF="$REPO/ml/roundtrip/full_run.sh"
PY=.cache/ml/roundtrip/venv/bin/python
OUT="${RT_OUT:-.cache/ml/roundtrip/out/full}"
RUN=full
PLAN="$OUT/plan.jsonl"

runner_args=(
  -m ml.roundtrip.run_roundtrip
  --probe ml/roundtrip/probe/seed.jsonl ml/roundtrip/probe/gen.jsonl
  --plan "$PLAN" --out "$OUT" --run "$RUN"
  --tts piper,supertonic3,kokoro1 --asr all
  --workers 8 --threads 2 --mem-gb 12 --seed 13
  --stop-file "$OUT/STOP" --max-wall-h "${MAX_WALL_H:-4}"
)

running_pid() {
  [ -f "$OUT/runner.pid" ] || return 1
  local pid; pid=$(cut -d' ' -f1 < "$OUT/runner.pid")
  kill -0 "$pid" 2>/dev/null && echo "$pid"
}

case "${1:-}" in
  start)
    if pid=$(running_pid); then echo "already running (pid $pid)"; exit 1; fi
    mkdir -p "$OUT"
    if [ ! -f "$PLAN" ]; then
      $PY -m ml.roundtrip.full_plan --n "${N_SENTENCES:-1000}" --out "$PLAN" > /dev/null
    fi
    rm -f "$OUT/DONE" "$OUT/STOP"
    echo "=== start $(date -Is) by $(id -un)" >> "$OUT/run.log"
    MURMUR_MEM="${MURMUR_MEM:-16G}" setsid nohup tools/heavy nice -n 15 "$SELF" _inner \
      >> "$OUT/run.log" 2>&1 < /dev/null &
    disown || true
    for _ in $(seq 1 20); do sleep 0.5; { running_pid > /dev/null || [ -f "$OUT/DONE" ]; } && break; done
    if pid=$(running_pid); then echo "started: pid $pid, log $OUT/run.log"
    elif [ -f "$OUT/DONE" ]; then echo "ran and ended already: $(cat "$OUT/DONE")"
    else echo "did not start; see $OUT/run.log"; exit 1; fi
    ;;
  _inner)
    echo "$$ $(date -Is)" > "$OUT/runner.pid"
    set +e
    "$PY" "${runner_args[@]}"
    rc=$?
    echo "exit=$rc $(date -Is)" > "$OUT/DONE"
    echo "=== end exit=$rc $(date -Is)"
    exit $rc
    ;;
  stop)
    touch "$OUT/STOP"
    echo "STOP requested; running shards finish, then DONE appears (exit=1 while jobs are pending)"
    ;;
  status)
    exec "$PY" -m ml.roundtrip.progress --out "$OUT" --run "$RUN" "${@:2}"
    ;;
  args)
    printf '%q ' "$PY" "${runner_args[@]}"; echo
    ;;
  *)
    sed -n '2,8p' "$SELF"; exit 2
    ;;
esac
