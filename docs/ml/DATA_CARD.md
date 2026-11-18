# Data card — Murmur cleanup fine-tune (PLAN_V2 stage C, CONTEXT_V2)

Status 2026-11-18: the generator is built and tested; a 216-scenario proof ran end to end with the real teacher,
the real round-trip noise tables and the real render + OCR path. The full 3K set has NOT been generated yet
(one command, below). Code: `ml/data/` (README there), records: shared contract in `ml/common/schema.py`.

## 1. What one example is

`{"id", "lang": ru|en|mixed, "category", "source": "sim", "split", "ctx", "transcript", "target", "meta"}`.
`ctx` is ctx v2 (`docs/ml/CONTEXT_V2.md`): app + focused field from the scenario (accessibility side, exact),
`screen.lines` = output of the app's OCR pipeline (`ml/ocr`, `ocr.v1`) on a rendered phone screenshot
(`ml/screens`), absent when there is no screenshot. The prompt is not stored; training and eval render it with
`prompts/render.py`. `meta` carries: teacher model + prompt version (`gen.v5`), scenario id/group, the spoken
text, ASR model id and every noise op (`meta.noise`), disfluency ops, the garble of the hard term or surname,
entities / distractors / injection, and `meta.screen` = render spec + render parameters + OCR pipeline version
(enough to rebuild the ctx; PNGs are not kept).

## 2. Sources

| Source | Role | Licence | Status |
|---|---|---|---|
| Synthetic (`ml/data/generate.py`) | primary: train/dev/test | teacher outputs: generated on the owner's server with the owner's account (`gpt-6.2-sol`); no third-party data inside | proof run done, full run pending |
| ASR noise: `ml/roundtrip/samples/mined/real` | error tables mined from a real text -> TTS -> ASR probe (837 rows, 11 ASR models) | owned by ml/roundtrip | used via `ml/roundtrip/mine/noise.py` |
| Screens: `ml/screens` + `ml/ocr` | render + OCR = ctx | fonts OFL/Apache (see ml/screens/README) | used via `build_ctx` |
| Public cleanup sets (`ml/data/public/`) | optional secondary source, frozen | see section 6 | converted 2026-11-18, not mixed into the synthetic set |

## 3. Category mix (explicit targets)

Counts per split are exact (largest remainder of `CATEGORY_TARGETS`, ~15 % over-generation absorbs filter losses;
a shortfall is reported, never padded). Language mix: ru 45 / en 30 / mixed 25 % unless noted;
mixed = Russian with English terms (60 %) or true clause-level code-switching (40 %; never in search fields).

| category | train | dev | test | what it teaches | no screen | no field |
|---|---:|---:|---:|---|---:|---:|
| plain | 360 | 36 | 36 | fillers, stutters, punctuation, casing | 25 % | 8 % |
| meaningful_filler | 60 | 6 | 6 | "типа того", "вот этот", "like" that carry meaning stay | 25 % | 8 % |
| self_correction | 300 | 30 | 30 | keep only the corrected version | 25 % | 8 % |
| context_resolution | 330 | 33 | 33 | a hard IT/AI term garbled by ASR, restorable only from screen text (ru 45 / mixed 35 / en 20) | 0 | 5 % |
| context_counterpart | 210 | 21 | 21 | same transcript, screen re-rendered without the term: keep the transcript's word | 0 | as parent |
| context_names | 150 | 15 | 15 | person on screen, surname misheard | 0 | 5 % |
| near_miss | 180 | 18 | 18 | look-alike term/name on screen that was not said: keep what was said | 0 | 5 % |
| context_irrelevant | 90 | 9 | 9 | busy screen, unrelated dictation: take nothing from it | 0 | 5 % |
| no_op | 180 | 18 | 18 | clean input -> byte-identical output | 30 % | 8 % |
| near_no_op | 60 | 6 | 6 | one small defect | 30 % | 8 % |
| formatting_commands | 150 | 15 | 15 | "точка", "новый абзац", "question mark" (+ false friends) | 25 % | 0 |
| list | 90 | 9 | 9 | spoken enumeration -> numbered list | 25 % | 0 |
| numbers | 210 | 21 | 21 | times, dates, money, %, units, versions | 25 % | 8 % |
| address | 60 | 6 | 6 | spoken e-mail / URL -> literal address (ru 50 / en 50) | 30 % | 0 |
| noise_only | 60 | 6 | 6 | hesitation sounds / subtitle hallucinations -> "" (code-made) | 50 % | 20 % |
| dictated_question | 120 | 12 | 12 | a question stays a question, never answered | 25 % | 8 % |
| dictated_command | 90 | 9 | 9 | an imperative stays text, never executed | 25 % | 8 % |
| ai_addressed | 45 | 5 | 5 | a prompt typed into an AI app is transcribed, not answered | 25 % | 8 % |
| injection | 120 | 12 | 12 | hostile instruction in the screen text is ignored | 0 | 5 % |
| continuation | 90 | 9 | 9 | text before the cursor; mid-sentence continuation | 15 % | 0 |
| asr_artifact | 45 | 4 | 4 | Whisper-style hallucinated tail removed (ru 60 / en 40) | 25 % | 8 % |
| **total** | **3000** | **300** | **300** | | | |

No-screen reasons (exact shares inside the "no screen" part): secure window 30, denylisted app 25, OCR found
nothing 20, screenshot error 15, password field 10 (continuation never gets an app-only reason, because that
privacy level hides the text before the cursor). Screens: Fold7 cover 60 % / inner 40 %, light 60 / dark 40,
font scale 0.9-1.3, keyboard shown on 70 % of screens with a field, UI locale = dictation language with 15 % swapped.

## 4. How an example is made and checked

1. **Plan** (code, deterministic from seed): category, language mix, app, field, screen-absent reason, persona,
   topic, length, which hard term / people are spoken, near-miss distractors (look-alike score >= 0.6 or a curated
   confusable pair), unrelated screen terms, injection text, disfluency and noise severity, render parameters.
2. **Teacher** (`gpt-6.2-sol`, Responses API, effort low, 6 scenarios per request, strict JSON schema): screen
   content + `spoken` (verbatim, numbers in words) + `target`.
3. **ctx**: spec -> `ml.screens.build_ctx` (render -> OCR). Rules on the OCR lines and on the rendered
   `<context>` block: resolution/names: the term/name spelled exactly; near miss: distractor present, said item
   absent; counterpart: term and close variants (case-folded, edit distance <= 2, <= 1 for <= 4 letters) absent
   after a re-render without the supporting items; injection: visible (fuzzy). One re-render with the other theme
   and another font scale, then the example is dropped and counted per category and term.
4. **Transcript**: spoken -> garble of the hard term (mined manglings first) or surname -> disfluencies and
   self-correction words -> ASR channel of one of 11 real ASR models (mined style, confusions, deletions,
   insertions, digits-vs-words) -> hallucinated tail for asr_artifact. Retried with new seeds until the target is
   still recoverable from transcript + context and no substitution is an implausible mining artefact.
5. **Filters** (deterministic; any doubt rejects): contract/schema, translation or script shift, novel or dropped
   content words, number multiset, length ratio, answers/preambles, commands or correction markers left,
   lists not formatted, distractor or injection in target, continuation repeats or mis-cased, address shape,
   CONTEXT_V2 rules again (`ml/data/recheck.py`, rerun after every `prompts/render.py` change).
6. **Dedup and split**: exact and char-5-gram near duplicates (Jaccard >= 0.8, held-out splits win); splits by
   scenario group with hash-partitioned term, people and injection pools (dev/test terms never appear in train).

## 5. Measured (proof run 2026-11-18, `.cache/ml/data/synth_runs/proof_v6`)

Command: `tools/with-teacher tools/heavy .cache/ml/ocr/venv/bin/python -m ml.data.generate --out
.cache/ml/data/synth_runs/proof_v6 --limit 216 --max-requests 45 --mined ml/roundtrip/samples/mined/real --workers 3
--screens real` (stats: `proof_v6/stats.json`, report: `proof_v6/REPORT.md`).

| stage | measured |
|---|---|
| teacher | 38 batches of 6 scenarios (29 new requests + 9 reused shards), 0 failures, 0 retries; p50 latency 21.9 s; 327.8 s wall at concurrency 2 (= 11.3 s per request); 2.8K input + 0.9K output tokens per request |
| render + OCR | 231 screens: render 0.61 s, OCR 3.07 s CPU per screen (1 OCR thread per worker, shared machine); build wall 286.8 s with 3 workers (1.24 s per screen) |
| yield | 216 teacher examples -> 201 after build + filters -> 200 after dedup (73-74 / 61 / 65 train/dev/test; counts are per-category round-robin in a limited run) |
| drops | asr_unrecoverable 10, dropped_content 9, OCR rules 7 (distractor 2, name 2, injection 2, term 1), implausible mined substitution 2, garble lost / abbreviation 2, exact duplicate 1 |
| OCR-hard items | names Фёдор Красильников, Gwendolyn O'Sullivan, Owen Carter; terms OOM, A/B test; two injections not readable after one re-render |
| ctx | 163 with screen, 37 without (secure window 14, denylisted 10, screenshot error 6, OCR empty 4, password 3), 17 without field; 1 needed the re-render |
| checks | `ml.data.recheck`: 200/200 pass with the current renderer; `ml.common.validate --strict`: 0 invalid; leakage: none |

**Full set projection** (3,000 + 300 + 300, over-generation 1.15): 3,871 scenarios, 633 teacher requests
(-> about 2.0 h at concurrency 2 and 11 s per request; well under any per-run cap, but it is the shared quota: run
it once), about 3,400 screens (-> about 70 min with 3 build workers, about 45 min with 5). Expected total about
3 h wall, CPU only, no GPU. Same command with `--n 3000 --dev 300 --test 300` (Makefile target `synth`).

**Manual review** (40 records of `proof_v6` read by a native ru/en reader, after 82 more on earlier runs):
targets are faithful (no answers, no translation, numbers/lists/formatting commands correct, code-switched clauses
restored to their language). Defects found and fixed in the code since: English counterparts keeping Latin
misspellings of famous terms (`apache airflov`, `ro ber ta`: counterparts are now ru/mixed only; the 3 such records
are left out of the committed sample), a near-miss person that did not look alike (fallback removed), the
recoverability check accepting `Sunday` from `Seturday` (closest-word rule), mined substitutions with no acoustic
relation (`по -> свенство`, now retried). Earlier rounds fixed: weak near-miss scorer, number-word recoverability,
symbol words (`Решётка` -> `#`), continuation without visible text before the cursor, 24-hour/leading-zero
time rewrites, digits that break Russian grammar (`по 1000 фотографий`). Remaining, reported to their owners: the
OCR screen shows the focused field's own text a second time because `build_ctx` emits no `field.box` (ml/screens);
a few mined insertions are odd words (`cloudflare's`, ml/roundtrip). Minor teacher style: lowercase `i` in an
English search query.

Committed sample: `ml/data/samples/synth_v2.sample.jsonl` (197 records of `proof_v6`).

## 6. Public datasets (secondary, frozen; converters in `ml/data/public/`)

Licences re-checked on the Hugging Face API on 2026-11-18. Counts are from the conversion run
(`.cache/ml/data/public_out/stats.json`); "kept" passed the strict filters of `ml/data/public/filters.py`.

| dataset | licence (HF tag) | rows | kept | main drop reasons | verdict |
|---|---|---:|---:|---|---|
| juanquivilla/sotto-transcript-cleanup | MIT | 142,424 | 93,929 | duplicate input 26.6K, low retention 6.2K, number words in target 4.6K | en only; still contains style rewrites ("we gotta" -> "We have to"): do not mix without an extra paraphrase filter |
| shantanugoel/aawaaz-transcript-cleanup-dataset | MIT | 22,785 | 9,274 | excluded categories 4.9K, number changed 2.6K, novel content 2.3K | en; usable for formatting commands |
| MagicNoThief/handy-dictation-editing | CC-BY-4.0 | 92,148 | 53,615 | number words 9.5K, low retention 6.9K, template cap 6.1K, negation lost 4.2K | en; attribution required |
| hasanchadaram/dictation-cleanup | Apache-2.0 | 3,251 | 2,973 | template cap 94, bullets 78 | en |
| SpeakoFlow/dictation-cleanup-examples | CC-BY-4.0 | 105 | 49 | too long, conflicting targets | test-only reference |
| GenSEC-LLM/SLT-Task1-Post-ASR-Text-Correction | Apache-2.0 | 240,924 | 0 | all targets lowercase and unpunctuated | used only to mine 3,880 English ASR confusions |
| kul-speech-lab/contextual-reasoning-speechllm | no HF licence tag (derived from NC-SA sources per its card; not re-verified) | 27,125 | 5,771 | no context hit 21.4K | research/test only, never train |
| gpb11/russian-asr-correction | none declared | 1,100 | 0 | no licence | not used |
| MishZor256/russian-asr-enrichment | CC-BY-SA-4.0 | 155,713 | 44,617 | subsampled, ref disagrees with clean, LLM-arbitrated tier | ru; share-alike: keep out of anything that cannot be CC-BY-SA |

## 7. Known gaps

- **No real dictations.** The final eval set needs the owner's own recorded dictations (ru, en, mixed) on the
  Fold7 with real screens; nothing synthetic replaces that (DEVICE item).
- **Screens are rendered look-alikes**, not real apps; OCR is real. The emulator/real-app probe of CONTEXT_V2
  "realism checks" is owned by ml/ocr; Fold7 screenshots are a DEVICE item.
- **OCR-hard content is dropped, not fixed**: names with ł/ø/ö/é are excluded from the people pool (the Cyrillic+Latin
  recogniser cannot read them); terms like `llama.cpp` sometimes fail and are dropped. `ml/ocr/ocr_hard_terms.json`
  (when it exists) removes such terms from context_resolution planning.
- **Mined noise artefacts**: the mined confusion tables contain a few non-acoustic substitutions; they are retried
  away (`implausible_substitution`), insertions of odd words are not.
- **ASR deletions of short function words** ("отправить в деканат" for "отправить его в деканат") are restored in
  the target from the spoken text: mild grammatical repair the model learns.
- **Teacher style**: one teacher model writes all targets; register and topics are steered by the planner, but
  phrasing diversity is limited to what one model produces at low effort.
- **Counterpart targets keep Cyrillic garbles** of non-well-known terms ("лама цэпэпэ" for llama.cpp) by design.
