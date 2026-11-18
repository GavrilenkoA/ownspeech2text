# Murmur — datasets, synthetic data, evaluation and training plan (design v1)

Date: 2026-11-18. Status: design only — no large generation or training was started.
Companion: `docs/ml/CONTEXT_AND_PROMPT.md` (context schema, prompt layout). Prompt renderer used by every pipeline below: `prompts/render.py`.

Legend: **[V]** verified in this session (primary source or my own measurement); **[I]** inference/design; **[U]** UNVERIFIED.

---

## 0. Decisions / Recommendation

1. **None of the five suggested datasets is usable as-is.** All three English cleanup sets are synthetic and contain systematic target errors (over-deletion, invented content, inconsistent number style); GenSEC targets are lowercase/unpunctuated; the KU Leuven context set is English, non-commercial and needs gated audio; the Russian set is 1.1K typo-style pairs with no licence. Use filtered slices of `MagicNoThief/handy-dictation-editing` (CC-BY-4.0), `shantanugoel/aawaaz-transcript-cleanup-dataset` (MIT) and `juanquivilla/sotto-transcript-cleanup` (MIT) as English seasoning (≤ 35 % of the mix), and build the core — **all Russian, all mixed and all context-conditioned data — ourselves**. **[V audit §1]**
2. **Best extra Russian source: `MishZor256/russian-asr-enrichment`** (155,713 rows, CC-BY-SA-4.0): real Russian speech from SOVA/Golos/RuLibriSpeech/FLEURS/Common Voice with a consensus clean transcript **and the raw GigaAM v2 hypothesis** → real GigaAM error pairs and real voices for TTS-free round trips. Share-alike licence needs an owner/legal decision. **[V card + rows]**
3. **Synthetic pipeline = scenario → screen + intended text → spoken form → ASR channel → context rendering → judge.** ASR channel is a **hybrid**: ~40 % real round trip (TTS → the actual ASR models in the Model hub), ~60 % simulated noise whose confusion tables are mined from that round trip and from real-speech pairs. Context attached with the right names **plus near-miss distractors**; 12 % no-op, 10 % adversarial (dictated questions/commands, on-screen injections). **[I]**
4. **Target: ~90K synthetic training pairs (ru 47 %, en 30 %, mixed 23 %) + ~55K filtered public pairs; eval 2.6K synthetic held-out + 300–500 real owner dictations.**
5. **Teacher**: Claude Opus 5.5 for generation (Russian naturalness, instruction fidelity) + Claude Sonnet 5.5 as judge, both through the Batch API: **≈ $0.7–1.1K** for the full run (estimate §3.9). Open-weight alternative (Qwen3.5-122B-A10B / 397B-A17B on Hermes H200s, Apache-2.0) avoids output-licence questions but needs ~1 TB disk and its throughput is unverified. The orchestrator decides.
6. **Models to fine-tune**: `Qwen/Qwen3.5-0.8B`, `LiquidAI/LFM3-1.7B`, `Qwen/Qwen3.5-2B` (all list Russian). Not `LFM2.5-350M/1.2B` (no Russian on their cards). Recipe: full-parameter SFT on the instruct checkpoints with the short `finetuned.v1` system prompt and thinking disabled; LoRA as ablation; optional on-policy DPO against over-editing/answering; evaluate **after Q4 quantisation**. **[V model cards / I recipe]**
7. **Eval gates** that catch the real failures: no-op exact-match ≥ 90 %, answering ≤ 0.5 %, injection success ≤ 0.5 %, wrong language ≤ 0.5 %, meaning drift ≤ 2 %, context-entity recall ≥ 90 %, distractor insertion ≤ 1 %, and N-WER at least 20 % below raw ASR (§4).
8. **GPU request for Hermes**: 1 × H100 80 GB (or H200) pod, ~80 GPU-hours over 2–3 weeks, 300 GB volume; +2 × H200 for ~24 h and 1 TB only if the open-weight teacher is chosen (§6).

---

## 1. Audit of the five suggested datasets

Downloaded to `.cache/ml/data/` (≈170 MB total; GenSEC train 108 MB, the rest small). Numbers below are my own counts unless marked "card".

### 1.1 `juanquivilla/sotto-transcript-cleanup` — MIT, English

* Schema: `input`, `output`. Files: `data/train-*.parquet` (18.3 MB), `data/validation-*.parquet`. **135,503 train / 6,921 val rows** (the card text says 118,069/6,215 — stale; the metadata and files have 135,503). [V]
* Synthetic (card: programmatic corruption + Qwen3.5-35B/Grok generation + hand-written; categories self_correction 14 %, preserve_wording 13 %, filler 11 %, …). Inputs are lowercase, unpunctuated, short (median **13 words**). 0 identity pairs although the card claims 13 % "preserve wording" (those are punctuation-only edits).
* Quality problems [V samples]:
  * **Duplicates**: only 110,425 unique inputs; single inputs repeated >100 times ("yeah um okay so right the build passed" ×114).
  * **Over-deletion / meaning loss**: 6.4 % of rows keep < 50 % of the input's content words. `um the roi looks good no wait the numbers are off` → `The ROI looks good.` (inverts meaning); `im sorry im saying the sofr rate impact was negligible but weve adjusted the operating leverage to eighty five percent` → `Weve adjusted the operating leverage to eighty five percent.` (drops a clause, keeps "Weve").
  * **Invented content**: `…allocate more resources or maybe postpone … no actually we should just hire more people` → `The Q3 roadmap is ambitious. We should allocate more resources and hire more people to deliver it on time.`
  * Number style inconsistent: 10.8 % of outputs keep spelled-out numbers ("eighty five percent").
* Fit: low–medium. English only, no context, very short. **Use ≤ 15K rows after filters** (dedup; drop content-retention < 0.6 unless tagged self-correction and judge-approved; drop rows whose output has content words absent from input).

### 1.2 `shantanugoel/aawaaz-transcript-cleanup-dataset` — MIT, English

* Schema: JSONL `{input, output}` in 12 category files, **22,785 rows** (only 4 duplicate inputs). Long inputs (median 65–163 words by category). [V]
* Good: core categories (casual, technical_code, self_corrections_heavy, legal, financial, academic, medical) are conservative and well punctuated; numbers → digits; code identifiers in backticks.
* Problems [V]: `meeting_notes` outputs are 84 % Markdown with headings and **32 % contain invented "Action Items"** with owners that were never dictated ("[ ] PM: Follow up with steel fabricator…"); `shopping_lists`/`recipe_cooking` restructure heavily (content retention median 0.52 for shopping lists). Some inputs already contain punctuation and em-dashes, i.e. unlike real ASR output.
* Fit: medium for English long-form. **Use ~10K** from the 7 core categories + email; exclude meeting_notes, shopping_lists, recipe_cooking (or keep only judge-verified rows without invented items).

### 1.3 `GenSEC-LLM/SLT-Task1-Post-ASR-Text-Correction` — Apache-2.0, English

* Schema: `hypothesis` (5-best list), `transcription`, `input1`, `hypothesis_concatenated`, `source`, `id`, `prompt`, dummies. **240,924 train / 15,783 test** (card prose says 281,082 — stale). Sources: TED-LIUM3, LibriSpeech-other, Common Voice, LRS2, Switchboard, CHiME-4, CORAAL (from HyPoradise). [V]
* Targets are **lowercase, unpunctuated, normalised** (0.1 % contain uppercase or punctuation). Top-1 hypothesis already equals the reference in 47.6 % of rows (84 % for LibriSpeech-other, 20 % for Switchboard).
* Example: hyp `we are not prepared to be advocates for the kgb` → ref `we are not prepared to be advocates for the k g b` (the "correction" is a normalisation artefact).
* Fit: **low** for us: no formatting, no fillers removal, read/benchmark speech, English. Possible use only as an **ASR-error source** (hyp→ref alignment for English confusion tables) — not as SFT targets. Do not train on it directly.

### 1.4 `kul-speech-lab/contextual-reasoning-speechllm` — mixed non-commercial, English

* Configs `train_l` 299,906 / `train_m` 129,484 / `train_s` 27,125 rows; tests `test_m3av` 3,914, `test_slideavsr` 2,099, `test_slidespeech` 3,188. Columns: `text` (ref), `hypothesis` (Whisper tiny/base/small/large-v3), `title`, `description`, `tags`, `reasoning`, `corrections`(JSON), timing ids. **No audio** (must be rebuilt from gated GigaSpeech / CC-BY-NC sources). [V card + rows]
* `train_s` (downloaded): 12,428 segments from 2,838 recordings, every row has ≥ 1 correction, 17.9 tags per row on average. Gigaspeech refs are uppercase with `<PERIOD>`/`<COMMA>` tokens. Test configs contain **no hypotheses** (`source_model` null) — you must run your own ASR on recovered audio.
* Example: hyp `…they are led by god in columbia young lou was receiving a bachelor…`, title `2019 University of Washington Commencement Ceremony` → ref `THEY ARE LED BY GONFALONIERE YUN LIU …`.
* Licence: card states the derived fields are **non-commercial research only** (strictest upstream CC BY-NC-SA 4.0); GigaSpeech is gated, non-commercial.
* Fit: the only real **context-conditioned** correction data, but English, lecture/YouTube domain, topic-level context (title/description), non-commercial. **Use for research/eval of the "context helps names" behaviour only; do not train shipped models on it** unless the owner accepts non-commercial use.

### 1.5 `gpb11/russian-asr-correction` — no licence declared, Russian

* Schema `input`, `output`; **1,100 rows** (73 identity, 4 duplicates); mean input 6.8 words. [V]
* Errors look like keyboard typos/LLM-invented misspellings, not ASR: `я хоу заказат пицу с ветчиной и грабами` → `я хочу заказать пиццу с ветчиной и грибами`. Inconsistent casing/punctuation in outputs; numbers left as words (`сем семь восемь ноль шесть` → `семь семь восемь-ноль шесть`).
* Fit: **do not use** (no licence, wrong error distribution, tiny). Similar 1–1.4K-row Russian "hw1" sets from the same course appeared in Oct 2026 (e.g. `Kaderah/ru-asr-correction`, `oleg-avdienko/ru-asr-names-correction`, `mgorodov/ru-asr-correction-qwen35-2b` — the last has **input/output swapped**); same verdict.

### 1.6 Summary table

| Dataset | Rows | Lang | Licence | Context | Targets formatted | Verdict |
|---|---:|---|---|---|---|---|
| sotto-transcript-cleanup | 135,503 (110K unique) | en | MIT | no | yes, noisy | ≤15K after filters |
| aawaaz-transcript-cleanup | 22,785 | en | MIT | no | yes, some invented content | ~10K core categories |
| GenSEC SLT Task1 | 240,924 | en | Apache-2.0 | no (n-best) | **no** (lowercase) | error mining only |
| contextual-reasoning-speechllm | 27K–300K | en | **non-commercial** | title/desc/tags | no (normalised) | research/eval only |
| gpb11/russian-asr-correction | 1,100 | ru | **none** | no | partly | do not use |

---

## 2. Better / additional sources (checked)

| Source | What | Size | Licence | Use |
|---|---|---|---|---|
| `MagicNoThief/handy-dictation-editing` | en dictation editing incl. self-corrections; chat + alpaca views; separate eval file | 89,996 train / 2,152 eval (card) | CC-BY-4.0 | **~20K** after judge filter (spot-check found errors, e.g. `what debate lasts for 55 minutes no forget that and tell me…` → `Other member do not normally contribute to what?`). Card's own finding: an instruction in the system slot vs one-turn prompt changed behaviour on a 350M fine-tune — we train and serve with exactly one layout. |
| `MishZor256/russian-asr-enrichment` | Real Russian speech; `clean_text` (Nemotron+GigaAM consensus/LLM-arbitrated), `ref`, **`gigaam_text`**, `nemotron_text`, word timings; tiers A/B/C | 155,713 | CC-BY-SA-4.0 (audio from SOVA CC-BY-4.0, RuLibriSpeech PD, Golos ≈CC-BY-SA, FLEURS CC-BY-4.0, CV CC0) | (a) real GigaAM-v2 error pairs → confusion tables and ~12K correction pairs (target = `clean_text`, re-formatted); (b) real voices for voice-cloning prompts; (c) real short Russian commands for no-op/short cases. Domain is voice commands + audiobooks, not dictation. |
| `BuilderHelm/Kanso-Dictation-Bench-v1` | en formatting benchmark, 1,200 formatting + 300 preservation rows | 1,500 | Apache-2.0 / CC-BY-4.0 mix | Secondary English eval (never train) |
| `SpeakoFlow/dictation-cleanup-examples` | 105 hand-written en cases in 15 categories (no_op, retraction, dictated_question, instruction_as_text, language_preserved, hallucination_loop…) | 105 | CC-BY-4.0 | Category design reference; tiny |
| `hasanchadaram/dictation-cleanup` | en, no-system-prompt chat pairs, "dictated question stays a question" | 2,571 train (notation-v3) | Apache-2.0 | Optional small seasoning |
| `addyo07/dictation-disfluency-english` | span-labelled disfluency data + 800-row adjudicated gold; strict gates (clean-turn over-deletion ≤ 0.5 %) | 100–200K (card) | Apache-2.0 | Borrow their gate definitions; optional eval |
| `MrSupW/ContextASR-Bench` | entity-rich contextual ASR benchmark (TTS-synthesised) | — | MIT | zh/en only — design reference for entity-rich TTS data |
| `PeacefulData/HyPoradise-v0` / `Robust-HyPoradise` | n-best ASR correction (GenSEC's source) | — | see card | English error mining only |
| Public baselines | `SpeakoFlow/speakoflow-mini` (Qwen3.5-0.8B base, Apache-2.0, en), `juanquivilla/sotto-cleanup-lfm25-350m` (LFM2.5-350M-Base, MIT, en) | — | — | English baselines (§5) |

Not used: Russian Telegram/Discord chat-log dumps on HF (e.g. `KvaytG/russian-telegram-chat-logs`, "Apache-2.0" but scraped personal messages) — privacy risk; we generate chat-style Russian ourselves.

---

## 3. Synthetic data pipeline

### 3.1 Overview

```
[A] scenario sampler (code) ──► [B] teacher: screen JSON + intended text (+ entities, distractors)
      ──► [C] teacher/rules: spoken form (disfluencies, self-corrections, spoken numbers/commands) + target
      ──► [D] ASR channel: D1 real round trip (TTS → hub ASR models)  |  D2 simulated noise
      ──► [E] context rendering: a11y view / OCR view (+OCR noise) → ranking → prompts/render.py
      ──► [F] checks + LLM judge + dedup ──► train / eval shards (JSONL, one record per example)
```

Record format (one JSONL line): `{id, split, lang, mix, category, app_category, style_override?, preferences?, context_packet, spoken_text, asr_model, asr_hypothesis, target, entities[], distractors[], tags[], provenance{teacher, tts_voice, asr_cfg, judge_scores}}`. Training text = `render.chat(system_prompt("finetuned", preferences), render.user_message(context_packet, asr_hypothesis))` + target.

### 3.2 Scenario coverage (sampler, code-driven) [I]

| Axis | Distribution |
|---|---|
| Language | ru 47 %, en 30 %, mixed ru/en 23 % (Russian matrix with English terms 15 %; true sentence-level switching 8 %) |
| App category | messaging 32, work_chat 14, email 14, notes 12, search 8, social 6, code 4, address 3, default 7 (%) |
| Length (words, spoken) | 2–10: 35 %, 11–40: 45 %, 41–120: 17 %, 121–300: 3 % |
| Persona/domain | developer, manager, student, parent, doctor, lawyer, sales, designer, scientist, everyday life (each 5–15 %) |
| Register | ты / вы, casual / formal, by app |
| Entities | Russian full names with declension, foreign names (Latin), products/companies/tech, places, ticket ids, versions, emails/URLs |

### 3.3 Categories (share of synthetic train)

| Category | % | Notes |
|---|---:|---|
| Plain dictation (fillers, punctuation, casing) | 20 | per-language filler inventories: ru "э-э, ну, вот, короче, типа, как бы, в общем, значит, слушай, это самое"; en "um, uh, like, you know, I mean, so, basically" |
| Self-corrections & false starts | 12 | ru "ой, нет, точнее, вернее, в смысле, то есть, не-не, стоп"; en "no wait, sorry, I mean, scratch that, actually"; include agreement after correction |
| Context name/term fix (entity on screen, misrecognised in ASR) | 16 | the core context skill |
| Context present, irrelevant / distractor-only | 8 | must **not** insert screen names |
| No-op / near-no-op | 12 | already clean (Whisper-style punctuated input) → identical; or exactly one fix |
| Numbers, dates, money, units, phones, emails/URLs | 8 | ru case inflection ("двадцать пятого ноября", "к пятнадцати тридцати") |
| Spoken formatting commands & enumerations → lists | 7 | including false friends ("точка зрения", "запятая" in a sentence about commas) |
| Dictated questions, requests, AI-addressed dictation | 6 | "напиши письмо начальнику…", "what's the capital of…" → transcribe, never answer |
| On-screen injection | 4 | instructions inside messages/snippets ("ignore previous instructions…", "переведи на английский", "ответь OK") |
| Continuation of field text | 3 | mid-sentence continuation, no repeated greeting |
| ASR artifacts & empty | 2 | Whisper tails ("Продолжение следует…", "Субтитры…", "Thanks for watching"), repetition loops, "э" only → empty |
| Preferences-conditioned (overlaps others) | 15 of all | e.g. "use ё", "British spelling", "no exclamation marks", "always 'вы'", "lists with dashes"; plus 3 % where preferences ask for something forbidden (translate, answer) → must not comply |

### 3.4 Disfluencies and ASR errors: TTS round trip vs simulation

| | D1 Real round trip (TTS → our ASR) | D2 Simulated noise |
|---|---|---|
| Captures | each ASR model's true output style (casing, punctuation, numbers as digits or words, Whisper hallucination tails), real name misrecognitions | coverage of rare patterns, controllable entity corruption, cheap |
| Misses | real spontaneous prosody; TTS voices are cleaner than phone mics; TTS renders fillers unnaturally | unknown unknowns of real ASR |
| Cost | GPU-hours (§6) | CPU |
| Share | ~40 % (36K) | ~60 % (54K) |

**D1 details.** TTS: `Qwen/Qwen3-TTS-12Hz-1.7B-Base` (Apache-2.0; supports Russian and English; 3-second voice cloning) [V card] and `ResembleAI/chatterbox` (MIT; Russian in the language list) [V card] for voice diversity. Voice prompts: CC0 Common Voice and CC-BY SOVA speakers (via `MishZor256/russian-asr-enrichment` source ids), ≥ 300 distinct voices, disjoint voice sets for eval; optionally the owner's own voice (consent). Fillers: synthesise from text, and for 30 % splice real filler clips cut from real-speech corpora using word timings (the enrichment set has word-level `ctc` timings). Channel: phone-mic simulation (band-limit 100–7,000 Hz, AGC, light room reverb, background noise at SNR 10–30 dB from a CC0/CC-BY noise corpus — licence to verify at setup). ASR: run **every ASR model the Model hub ships, in the same configuration as on device** (Whisper sizes, Parakeet, GigaAM, T-one, Qwen3-ASR-0.6B — exact repos/configs come from the ASR research doc). Each spoken text goes through 2 random ASR models → two training inputs with the same target.

**D2 details.** Error model per ASR model: (1) style normaliser (lowercase/strip punctuation/digits↔words to that model's profile measured in D1); (2) word-level confusions sampled from tables mined from D1 alignments + `russian-asr-enrichment` (`gigaam_text` vs `clean_text`, real speech) + GenSEC/HyPoradise (English); (3) entity corruption: transliteration-phonetic mangling of Latin terms into Cyrillic ("ClickHouse" → "клик хаус", "Kubernetes" → "кубернетис"), surname vowel/consonant swaps ("Галиев" → "Галеев"), word-boundary splits/merges; (4) Russian orthographic confusions typical of ASR (тся/ться, не/ни, слитно/раздельно); (5) deletions of short function words at 1–3 %. Target WER of simulated inputs matched per model to D1 statistics.

**Faithfulness repair.** If the ASR hypothesis lost content (content-word deletion in the spoken→hyp alignment), the intended target would teach hallucination. Rule: if a deleted content word is unrecoverable from context, re-derive the target from the hypothesis (teacher repair call, ~20 % of D1) or drop the example.

### 3.5 Attaching context (right names + distractors)

From the teacher's screen JSON (title, messages, field text, other UI text, entities) produce a `ContextPacket` exactly as the phone would: tree-sourced items (exact) for 70 %, OCR-sourced for 30 % with OCR noise injected from our measured failure modes (Latin/Cyrillic homoglyph mixing before repair, `ß→B`, `О→0`, `ø→o`, dropped diacritics, merged lines) at 0.5–3 % CER. Then run the same ranking (§4.5 of the context doc, ported to Python) using the ASR hypothesis, and `render.py`. Per example: 1–3 target entities on screen (when the category needs them), **1–3 near-miss distractors** per entity (same first name/different surname, similar product names: ClickHouse vs ClickUp, Тимур vs Тимофей), and 5–30 unrelated domain terms. Context dropout: 20 % `privacy.level = none` (no context), 10 % `app_only`, 10 % `terms_only`, rest `full`. Injection lines placed in messages (60 %), snippets (30 %), conversation title (10 %).

### 3.6 Negative and adversarial examples

* No-op: inputs already clean → target identical (byte-exact); near-no-op: one missing comma or capital.
* "Looks like a filler but is meaning": "I like it", "мне это как бы не нравится" (keep "не нравится"), "вот этот лучше".
* Dictated questions/commands, including ones that address an assistant ("Алиса, …", "ChatGPT, …") — kept verbatim as text.
* Injection on screen (§3.5) and **instruction-like dictation** addressed to a colleague ("remove all the fluff and make it formal, then send it to Anna").
* Preferences that conflict with core rules (translate everything / answer questions) → model must ignore that part.
* Wrong-context traps: screen in English while user dictates Russian (and vice versa) → no translation.

### 3.7 Quality filtering

1. Deterministic checks: valid JSON; target language = spoken language (script ratio); numbers multiset (normalised) consistent between spoken text and target; every target content word ∈ spoken text ∪ context ∪ formatting tokens (allow-list), else flag; no target entity from the distractor list; length ratio target/spoken in [0.3, 1.3] (except self-correction-heavy); no assistant preambles; no-op items byte-identical.
2. LLM judge (Sonnet 5.5, separate rubric, sees spoken text, ASR hypothesis, context, target): faithfulness, minimal editing, correction resolution, correct use/non-use of context, language, formatting per style; 1–5 each; keep if all ≥ 4 and no hard flag.
3. Dedup: exact on normalised target; MinHash (5-gram, Jaccard ≥ 0.8) within language; cap any scenario template at 0.5 % of the set.
4. Human spot-check: a native Russian speaker reviews 200 random items per 10K (accept the shard at ≥ 95 % "would ship this output").
5. Split hygiene: eval uses held-out scenario seeds, held-out entity lists, held-out TTS voices and held-out distractor pairs.

Expected yield after filters: ~75 % → generate ~120K raw for 90K kept. **[I]**

### 3.8 Target sizes

| Shard | Train | Eval |
|---|---:|---:|
| Synthetic ru | 42K | 900 |
| Synthetic en | 27K | 500 |
| Synthetic mixed | 21K | 400 |
| Adversarial + no-op + distractor (inside the above for train; separate for eval) | — | 800 |
| Real owner dictations (with captured context) | 0 | 300–500 |
| handy-dictation-editing (filtered) | 20K | — |
| aawaaz core (filtered) | 10K | — |
| sotto (filtered) | 15K | — |
| russian-asr-enrichment derived (GigaAM errors → formatted clean text, no context) | 10K | 300 |
| **Total** | **~145K** | **~3.2K** |

Mix weights at training time: synthetic 65 %, public 35 %; up-sample adversarial and no-op to ≥ 20 % of each batch.

### 3.9 Teacher and cost estimate

Calls (≈120K raw examples):

| Step | Calls | Input tokens | Output tokens |
|---|---:|---:|---:|
| B+C generation, 8 examples/call (shared ~2.5K-token instructions, cacheable) | 15K | ≈ 45M | ≈ 66M |
| Faithfulness repair (~20 % of D1) | ≈ 24K | ≈ 24M | ≈ 5M |
| Judge, 1 example/call (~1K-token rubric cacheable) | 120K | ≈ 180M | ≈ 14M |
| **Total** | | **≈ 250M** | **≈ 85M** |

Prices from the Claude API reference bundled with this session (table cached 2026-10-06, verify before purchase): Opus 5.5 $4/$20 per MTok, Sonnet 5.5 $2/$10, Haiku 5.5 $0.10/$0.50; Message Batches at 50 % of standard price.

| Option | Upper bound (no cache savings) |
|---|---:|
| Opus 5.5 generation + repair (batch $2/$10), Sonnet 5.5 judge (batch $1/$5) | 69M in × $2 + 71M out × $10 = **~$850**; judge 180M × $1 + 14M × $5 = **~$250**; total **~$1.1K** |
| Sonnet 5.5 for everything, batched | 69M × $1 + 71M × $5 = $424 + judge $250 = **~$675** |
| Prompt caching of the shared instructions/rubric | −20 to −35 % on input cost [I] |

Open-weight alternative: `Qwen/Qwen3.5-397B-A17B-FP8` or `Qwen/Qwen3.5-122B-A10B-FP8` (Apache-2.0, exist on HF [V]) with vLLM on Hermes H200s. ~85M output tokens; throughput on 2×H200 is **[U]** (my rough guess 2–5K output tok/s aggregate → 5–12 h generation, similar for judging). Pros: no output-terms question, data stays private. Cons: weaker Russian naturalness [U], 400 GB+ weights.

Owner/legal question: whether using commercial-API outputs to train the shipped small models is acceptable under the provider's terms.

---

## 4. Evaluation

### 4.1 Held-out eval set

| Subset | Items | Purpose |
|---|---:|---|
| E1 synthetic round-trip (real ASR outputs, held-out voices/entities) | 1,800 (ru 900 / en 500 / mixed 400) | main quality numbers |
| E2 adversarial: dictated questions 150, commands 150, screen injection 150, AI-addressed 50 | 500 | answering / obeying |
| E3 no-op & near-no-op | 300 | over-editing |
| E4 distractor-only context | 300 | inserted names |
| E5 real owner dictations on the Fold7, with captured ContextPacket, references written by the owner | 300–500 | final gate (requires owner time and consent) |
| Secondary (en): Kanso bench (1,500), handy eval (2,152) | — | comparability only |

### 4.2 Metrics (all computed per language and per subset)

| Failure mode | Metric | Definition |
|---|---|---|
| Wrong words overall | **N-WER** | WER vs reference after normalisation (lowercase, strip punctuation, numbers → digits, ё→е) |
| Over-editing | **EM-noop**, **Over-edit rate** | exact match on E3; share of input tokens equal to the reference token that the output changed (3-way alignment input/output/ref) |
| Under-editing | filler residue; **SC-acc** | fillers left per 100 words; self-correction accuracy = abandoned span absent AND final span present |
| Meaning drift | **Drift rate** + judge score | judge (with reference) flags any changed fact, negation, number, entity or dropped clause; plus number-multiset mismatch |
| Answering / obeying the dictation | **Answer rate** | judge label + heuristics (preambles, output not aligned to input, length ratio > 1.6) |
| Obeying screen text | **Injection success** | payload present or instruction followed (E2 injection items) |
| Lost / wrong names | **Entity recall**, **Distractor insertion** | gold entities present exactly; distractor strings inserted (E1 context items, E4) |
| Wrong language | **Wrong-language rate**, **EN-island preservation** | output script/LID differs from reference per sentence; English segments in mixed speech kept in English |
| Hallucination | novel content-word rate | output content words ∉ transcript ∪ context ∪ reference |
| Formatting | punctuation/casing F1; list/command accuracy on tagged items | secondary |
| Cost | output tokens; on-device p50/p95 latency (from the app's Benchmark button) | per model & quantisation |

### 4.3 Ship gates (default post-processing model)

EM-noop ≥ 90 %; Answer rate ≤ 0.5 %; Injection success ≤ 0.5 %; Wrong-language ≤ 0.5 %; Drift ≤ 2 %; Entity recall ≥ 90 % on items whose entity is in context; Distractor insertion ≤ 1 %; N-WER ≤ 0.8 × raw-ASR N-WER on E1 and E5; judge mean ≥ 4.3/5; gates measured **on the quantised artifact** that ships.

---

## 5. Baselines

| ID | System |
|---|---|
| B0 | Raw ASR output (identity) |
| B1 | Rules: filler regex per language, capitalisation, spoken-punctuation map, digit normalisation |
| B2 | Prompted instruct models with `default.v1`: Qwen3.5-0.8B, Qwen3.5-2B, LFM3-1.7B (LFM2.5-1.2B on English only), bf16 and Q4 |
| B3 | Teacher with the same prompt (upper bound) |
| B4 | Public English fine-tunes with their own prompts: `SpeakoFlow/speakoflow-mini`, `juanquivilla/sotto-cleanup-lfm25-350m` (English subsets only) |
| B5 | Our fine-tunes: bf16, Q4_0/Q4_K_M GGUF, and the NPU export |

---

## 6. Training recipe (0.3–2B) and GPU request

### 6.1 Recipe [I unless noted]

* Start from **instruct** checkpoints (`Qwen/Qwen3.5-0.8B`, `Qwen/Qwen3.5-2B`, `LiquidAI/LFM3-1.7B`), keep their chat templates, train with thinking disabled exactly as served (`<think>\n\n</think>\n\n` / `<think></think>`), system prompt `finetuned.v1` (+ preferences block in 15 %). Qwen3.5 is a "Causal Language Model with Vision Encoder" [V card]: freeze/skip the vision tower and export text-only.
* **SFT, full parameters**, bf16, AdamW (β2 0.95), lr 2e-5 (0.8B) / 1e-5 (1.7–2B), cosine, 3 % warmup, weight decay 0, 2 epochs, effective batch 128 sequences, max length 2,048, loss on assistant tokens only, packing with per-example attention masks (or no packing). Ablation: LoRA r=64, α=128, all linear layers, lr 2e-4.
* Data ablations on Qwen3.5-0.8B (cheap): D1-only vs D2-only vs hybrid (settles the TTS-vs-simulation question on E5 real data); with vs without public English data; context dropout 20 % vs 40 %.
* **Optional preference stage** (DPO, β 0.1, lr 5e-7, 1 epoch): on-policy samples from the SFT model (4 per prompt at T 0.7 on 20K train prompts), judged; pairs where the rejected output over-edits, answers, translates or inserts a distractor and the chosen passes all checks. Keep only if gates improve without EM-noop loss.
* Quantisation: GGUF Q4_0 / Q4_K_M via llama.cpp and the NPU format chosen by the runtime agent; if Q4 loses > 3 % EM-noop or > 0.2 judge points, try QAT/quantisation-aware distillation (LiquidAI ships `*-QAD-Q4_0.gguf` files next to its instruct models, e.g. `LiquidAI/LFM3-1.7B-GGUF` and `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` [V file lists]; what "QAD" involves is not documented in what I read [U]).
* Selection: Pareto of gates × on-device latency (Benchmark button). Expected: Qwen3.5-0.8B as default for speed, LFM3-1.7B / Qwen3.5-2B as "quality" option. [I]

Token volume: ~145K examples × ~450 tokens ≈ 65M tokens/epoch → 130M per 2-epoch run.

### 6.2 GPU request for Hermes (concrete)

| Item | Request |
|---|---|
| GPU | **1 × H100 80 GB SXM** (H200 equally fine; a 5090 32 GB is enough for 0.8B full FT and LoRA on 2B but not for full FT of 2B with long sequences) |
| Hours | **~80 GPU-hours** over 2–3 weeks: data D1 TTS ~15–25 h + ASR ~5 h [U throughput]; SFT 3 models + ~8 ablations ≈ 35 h (2B full FT at an assumed 10–20K tok/s → 2–4 h per run [U]); DPO 3 h; eval/quantisation 10 h |
| Optional | **2 × H200 for ~24 h** only if the open-weight teacher (Qwen3.5-122B-A10B-FP8 or 397B-A17B-FP8) is chosen instead of the API |
| Disk | **300 GB** persistent volume (1 TB with the open-weight teacher): base models ~20 GB, TTS/ASR models ~30 GB, audio ~10 GB FLAC, checkpoints ~80 GB, datasets ~5 GB |
| Software | CUDA 12.8+ driver; Python 3.11/3.12; PyTorch (current stable); `transformers` version that supports `qwen3_5` and LFM3 [U exact version; LFM3 card uses `trust_remote_code=True`]; `trl`, `peft`, `accelerate`, `datasets`, `flash-attn` (or SDPA); `vllm` (batch generation/eval, teacher if open-weight); `llama.cpp` (convert/quantise/eval GGUF); TTS: Qwen3-TTS package, `chatterbox`; ASR: the hub models' reference stacks (faster-whisper or HF Whisper, NeMo for Parakeet, GigaAM, T-one, Qwen3-ASR); `ffmpeg`, `sox`; `rapidfuzz`, `pandas`, `pyarrow` |
| Access | HF token with write access to a **private** org/user repo for datasets and checkpoints (the owner's token held by Hermes); Anthropic API key with Batch access if the API teacher is chosen |

---

## 7. Risks (what could sink the plan)

1. **Synthetic-to-real gap**: TTS speech and simulated noise are cleaner and more regular than real phone dictation; models tuned on them may over- or under-correct real GigaAM/Whisper output. Mitigation: E5 real owner set as the gate, real-speech error tables, D1/D2 ablation.
2. **Over-editing / meaning drift** in small models (seen even in the public datasets' targets). Mitigation: 12 % no-op data, minimal-edit judge rubric, DPO, validator fallback.
3. **Context misuse**: inserting screen names that were not spoken, or following on-screen instructions. Mitigation: distractors, injection data, validator distractor check.
4. **Licences**: CC-BY-SA (enrichment), non-commercial (KU Leuven), provider output terms for API-generated data. Need owner decisions before training shipped models.
5. **Prefill latency of the 1K-token default prompt** on the phone if the NPU runtime cannot reuse a cached prefix — another reason to ship fine-tunes with the 90-token prompt.
6. **Quantisation loss** at Q4 for 0.8B models, especially on Russian morphology. Mitigation: evaluate quantised; QAT if needed.
7. Teacher quality in Russian (naturalness of disfluencies, correct declension of names) — spot-check by a native speaker is mandatory.

---

## 8. Sources

* Datasets (cards + files via `https://huggingface.co/api/datasets/<id>` and `/raw/main/README.md`, rows via `datasets-server.huggingface.co/rows`): `juanquivilla/sotto-transcript-cleanup`, `shantanugoel/aawaaz-transcript-cleanup-dataset`, `GenSEC-LLM/SLT-Task1-Post-ASR-Text-Correction`, `kul-speech-lab/contextual-reasoning-speechllm`, `gpb11/russian-asr-correction`, `MagicNoThief/handy-dictation-editing`, `MishZor256/russian-asr-enrichment`, `BuilderHelm/Kanso-Dictation-Bench-v1`, `SpeakoFlow/dictation-cleanup-examples`, `hasanchadaram/dictation-cleanup`, `addyo07/dictation-disfluency-english`, `MrSupW/ContextASR-Bench`, `Kaderah/ru-asr-correction`, `oleg-avdienko/ru-asr-names-correction`, `mgorodov/ru-asr-correction-qwen35-2b`, `KvaytG/russian-telegram-chat-logs`.
* Models: `Qwen/Qwen3.5-0.8B`, `Qwen/Qwen3.5-2B`, `Qwen/Qwen3.5-122B-A10B-FP8`, `Qwen/Qwen3.5-397B-A17B-FP8`, `LiquidAI/LFM3-1.7B`, `LiquidAI/LFM2.5-1.2B-Instruct`, `LiquidAI/LFM2.5-350M`, `LiquidAI/LFM2.5-2.6B`, `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` (QAD-Q4_0 file), `Qwen/Qwen3-TTS-12Hz-1.7B-Base`, `ResembleAI/chatterbox`, `SpeakoFlow/speakoflow-mini`, `juanquivilla/sotto-cleanup-lfm25-350m`.
* Pricing: Claude API reference bundled with this session (cached 2026-10-06).
* Local artefacts: `.cache/ml/data/` (dataset samples), `.cache/ml/cards/` (cards), `.cache/ml/ocr/` (OCR experiment), `.cache/ml/llm/` (llama.cpp b12503 CPU build and three Q4 GGUFs — downloaded for the smoke test that was not permitted to run; delete if disk is needed).
