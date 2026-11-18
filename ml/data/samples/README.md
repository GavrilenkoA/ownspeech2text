# Samples

`synth_v2.sample.jsonl` — 197 records of the 2026-11-18 proof run (`.cache/ml/data/synth_runs/proof_v6`):
real teacher (`gpt-6.2-sol`, prompt `gen.v5`), real round-trip noise tables (`ml/roundtrip/samples/mined/real`),
real render + OCR ctx v2 (`ml/screens` + `ml/ocr`, `ocr.v1`). Three English `context_counterpart` records were
left out because the planner no longer produces that cell (docs/ml/DATA_CARD.md §5).

Read one with its rendered prompt:

```bash
.cache/ml/data/venv/bin/python -c "import json,sys; from ml.common.render_bridge import to_prompt; \
r=[json.loads(l) for l in open('ml/data/samples/synth_v2.sample.jsonl')][int(sys.argv[1])]; \
p=to_prompt(r); print(p['messages'][1]['content']); print('---'); print(p['completion'])" 0
```
