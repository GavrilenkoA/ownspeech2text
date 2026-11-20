# Data card — Murmur cleanup fine-tune (PLAN_V2 stage C, CONTEXT_V2)

Status 2026-11-20 (second pass): **the full set is rebuilt after the data review and assembled** (section 5):
3,000 train + 300 dev + 300 test records in `.cache/ml/data/final/` (`python -m ml.train.assemble` output, the
`DATA_DIR` of `docs/ml/TRAINING_RUNBOOK.md`), built by `make -C ml/data rebuild` into `.cache/ml/data/synth_v3/` from
the saved teacher answers plus 13 new requests (section 5.2a). The first assembled set (2,997 / 300 / 300, build
`.cache/ml/data/synth_v2`) is kept as `.cache/ml/data/final_v1/`. Code: `ml/data/` (README there), records: shared
contract in `ml/common/schema.py`.

## 1. What one example is

`{"id", "lang": ru|en|mixed, "category", "source": "sim", "split", "ctx", "transcript", "target", "meta"}`.
`ctx` is ctx v2 (`docs/ml/CONTEXT_V2.md`): app + focused field from the scenario (accessibility side, exact),
`screen.lines` = output of the app's OCR pipeline (`ml/ocr`, `ocr.v1`) on a rendered phone screenshot
(`ml/screens`), absent when there is no screenshot. The prompt is not stored; training and eval render it with
`prompts/render.py`. `meta` carries: teacher model + prompt version (`gen.v7`), scenario id/group, the spoken
text, ASR model id and every noise op (`meta.noise`), disfluency ops, the garble of the hard term or surname,
entities / distractors / injection, and `meta.screen` = render spec + render parameters + OCR pipeline version
(enough to rebuild the ctx; PNGs are not kept).

## 2. Sources

| Source | Role | Licence | Status |
|---|---|---|---|
| Synthetic (`ml/data/generate.py`) | primary: train/dev/test | targets and screen content are outputs of a proprietary hosted model (`gpt-6.2-sol`) reached with the owner's account; **terms of use for training on its outputs UNCONFIRMED, see 2a** | full set generated 2026-11-20 (section 5) |
| ASR noise: `ml/roundtrip/samples/mined/full` | error tables mined from the full text -> TTS -> ASR round trip (8,878 rows after the row rules, 968 clips, 12 ASR models; the small probe's `mined/real` was used for the proof runs) | owned by ml/roundtrip | used via `ml/roundtrip/mine/noise.py` |
| Screens: `ml/screens` + `ml/ocr` | render + OCR = ctx | fonts OFL/Apache (see ml/screens/README) | used via `build_ctx` |
| Public cleanup sets (`ml/data/public/`) | optional secondary source, frozen | see section 6 | converted 2026-11-18, not mixed into the synthetic set |

## 2a. Provenance and terms of the teacher outputs (owner decision before any public upload)

- **Who wrote what.** Every `target`, every `spoken` text and all screen content (titles, messages, snippets) were
  written by the teacher `gpt-6.2-sol` (PLAN_V2: the owner's Hermes agent's hosted "codex" model behind a local
  proxy speaking the OpenAI Responses API), prompt `gen.v7` (`ml/data/synth/teacher_prompts.py`; the proof runs used `gen.v4`-`gen.v6`), generated
  2026-11-18/19 (proof runs) and 2026-11-20 (full set) on the owner's server. Each record carries `meta.teacher` and `meta.gen`; each run writes
  `stats.json: provenance` (teacher, API, date, prompt, terms status). Code-made parts: transcripts (ASR noise
  from `ml/roundtrip` mined tables), rendered screens + OCR (`ml/screens`, `ml/ocr`), noise_only examples.
- **No third-party text is copied** into the synthetic set (no scraped or public-dataset text); the public sets of
  section 6 are not mixed in.
- **Open question (NOT verified):** the model's provider and its current terms. Hosted-model providers commonly
  restrict using outputs to develop competing models (OpenAI's terms of use have contained such a clause; I could
  not re-read the current text: openai.com returned HTTP 403 to this server on 2026-11-19). Whether training a
  small cleanup model on these outputs, and **publishing its weights or this data on Hugging Face**, is allowed
  depends on the terms that apply to the owner's account. Until the owner confirms, treat the data and every model
  trained on it as **private** (private HF repo, no public dataset upload). If the terms do not allow it, the
  fallback is regenerating targets with an open-weight teacher whose licence allows it (same code: `--teacher chat`
  against any OpenAI-compatible endpoint).
- The model card (`ml/train/card.py`, owned by the training engineer) must carry the same provenance block:
  teacher model, its terms status, generation dates, `gen` version (requested in the data owner's report).

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
| asr_artifact | 45 | 4 | 4 | Whisper-family hallucinated tail (train and held-out strings disjoint) or a Nemotron stutter loop removed (ru 60 / en 40) | 25 % | 8 % |
| **total** | **3000** | **300** | **300** | | | |

Apps: `app.category` comes only from the shared `prompts/tones/app_categories.v1.json` (unknown package ->
`default`, as on the phone); the screen template follows the app (`synth/screen.py: template_for`: Telegram ->
chat, Slack/Teams/Mattermost -> work chat, browsers -> page/search, calendars and AI apps for `default`); address
fields only in mail apps (e-mail) and browsers (URL); no prose into a terminal (the `code` style is unused until
the shared table lists a non-terminal code app).
No-screen reasons (weights inside the "no screen" part): secure window 30, denylisted app 25, OCR found
nothing 20, screenshot error 15. `privacy.level` is read from the shared table `prompts/render.py NO_SCREEN_LEVEL`
(ADR-070: secure window, denylisted app -> `app_only`; screenshot error, OCR empty -> `full`), never copied.
A password field is never generated: the app does not run the LLM there (ADR-070, level `none`), so such a
context never reaches the model; `ml.data.recheck` rejects a stored one (`privacy:reason_not_generated`) and any
level that differs from the table (`privacy:level_mismatch`). Continuation never gets an app-only reason, because
that privacy level hides the text before the cursor. Screens: Fold7 cover 60 % / inner 40 %, light 60 / dark 40,
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
   and another font scale, then the example is dropped and counted per category and term. On chat screens the
   injected text is always another person's message (the title is cut to one short line there and snippets show
   only sometimes); `meta.injection.planned_where` keeps the planner's choice.
4. **Transcript**: spoken -> garble of the hard term (mined manglings first; English-style when the term sits in
   an English clause) or surname -> disfluencies (only fillers the deployed prompt deletes; each hesitation sound and
   repeat survives with the ASR model's rate, `ml/data/probe/disfluency_render.v1.json`) -> ASR channel of one of
   12 real ASR models (mined style, confusions, deletions, insertions, digits-vs-words; English clauses of
   code-switched speech simulated as English by recognisers that write Latin) -> for asr_artifact a Whisper-family
   hallucinated tail (any Whisper-family model; train and held-out pools disjoint, every string the project's eval
   sets use is held-out only) or, for 25 % of Russian/mixed ones, a Nemotron 3.5 stutter loop (`с с с с сентенс`, seen
   in the real round trip). Realism guards: a substitution must sound like the word (phonetic similarity >= 0.45 for 4+
   letters, >= 0.34 shorter, or a known function-word swap), an insertion must be a function word or filler, a
   generic term garble must match the model's mined habits (no single letters; interior letter drops only for
   models whose manglings show them; Cyrillic-writing models get a phonetic transliteration instead). Retried with
   new seeds until the target is recoverable from transcript + context: every content word heard (same script:
   spelling similarity >= 0.6, other script: phonetic >= 0.75, never from a single letter) or on screen, and every
   number heard or on screen. Full-run additions (2026-11-20): (a) a hard term's mined garble for English speech
   comes only from English sentences (a mixed sentence's `декод` is not how a recogniser writes "decode" inside
   English speech); (b) a spoken term the channel garbled may be written correctly in the target without screen
   support only when the garble is a spelling variant of it (`docusign`, `Elastic Search`), a Cyrillic rendering
   that sounds like it (`докир`, `Джира`), a letter-by-letter acronym (`ди эн эс`) or a near-identical Latin
   misspelling of a 5+ letter term (`Confloence`); another word (`Traffic` for Traefik, `Equalization` for
   quantization, `EWS` for AWS) is retried with a new seed (`unsupported_term_garble`), because nothing in the input
   says which word was meant; (c) a context-resolution scenario that has a counterpart retries a Latin garble on
   its first three attempts (the pair needs a Cyrillic rendering the no-context target can keep).
   Second pass (data review, section 5.2a), replacing (b) and (c): **binding rule, mechanical** — a channel-garbled
   term is written correctly in the target only when it is on screen, the garble is a spelling variant (`docu sign`,
   `Zero Shot`), or the term is well-known (`build.is_well_known` = `terms.WELL_KNOWN`, `lexicon.KNOWN_TECH`, plain
   English phrases: exactly the terms that never get a counterpart); for a well-known term a near-identical Latin
   misspelling, a Cyrillic rendering that sounds like it or a spelled acronym also count. Anything else is retried.
   **No guessed words** — a channel substitution of one real word (vocabulary of all teacher `spoken` texts and the
   mined tables) by another (`Would` for `Could`, `Проверю` for `Проверь`, `появилось` for `отвалилось`) that the
   target undoes with no support on screen is retried (`real_word_guess`); Russian noun/adjective agreement repairs on
   one stem (`до понедельник` -> `до понедельника`) and English plural/-ed/-ing or homophones pass. **Garbles** of the
   context term: this recogniser's mined manglings, else another recogniser's mined manglings of the same term, else
   a Latin misspelling made with the mined sampler's own garbler (`perturb_word`, no generic respelling rules) or the
   lexicon reading / transliteration; the script follows the recogniser's mined share of Latin misspellings among its
   term errors (x2 for scenarios without a counterpart, cap 0.8; paired scenarios stay Cyrillic). **Channel style** —
   per model and language from the real round trip (`ml/data/probe/asr_style.v1.json`, `python -m ml.data.realism
   measure`): sentence-final mark at the measured rate given that the utterance is punctuated (also after a spoken
   `вопросительный знак`), first-word drop for the streaming models (Nemotron English: 0.32 above the mid-sentence
   rate), straight apostrophes; non-term word substitution/deletion rates scaled per (model, language)
   (`word_noise_scale.v1.json`, refit on built sets, damped, capped at 2x); one recogniser per scenario for every
   attempt but the last. **Targets** — never spell out a number the transcript has in digits; durations with digits
   (`ten minutes` -> `10 minutes`, prompt rule 4; `одну минуту` stays); no transliterated or translated Russian
   word in an address path (`слэш расписание` -> `/raspisanie`); `’` -> `'` in spoken, transcript and target.
5. **Filters** (deterministic; any doubt rejects): contract/schema, translation or script shift, novel or dropped
   content words, number multiset, length ratio, answers/preambles, commands or correction markers left,
   lists not formatted, distractor or injection in target, continuation repeats or mis-cased, address shape,
   CONTEXT_V2 rules again (`ml/data/recheck.py`, rerun after every `prompts/render.py` change).
6. **Dedup, eval contamination and split**: exact and char-5-gram near duplicates (Jaccard >= 0.8, held-out splits
   win); records that near-duplicate any `prompts/eval` item (sets v1/v2/v2dense/v2fx + smoke cases, same rule) are
   dropped (`ml/data/contam.py`; the teacher prompt's quoted examples are tested against the same sets); splits by
   scenario group with hash-partitioned term, people and injection pools (dev/test terms never appear in train).
7. **Review list**: kept records whose target has a content word found in none of spoken / transcript / screen
   go to `OUT/review.jsonl` for a human pass before training.

## 5. The full set (2026-11-20, `.cache/ml/data/synth_v3` -> `.cache/ml/data/final`)

Commands: `make -C ml/data synth STAGE=teacher` (teacher shards, resumable), then `make -C ml/data rebuild` (build
from the shards with `--offline`: never sends a request), then `python -m ml.train.assemble --out .cache/ml/data/final
.cache/ml/data/synth_v3/{train,dev,test}.jsonl`. Defaults in `ml/data/Makefile`: N 3000 / DEV 300 / TEST 300,
`--mined ml/roundtrip/samples/mined/full`, 5 build workers at nice 15 in a 24 GB scope, real screens, the `RESERVE`
layers below, `CTX_CACHE=.cache/ml/data/ctx_cache` (render + OCR result per render spec, 18 MB). First pass:
`.cache/ml/data/synth_v2` -> `final_v1` (logs `full_teacher*.log`, `full_build{1..4}.log`); second pass after the
data review: `synth_v3` (logs `fix_full{1..13}.log`, `fix_teacher{3,4,5}.log` in `.cache/ml/data/synth_runs/`).

| stage | measured |
|---|---|
| teacher | 699 batches answered, 0 failed: 686 of the first pass (633 primary batches of 6 scenarios + 53 reserve batches; 6,705 s + 664 s + 132 s wall at concurrency 2, p50 latency 20.5 s, 1.98M input + 0.66M output tokens) + 13 reserve batches of the second pass (layers 3-5: 8 + 2 + 3 requests, 0 retries, 0 HTTP 429); 4,141 teacher examples |
| reserves | another seed's plan, sorted after every primary scenario, batched apart so no answered batch changes. Layer 1: train CR 150, near_miss 58, injection 18, list 5; dev/test CR 15 each, near_miss 6, injection 3, list 1. Layer 2: train address 6, injection 6; dev names 4, address 2. Layers 3-5 (second pass, the stricter rules cost yield): train near_miss 16+4, meaningful_filler 5, address 5, injection 3+4+6; dev near_miss 5, context_irrelevant 3; test meaningful_filler 3. 198 final records come from reserves |
| build | 4,224 scenarios planned, 4,079 render + OCR calls. Cold build (second pass, first rebuild): 1,314 s wall, render 1,858 s + OCR 4,646 s CPU; rebuilds with a warm ctx cache: 10-25 s wall (0 render/OCR). 13 rebuilds of the second pass, all from the same shards |
| yield | 3,996 built + filtered, 3,985 after dedup (`survivors.jsonl`) -> exact counts per (split, category) -> **3,000 / 300 / 300**; assembly drops nothing |
| shortfall | none |
| drops | implausible noise 97, OCR rules 196 (near-miss distractor not readable 73, term not readable 56, injection not visible 42, name 20, counterpart term still visible 3, said item on screen 2), unsupported term garble 69, ASR not recoverable 61, counterpart Latin garble 31 (term in an English clause), real-word guess 27 (6 of them counterparts re-checked on their own screen), novel content 20, garble is an abbreviation 17, term not in spoken 15, said term garbled 11, address slug guess 9, exact duplicate 9, prompt-echo cap 5, others 32; 2 teacher examples dropped by name (section 5.2a, finding 6) |
| ctx | 2,993 with screen, 607 without (secure window 199, denylisted app 169, screenshot error 119, OCR empty 120; no password field, ADR-070), 219 without field, 54 re-rendered once; Fold7 cover 1,780 / inner 1,213 screens |
| apps | messaging 1,015, work_chat 875, email 535, notes 515, search 312, default 244, social 104 |
| ASR models | all 12: Parakeet Ultra 444, Parakeet TDT 411, Whisper medium 408, Whisper large-v3-turbo 404, Whisper small 325, Nemotron 3.5 264, GigaAM RNNT 247, GigaAM CTC 210, T-one 173, Qwen3-ASR 171, distil 103, Nemotron EN 80; code-made (no_op, near_no_op, noise_only) 360 |
| checks | `ml.data.recheck` 3,600/3,600 with the current `prompts/render.py`; `ml.common.validate --strict` 0 invalid in every split of `synth_v3` and of the assembled `final`; `ml.data.contam --prompt --data`: 0 records and 0 of 73 prompt phrases near any eval text; char-5-gram Jaccard vs the 1,151 eval texts of `prompts/eval` (transcripts, targets): 0 records at >= 0.5; scenario groups across splits 0; dev/test terms, people, injection strings in train 0; train targets near a dev/test target: 6 at 0.5, 2 at 0.6, 0 at >= 0.8; review list 1 record; all 3,000 train records load through `ml/train/render_data.build` with the Qwen3.5 and LFM3 tokenizers, 0 too long, decoded labels = target + end-of-turn for 3,000 / 3,000 |

### 5.1 Final mix (`.cache/ml/data/final`)

| category | train | dev | test | share of train |
|---|---:|---:|---:|---:|
| plain | 360 | 36 | 36 | 12.0 % |
| context_resolution | 330 | 33 | 33 | 11.0 % |
| self_correction | 300 | 30 | 30 | 10.0 % |
| numbers | 210 | 21 | 21 | 7.0 % |
| context_counterpart | 210 | 21 | 21 | 7.0 % |
| near_miss | 180 | 18 | 18 | 6.0 % |
| no_op | 180 | 18 | 18 | 6.0 % |
| context_names | 150 | 15 | 15 | 5.0 % |
| formatting_commands | 150 | 15 | 15 | 5.0 % |
| dictated_question | 120 | 12 | 12 | 4.0 % |
| injection | 120 | 12 | 12 | 4.0 % |
| context_irrelevant | 90 | 9 | 9 | 3.0 % |
| list | 90 | 9 | 9 | 3.0 % |
| dictated_command | 90 | 9 | 9 | 3.0 % |
| continuation | 90 | 9 | 9 | 3.0 % |
| meaningful_filler | 60 | 6 | 6 | 2.0 % |
| near_no_op | 60 | 6 | 6 | 2.0 % |
| address | 60 | 6 | 6 | 2.0 % |
| noise_only | 60 | 6 | 6 | 2.0 % |
| ai_addressed | 45 | 5 | 5 | 1.5 % |
| asr_artifact | 45 | 4 | 4 | 1.5 % |
| **total** | **3,000** | **300** | **300** | |

Languages: train ru 1,425 (47.5 %) / en 839 (28.0 %) / mixed 736 (24.5 %); dev 143 / 85 / 72; test 150 / 81 / 69.
Empty targets (noise_only): 60 / 6 / 6.

**Token lengths** (measured 2026-11-20 with `python -m ml.train.sft --config ml/train/configs/base.yaml --prepare-only`
on the current `final/` (train.jsonl sha256 `ba13d02e…`), real tokenizers, the training prompt mix of `base.yaml`:
finetuned.v2 95 % (2,859 rows; 480 of all rows with a preferences block) + the stock profile **default.v1** 5 %
(141 rows; 13 of the 300 dev rows); prompt + completion):

| tokenizer | rendered p50 / p90 / p99 / max | mean | completion tokens (train) | real / padded tokens per epoch | `max_len: auto` |
|---|---|---:|---:|---:|---:|
| Qwen3.5 | 325 / 457 / 1,328 / 1,463 | 373 | 79,070 | 1.12M / 1.18M | 1,472 |
| LFM3 | 338 / 489 / 1,369 / 1,504 | 388 | 92,317 | 1.16M / 1.23M | 1,536 |

The long tail is the default.v1 share (141 records, ~1.3-1.5K tokens each); 0 examples dropped, none near the
4,096 cap; dev max 1,434 / 1,475. Without that share (`prompt.default_share=0`, finetuned.v2 only) the same rows
render to p50 / p99 / max 321 / 536 / 687 (Qwen3.5) and 333 / 580 / 760 (LFM3). Batching and padding figures:
TRAINING_RUNBOOK section 2.1.

### 5.2 Quality pass of the first pass (generator fixed and rebuilt from the shards, no record edited)

Read: the first teacher answers built early (370 records of two scratch builds) and 106 final records of builds 1-3, stratified over all 21 categories, ru/en/mixed, with and without screen and field; plus a scripted audit of every
final record (`rule_errors` of `ml.data.recheck` on the OCR lines and the rendered block, pool terms written in the
target but absent from the transcript and the screen, distractors and injection strings in targets, answer-length
targets of questions/commands/AI prompts). Final audit: 0 CONTEXT_V2 rule failures, 0 counterparts writing their term,
0 near-miss targets taking the distractor, 0 injection strings or obeyed injections in targets, 0 answer-like targets.
Defects found, each fixed in the generator and verified on the next build:

1. **Terms restored without context.** The channel's mined manglings turned a spoken term into another word and the
   target still wrote the term (`Traffic` -> Traefik, `Equalization` -> quantization, `EWS` -> AWS); 13 records of
   build 2 by the final rule. Now retried unless the garble is a spelling variant / phonetic Cyrillic rendering / one letter
   off, or the term is on screen (section 4, 4b); 17 scenarios dropped where every seed failed.
2. **English speech borrowed Cyrillic manglings** from mixed sentences (`декод`, `ранчер` inside English; 5 of 154
   early en records). Fixed in `_term_variants_mined`.
3. **Deleted words the target restores from nothing** (`So ask Dmitri Gallagher to drive` -> `Can you ask ...?`;
   deleted `не`, `почему`, `can`, `you`; 53 records of build 1). Now only prepositions, conjunctions and articles may
   be lost (`DELETABLE`), else the attempt is retried.
4. **noise_only collapsed to 9 of 60** (a dozen fixed strings, exact-duplicate filter). Now varied hesitation
   spellings/punctuation per recogniser style plus subtitle hallucinations; at most 3 records per transcript, never
   across splits.
5. **Counterparts silently lost** (213 of 252): the exact-count cut dropped context-resolution parents whose
   counterpart had survived. Selection now prefers those parents and draws counterparts only from selected parents.
6. **Injection on chat screens** placed in the title (cut to one short line) or a snippet (shown only sometimes):
   now always a message there; injection yield 0.67 -> 0.78.
7. **False rejects** (deterministic filters): ordinals `в` of `в-третьих`, `question` of "question mark", number
   scale words (`hundred`), spoken symbols of file names (`config dot yaml`), hyphenated term parts, the user's own
   "Here's my plan:" taken as a preamble, number-rule examples of the prompt (`к девяти утра`) taken as copied
   illustrations, `Yes` said by the user taken as an obeyed injection, digits shifting the script share. 79 build-1
   rejects pass now; the strict rules themselves are unchanged.
8. **near_no_op without a defect** (5 search queries already lowercase, unpunctuated): the recogniser's capital or
   final period is the defect now. **Prompt echo**: the false friend "точка сбора" (the prompt's illustration
   reworded) in 12 formatting examples: capped at 3 per split.

Left as known, judged acceptable: restorations of well-known terms from close misspellings or Cyrillic renderings
without screen support (about 220 (record, term) pairs in the 2,447 records outside the context categories, e.g. `Teraform` -> Terraform, `фигма` ->
Figma, `рост` -> Rust), which rule 7 of the teacher prompt asks for; Cyrillic address words transliterated by the
teacher (`Гриндата точка ру` -> `greendata.ru`), inherently ambiguous; work-chat targets that wrap a file path in
backticks the user did not dictate (seen once); the screen generator's notification-permission dialog can cover
the content OCR needs (seen in one dropped near-miss example, `distractor_not_readable`).

### 5.2a Second pass: the seven findings of the independent data review (2026-11-20)

The review (`.cache/progress/resume/ml_verify-data.json`) found no blocker and seven minor findings. Each is fixed by
a generator rule and a rebuild from the saved teacher answers; new teacher requests (13) were spent only on reserve
scenarios for cells the stricter rules left short. Numbers: `python -m ml.data.realism audit` on train (`F1`..`F7`
keys) and the reviewer's own scripts (`.cache/ml/review_data/*.py`), before = `final_v1`, after = `final`.

| # | finding | rule (code) | before | after |
|---|---|---|---|---|
| 1 | target turns a Cyrillic rendering of a non-well-known term into Latin with no screen support (Traefik `трефик` fixed, counterparts keep `трэфик`) | binding rule made mechanical: the transcript's rendering is kept unless the term is on screen, a spelling variant, or well-known (= no counterpart); retried otherwise (`term_restore_ok`, `is_well_known`) | reviewer `terms.py`: 26 Cyrillic non-well-known fixes, 84 non-well-known fixes, 229 records with an unsupported term fix, 8 terms both fixed without screen and kept in a counterpart (Traefik Cyrillic; Parquet, Selectel, TestFlight, YouTrack, guardrails, pytest, zero-shot Latin) | 3 "Cyrillic" (well-known terms the script counts by the full token: `Gpo-часов` for GPU-часов, `Prкриую` for PR-кривую, `вифи` for wi-fi), 7 non-well-known fixes (spelling variants: `Swift UI`, `top K`, `few shot`, `Cross Fit`), 170 records, **0 conflicting terms**; generator audit (`F1`): 0 Cyrillic non-well-known fixes, 0 conflicts |
| 2 | channel swaps a word for another real word and the target restores the spoken one without support (`Would`/`Could`, `появилось`/`отвалилось`) | `real_word_guess`: retry when the substitute is a real word (all teacher `spoken` texts + mined words) of another lemma and the spoken word is not on screen; noun/adjective agreement and English plural/-ed/-ing/homophones pass | reviewer `subs.py`: 90 real-word restorations in 81 records; generator rule: 62 records | 44 in 42 records, agreement repairs on one stem (`ответа`/`ответы`, `холодильника`/`холодильнике`, `requests`/`request`); generator rule: 0 (a counterpart is re-checked on its own re-rendered screen) |
| 3 | term garbles skewed: Latin misspellings rare in ru/mixed, 65 % from the hand-written lexicon, 24 generic `respell` garbles | garbles from this model's mined manglings, then other models' mined manglings of the term, then the sampler's own garbler; script at the model's measured Latin share (x2 for unpaired scenarios, cap 0.8; pairs stay Cyrillic); CR selection takes only as many paired parents as counterparts needed | CR garble sources lexicon 203 / mined 103 / respell 21 / split 3; unpaired ru/mixed CR Latin 31 of 77; all ru/mixed garbles Latin 5.8 % | lexicon 65 / mined 109 / mined by another model 115 / sampler garbler 40 / split 1 / respell 0; unpaired ru/mixed CR Latin 31 of 55 (56 %); all ru/mixed garbles 6.5 % (210 counterpart pairs are Cyrillic by design) |
| 4 | non-term word noise lighter than real (synthetic/real word-miss ratio 0.6-0.9, Nemotron 0.40-0.42, Qwen3-ASR ru 0.51); Nemotron streaming drops the first word | per (model, language) factor on the mined substitution/deletion rates, fitted on built sets (`realism calibrate`, damped, cap 2); first-word drop at the measured excess rate; one recogniser per scenario across retries | ratio median 0.77, range 0.40-1.28, 11 of 29 cells within 0.8-1.2; Nemotron 3.5 en 0.40, Nemotron EN 0.42 | median 0.89, range 0.42-1.16, 17 of 29 within 0.8-1.2; Nemotron 3.5 en 0.95 / ru 1.00 / mixed 1.00, Nemotron EN 0.61; still low: Qwen3-ASR ru 0.42, Whisper medium 0.52-0.57, distil 0.64 (their extra substitutions are real words or unrecoverable, so the guards of findings 2 and recoverability remove them) |
| 5 | final punctuation not per model (Nemotron en ends with a mark in 31 % vs 0 % real; Whisper/Parakeet/Qwen3-ASR 81-94 % vs 97-100 %); 62 curly apostrophes | model's measured end-mark rate given punctuation (also after spoken commands); `’` -> `'` | mean abs. difference real vs synthetic end-mark rate 0.092 over 29 (model, language) cells, max 0.31, 5 cells within 0.05; 75 transcripts with `’` | mean 0.018, max 0.14 (Nemotron 3.5 ru 0.48 vs 0.34), 26 of 29 within 0.05; 0 |
| 6 | targets spell out a digit the transcript has (`10 minutes` -> `ten minutes`); en `N minutes/hours` mixed; odd records | retry when a transcript digit is a word in the target (`digits_spelled_out`, also a filter); durations to digits (`digit_durations`); `sc-6a9f1251f168` (SRE in a grocery order) and `sc-af6c2f4d893a` (`ritmfit.ru/raspisanie`) dropped by name; Russian words in address paths rejected (9 examples) | 3 reversions; en `N minutes/hours/days...` words 9 / digits 29 | 0 reversions; words 0 / digits 36 |
| 7 | every asr_artifact from Whisper large-v3-turbo, 10 tail strings shared by train and dev/test | Whisper-family model drawn at random (incl. distil for English), 25 % of Russian/mixed asr_artifact get the Nemotron 3.5 stutter loop seen in the round trip; train and held-out tail pools disjoint, the four strings the project's eval sets use held-out only; noise_only tails from the split's pool | models: large-v3-turbo 53; train tail strings seen in dev/test: 7 | large-v3-turbo 21, small 13, medium 10, distil 4, Nemotron 3.5 loop 5; 0 shared strings (14 train / 4 dev / 4 test distinct) |

Not available in the round trip: real hallucinated tails. Its 10,605 hypotheses contain no subtitle tail (TTS clips have
no silence); the only real artifacts are 12 foreign-script outputs (Qwen3-ASR, Nemotron 3.5; the whole utterance
becomes Ukrainian/Czech-like text, no recoverable target), one stutter loop (used); empty outputs appear only in the disfluency probe (2 of 724). The tail
strings are well-known Whisper hallucinations from public reports.

### 5.3 Proof runs (2026-11-18/19)

Four proof runs of 216 teacher scenarios each (`proof_v4` .. `proof_v7`, 143 requests) tuned the generator before the
full run; `proof_v7`: 38 requests, 216 -> 198 records, 2 of them with the no-screen reason `password_field` that is no
longer generated (recheck now rejects them).

**Disfluency probe** (`make disfl-probe`, 2026-11-19): 32 disfluent sentences x 3 CPU TTS systems -> 12 ASR models,
724 hypotheses, 814 s wall. Repeats of 3+ letter words kept: Whisper large-v3-turbo 6 %, medium 24 %, small 29 %,
distil 22 %, Parakeet 53-59 %, Nemotron 22-53 %, Qwen3-ASR 53 %, GigaAM 63 %, T-one 63 %. TTS-read hesitations
kept 23-79 % (upper bound, see section 7). Table: `ml/data/probe/disfluency_render.v1.json`.

**Proof-run review** (40 records of `proof_v7` read by a native ru/en reader, stratified over all 21 categories, after
122 on earlier runs): targets are faithful (no answers, no translations, no executed commands; numbers, lists,
formatting commands, code-switched clauses and surnames restored from the screen are correct; "Слушай" is no longer
injected as a filler). Code-switched transcripts now keep the English clause in English. Defects found and handled:
the teacher copied the prompt's illustration "точка сбора у входа" into 5 of 9 formatting-command examples (prompt
`gen.v7` forbids copying; filter `copied_prompt_example`); `вопросительный -> пользователей` passed the
substitution rule (phonetic 0.50; long words now need 0.55). Left as known risks: a few substitutions produce
another valid word (`сохранить -> сравнить`, `приходят -> находят`) that only sentence context resolves; a target
restores a function word the ASR deleted (`you like` -> `If you like`); one target capitalises a place name the
speaker said in lowercase (`Design Museum` in a search query).

Committed sample: `ml/data/samples/synth_v2.sample.jsonl` (200 train records of the second-pass set `synth_v3`, all 21 categories; file name kept for the Makefile target).

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
- **Mined noise artefacts** (non-acoustic substitutions and insertions, typo-like Latin garbles): filtered by the
  realism guards of section 4; what the guards cannot judge (e.g. a near-miss Cyrillic misspelling such as
  `пожалуйсем`, which CTC models do produce in kind) stays.
- **Disfluency rendering is a TTS proxy.** Hesitation and repeat survival per ASR model was measured by reading 32
  disfluent sentences with three CPU TTS systems (piper, supertonic3, kokoro1) into all 12 models (724
  hypotheses). TTS articulates "э"/"um" as words and misreads some ("эм" -> the letter "М", "uh" -> "oh"), so the
  hesitation rate is capped by a family prior (Whisper/distil 0.1, Qwen3-ASR 0.15, Parakeet/Nemotron 0.35,
  GigaAM/T-one 0.6); repeats of 3+ letter words are used as measured. Real disfluent recordings (owner, DEVICE)
  should replace it.
- **ASR deletions of short function words** ("отправить в деканат" for "отправить его в деканат") are restored in
  the target from the spoken text: mild grammatical repair the model learns.
- **Teacher style**: one teacher model writes all targets; register and topics are steered by the planner, but
  phrasing diversity is limited to what one model produces at low effort.
- **Counterpart targets keep Cyrillic garbles** of non-well-known terms ("лама цэпэпэ" for llama.cpp) by design.
- **Well-known terms are an exception to CONTEXT_V2's binding rule** (deviation pending the owner's acceptance):
  a target writes `Docker` for `докир` or `Confluence` for `Confloence` without screen support when the term is in
  `terms.WELL_KNOWN` / `lexicon.KNOWN_TECH` or a plain English phrase (`is_well_known`), the same set that never gets a
  no-context counterpart, so no pair contradicts it. Every other term keeps the transcript's rendering unless the
  screen shows it.
- **Latin misspellings of hard terms in Russian speech stay rarer than in real data** (6.5 % of ru/mixed context
  garbles vs 17-44 % of real term errors per model, 2 % for T-one): the 210 counterpart pairs need a Cyrillic rendering. The 55
  unpaired ru/mixed context-resolution records carry 31 Latin misspellings.
- **Language-model decoders stay less noisy than real** (Qwen3-ASR Russian 0.42, Whisper medium 0.52-0.57 of the
  real non-term word-miss rate): their substitutions are real words, which the no-guessing guard retries, and the
  heaviest transcripts are unrecoverable. The ratios are measured on 3,000 records with a few dozen misses per cell
  (refit noise of about +-15 %).
