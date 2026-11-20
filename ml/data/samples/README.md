# Samples

`synth_v2.sample.jsonl` — 200 training records (all 21 categories; no dev/test record) of the full set generated
2026-11-20 (`.cache/ml/data/synth_v2`, 3,599 records): real teacher (prompt `gen.v7`), the full round-trip noise
tables (`ml/roundtrip/samples/mined/full`, 12 ASR models) with the realism guards and per-model disfluency
rendering, real render + OCR ctx v2 (`ml/screens` + `ml/ocr`). Rebuild: `make -C ml/data sample RUN=.cache/ml/data/synth_v2`.

Provenance: targets, spoken texts and screen content are outputs of a proprietary hosted model; whether they may be
used to train and publish models is UNCONFIRMED (DATA_CARD §2a). Keep derived models private until the owner
confirms.

Read one with its rendered prompt:

```bash
.cache/ml/data/venv/bin/python -c "import json,sys; from ml.common.render_bridge import to_prompt; \
r=[json.loads(l) for l in open('ml/data/samples/synth_v2.sample.jsonl')][int(sys.argv[1])]; \
p=to_prompt(r); print(p['messages'][1]['content']); print('---'); print(p['completion'])" 0
```
