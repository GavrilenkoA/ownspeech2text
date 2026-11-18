# Round trip: probe set → TTS → ASR → mined errors

PLAN_V2 stages A and B. Each section is owned by one part of the work; edit only your own section.

- **Probe set** — sentences and the hard-term lexicon (`ml/roundtrip/probe/`)
- **Round trip** — TTS voices × ASR models over the probe set (`ml/roundtrip/tts/`, `ml/roundtrip/asr/`)
- **Mining** — error profiles, confusions and term manglings (`ml/roundtrip/mine/`)

<!-- BEGIN SECTION: Probe set (owner: probe-set agent) -->
## Probe set

The probe set is the text side of the round trip: short sentences in the ideal final written form
(casing, punctuation and term spelling as a careful engineer would type them), dense with the IT/AI
vocabulary that Russian-speaking engineers dictate and ASR gets wrong. All tooling is stdlib-only Python
(pytest for the tests); nothing here needs a GPU or a model download.

### Files

| path | what |
|---|---|
| `ml/roundtrip/probe/terms.jsonl` | lexicon, 725 hard terms in 14 categories (built from `src/terms.txt`) |
| `ml/roundtrip/probe/seed.jsonl` | 592 hand-written seed sentences: ru 189, en 176, mixed 227 (built from `src/seed.txt`) |
| `ml/roundtrip/probe/gen.jsonl` | 120 teacher-generated sentences (ru 36, en 30, mixed 54), `--seed 1` |
| `ml/roundtrip/probe/gen.jsonl.state.json` | resume state of that run (batch counter, request count) |
| `ml/roundtrip/probe/src/terms.txt`, `src/seed.txt` | the editable sources; never hand-edit the JSONL |
| `ml/roundtrip/probe/build_probe.py` | sources → `terms.jsonl` + `seed.jsonl`; fails on any invalid record or duplicate |
| `ml/roundtrip/probe/validate_probe.py` | validator + coverage report (the tables below) |
| `ml/roundtrip/probe/gen_probe.py`, `gen_prompt.md` | teacher-driven scaling; the full prompt text is in `gen_prompt.md` |
| library modules | `lexicon.py` (schema, Russian-inflection-aware matcher), `schema.py` (record checks), `dedup.py`, `respell.py` (`tts_text`), `plan.py` (slot planning), `prompt.py`, `teacher.py`, `textutil.py` |
| `ml/roundtrip/tests/test_probe_*.py` | 66 tests (validator, dedup, respell, planning, generator with a fake teacher) |

Probe records follow the binding interface `{"id","lang","domain","text","terms","tts_text"}`. Ids are
`seed-<lang>-NNNN` and `gen-<lang>-bNNNNN-sNN`. Downstream code should read probe sentences from
`seed.jsonl` and `gen.jsonl` by name: `terms.jsonl` in the same directory is the lexicon, not a probe file.

### Lexicon (`terms.jsonl`)

`{"term","category","written_forms","ru_spoken","en_spoken","notes"}`. `written_forms[0]` is the
canonical spelling; the rest are accepted variants (`Postgres` for `PostgreSQL`, `Fold7` for
`Galaxy Z Fold7`, inflection seeds such as `задеплоил`). `ru_spoken` holds the Cyrillic readings an
engineer uses inside Russian speech (`k8s` → `кубер; кубернетес; кейтс`, `H100` → `аш сто; эйч сто`);
`en_spoken` holds the English reading wherever it differs from the spelling (`nginx` → `engine x`,
`Traefik` → `traffic`). 372 entries have several Russian readings, 317 have an English one, and there are
173 alternate written forms.

| category | entries | examples |
|---|---:|---|
| ai_ml_jargon | 82 | LoRA, QLoRA, RAG, KV cache, logits, top-p, GGUF, quantization |
| dev_slang_ru | 81 | деплой, задеплоить, стейджинг, пулреквест, мердж, ревью, фича, бэкенд |
| acronyms | 72 | API, SDK, GPU, NPU, CI/CD, SLA, JSON, YAML, p99 |
| cloud_services | 61 | AWS, EC2, Cloudflare, RunPod, Yandex Cloud; Russian services in Cyrillic (Яндекс Трекер, Т-Банк) |
| languages_frameworks | 55 | Kotlin, FastAPI, Pydantic, Next.js, Spring Boot |
| model_names | 54 | Qwen3.5 2B, LFM3-1.7B, Whisper large-v3-turbo, Parakeet TDT 0.6B v3, GigaAM, T-one |
| ai_tools | 52 | vLLM, llama.cpp, ONNX Runtime, Hugging Face, LangChain, sherpa-onnx |
| devops_infra | 47 | Kubernetes, k8s, Helm chart, Argo CD, Grafana, Nginx |
| cli_commands | 43 | git rebase, docker compose up, kubectl apply, nvidia-smi, adb install |
| databases_data | 41 | PostgreSQL, ClickHouse, Redis, pgvector, Qdrant |
| ai_ml_jargon_ru | 41 | инференс, датасет, батч, эмбеддинг, дообучение (Cyrillic-only ML jargon) |
| files_config | 33 | .env, README, values.yaml, docker-compose.yml, build.gradle.kts |
| mobile_android | 33 | Jetpack Compose, ViewModel, WorkManager, AccessibilityService |
| hardware | 30 | H100, RTX 5090, Snapdragon 8 Elite, Hexagon NPU, Galaxy Z Fold7 |

**Matching** (`lexicon.TermMatcher`). Latin forms match whole tokens; a form with an upper-case letter
is matched case-sensitively (`Room` ≠ `room`). Cyrillic forms match with Russian inflection through a light
stemmer, so `стейджинг` finds `на стейджинге` and `задеплоить` finds `задеплоили`. Overlaps resolve
longest first (`Helm chart` beats `Helm`). `validate_lexicon` requires unique terms, no written form owned by
two entries, and every form to resolve to its own entry.

### Seed sentences (`seed.jsonl`)

Hand-written in `src/seed.txt` under `@<lang> <domain>` headers, with `[term]` for lexicon terms and
`{Name}` or `{Latin=Кириллица}` for people. Lexicon terms the matcher finds outside brackets are tagged
too, so the `terms` lists are complete. They cover messenger replies, work-chat updates, e-mails, notes,
stand-ups, commit and PR text, prompts to an AI assistant, terminal-style requests, calendar requests and
docs. The sentences use names, versions, numbers, dates and times, and decline Cyrillic borrowings
correctly ("на стейджинге", "в дообучении"). Latin terms stay uninflected ("в Kubernetes", "через LoRA").

- `ru` contains no Latin letters at all, so its terms come from the Cyrillic categories (`dev_slang_ru`,
  `ai_ml_jargon_ru`, Cyrillic `cloud_services`). Latin product names said inside Russian speech are the
  `mixed` set: a Russian sentence with the terms kept in Latin, Cyrillic words ≥ Latin words.
- `tts_text` is required for `mixed`: the sentence with every Latin term replaced by a Russian reading,
  for Russian voices that cannot read Latin. For `en` it is set only where the English reading differs from
  the spelling (42 seed sentences). For `ru` it is `null`.

**How `tts_text` picks a reading** (`respell.pick_reading`). A careless choice makes TTS drop or add words
that the reference text still contains, and mining would then count fake deletions. The rules:
- For the canonical spelling, the first reading that has at least as many words as the surface has
  Latin/digit pieces wins (`Parakeet TDT` → `паракит ти ди ти`, not `паракит`).
- For an alternate form, the reading that sounds most like the surface wins (character similarity to a rough
  transliteration, with acronyms spelled out and digits ignored). Ties go to the word count closest to the
  surface: `Fold7` → `фолд семь`, `Material You` → `материал ю`, `HF` → `аш эф`, `5090` → `пятьдесят девяностая`.
- A Russian case ending on the surface is carried into the reading: `A/B-теста` → `аб теста`.

This pass changed 13 seed `tts_text` values versus the predecessor's version and added the missing readings
for about 20 alternate forms. Known limitation: a Latin term in an oblique case keeps the reading's
nominative form ("записать perplexity" → "записать перплексия").

### Coverage of the seed set

`python3 ml/roundtrip/probe/validate_probe.py ml/roundtrip/probe/seed.jsonl --min-lang ru=170,en=100,mixed=150`
→ exit 0, `OK: 592 valid sentences`.

- Sentences: **592**: ru 189, en 176, mixed 227
- Lexicon entries used: **649 / 725** (90%); 1105 term occurrences; 48 person-name mentions
- Terms per sentence: 1: 182, 2: 318, 3: 81, 4: 11
- Words per sentence: ru 6–25 (median 11), en 10–28 (median 14), mixed 8–26 (median 12)
- tts_text set: ru 0, en 42, mixed 227

| category | lexicon | used | coverage | occurrences | sentences | ru | en | mixed |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| devops_infra | 47 | 35 | 74% | 52 | 39 | 0 | 13 | 26 |
| databases_data | 41 | 34 | 83% | 46 | 33 | 0 | 14 | 19 |
| languages_frameworks | 55 | 35 | 64% | 56 | 38 | 0 | 15 | 23 |
| ai_tools | 52 | 46 | 88% | 63 | 48 | 0 | 19 | 29 |
| ai_ml_jargon | 82 | 76 | 93% | 111 | 72 | 0 | 39 | 33 |
| ai_ml_jargon_ru | 41 | 41 | 100% | 89 | 59 | 55 | 0 | 4 |
| model_names | 54 | 45 | 83% | 54 | 38 | 0 | 15 | 23 |
| dev_slang_ru | 81 | 81 | 100% | 262 | 160 | 131 | 0 | 29 |
| cli_commands | 43 | 43 | 100% | 54 | 42 | 0 | 22 | 20 |
| files_config | 33 | 30 | 91% | 37 | 33 | 0 | 8 | 25 |
| acronyms | 72 | 66 | 92% | 121 | 89 | 0 | 39 | 50 |
| cloud_services | 61 | 59 | 97% | 73 | 59 | 16 | 17 | 26 |
| hardware | 30 | 27 | 90% | 39 | 35 | 0 | 15 | 20 |
| mobile_android | 33 | 31 | 94% | 48 | 31 | 0 | 14 | 17 |

| domain | ru | en | mixed | total |
|---|---:|---:|---:|---:|
| messenger | 28 | 15 | 23 | 66 |
| work_chat | 42 | 24 | 27 | 93 |
| email | 22 | 16 | 21 | 59 |
| notes | 18 | 15 | 25 | 58 |
| standup | 21 | 16 | 25 | 62 |
| commit_pr | 14 | 16 | 23 | 53 |
| ai_prompt | 19 | 22 | 26 | 67 |
| terminal | 6 | 21 | 23 | 50 |
| calendar | 8 | 9 | 11 | 28 |
| docs | 11 | 22 | 23 | 56 |

**Seed + generated** (`validate_probe.py seed.jsonl gen.jsonl --min-lang ...` → exit 0, 712 valid):
ru 225, en 206, mixed 281; lexicon entries used **719 / 725 (99%)**; 1339 term occurrences. Per category,
the used/lexicon counts become: devops_infra 46/47, databases_data 41/41, languages_frameworks 50/55, and
100% in every other category. The generator fills the terms the seed never used first.

### Validator (`validate_probe.py`)

`python3 ml/roundtrip/probe/validate_probe.py [files...] [--min-lang ru=170,en=100,mixed=150] [--format md|json|none]`.
Exit 0 when valid, 1 on any violation (listed on stderr), 2 when the lexicon or inputs cannot be read.
Checks:
- **Lexicon:** the schema and the cross-entry invariants above.
- **Schema:** the exact key set, id format, `lang` and `domain` enums, types.
- **Text:** NFC, no stray whitespace, 6–35 words, ends with sentence punctuation.
- **`lang` against the script mix:** ru has no Latin letter, en has no Cyrillic letter, mixed has both with
  a Cyrillic majority.
- **Terms:** 1–4 lexicon terms plus at most 3 person names. Each occurs verbatim in `text` as a whole token
  and resolves to a lexicon entry of the stated category.
- **`tts_text`:** `null` or Cyrillic-only for ru/mixed, Latin-only for en; required for mixed.
- **Duplicates:** ids unique across files. Per language there must be no exact (normalised), near
  (token similarity ≥ 0.85) or template (masked-skeleton similarity ≥ 0.92) duplicates.
- **Coverage:** every lexicon category is used, and the optional per-language minimum counts are met.

### Generator (`gen_probe.py`)

```
tools/with-teacher .cache/ml/roundtrip/venv/bin/python ml/roundtrip/probe/gen_probe.py \
    --n 500 --seed 1 --batch 10 --workers 2 --max-requests 80        # appends to probe/gen.jsonl
python3 ml/roundtrip/probe/gen_probe.py --n 10 --dry-run              # print the first request; no teacher needed
```

1. **Plan.** `plan.Planner` turns each request into a batch of *slots* (lang, domain, target word range,
   1–3 required terms with the written forms allowed in that language). Within the generated file the
   language is kept at its target share (default ru 0.30 / en 0.25 / mixed 0.45, `--langs`), the domain is
   the least-used one for that language, and the category is the one furthest below its share of the
   lexicon. Within the category, the least-used entry across seed *and* generated wins, so the seed's
   uncovered terms come first. Extra terms come from the same category 60% of the time, for coherence.
   Mixed slots always carry a Latin term, and slots of 12 words or fewer get at most 2 terms. All randomness is
   `random.Random(seed, batch)`, so the same seed and accepted state give the same plan.
2. **Ask.** `gen_prompt.md` holds the system, user and repair prompt blocks; the script sends only the
   marked blocks. The teacher must answer with strict JSON
   `{"sentences":[{"slot","text","tts_text","names"}]}`. A reply that is not usable JSON gets one repair turn
   (previous answer plus the reason). `teacher.py` speaks the Responses API (`MURMUR_TEACHER_API=responses`,
   the request shape in PLAN_V2 § Teacher) or chat completions (the default when the variable is unset). It
   retries 408/409/425/429/5xx, timeouts and empty answers with exponential backoff, honouring `Retry-After`
   (capped at 120 s). Other HTTP errors fail at once. The key is redacted from every message and never
   logged or stored. At most 2 concurrent requests (`--workers`), plus a request budget per output file
   (`--max-requests`, retries included).
3. **Accept.** Each sentence is rebuilt as a probe record: `terms` from the lexicon matcher plus the
   teacher's `names` that really occur. Every required term must be present. `tts_text` comes from the
   lexicon respelling; the teacher's `tts_text` is used only when the sentence has Latin tokens the
   lexicon cannot respell. The record then goes through the validator's checks and the de-duplicator
   against the seed, earlier output and the same batch. Accepted records are appended and fsynced;
   rejected ones go to `<out>.rejects` (JSON lines with reasons).
4. **Resume.** `--n` is the total wanted in `--out`. A re-run continues from the accepted records, the batch
   counter in `<out>.state.json` and the ids, and truncates a half-written last line. The seed is fixed per
   output file. Without the teacher variables the script exits 2 with a message naming them.

**Verified run (2026-11-18).** `--n 120 --batch 10 --workers 2 --max-requests 30 --seed 1` against
`gpt-6.2-sol` through `tools/with-teacher`: 12 requests, about 25 s per batch, 120 accepted, 0 rejected.
The output passes the validator alone and together with the seed set. Spot checks: grammar is natural and
Latin terms stay uninflected. Some term combinations read as slightly contrived ("проверка на overfitting
… на узлах с InfiniBand"), which is acceptable for an ASR probe. This work's total teacher use is 16 requests:
2 shape checks, 2 for a scratch run, 12 for the run above.

### Tests

`.cache/ml/roundtrip/venv/bin/python -m pytest -q ml/roundtrip/tests/test_probe_*.py`: 66 tests, about 15 s.
`test_probe_gen.py` runs `gen_probe.main` against a local `http.server` fake that implements the Responses
and chat-completions shapes. It covers:
- the request body and auth header;
- fenced-JSON parsing;
- 429/503 retry with `Retry-After`, exponential backoff and giving up, and a non-retryable 401;
- the repair turn;
- rejection of sentences that lack required terms or duplicate existing ones;
- resume after a stale state file and a half-written line;
- the seed lock, the missing-environment message, `--dry-run`, and that the API key appears in no output.

Mutation checks confirmed the tests fail when Retry-After handling, dedup or the required-term check is
removed. The whole `ml/roundtrip/tests/` suite (176 tests including other sections') passed.

### UNVERIFIED

- The Russian and English **readings** (`ru_spoken`, `en_spoken`) are the authors' judgement of how
  engineers say these terms. No recordings or ASR runs back them.
- **Product and model names** in the lexicon were not checked one by one against Hugging Face or vendor
  sites. The ones the app ships (Parakeet TDT 0.6B v3, Whisper large-v3-turbo, GigaAM, T-one, Qwen3-ASR,
  Qwen3.5, LFM3-1.7B) match `docs/research/asr-catalog.json` and PLAN_V2. Names such as GLM-4.5, Kimi K2,
  Gemini 2.5 Pro and Snapdragon 8 Elite are written from memory; a wrong version string in one of them only
  makes that probe term less realistic, not invalid.
- Whether a given TTS voice actually pronounces each `tts_text` respelling plausibly is for the Round trip
  section to measure.
<!-- END SECTION: Probe set -->

<!-- BEGIN SECTION: Round trip (owner: round-trip agent) -->
## Round trip

_Not written yet: owned by the round-trip part of this workflow._
<!-- END SECTION: Round trip -->

<!-- BEGIN SECTION: Mining (owner: mining agent) -->
## Mining

Mining turns round-trip rows (interface 2) into per-model noise tables (interface 3). The noise sampler
replays those tables over clean text, and PLAN_V2 stage C calls it to build the cleanup LLM's synthetic
inputs. All of it is standard-library Python except the dataset adapter, which needs `pyarrow`. No GPU,
no model and no teacher is involved.

### Files

| path | what |
|---|---|
| `ml/roundtrip/mine/norm.py` | tokeniser, style folding (case, punctuation, ё/е, apostrophes), number words ↔ digits for ru and en (cardinals, ordinals, times, years, %, scale words) |
| `ml/roundtrip/mine/translit.py` | Latin → Cyrillic reader (English-style, acronym letter names: `API` → `эй пи ай` / `апи`), Cyrillic → Latin, a script-independent phonetic key, char similarity |
| `ml/roundtrip/mine/align.py` | style-aware word/phrase alignment; every pair gets a kind: style (`match case yo punct number translit`) or error (`sub_near sub del ins`) |
| `ml/roundtrip/mine/mine_errors.py` | rows → `profiles.json`, `confusions.jsonl`, `term_manglings.jsonl`, `REPORT.md` (CLI) |
| `ml/roundtrip/mine/noise.py` | mined tables → simulated raw hypothesis; `sample`, `simulate` and `calibrate` subcommands |
| `ml/roundtrip/mine/adapters.py` | public data that already holds real ASR hypotheses → interface-2 rows (`enrich`) |
| `ml/roundtrip/mine/regen_outputs.sh` | regenerates every output below with the current code; skips inputs that are absent |
| `ml/roundtrip/samples/mined/fixture_roundtrip.jsonl` | hand-made fixture: 140 rows, 26 probes × 6 models (ru 54, en 32, mixed 54). **Not real ASR output** |
| `ml/roundtrip/samples/mined/fixture/` | mined fixture, plus in-sample and held-out calibration |
| `ml/roundtrip/samples/mined/real/` | mined **real** round trip: the full tables (usable as `--mined`), the report and both calibrations |
| `ml/roundtrip/samples/mined/enrich/` | mined real Russian speech (the enrich adapter): report, profiles, top 150 confusions per model, calibrations |
| `ml/roundtrip/tests/test_mine_*.py` | 91 tests, about 15 s |

### Alignment

A single weighted edit-distance DP over word tokens. Besides 1:1 pairs it allows phrase blocks: one
reference word onto up to 6 hypothesis words (`ClickHouse` → `клик хаус`, `CI/CD` → `си ай си ди`,
`2025` → `две тысячи двадцать пятый`), or up to 4 reference words onto one (`Hugging Face` → `хаггингфейс`).
A block must reach a similarity of at least 0.6 to exist. Each pair is classified:

- **Style:** not a recognition error, but the cleanup LLM still has to undo it.
  - `case`, `yo` (ё/е and apostrophes), `punct` (separators or word splitting: `e-mail`/`email`,
    `ClickHouse`/`click house`).
  - `number`: the same value as digits or words, in either direction. Formatting such as `10:30`/`1030` also
    counts.
  - `translit`: the same term in the other script, phonetically faithful. It needs a phonetic-key
    similarity ≥ 0.8 and a spelling similarity ≥ 0.65 to the expected transliteration (`Kubernetes`/`кубернетис`).
- **Errors:**
  - `sub_near`: close but wrong (`Kafka`/`кофка`, `LoRA`/`low RA`).
  - `sub`, `del`, `ins`: everything else.

A cross-script block that also swallows a Russian neighbour (`в Slack` → `вслаг`, `по Kubernetes` →
`покубернитесь`) is always an error, never `translit`. This case was seen in the real T-one and GigaAM output.

Two WERs are reported:
- conventional normalised **WER**: case, punctuation and ё are folded, nothing else is forgiven;
- **style-aware WER**: counts only error kinds.

`describe(align(ref, hyp))` prints an alignment for inspection.

### Mined artefacts (interface 3)

- **`profiles.json`**, per model:
  - WER, style-aware WER and CER, overall and by language and channel; term WER against non-term WER;
  - casing (all-lowercase rate, first-letter rate, names kept capitalised), punctuation (any, terminal,
    comma ratio);
  - digits (digits kept, spelled out, spoken numbers written as digits), Latin kept against transliterated,
    ё kept;
  - hallucination (trailing and leading insertion runs of at least `--tail-min` words, empty output,
    over-long output), with the observed tails;
  - a `sampler` block: per-word sub/del/ins rates over non-term words, the near-miss share, the insertion
    vocabulary and the confusable words.

  Rows whose reference is unstyled (`"style_comparable": false`) or already normalised
  (`"hyp_normalised": true`) are kept out of the style statistics.
- **`confusions.jsonl`**: `{"asr_model","lang","ref","hyp","count","kind","in_term"}`, word and phrase level.
- **`term_manglings.jsonl`**: `{"term","category","lang_context","asr_model","variants":[{"hyp","count"}],"n",
  "exact_rate","folded_rate","translit_rate","error_rate"}`.
- **`REPORT.md`** has, per model:
  - WER, style and term tables;
  - the "what the cleanup LLM must learn to undo" list, derived from the profile thresholds;
  - top term manglings and top non-term errors.

The miner is deterministic: the same rows give byte-identical outputs, and `--jobs 1` and `--jobs 12` match
(checked on the real set, all four files compared with `cmp`).

### Noise sampler

```
python -m ml.roundtrip.mine.noise sample --mined ml/roundtrip/samples/mined/real --asr-model t-one@onnx \
    --text "Созвон с Laura по Kubernetes в 10:30, потом задеплоим Airflow в прод." --terms Laura Kubernetes Airflow --events
python -m ml.roundtrip.mine.noise simulate --mined DIR --in texts.jsonl --out sim.jsonl --all-models --repeats 3
python -m ml.roundtrip.mine.noise calibrate --rows rows.jsonl --mined DIR --out DIR/calibration          # in-sample
python -m ml.roundtrip.mine.noise calibrate --rows rows.jsonl --holdout-frac 0.3 --out DIR/calibration_holdout
```

In code: `NoiseModel.load(dir).simulate(text, asr_model, terms=None, lang=None, seed=0, severity=1.0)` returns
`Simulation(hypothesis, events)`. The sampler works in four steps.

1. **Terms.** A term the tables have seen gets one of that model's observed variants, sampled by count; errors
   are drawn at the mined term-error rate × severity. Variants are borrowed only from the same spoken
   language: English speech never borrows a Russian-context `лора`. An unseen term follows the model's
   habits:
   - Latin is kept at the observed rate (case or CamelCase split at the observed rate); otherwise it gets a
     phonetic transliteration (`ClickHouse` → `клик хаус`).
   - At the model's term-error rate it is garbled until the miner would itself count it as an error.
   - Unannotated Latin words in Russian text are treated as terms.
2. **Numbers.** Digits are spelled out at the mined rate; spoken-number runs are written as digits at the
   mined rate. For a model with no digit references, the rule depends on what was seen:
   - it ever wrote a digit: it keeps digits;
   - it heard at least 3 spoken numbers and never wrote one as digits: it spells numbers out (T-one);
   - otherwise: a coin toss.
3. **Words.** Substitutions, deletions and insertions are drawn at the mined per-word rates; words with
   observed confusions use their own rates, smoothed toward the base rate. A substitution is an observed
   confusion first, else a near miss (vowel reduction, devoicing, ending change) or an unrelated word from the
   model's own output vocabulary, split by the mined near-miss share.
4. **Style and failures.**
   - Casing, punctuation presence, commas, terminal marks and ё loss follow the mined rates.
   - Empty output and hallucinated tails follow the mined rates.

`severity` scales only the error part: 0 means style only, 1 is as mined, 2 is roughly twice the errors.
Style is never scaled. The same (text, model, seed, severity) always gives the same output.

Calibration simulates the real rows' own texts, mines the simulated rows with the same miner and compares
14 statistics per model, each with an absolute tolerance:

- WER, style-aware WER and CER;
- term exact and term error rates;
- all-lowercase and first-upper rates;
- any-punctuation and terminal-kept rates;
- digits kept and words-to-digits rates;
- Latin kept;
- tail and empty rates.

A metric whose real value rests on fewer than 10 observations is shown in brackets and not judged. The
out-of-sample mode mines on 70% of the probe ids and compares on the other 30%. It also prints the
"sampler gap" (simulated against the rows the tables were mined from), which separates sampler fidelity from
differences between the two text sets.

### Data mined (verified 2026-11-18)

| set | rows | source | in-sample flagged | held-out flagged |
|---|---:|---|---:|---:|
| fixture | 140 (6 models) | hand-made, from catalog behaviours | 0 / 78 | 14 / 60 (26 probes only) |
| **real round trip** | 837 (11 models, 45 probes: 15 ru / 15 en / 15 mixed; piper, supertonic3, kokoro1; clean channel) | snapshot of the runner's `local_proof.jsonl`. The runner job stopped at 837/960 rows; rows were copied as-is | 1 / 134 | 21 / 126 |
| enrich (real speech) | 5000 (2500 utterances × 2 models) | adapter over `MishZor256/russian-asr-enrichment`: 1000 cv21_ru + 1500 rulibrispeech, human `ref` | 0 / 20 | 0 / 18 |

Real round trip, per model (from `samples/mined/real/REPORT.md`):

| ASR model | rows | WER | style-aware WER | term WER | non-term WER | term exact | style |
|---|---:|---:|---:|---:|---:|---:|---|
| parakeet-ultra-0.6b | 105 | 15.3% | 12.1% | 40.8% | 6.0% | 48.9% | cased, punctuated, digits, Latin |
| parakeet-tdt-0.6b-v3 | 105 | 17.3% | 13.5% | 48.1% | 6.3% | 46.6% | cased, punctuated, digits, Latin |
| whisper-large-v3-turbo | 72 | 14.3% | 11.5% | 33.1% | 6.2% | 56.8% | cased, punctuated, digits, Latin |
| distil-large-v3.5 (en only) | 45 | 8.3% | 5.6% | 20.4% | 3.1% | 62.1% | cased, punctuated, Latin |
| whisper-small | 96 | 21.0% | 16.6% | 45.4% | 10.4% | 40.7% | cased, punctuated, digits, Latin |
| whisper-medium | 24 | 18.4% | 15.7% | 46.5% | 7.7% | 51.6% | cased, punctuated, digits, Latin |
| nemotron-3.5-asr-streaming-0.6b (560 ms) | 105 | 24.4% | 18.9% | 58.3% | 10.6% | 27.4% | cased; terminal mark only 15%; numbers half spelled |
| qwen3-asr-0.6b | 105 | 25.1% | 19.7% | 61.7% | 10.8% | 31.4% | cased, punctuated, numbers as words |
| gigaam-v3-e2e-rnnt (ru/mixed only) | 60 | 21.6% | 17.6% | 65.5% | 5.6% | 27.9% | cased, punctuated, digits, **Latin → Cyrillic 80%** |
| gigaam-v3-e2e-ctc (ru/mixed only) | 60 | 23.0% | 19.0% | 73.2% | 5.5% | 22.1% | same, terminal mark only 33% |
| t-one (ru/mixed only) | 60 | 33.4% | 25.5% | 75.3% | 13.0% | 21.3% | **lowercase Cyrillic, no punctuation, no digits** |

The main finding for the cleanup LLM: the hard terms are where the errors are. Term WER is 4 to 13 times
non-term WER for every model, and the mixed ru+en sentences have about 1.5 to 2.5 times the WER of
ru or en alone.
Typical manglings:
- GigaAM: `ClickHouse` → `клипхаус`, `Greenplum` → `Гримплум`, `H100` → `хитсад`;
- Whisper: Latin mostly kept, but `vLLM` → `VLM`, `Sentry` → `центре`, and in Russian context
  `Elasticsearch` → `Эластича` (distil-large-v3.5: `Sentry` → `Century`);
- English models: CamelCase products split (`OpenRouter` → `open router`, `Elasticsearch` → `elastic search`).

**Calibration gaps.**
- *In-sample* (real): 1 of 134 judged metrics is outside tolerance, GigaAM-CTC's terminal-mark rate
  (0.33 real, 0.48 simulated).
- *Out-of-sample* (real, 3 split seeds, 33 model×metric pairs each): the mean gap is −0.021 for style-aware
  WER, +0.051 for term exact rate, −0.013 for term error rate and −0.005 for any-punctuation rate. The sign
  of single gaps flips between split seeds. With only 45 probes the held-out part holds about 14 sentences,
  so a single split is mostly split variance. The consistent part of the gap: on new text the sampler
  slightly under-states word errors (about 2 points of WER) and over-states how often an unseen term comes
  out exactly (about 5 points).

  Stage C should either use severity ≈ 1.15 or re-mine once the full probe run exists. The enrich set
  (2500 utterances) calibrates within tolerance out of sample: 0 of 18. Its WER gap is −0.007 for GigaAM
  and −0.022 for Nemotron, the same direction.

### Adapter: real Russian speech with real hypotheses

`python -m ml.roundtrip.mine.adapters enrich --out .cache/ml/roundtrip/mined/enrich_rows.jsonl` reads
`.cache/ml/data/enrich/train.parquet`, which ml/data downloaded. Every row there carries `gigaam_text` and
`nemotron_text` next to a human `ref`. Counts checked on the parquet: 155,713 rows. Hypotheses are present
in cv21_ru (1000/1000), rulibrispeech (53,691/54,472) and sova (79,382/80,117), and absent in fleurs,
golos_crowd and golos_farfield (0 of 2562 / 7992 / 9570).

The default reference is the human `ref`, not `clean_text`: tier-A `clean_text` copies the Nemotron
hypothesis and would hide its errors. The default domains are the written, punctuated ones (cv21_ru,
rulibrispeech). The rows use dataset-scoped ids (`gigaam-v2@enrich-dataset`,
`nemotron-3.5-transcribe@enrich-dataset`), not catalog ids, because the card's "GigaAM v2" output is
punctuated and so does not match a catalog GigaAM build with certainty.

### Tests

`.cache/ml/roundtrip/venv/bin/python -m pytest -q ml/roundtrip/tests/test_mine_*.py`: 91 passed. They cover:

- **Alignment:** style-only differences; multi-word manglings merged into one translit block; numbers both
  ways, with scale words, and a wrong number; case, ё and separators; a garbled split term and a term
  merged with a Russian neighbour (both errors, not translit); near against unrelated substitution;
  empty hypothesis or reference; hallucinated tail; term-span mapping; determinism.
- **Mining:** fixture profiles (T-one lowercase, no punctuation, no digits; Whisper cased); the WER split;
  the interface fields; null hypotheses skipped; byte-identical CLI output across runs.
- **Noise:**
  - same seed, same output; T-one style replayed; observed manglings used; severity 0 gives no errors and
    higher severity gives more;
  - mining 4 simulated repeats of the fixture reproduces every judged profile metric within tolerance and
    the per-word sub/del/ins rates within 0.02;
  - garbled terms are counted as errors by the miner; the unseen-term style follows the model;
  - words → digits is mined and replayed; the digit-spelling prior;
  - low-support metrics are not judged; no cross-language borrowing.
- **Adapter:** the interface fields, deterministic sampling, unstyled references excluded from the style
  statistics, bad arguments.

### UNVERIFIED and limits

- The fixture hypotheses are hand-written from documented behaviour (`docs/research/asr-catalog.json`), not
  recogniser output. Use `samples/mined/real/` for anything quantitative.
- The real set is a 45-probe, clean-channel, TTS-only snapshot (837 of the 960 planned rows; whisper-medium
  has only 24 rows). Channel-simulated presets and the full 712-probe run are not mined yet: rerun
  `ROUNDTRIP_ROWS=<runner output> ml/roundtrip/mine/regen_outputs.sh` when they exist.
- The Latin → Cyrillic reader and the number grammar cover what the probe set and the fixture exercise. Rare
  forms, such as fractions spoken in words or Russian ordinals in every case, may come out as a `sub_near`
  where a human would call them style.
- The enrich recognisers' exact builds are not known (see the adapter section above), so those profiles are
  not attached to any catalog id.
<!-- END SECTION: Mining -->
