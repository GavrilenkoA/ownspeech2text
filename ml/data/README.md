# ml/data — dataset factory for the cleanup fine-tune

Owner of `ml/data/`, `ml/common/`, `docs/ml/DATA_CARD.md`. Binding: `docs/ml/PLAN_V2.md` (stage C) and
`docs/ml/CONTEXT_V2.md` (context = what OCR sees). Every record follows the shared contract
(`ml/common/schema.py`, ctx v2 validated against `prompts/context.schema.v2.json` or the fallback
`ml/common/ctx_v2.schema.json`); prompts are never stored, they are rendered at load time by `prompts/render.py`.

## Decisions

1. **Synthetic first, small**: 3,000 train + 300 dev + 300 test with explicit per-category counts
   (`synth/sampler.py: CATEGORY_TARGETS`), explicit shares of no-screen and no-field examples
   (`SCREEN_ABSENT_SHARE`, `FIELD_ABSENT_SHARE`). Public sets are an optional, frozen secondary source.
2. **The teacher writes meaning, code writes noise.** One Responses-API request carries 6 scenarios; the teacher
   returns screen content + `spoken` (clean verbatim words, numbers in words) + `target`. Code adds disfluencies,
   the ASR channel and hallucinated tails, so every noise op is known and recorded in `meta`.
3. **ASR noise = the round-trip team's mined tables** (`ml/roundtrip/mine/noise.py` behind `synth/channel.py`,
   `--mined DIR`; real tables: `ml/roundtrip/samples/mined/real`, 11 ASR models). Fallback: built-in simulator
   (`synth/asr_noise.py`). `meta.noise.source` says which one produced each transcript. Realism guards on top
   (`Channel.implausible` / `repair_terms`, review 2026-11-19): substitutions that do not sound like the word
   (`по -> девять`, `стенде -> съем`) and inserted content words (`папы`) are retried with another seed; generic
   term garbles a model's mined manglings never show (single letters, interior letter drops such as `Kuberetes`)
   are replaced by a phonetic transliteration for models that write Latin terms in Cyrillic, else retried;
   English clauses of code-switched speech are simulated as English for recognisers that write Latin (no
   word-by-word `the -> tha`, `you -> йау`).
9. **Disfluencies follow the recogniser.** Injected fillers are only those the deployed prompt deletes ("слушай"
   is never injected: it often carries meaning). Whether a hesitation sound or a repeated word reaches the
   transcript depends on the ASR model (`ml/data/probe/disfluency_render.v1.json`, measured with a CPU TTS -> ASR
   probe, `make disfl-probe`; hesitations capped by a family prior because TTS articulates "э"/"um" as words).
   `stats.json: asr_disfluency` reports the per-model hesitation-token rate of every run.
10. **No eval leakage, human review list.** Records that near-duplicate a `prompts/eval` item (char-5-gram Jaccard
   >= 0.8) are dropped (`ml/data/contam.py`); the teacher prompt's quoted examples are tested against the same sets.
   Kept records whose target has a content word found in none of spoken / transcript / screen go to `review.jsonl`.
11. **Apps are coherent**: `app.category` comes only from the shared `prompts/tones/app_categories.v1.json`
   (unknown package -> `default`, as on the phone); the screen template follows the app
   (`synth/screen.py: template_for`), address fields exist only in mail apps (e-mail) and browsers (URL), and no
   prose is dictated into a terminal.
4. **ctx = render -> real OCR** (`synth/screen.py`): teacher content -> `ml.screens.specs.from_teacher` spec
   (device / theme / font scale / keyboard / locale from the scenario seed, `avoid` list for must-not-show items) ->
   `ml.screens.build_ctx` -> ctx v2; app / field come from the scenario (accessibility side, exact). Category rules
   are checked on `ctx.screen.lines` AND on the `<context>` block of the current `prompts/render.py`; one re-render
   with the other theme and another font scale, then drop (`build:ocr:<rule>`, counted per category and per term).
   `meta.screen` keeps the spec, the render parameters and the OCR pipeline version, so an example can be rebuilt.
   OCR lines are never edited. PNGs are not kept.
5. **Context cases in pairs.** A hard IT/AI term garbled in the transcript and readable on screen
   (`context_resolution`); its counterpart re-renders the same screen without the supporting items and runs OCR
   again; the target keeps the transcript's word. Well-known terms and plain English phrases get no counterpart.
6. **Leakage-free by construction**: terms, people and injection strings are hash-partitioned into train/dev/test
   pools; held-out scenarios only draw from their pool; near-duplicates prefer held-out splits.
7. **Strict deterministic filters** (`synth/filters.py`, `recheck.py`) instead of an LLM judge (quota is shared):
   anything that adds, drops, translates, answers, leaves a command/marker, or misuses context is rejected and logged.
8. **Resumable and honest about reuse**: teacher job ids hash the exact request text, so a changed scenario or
   prompt is never answered from an old shard; re-running a command continues where it stopped.

## Layout

| Path | What |
|---|---|
| `generate.py` | ONE command: plan -> teacher (resumable shards) -> render + OCR + noise + filters (worker pool) -> dedup -> exact counts -> split |
| `recheck.py` | re-apply CONTEXT_V2 rules to stored records with the current `prompts/render.py` (no OCR, no teacher) |
| `synth/sampler.py` | scenario planner: category/lang/app/field/screen-absent/persona/terms/distractors/injection/noise/render knobs |
| `synth/screen.py` | render specs, real / fixture backends, OCR-line rule checks |
| `synth/screen_fixture.py` | layout-only stand-in (tests; refused for full-size runs) |
| `synth/terms.py`, `synth/lexicon.py` | hard-term pool (`ml/roundtrip/probe/terms.jsonl`), near-miss finder, people |
| `synth/teacher_prompts.py` | batch prompt + strict JSON schema (`gen.v6`) |
| `synth/teacher.py` | Responses / chat client: retries, Retry-After, request cap, resumable shards |
| `synth/stub_teacher.py` | offline stub with the same interface |
| `synth/build.py` | ctx v2, term garbling, surname misspelling, counterparts, recoverability check |
| `synth/disfluency.py`, `synth/channel.py`, `synth/asr_noise.py` | fillers/restarts; ASR adapter; built-in simulator |
| `synth/filters.py` | quality filters, near-dup removal, leakage report |
| `make_sample.py` | reviewer sample (<= 200 lines) from a run |
| `contam.py` | eval-contamination check (records and teacher prompt vs `prompts/eval`) |
| `probe/` | disfluency probe sentences, `measure_disfluency.py`, measured `disfluency_render.v1.json` |
| `public/` | converters + filters for public cleanup sets (secondary, frozen) |
| `samples/` | committed sample of real-teacher, real-OCR output |
| `tests/` | unit + end-to-end tests (stub teacher, fake Responses server, fixture and real screens) |

## Commands

The real screen path needs the OCR venv (`.cache/ml/ocr/venv`: numpy, onnxruntime, Pillow; Chrome + playwright-core
from `ml/screens`). The generator itself is stdlib-only, so the same interpreter runs everything.

```bash
PY=.cache/ml/ocr/venv/bin/python
$PY -m pytest -q ml/data/tests                       # 46 tests (the real-OCR one is skipped without READY files)
make -C ml/data stub                                 # offline end-to-end run: stub teacher, fixture screens
# THE full set (~633 teacher requests; see docs/ml/DATA_CARD.md for measured wall time)
tools/with-teacher tools/heavy $PY -m ml.data.generate --out .cache/ml/data/synth_v2 \
    --n 3000 --dev 300 --test 300 --mined ml/roundtrip/samples/mined/real --workers 5 --screens real
# rerunning the same command resumes (finished teacher batches are never re-requested)
$PY -m ml.data.recheck --run .cache/ml/data/synth_v2  # after any prompts/render.py change
```

Output: `OUT/{train,dev,test}.jsonl`, `OUT/stats.json` (incl. `provenance`, `asr_disfluency`), `OUT/REPORT.md`,
`OUT/rejects.jsonl` (every rejected example with its reasons), `OUT/review.jsonl` (for a human pass), `OUT/teacher/*.jsonl` (raw teacher shards), `OUT/scenarios.jsonl`.
