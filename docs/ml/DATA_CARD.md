# Data card — Murmur cleanup fine-tune (PLAN_V2 stage C, CONTEXT_V2)

Status 2026-11-20: **the full set is generated and assembled** (section 5): 2,997 train + 300 dev + 300 test records
in `.cache/ml/data/final/` (`python -m ml.train.assemble` output, the `DATA_DIR` of `docs/ml/TRAINING_RUNBOOK.md`),
built by `make -C ml/data synth` into `.cache/ml/data/synth_v2/`. Code: `ml/data/` (README there), records: shared
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
| asr_artifact | 45 | 4 | 4 | Whisper-style hallucinated tail removed (ru 60 / en 40) | 25 % | 8 % |
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
   code-switched speech simulated as English by recognisers that write Latin) -> hallucinated tail for
   asr_artifact. Realism guards: a substitution must sound like the word (phonetic similarity >= 0.45 for 4+
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

## 5. The full set (2026-11-20, `.cache/ml/data/synth_v2` -> `.cache/ml/data/final`)

Commands: `make -C ml/data synth STAGE=teacher` (teacher shards), then `make -C ml/data synth` (build from the shards,
0 requests), then `python -m ml.train.assemble --out .cache/ml/data/final .cache/ml/data/synth_v2/{train,dev,test}.jsonl`.
Defaults in `ml/data/Makefile`: N 3000 / DEV 300 / TEST 300, `--mined ml/roundtrip/samples/mined/full`, 5 build workers,
real screens, the `RESERVE` cells below. Logs: `.cache/ml/data/synth_runs/full_teacher*.log`, `full_build{1..4}.log`.

| stage | measured |
|---|---|
| teacher | 686 batches answered (633 primary batches of 6 scenarios + 53 reserve batches), 0 failed; one HTTP 502 retried once, no HTTP 429; 6,705 s + 664 s + 132 s wall at concurrency 2 (about 10.7 s per request, p50 latency 20.5 s); 1.98M input + 0.66M output tokens; 4,087 teacher examples (3,788 primary + 299 reserve scenarios) |
| reserves | measured yields (scratch builds of the first answers, 0 requests) were below the 1.15 over-generation for a few cells: context_resolution 0.79, counterpart per paired scenario 0.60, near_miss 0.70, injection 0.78. Reserve scenarios (`--reserve`, another seed's plan, sorted after every primary scenario, batched apart so no answered batch changes): train CR 150, near_miss 58, injection 24, list 5, address 6; dev/test CR 15 each, near_miss 6, injection 3, list 1; dev names 4, address 2. 218 final records come from reserves |
| build | 4,170 scenarios -> 4,014 render + OCR calls; render 1,838 s + OCR 4,819 s CPU; 1,340 s wall with 5 workers at nice 15 in a 24 GB scope. Built four times from the same shards while fixing the generator (section 5.2); each rebuild 0 requests, 20-27 min |
| yield | 4,105 survivors of build + filters + dedup (`survivors.jsonl`) -> exact counts per (split, category) -> 3,599 (2,999 / 300 / 300) -> `ml.train.assemble` drops 2 train noise transcripts that also occur in dev/test -> **2,997 / 300 / 300** |
| shortfall | train noise_only 57 of 60 (code-made: 59 survived build + dedup, assembly removed 2 whose transcript also occurs in dev/test; the generator now keeps a noise transcript in one split); every other cell exact |
| drops (build 4) | OCR rules 188 (near-miss distractor not readable 69, term not readable 56, injection not visible 38, name 20, counterpart term still visible 3, said item on screen 2), counterpart Latin garble 49, ASR not recoverable 30, no garble 23, novel content 20, implausible noise 19, unsupported term garble 17, said term garbled 17, term not in spoken 15, length ratio 9, exact duplicate 7, prompt-echo cap 6, eval contamination 4, others 25 |
| ctx | 2,982 with screen, 617 without (secure window 199, denylisted app 176, screenshot error 119, OCR empty 123; no password field, ADR-070), 212 without field, 52 re-rendered once; Fold7 cover 1,771 / inner 1,211 screens |
| apps | messaging 1,016, work_chat 868, email 532, notes 518, search 310, default 248, social 107 |
| ASR models | all 12: Whisper large-v3-turbo 491, Parakeet TDT 448, Parakeet Ultra 391, Whisper medium 379, Whisper small 292, Nemotron 3.5 267, GigaAM RNNT 231, GigaAM CTC 220, Qwen3-ASR 201, T-one 164, distil 102, Nemotron EN 54; code-made (no_op, near_no_op, noise_only) 359 |
| checks | `ml.data.recheck` 3,599/3,599 with the current `prompts/render.py`; `ml.common.validate --strict` 0 invalid in every split (ctx against `prompts/context.schema.v2.json`), also after assembly; `ml.data.contam --prompt --data`: 0 records and 0 of 73 prompt phrases near any of 1,185 eval texts (`prompts/eval/sets/*.jsonl` incl. dev/test.v2, v2dense, v2fx, v1 + `smoke_cases*.jsonl`), 4 records dropped at generation for that reason; scenario groups across splits 0; dev/test terms, people, injection strings in train 0; train targets with a char-5-gram near duplicate (Jaccard >= 0.8) in dev/test 0; review list 1 record |

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
| noise_only | 57 | 6 | 6 | 1.9 % |
| ai_addressed | 45 | 5 | 5 | 1.5 % |
| asr_artifact | 45 | 4 | 4 | 1.5 % |
| **total** | **2,997** | **300** | **300** | |

Languages: train ru 1,413 (47.1 %) / en 821 (27.4 %) / mixed 763 (25.5 %); dev 141 / 82 / 77; test 149 / 80 / 71.
Empty targets (noise_only): 57 / 6 / 6.

**Token lengths of rendered examples** (`ml.eval.contract.render_prompt` -> `prompts/render.py`, all 3,597 records,
tokenizers of the base models; prompt = system + context + transcript up to the assistant header, completion =
target + end-of-turn):

| profile | tokenizer | prompt p50 / p90 / p95 / p99 / max | completion p50 / p90 / p99 / max | total p50 / p95 / p99 / max |
|---|---|---|---|---|
| finetuned.v2 (95 % of training) | Qwen3.5 | 286 / 372 / 398 / 459 / 547 | 22 / 55 / 109 / 150 | 310 / 443 / 515 / 687 |
| finetuned.v2 | LFM3 | 295 / 391 / 419 / 486 / 592 | 25 / 67 / 139 / 190 | 322 / 480 / 572 / 761 |
| default.v4s (5 % mix) | Qwen3.5 | 2,171 / 2,257 / 2,283 / 2,344 / 2,432 | as above | 2,195 / 2,328 / 2,400 / 2,572 |
| default.v4s | LFM3 | 2,234 / 2,330 / 2,358 / 2,425 / 2,531 | as above | 2,261 / 2,419 / 2,511 / 2,700 |

Mean total with finetuned.v2: 317 (Qwen3.5) / 332 (LFM3) tokens, below the runbook's 395-token planning figure.
`max_len: auto` is set by the default.v4s share (longest 2,700 LFM3 tokens -> 2,752); no example exceeds 4,096.

### 5.2 Quality pass (generator fixed and rebuilt from the shards, no record edited)

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

Committed sample: `ml/data/samples/synth_v2.sample.jsonl` (200 train records of the full set, all 21 categories).

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
