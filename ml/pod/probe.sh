#!/usr/bin/env bash
# ======================================================================================
#  ROUND-TRIP PROBE STAGE: OWNED BY ml/roundtrip (docs/ml/ROUNDTRIP.md). NOT IMPLEMENTED HERE.
# ======================================================================================
# This wrapper only gives the probe the same pod conventions as the other stages: env, timing into
# $MURMUR_LOGS/timings.jsonl, logs, and an idempotency marker. The roundtrip team provides the command
# (real text -> several TTS voices -> every hub ASR model, on the GPU; first a 200-utterance throughput
# measurement) and writes its outputs under $W/roundtrip/ (pulled back by sync_results.sh WITH_PROBE=1;
# error mining then runs on the dev server, not on the pod).
#   MURMUR_PROBE_CMD='python -m ml.roundtrip.<entry> --out $W/roundtrip ...' bash ml/pod/probe.sh
# If ml/roundtrip/pod_probe.sh exists it is used when MURMUR_PROBE_CMD is unset.
set -euo pipefail
cd "$(dirname "$0")/../.."
source ml/pod/env.sh
mkdir -p "$W/roundtrip"
[[ -f "$MARK_VOL/probe-done" && -z "${REDO_PROBE:-}" ]] && { log "probe: done, skip (REDO_PROBE=1 to redo)"; exit 0; }
cmd="${MURMUR_PROBE_CMD:-}"
[[ -z "$cmd" && -x ml/roundtrip/pod_probe.sh ]] && cmd="bash ml/roundtrip/pod_probe.sh"
if [[ -z "$cmd" ]]; then
  log "probe: no MURMUR_PROBE_CMD and no ml/roundtrip/pod_probe.sh: nothing to run (owned by ml/roundtrip)"
  exit 4
fi
log "probe: $cmd"
timed probe bash -c "$cmd" 2>&1 | tee -a "$MURMUR_LOGS/probe.log"
rc=${PIPESTATUS[0]}
[[ $rc -eq 0 ]] && touch "$MARK_VOL/probe-done"
exit "$rc"
