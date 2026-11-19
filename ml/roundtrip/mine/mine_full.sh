#!/usr/bin/env bash
# Mine the full CPU round trip (docs/ml/ROUNDTRIP.md, Mining, "Full run results") and publish the committed
# sample ml/roundtrip/samples/mined/full/ (the generator's --mined directory). Run from anywhere.
#   ml/roundtrip/mine/mine_full.sh            # everything
#   STEPS=publish ml/roundtrip/mine/mine_full.sh   # only re-copy the committed sample from the cache
# Big intermediates stay in .cache/ml/roundtrip/mined/full/.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO"
P=.cache/ml/roundtrip/venv/bin/python
H="tools/heavy nice -n 15"
J=12
O=.cache/ml/roundtrip/out/full
R=$O/roundtrip/full.jsonl
F=$O/flags_supertonic_digit_names.jsonl
M=.cache/ml/roundtrip/mined/full
S=ml/roundtrip/samples/mined/full
STEPS="${STEPS:-clean mine calibrate publish}"
mkdir -p $M
has(){ case " $STEPS " in *" $1 "*) return 0;; *) return 1;; esac; }

if has clean; then
  # primary rows: rules R1-R6 (clean_rows.py); "all" rows: the same without R1 (Piper's Russian voices kept)
  $P -m ml.roundtrip.mine.clean_rows $R --flags $F --out $M/primary_rows.jsonl \
    --excluded $M/primary_excluded.jsonl --summary $M/primary_clean.json
  $P -m ml.roundtrip.mine.clean_rows $R --flags $F --keep-piper-ru --out $M/all_rows.jsonl \
    --excluded $M/all_excluded.jsonl --summary $M/all_clean.json
fi

if has mine; then
  $H $P -m ml.roundtrip.mine.mine_errors $M/primary_rows.jsonl --out $M/primary --jobs $J \
    --title "Error mining — full CPU run, primary voices (no Piper ru, TTS-garbled clips dropped)"
  $H $P -m ml.roundtrip.mine.mine_errors $M/all_rows.jsonl --out $M/all --jobs $J --title "Error mining — full CPU run, all voices"
  for t in supertonic3 piper kokoro1; do
    $H $P -m ml.roundtrip.mine.mine_errors $M/all_rows.jsonl --tts $t --out $M/tts-$t --jobs $J \
      --title "Error mining — full CPU run, $t"
    $H $P -m ml.roundtrip.mine.mine_errors $M/all_rows.jsonl --tts $t --channels clean --out $M/tts-$t-clean --jobs $J \
      --title "Error mining — full CPU run, $t, clean"
  done
  for c in clean phone_noisy; do
    $H $P -m ml.roundtrip.mine.mine_errors $M/primary_rows.jsonl --channels $c --out $M/ch-$c --jobs $J \
      --title "Error mining — full CPU run, primary, $c"
  done
fi

if has calibrate; then
  $H $P -m ml.roundtrip.mine.noise calibrate --rows $M/primary_rows.jsonl --mined $M/primary \
    --out $M/primary/calibration --repeats 4 --jobs $J
  $H $P -m ml.roundtrip.mine.noise calibrate --rows $M/primary_rows.jsonl --holdout-frac 0.3 \
    --out $M/primary/calibration_holdout --repeats 4 --jobs $J
  $H $P -m ml.roundtrip.mine.noise calibrate --rows ml/roundtrip/samples/roundtrip/final80.jsonl \
    --mined $M/primary --cross --out $M/primary/calibration_cross_final80 --repeats 4 --jobs $J
  # like for like: only the 80-probe run's Supertonic rows (the full tables have no Piper ru)
  $P -I -c 'import json, sys
with open(sys.argv[2], "w", encoding="utf-8") as out:
    for line in open(sys.argv[1], encoding="utf-8"):
        if json.loads(line)["tts_system"] == "supertonic3":
            out.write(line)' ml/roundtrip/samples/roundtrip/final80.jsonl $M/final80_supertonic.jsonl
  $H $P -m ml.roundtrip.mine.noise calibrate --rows $M/final80_supertonic.jsonl \
    --mined $M/primary --cross --out $M/primary/calibration_cross_final80_supertonic --repeats 4 --jobs $J
fi

if has publish; then
  rm -rf $S
  mkdir -p $S
  cp $M/primary/profiles.json $M/primary/term_manglings.jsonl $M/primary/REPORT.md $S/
  # confusions: only the non-term rows. The noise sampler skips in-term rows (term errors come from
  # term_manglings.jsonl), so the sampler built from this copy is identical; the full table stays in $M.
  grep -F '"in_term": false' $M/primary/confusions.jsonl > $S/confusions.jsonl
  cp $M/primary_clean.json $S/clean_summary.json
  for c in calibration calibration_holdout calibration_cross_final80 calibration_cross_final80_supertonic; do
    if [ -f $M/primary/$c/calibration.json ]; then
      mkdir -p $S/$c
      cp $M/primary/$c/CALIBRATION.md $M/primary/$c/calibration.json $S/$c/
    fi
  done
  # per-TTS and per-channel reports (human-readable only)
  mkdir -p $S/reports
  for d in all tts-supertonic3 tts-piper tts-kokoro1 tts-supertonic3-clean tts-piper-clean tts-kokoro1-clean ch-clean ch-phone_noisy; do
    [ -f $M/$d/REPORT.md ] && cp $M/$d/REPORT.md $S/reports/$d.md
  done
  du -sb $S
fi
echo MINE_FULL_DONE
