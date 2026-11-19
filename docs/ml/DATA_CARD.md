# Data card — Murmur cleanup fine-tune (PLAN_V2 stage C, CONTEXT_V2)

Status 2026-11-19 (review fixes applied): the generator is built and tested; a 216-scenario proof ran end to end with the real teacher,
the real round-trip noise tables and the real render + OCR path. The full 3K set has NOT been generated yet
(one command, below). Code: `ml/data/` (README there), records: shared contract in `ml/common/schema.py`.

## 1. What one example is

`{"id", "lang": ru|en|mixed, "category", "source": "sim", "split", "ctx", "transcript", "target", "meta"}`.
`ctx` is ctx v2 (`docs/ml/CONTEXT_V2.md`): app + focused field from the scenario (accessibility side, exact),
`screen.lines` = output of the app's OCR pipeline (`ml/ocr`, `ocr.v1`) on a rendered phone screenshot
(`ml/screens`), absent when there is no screenshot. The prompt is not stored; training and eval render it with
`prompts/render.py`. `meta` carries: teacher model + prompt version (`gen.v6`), scenario id/group, the spoken
text, ASR model id and every noise op (`meta.noise`), disfluency ops, the garble of the hard term or surname,
entities / distractors / injection, and `meta.screen` = render spec + render parameters + OCR pipeline version
(enough to rebuild the ctx; PNGs are not kept).

## 2. Sources

| Source | Role | Licence | Status |
|---|---|---|---|
| Synthetic (`ml/data/generate.py`) | primary: train/dev/test | targets and screen content are outputs of a proprietary hosted model (`gpt-6.2-sol`) reached with the owner's account; **terms of use for training on its outputs UNCONFIRMED, see 2a** | proof run done, full run pending |
| ASR noise: `ml/roundtrip/samples/mined/real` | error tables mined from a real text -> TTS -> ASR probe (837 rows, 11 ASR models) | owned by ml/roundtrip | used via `ml/roundtrip/mine/noise.py` |
| Screens: `ml/screens` + `ml/ocr` | render + OCR = ctx | fonts OFL/Apache (see ml/screens/README) | used via `build_ctx` |
| Public cleanup sets (`ml/data/public/`) | optional secondary source, frozen | see section 6 | converted 2026-11-18, not mixed into the synthetic set |

## 2a. Provenance and terms of the teacher outputs (owner decision before any public upload)

- **Who wrote what.** Every `target`, every `spoken` text and all screen content (titles, messages, snippets) were
  written by the teacher `gpt-6.2-sol` (PLAN_V2: the owner's Hermes agent's hosted "codex" model behind a local
  proxy speaking the OpenAI Responses API), prompt `gen.v6` (`ml/data/synth/teacher_prompts.py`), generated
  2026-11-18/19 on the owner's server. Each record carries `meta.teacher` and `meta.gen`; each run writes
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
4. **Transcript**: spoken -> garble of the hard term (mined manglings first; English-style when the term sits in
   an English clause) or surname -> disfluencies (only fillers the deployed prompt deletes; each hesitation sound and
   repeat survives with the ASR model's rate, `ml/data/probe/disfluency_render.v1.json`) -> ASR channel of one of
   11 real ASR models (mined style, confusions, deletions, insertions, digits-vs-words; English clauses of
   code-switched speech simulated as English by recognisers that write Latin) -> hallucinated tail for
   asr_artifact. Realism guards: a substitution must sound like the word (phonetic similarity >= 0.45 for 4+
   letters, >= 0.34 shorter, or a known function-word swap), an insertion must be a function word or filler, a
   generic term garble must match the model's mined habits (no single letters; interior letter drops only for
   models whose manglings show them; Cyrillic-writing models get a phonetic transliteration instead). Retried with
   new seeds until the target is recoverable from transcript + context: every content word heard (same script:
   spelling similarity >= 0.6, other script: phonetic >= 0.75, never from a single letter) or on screen, and every
   number heard or on screen.
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

## 5. Measured (proof run 2026-11-19, `.cache/ml/data/synth_runs/proof_v7`, after the review fixes)

Command: `tools/with-teacher tools/heavy .cache/ml/ocr/venv/bin/python -m ml.data.generate --out
.cache/ml/data/synth_runs/proof_v7 --limit 216 --max-requests 45 --mined ml/roundtrip/samples/mined/real --workers 3
--screens real` (stats: `proof_v7/stats.json`, report: `proof_v7/REPORT.md`). Prompt `gen.v6`; the two tweaks made
after reading it (`gen.v7`: "never copy the quoted illustrations"; long-word substitution threshold 0.55; filter
`copied_prompt_example`) were applied to its output post hoc: 8 of 198 records fail them and are left out of the
committed sample.

| stage | measured |
|---|---|
| teacher | 38 requests (all new), 0 failures, 0 retries; p50 latency 22.2 s; 429.3 s wall at concurrency 2 (= 11.3 s per request); 2.8K input + 0.9K output tokens per request |
| render + OCR | 227 screens: render 0.89 s, OCR 2.36 s CPU per screen; build wall 254.8 s with 3 workers |
| yield | 216 teacher examples -> 199 after build + filters -> 198 after dedup (70 / 62 / 66 train/dev/test); 0 eval-contaminated, 0 on the review list |
| drops | implausible ASR noise 7 (new guards), dropped_content 7, OCR rules 8 (injection 3, name 2, distractor 2, term 1), term not in spoken 2, asr_unrecoverable 2, one each: correction marker, abbreviation garble, length ratio, novel content, number mismatch, duplicate |
| ctx | 161 with screen, 37 without (secure window 14, denylisted 12, screenshot error 6, OCR empty 3, password 2), 15 without field, 1 re-rendered |
| ASR disfluency | hesitation tokens per 100 transcript words: Whisper large-v3-turbo 0.16, medium 0.0, small 1.57, distil 0.65, Parakeet 0.45-0.56, GigaAM 0.65-0.9, Nemotron 1.0-2.1, T-one 0.0 (`stats.json: asr_disfluency`, next to each model's render profile and mined WER) |
| checks | `ml.data.recheck` 198/198 pass with the current renderer; `ml.common.validate --strict` 0 invalid; `ml.data.contam` 0 records and 0 prompt phrases near an eval item; leakage none |

Effect of the noise guards alone (offline A/B on the 216 `proof_v6` teacher examples, fixture screens, 0 requests):
199 kept; the guards cost 5 examples whose every seed stayed implausible (`implausible_noise`) and 5 counterparts
whose term now garbles the English way inside an English clause (`counterpart_latin_garble`); 2 Latin garbles
became Cyrillic transliterations and 18 code-switched transcripts were simulated clause by clause.

**Disfluency probe** (`make disfl-probe`, 2026-11-19): 32 disfluent sentences x 3 CPU TTS systems -> 12 ASR models,
724 hypotheses, 814 s wall. Repeats of 3+ letter words kept: Whisper large-v3-turbo 6 %, medium 24 %, small 29 %,
distil 22 %, Parakeet 53-59 %, Nemotron 22-53 %, Qwen3-ASR 53 %, GigaAM 63 %, T-one 63 %. TTS-read hesitations
kept 23-79 % (upper bound, see section 7). Table: `ml/data/probe/disfluency_render.v1.json`.

**Full set projection** (3,000 + 300 + 300, over-generation 1.15): 3,871 scenarios, 3,788 teacher scenarios in
633 requests (-> about 2.0 h at concurrency 2 and 11.3 s per request; shared quota: run it once), about 3,460
screens (-> about 65 min with 3 build workers, about 40 min with 5). Expected total about 2.7-3.1 h wall, CPU only.
Command: `make -C ml/data synth`.

**Manual review** (40 records of `proof_v7` read by a native ru/en reader, stratified over all 21 categories, after
122 on earlier runs): targets are faithful (no answers, no translations, no executed commands; numbers, lists,
formatting commands, code-switched clauses and surnames restored from the screen are correct; "Слушай" is no longer
injected as a filler). Code-switched transcripts now keep the English clause in English. Defects found and handled:
the teacher copied the prompt's illustration "точка сбора у входа" into 5 of 9 formatting-command examples (prompt
`gen.v7` forbids copying; filter `copied_prompt_example`); `вопросительный -> пользователей` passed the
substitution rule (phonetic 0.50; long words now need 0.55). Left as known risks: a few substitutions produce
another valid word (`сохранить -> сравнить`, `приходят -> находят`) that only sentence context resolves; a target
restores a function word the ASR deleted (`you like` -> `If you like`); one target capitalises a place name the
speaker said in lowercase (`Design Museum` in a search query).

Committed sample: `ml/data/samples/synth_v2.sample.jsonl` (190 records of `proof_v7`).

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
