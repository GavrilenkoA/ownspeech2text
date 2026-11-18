#!/usr/bin/env bash
# Regenerate every mining output with the current code:
#   1. the hand-made fixture            -> ml/roundtrip/samples/mined/fixture/
#   2. real speech, enrich adapter      -> .cache/ml/roundtrip/mined/enrich/  (sample: samples/mined/enrich/)
#   3. the real TTS->ASR round trip     -> .cache/ml/roundtrip/mined/real/    (sample: samples/mined/real/)
# Steps 2 and 3 are skipped when their input is absent. Run from anywhere; heavy steps go through tools/heavy.
#   ROUNDTRIP_ROWS=path/to/roundtrip.jsonl ml/roundtrip/mine/regen_outputs.sh
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO"
PY=.cache/ml/roundtrip/venv/bin/python
H=tools/heavy
S=ml/roundtrip/samples/mined

F=$S/fixture
$PY -m ml.roundtrip.mine.mine_errors $S/fixture_roundtrip.jsonl --out $F \
  --title "Error mining — hand-made fixture (not real ASR output)"
$PY -m ml.roundtrip.mine.noise calibrate --rows $S/fixture_roundtrip.jsonl --mined $F --out $F/calibration --repeats 5
$PY -m ml.roundtrip.mine.noise calibrate --rows $S/fixture_roundtrip.jsonl --holdout-frac 0.35 \
  --out $F/calibration_holdout --repeats 5

ENRICH_ROWS=.cache/ml/roundtrip/mined/enrich_rows.jsonl
if [ -f .cache/ml/data/enrich/train.parquet ]; then
  $H $PY -m ml.roundtrip.mine.adapters enrich --out $ENRICH_ROWS
fi
if [ -f $ENRICH_ROWS ]; then
  E=.cache/ml/roundtrip/mined/enrich
  $H $PY -m ml.roundtrip.mine.mine_errors $ENRICH_ROWS --out $E --jobs 12 \
    --title "Error mining — real speech, MishZor256/russian-asr-enrichment (cv21_ru + rulibrispeech, human ref)"
  $H $PY -m ml.roundtrip.mine.noise calibrate --rows $ENRICH_ROWS --mined $E --out $E/calibration --repeats 2 --jobs 12
  $H $PY -m ml.roundtrip.mine.noise calibrate --rows $ENRICH_ROWS --holdout-frac 0.3 --out $E/calibration_holdout \
    --repeats 2 --jobs 12
  mkdir -p $S/enrich/calibration $S/enrich/calibration_holdout
  cp $E/REPORT.md $E/profiles.json $S/enrich/
  # the full confusion table is large: commit the first (most frequent) 150 rows per model
  $PY -I -c 'import collections, sys
seen = collections.Counter()
with open(sys.argv[1], encoding="utf-8") as src, open(sys.argv[2], "w", encoding="utf-8") as dst:
    for line in src:
        m = line.split("\"asr_model\": \"", 1)[1].split("\"", 1)[0]
        if seen[m] < 150:
            dst.write(line); seen[m] += 1' $E/confusions.jsonl $S/enrich/confusions.top300.jsonl
  for c in calibration calibration_holdout; do cp $E/$c/CALIBRATION.md $E/$c/calibration.json $S/enrich/$c/; done
fi

RT_ROWS="${ROUNDTRIP_ROWS:-.cache/ml/roundtrip/mined/real/rows.jsonl}"
if [ -f "$RT_ROWS" ]; then
  R=.cache/ml/roundtrip/mined/real
  mkdir -p $R
  [ "$(realpath "$RT_ROWS")" = "$(realpath -m $R/rows.jsonl)" ] || cp "$RT_ROWS" $R/rows.jsonl
  N=$(wc -l < $R/rows.jsonl)
  $H $PY -m ml.roundtrip.mine.mine_errors $R/rows.jsonl --out $R --jobs 12 \
    --title "Error mining — real round trip ($N rows, TTS -> ASR over the probe set)"
  $H $PY -m ml.roundtrip.mine.noise calibrate --rows $R/rows.jsonl --mined $R --out $R/calibration --repeats 4 --jobs 12
  $H $PY -m ml.roundtrip.mine.noise calibrate --rows $R/rows.jsonl --holdout-frac 0.3 --out $R/calibration_holdout \
    --repeats 4 --jobs 12
  mkdir -p $S/real/calibration $S/real/calibration_holdout
  cp $R/REPORT.md $R/profiles.json $R/confusions.jsonl $R/term_manglings.jsonl $S/real/
  for c in calibration calibration_holdout; do cp $R/$c/CALIBRATION.md $R/$c/calibration.json $S/real/$c/; done
fi
echo REGEN_DONE
