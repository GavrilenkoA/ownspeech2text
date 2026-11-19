# Samples

`synth_v2.sample.jsonl` — 190 records of the 2026-11-19 proof run (`.cache/ml/data/synth_runs/proof_v7`):
real teacher (`gpt-6.2-sol`, prompt `gen.v6`), real round-trip noise tables (`ml/roundtrip/samples/mined/real`)
with the realism guards and per-model disfluency rendering, real render + OCR ctx v2 (`ml/screens` + `ml/ocr`).
8 records that fail two rules added after the run (copied prompt illustration, long-word substitution threshold)
were left out (docs/ml/DATA_CARD.md §5).

Provenance: targets, spoken texts and screen content are outputs of a proprietary hosted model; whether they may be
used to train and publish models is UNCONFIRMED (DATA_CARD §2a). Keep derived models private until the owner
confirms.

Read one with its rendered prompt:

```bash
.cache/ml/data/venv/bin/python -c "import json,sys; from ml.common.render_bridge import to_prompt; \
r=[json.loads(l) for l in open('ml/data/samples/synth_v2.sample.jsonl')][int(sys.argv[1])]; \
p=to_prompt(r); print(p['messages'][1]['content']); print('---'); print(p['completion'])" 0
```
