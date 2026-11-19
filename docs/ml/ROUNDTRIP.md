# Round trip: probe set → TTS → ASR → mined errors

PLAN_V2 stages A and B. Each section is owned by one part of the work; edit only your own section.

- **Probe set** — sentences and the hard-term lexicon (`ml/roundtrip/probe/`)
- **Round trip** — TTS voices × ASR models over the probe set (`ml/roundtrip/tts/`, `ml/roundtrip/asr/`)
- **Mining** — error profiles, confusions and term manglings (`ml/roundtrip/mine/`)

## Status after the review of 2026-11-19

An adversarial review ran the tooling end to end and reported 8 major and 12 minor findings. All were
checked and fixed here; the final CPU run, the miner and the calibrations were then re-run, and every
number in this file comes from those runs unless it is marked as earlier or UNVERIFIED.

| finding | status |
|---|---|
| noise sampler simulated languages a model never sees | fixed: language gate (Mining, Noise sampler step 0) |
| term errors vanished on new text (raw per-term rate) | fixed: Bayesian estimate with the model's term error rate as prior; cross-set English term error for whisper-turbo 0.206 real vs 0.110 simulated (was 0.284 vs 0.020); the rest is set difference |
| word error rates pooled over languages | fixed: per-language sampler rates; in-sample, no per-language metric is flagged (Mining, Calibration) |
| term variants swallowed neighbouring words | fixed: phrase-level manglings stored and replayed as whole phrases (102 of 2898 hits) |
| transliteration style-vs-error test unreliable | fixed: lexicon-based `faithful_translit`, 146-pair gold test |
| profiles depend on the TTS | measured and reported per TTS (`by_tts`, REPORT table, `--tts` filter); the stage-C mix is a recommendation, not data |
| 45 probe texts spelled numbers against prompt rule 4 | fixed in 54 texts (47 seed, 7 generated), validator check added, words kept in `tts_text` |
| PLAN_V2 gaps | probe set 1,511 sentences; English Nemotron mined; hub-id → profile map with proxy flags; phone_noisy channel run; 10 CC-BY FLEURS reference voices; pod-sizing plan below. GPU TTS and NPU builds still need the pod and the device |
| minor: exit status, orphaned workers, shard tail, English `tts_text` unused, units as errors, sampler realism, stale samples, backticks/apostrophes, uninflected names, teacher default, whisper-small pin, REPORT ranking | all fixed (sections below); sampler realism partly: letter-name spellings of unseen acronyms now use lexicon readings, but no explicit model of spelled-out letters |

Also found and fixed during the fix pass: Supertonic drops "80%" and "11:30" in Russian (numbers are now
handed to it as words); huggingface_hub wrote Xet logs to `~/.cache/huggingface` (now kept in our cache).

### What still needs the GPU pod

- **GPU TTS** (Qwen3-TTS 1.7B Base, Chatterbox multilingual): first real load, CUDA/flash-attn, speed,
  batch memory, output quality and the Russian accent of voices cloned from the FLEURS clips. Code and
  reference voices are ready (`--ref-dir .cache/ml/roundtrip/refvoices`).
- **Pod-sizing step first**: `run_roundtrip --tts qwen3tts-1.7b,chatterbox-mtl --skip-asr --limit 100
  --workers 1 --tts-batch 8 --tts-shard 32` = 200 utterances, about 1,070 s of audio; its report gives
  each system's x-realtime, which replaces the assumptions below.
- **Estimate for 1,500 sentences** (1 cloned voice per sentence per system = 3,000 clips; the final run
  measured 5.35 s of audio per clip, so about 8,000 s of audio per system). Neither model card publishes
  throughput (Qwen3-TTS states only a 97 ms first-packet streaming latency), so the speeds are assumptions:
  Qwen3-TTS batched by 8 on one RTX 5090 at 5-15x realtime → 9-27 min; Chatterbox, no batch API, at
  1.5-4x → 34-90 min; plus 15-20 min for two torch venvs and about 8 GB of weights, and 5-10 min for
  the sizing step. **Total 65-145 pod minutes** (UNVERIFIED). To stay near PLAN_V2's ~15-minute probe
  slot: Qwen3-TTS on all 1,500 and Chatterbox on 300 → about 35-65 min including setup.
- **ASR stays on CPU** (the app's CPU graphs; no GPU minutes): at the final run's measured 2.35 s of
  wall time per second of TTS audio through all 12 models, 1,500 sentences take about 10.5 h on this
  server per 2 voices per sentence (overnight; whisper-medium is a third of it), or less on the pod's own
  CPU in parallel with synthesis.

### What needs the device (Fold7)

- Fit the `phone` / `phone_noisy` presets to recordings from the Fold7 microphone (`channel.py stats`).
- Run the NPU builds the app ships (QNN w8a16, LiteRT) on the same audio, dump hypotheses and diff them
  against the CPU profiles; `hub_map.json` marks those profiles `proxy: true` until then.

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
| `ml/roundtrip/probe/gen.jsonl` | 919 teacher-generated sentences (ru 276, en 230, mixed 413), `--seed 1`: 120 from the first run (14 of them repaired by hand on 2026-11-19, see below) and 799 from the second |
| `ml/roundtrip/probe/gen.jsonl.state.json`, `gen.jsonl.rejects` | resume state of that file (batch counter, request count) and the rejected sentences with reasons |
| `ml/roundtrip/probe/src/terms.txt`, `src/seed.txt` | the editable sources; never hand-edit the JSONL |
| `ml/roundtrip/probe/build_probe.py` | sources → `terms.jsonl` + `seed.jsonl`; fails on any invalid record or duplicate |
| `ml/roundtrip/probe/validate_probe.py` | validator + coverage report (the tables below) |
| `ml/roundtrip/probe/gen_probe.py`, `gen_prompt.md` | teacher-driven scaling; the full prompt text is in `gen_prompt.md` |
| library modules | `lexicon.py` (schema, Russian-inflection-aware matcher), `schema.py` (record checks), `numstyle.py` (digit rule, markup), `spoken.py` (numbers as spoken words), `dedup.py`, `respell.py` (`tts_text`), `plan.py` (slot planning), `prompt.py`, `teacher.py`, `textutil.py` |
| `ml/roundtrip/tests/test_probe_*.py` | 68 tests (validator, dedup, respell, planning, generator with a fake teacher) |

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

Hand-written in `src/seed.txt` under `@<lang> <domain>` headers, with `[term]` for lexicon terms,
`{Name}` or `{Latin=Кириллица}` for people and `<20=двадцать>` for a number written in digits and spoken as
words (the words go to `tts_text`, declined as said: `до <11:00=одиннадцати>`). Lexicon terms the matcher finds outside brackets are tagged
too, so the `terms` lists are complete. They cover messenger replies, work-chat updates, e-mails, notes,
stand-ups, commit and PR text, prompts to an AI assistant, terminal-style requests, calendar requests and
docs. The sentences use names, versions, numbers, dates and times, and decline Cyrillic borrowings
correctly ("на стейджинге", "в дообучении"). Latin terms stay uninflected ("в Kubernetes", "через LoRA").
People's names inside Russian sentences are written in declined Cyrillic ("с Марком", "с Лорой"), as a
careful writer would; six mixed sentences had them in uninflected Latin ("с Mark") until 2026-11-19, which
made a correct "Марком" count as an error.

**Numbers follow the app's prompt.** `text` is the stage-C target, so it obeys rule 4 of
`prompts/system/default.v5.md`: digits for times, dates, money, percentages, measurements, versions and
numbers above ten. 47 seed and 7 generated texts spelled such numbers out ("sixty gigabytes", "через
двадцать минут", "восьмидесяти процентов", "в одиннадцать"); they now read "60 gigabytes", "через 20 минут",
"80%", "в 11:00", with the words kept in `tts_text`. Small counts stay words ("два бага", "three times"). The
validator enforces this (below). One seed sentence with broken coordination was rewritten
(seed-ru-0165: "…про ретро и про то, что надо подготовить слайды.").

- `ru` contains no Latin letters at all, so its terms come from the Cyrillic categories (`dev_slang_ru`,
  `ai_ml_jargon_ru`, Cyrillic `cloud_services`). Latin product names said inside Russian speech are the
  `mixed` set: a Russian sentence with the terms kept in Latin, Cyrillic words ≥ Latin words.
- `tts_text` is required for `mixed`: the sentence with every Latin term replaced by a Russian reading
  and every number in words, for Russian voices that cannot read Latin. For `en` it is set where the
  English reading differs from the spelling or the text has numbers in digits (49 seed sentences); for `ru`
  only when the text has digits (22 seed sentences). The round trip uses it for every `en` and `ru` voice
  and for the respelling voices on `mixed` (see Round trip, TTS drivers).

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
- tts_text set: ru 22, en 49, mixed 227

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

**Seed + generated** (`validate_probe.py seed.jsonl gen.jsonl --min-lang ru=170,en=100,mixed=150` → exit 0,
**1511 valid**, inside PLAN_V2's 1–2K): ru 465, en 406, mixed 640; lexicon entries used **725 / 725 (100%)**,
every category at 100%; 2879 term occurrences, 103 person-name mentions; terms per sentence 1: 433,
2: 806, 3: 254, 4: 18; tts_text set ru 42, en 123, mixed 640. The generator fills the terms the seed never
used first.

| category | used / lexicon | occurrences | sentences | ru | en | mixed |
|---|---:|---:|---:|---:|---:|---:|
| devops_infra | 47 / 47 | 147 | 106 | 0 | 40 | 66 |
| databases_data | 41 / 41 | 129 | 96 | 0 | 35 | 61 |
| languages_frameworks | 55 / 55 | 166 | 115 | 0 | 45 | 70 |
| ai_tools | 52 / 52 | 171 | 128 | 0 | 51 | 77 |
| ai_ml_jargon | 82 / 82 | 277 | 185 | 0 | 80 | 105 |
| ai_ml_jargon_ru | 41 / 41 | 264 | 190 | 167 | 0 | 23 |
| model_names | 54 / 54 | 164 | 112 | 0 | 43 | 69 |
| dev_slang_ru | 81 / 81 | 607 | 391 | 327 | 0 | 64 |
| cli_commands | 43 / 43 | 142 | 103 | 0 | 45 | 58 |
| files_config | 33 / 33 | 105 | 80 | 0 | 27 | 53 |
| acronyms | 72 / 72 | 265 | 192 | 0 | 77 | 115 |
| cloud_services | 61 / 61 | 226 | 178 | 67 | 43 | 68 |
| hardware | 30 / 30 | 101 | 87 | 0 | 34 | 53 |
| mobile_android | 33 / 33 | 115 | 75 | 0 | 32 | 43 |

### Validator (`validate_probe.py`)

`python3 ml/roundtrip/probe/validate_probe.py [files...] [--min-lang ru=170,en=100,mixed=150] [--format md|json|none]`.
Exit 0 when valid, 1 on any violation (listed on stderr), 2 when the lexicon or inputs cannot be read.
Checks:
- **Lexicon:** the schema and the cross-entry invariants above.
- **Schema:** the exact key set, id format, `lang` and `domain` enums, types.
- **Text:** NFC, no stray whitespace, no Markdown backticks and no typographic apostrophes (`’`), 6–35 words,
  ends with sentence punctuation.
- **Numbers** (`numstyle.py`, default.v5 rule 4): no run of number words worth more than ten ("двадцать
  минут"; a bare scale word such as "на миллион сообщений" is allowed), no number word before a percent word
  and none before a unit of data size, frequency, bits or milliseconds ("сорок мегабайт"). Clock times said
  as small numbers ("at nine") cannot be told from counts and are left to review.
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
   (previous answer plus the reason). `teacher.py` speaks the Responses API (the default, as the only
   teacher speaks nothing else; request shape in PLAN_V2 § Teacher) or chat completions
   (`MURMUR_TEACHER_API=chat`). It
   retries 408/409/425/429/5xx, timeouts and empty answers with exponential backoff, honouring `Retry-After`
   (capped at 120 s). Other HTTP errors fail at once. The key is redacted from every message and never
   logged or stored. At most 2 concurrent requests (`--workers`), plus a request budget per output file
   (`--max-requests`, retries included).
3. **Accept.** Each sentence is rebuilt as a probe record: `terms` from the lexicon matcher plus the
   teacher's `names` that really occur. Every required term must be present. Typographic apostrophes are
   normalised to `'`. `tts_text` comes from the lexicon respelling; the teacher's `tts_text` is used when the
   sentence has Latin tokens the lexicon cannot respell, and, when the text has digits, for the spoken
   numbers only (`spoken.transfer_spoken_numbers` moves them into the lexicon respelling, declined as the
   teacher wrote them). Prompt v2 (`gen_prompt.md`) asks for digits per rule 4, declined Cyrillic names in
   Russian sentences, plain text and numbers in words in `tts_text`. The record then goes through the validator's checks and the de-duplicator
   against the seed, earlier output and the same batch. Accepted records are appended and fsynced;
   rejected ones go to `<out>.rejects` (JSON lines with reasons).
4. **Resume.** `--n` is the total wanted in `--out`. A re-run continues from the accepted records, the batch
   counter in `<out>.state.json` and the ids, and truncates a half-written last line. The seed is fixed per
   output file. Without the teacher variables the script exits 2 with a message naming them.

**Verified runs.** Second run (2026-11-19, prompt v2): `--n 910 --seed 1 --batch 10 --workers 2
--max-requests 125` continued the same file: 80 requests, 18-30 s per batch, 799 accepted, 1 rejected
(a number above ten in words, caught by the new check); the log shows no repair turns or retries. 144 of the 799 have digits. A read of
22 random new sentences found natural dictation with correct declension; the calendar domain repeats one
frame often ("Поставь на четверг в 14:30 встречу…"), which the de-duplicator lets through because the rest
differs. Before that run, 14 sentences of the first run were repaired by hand (scratch script, not kept):
backticks removed in 4 texts and 2 `tts_text`s, 3 curly apostrophes straightened, 7 spelled numbers turned to
digits with the words moved to `tts_text` (gen-mixed-b00001-s04, gen-en-b00001-s07, gen-en-b00005-s00,
gen-mixed-b00009-s02, gen-mixed-b00006-s07 "в половину третьего" → "в 14:30", gen-ru-b00007-s05,
gen-ru-b00002-s08).

First run (2026-11-18). `--n 120 --batch 10 --workers 2 --max-requests 30 --seed 1` against
`gpt-6.2-sol` through `tools/with-teacher`: 12 requests, about 25 s per batch, 120 accepted, 0 rejected.
The output passes the validator alone and together with the seed set. Spot checks: grammar is natural and
Latin terms stay uninflected. Some term combinations read as slightly contrived ("проверка на overfitting
… на узлах с InfiniBand"), which is acceptable for an ASR probe. This work's total teacher use is 16 requests:
2 shape checks, 2 for a scratch run, 12 for the run above; the 2026-11-19 fix pass added 80.

### Tests

`.cache/ml/roundtrip/venv/bin/python -m pytest -q ml/roundtrip/tests/test_probe_*.py`: 68 tests, about 10 s.
`test_probe_gen.py` runs `gen_probe.main` against a local `http.server` fake that implements the Responses
and chat-completions shapes. It covers:
- the request body and auth header;
- fenced-JSON parsing;
- 429/503 retry with `Retry-After`, exponential backoff and giving up, and a non-retryable 401;
- the repair turn;
- rejection of sentences that lack required terms or duplicate existing ones;
- resume after a stale state file and a half-written line;
- the seed lock, the missing-environment message, `--dry-run`, and that the API key appears in no output;
- spoken numbers taken from the teacher's hint, apostrophe normalisation, rejection of numbers above ten in
  words, and the Responses default.

Mutation checks confirmed the tests fail when Retry-After handling, dedup or the required-term check is
removed.

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

Text -> TTS (several systems and voices) -> optional phone-mic channel -> every ASR model the app
ships, run on the same CPU ONNX graphs the phone uses in CPU mode. Nothing is trained; TTS and ASR
are noise sources for the cleanup LLM's training inputs. Code: `ml/roundtrip/tts/`,
`ml/roundtrip/asr/`, `ml/roundtrip/channel.py`, `ml/roundtrip/run_roundtrip.py`; tests
`ml/roundtrip/tests/test_roundtrip_*.py`.

### How to run

```bash
# shared venv .cache/ml/roundtrip/venv (pins in ml/roundtrip/requirements.txt)
P=.cache/ml/roundtrip/venv/bin/python
$P -m ml.roundtrip.asr list                         # registered ASR models and their status
$P -m ml.roundtrip.tts list --systems all           # TTS systems, voices, licences, Latin policy
tools/heavy $P -m ml.roundtrip.asr verify --models all --ru ru.flac --en en.flac --json report.json
tools/heavy $P -m ml.roundtrip.tts say --systems cpu --lang mixed --text "..." --out /tmp/say

# the round trip (the final CPU run below); resumable: rerun the same command after an interruption
MURMUR_MEM=16G tools/heavy $P -m ml.roundtrip.run_roundtrip \
    --probe ml/roundtrip/review/probe80.jsonl --out .cache/ml/roundtrip/final_out --run final80 \
    --tts piper,supertonic3 --voices-per-sentence 1 --channels clean --asr all \
    --workers 8 --threads 2 --mem-gb 12

# throughput only, one process: audio-seconds per wall-second for every TTS system and ASR model
tools/heavy $P -m ml.roundtrip.run_roundtrip --measure --probe ml/roundtrip/review/probe80.jsonl \
    --tts cpu --asr all --threads 2 --measure-n 5

# reference voices for the GPU cloning systems (10 FLEURS clips, CC-BY-4.0, ~1 MB)
$P -m ml.roundtrip.tts.fetch_refvoices --ru 6 --en 4      # -> .cache/ml/roundtrip/refvoices
```

Main flags: `--tts cpu|gpu|all|<ids>`, `--voices <ids>` (also `system/voice`),
`--voices-per-sentence K`, `--channels clean,phone,...`, `--asr all|<ids or unique substrings>`,
`--limit N` (first N probes, round-robin over ru/en/mixed), `--seed`, `--workers`, `--threads`,
`--tts-shard`, `--asr-shard` (clip cap per ASR task), `--asr-shard-s` (expected seconds per ASR task),
`--mem-gb`, `--skip-asr`, `--measure`, `--measure-n`, `--ref-dir`, `--allow-errors`.

**Exit status.** 0 when every job produced a row and nothing failed; 1 when any model could not
load, any clip failed or any job is still pending (rerun to retry them), unless `--allow-errors`.
`--measure` exits 1 when a system is unavailable. Before 2026-11-19 the runner always exited 0.

**Interrupting.** Workers die with the runner: the pool initializer sets `prctl(PR_SET_PDEATHSIG,
SIGTERM)` and every shard also checks the parent pid between clips. Before this, a `kill -9` of the
runner left up to 9 workers transcribing for minutes (one at 2 GB) and kept the systemd scope alive;
`test_pool_workers_exit_when_the_runner_is_killed` now kills a runner with SIGKILL and requires its
workers to be gone within 10 s (it fails with the prctl line removed). If a scope ever lingers anyway:
`systemctl --user list-units 'run-*.scope'` and `systemctl --user stop <unit>`.

Outputs go to `--out` (default `.cache/ml/roundtrip/out`, gitignored): results
`<out>/roundtrip/<run>.jsonl` (interface 2), a report `<out>/roundtrip/<run>.report.json`
(throughput, errors, pending count, args of the last invocation) and audio
`<out>/audio/<tts_system>/<voice>/<probe_id>.<channel>.flac` (16 kHz, 16-bit FLAC). `audio` in each
row is relative to `--out`. Passing `--out ml/roundtrip/out` gives the layout named in the
interface, but that directory is not gitignored today, so the default stays in `.cache`.

Each row carries one field beyond interface 2: `tts_input`, which says what the TTS read:
`"text"`, `"tts_text"`, or `"text_numbers"` (`text` with its digits replaced by spoken words, see
TTS drivers). The reference for mining is always `text`.

### Matrix, routing, execution

**Matrix.** For each probe and each TTS system the runner samples `--voices-per-sentence` voices
from the voices that speak the probe's language (`random.Random(crc32(seed, probe_id, system))`, so
the same command always picks the same voices). Each (probe, system, voice) is synthesised once
(the `clean` clip, TTS seed shared by all channels); every other `--channels` preset is derived
from that clip with its own seed. Each clip then goes to every selected ASR model that accepts its
language:

| probe lang | ASR models | language hint |
|---|---|---|
| `en` | models that know English (multilingual + distil-large-v3.5 + Nemotron Speech Streaming EN) | `en` |
| `ru` | models that know Russian (multilingual + GigaAM rnnt/ctc + T-one) | `ru` |
| `mixed` | same as `ru` (never the English-only models) | `ru` |

The hint matters for Whisper (forced language) and the multilingual Nemotron (per-stream `language`
option; the English-only Nemotron gets none). Parakeet, GigaAM, T-one and Qwen3-ASR ignore it.

**Execution.** Spawn-context process pools (`--workers`, each with `--threads` ONNX threads;
8 x 2 fills the 16 cores). TTS work goes out in shards of `--tts-shard` sentences (a voice's clean
clip and its channel variants stay in one shard). ASR work (`plan_asr_shards`):

- Each model's clips are cut into shards of about `--asr-shard-s` (default 150) expected wall
  seconds: clip length divided by the model's x-realtime per worker (`asr/registry.py:XRT`, from the
  review run), at most `--asr-shard` (48) clips, one language hint per shard.
- The model with the most total expected work goes first (in practice whisper-medium, the critical
  path), each model's longest shard first.
- A shard is admitted only while the summed peak RSS of the models in flight stays under `--mem-gb`
  (`asr/registry.py:MEM_MB`), and one model may hold at most 2/3 of that budget while other models'
  shards wait, so whisper-medium cannot keep everything else queued.
- ASR workers exit after one shard (`max_tasks_per_child=1`): onnxruntime does not give a freed
  model's arena back to the OS (whisper-medium: 3.5 GB peak, still 2.0 GB after `unload()`).

The fixed 24-clip shards used before let one whisper-medium shard run alone for about 20 minutes
at the end of the review run while 7 workers sat idle. The final clean run below still used the
first version of the new planner (equal-length shards, longest first), and its last ~15 minutes
were again whisper-medium only, 3 at a time; the critical-path ordering and the 2/3 cap were added
after that and used for the channel run (580 rows in 786 s; its last 60 rows took 26 s). Both
are unit-tested (`test_roundtrip_scheduling.py`).

Results are appended and flushed per shard. Resume skips result keys
`(probe_id, tts_system, tts_voice, channel, asr_model)` already in the JSONL and audio files that
exist (a torn last line from a killed run is ignored and re-done). The reviewer verified this on
2026-11-19 with a `kill -9` at 288 of 1492 rows: the rerun found exactly 1204 pending jobs and
finished without duplicates. A model that cannot load is reported once in the report's `errors`
and skipped; a clip that fails is logged and retried on the next run.

### ASR drivers

One interface (`ml/roundtrip/asr/base.py`): `name` = catalog id, `load()`, `unload()`, `loaded`,
`transcribe(wav_path | 16 kHz float32 samples, lang_hint) -> str` (raw recogniser text, untouched;
loads lazily). All go through the `sherpa-onnx` 2.0.1 Python package. Files are resolved from
`docs/research/asr-catalog.json` (repo, path and 40-char revision per file) and downloaded lazily
into `.cache/ml/roundtrip/models/hf` with a file lock (several workers can ask at once).
huggingface_hub's Xet cache and logs are pointed into `.cache/ml/roundtrip/models/xet`
(`asr/fetch.py:keep_hf_state_local`); before that, each download left a log under
`~/.cache/huggingface/xet/logs`.

| id | sherpa-onnx recogniser | languages | notes |
|---|---|---|---|
| parakeet-tdt-0.6b-v3@onnx-int8 | `OfflineRecognizer.from_transducer` (nemo_transducer) | ru, en | |
| parakeet-ultra-0.6b@onnx-int8 | same | ru, en | a CPU entry in the catalog, so registered too |
| nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms | `OnlineRecognizer.from_transducer`, `stream.set_option("language", hint)` | ru, en | 1.2 s of silence appended to flush the last 560 ms chunk |
| nemotron-speech-streaming-en-0.6b@onnx-int8-560ms | `OnlineRecognizer.from_transducer`, no language option | en | **not a catalog id**: CPU export `csukuangfj2/sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25` @ 52056fdc of the checkpoint behind the hub's `nemotron-speech-streaming-en-0.6b@qnn-sm8750-560ms`; NVIDIA Open Model License (HF card). Verified 2026-11-19: loads in 90 s (first download), "Hi Laura attached is the Clickhouse schema proposal. The Kafka topics stay the same" on a Supertonic clip, 4.0x realtime at 4 threads |
| gigaam-v3-e2e-rnnt@onnx-int8 | `OfflineRecognizer.from_transducer` (nemo_transducer) | ru | punctuated e2e export |
| gigaam-v3-e2e-ctc@onnx-int8 | `OfflineRecognizer.from_nemo_ctc` | ru | punctuated e2e export |
| t-one@onnx | `OnlineRecognizer.from_t_one_ctc` | ru | 8 kHz model: we resample 16k -> 8k, pad 0.3 s left (upstream example) and 1.2 s right |
| qwen3-asr-0.6b@onnx-int8 | `OfflineRecognizer.from_qwen3_asr` (max_new_tokens=256) | ru, en | auto language |
| whisper-large-v3-turbo@onnx-int8 | `OfflineRecognizer.from_whisper(language=hint)` | ru, en | one recogniser per forced language, one loaded at a time |
| distil-large-v3.5@onnx-int8 | `from_whisper(language="en")` | en | English-only |
| whisper-small@onnx-int8 | `from_whisper` | ru, en | resolved from its catalog entry (`csukuangfj/sherpa-onnx-whisper-small` @ 8f3c18b3); until 2026-11-19 the registry kept its own copy of that pin |
| whisper-medium@onnx-int8 | `from_whisper` | ru, en | **not a catalog id**: the catalog has only `whisper-medium@qnn-sm8750`; CPU path `csukuangfj/sherpa-onnx-whisper-medium` @ 8c31d285, same OpenAI checkpoint |

Every hub catalog id maps to the profile that stands in for it in `ml/roundtrip/mine/hub_map.json`
(`NoiseModel.profile_for(hub_id)`), with `proxy: true` where the app runs another build than the
one mined here: the six NPU/GPU builds (QNN SM8750 for Nemotron, Nemotron EN, Parakeet, Whisper
small/medium; AI Hub w8a16 for Whisper turbo; LiteRT NPU for Qwen3-ASR 0.6B) map to their CPU int8
counterparts, and **`qwen3-asr-1.7b@litert-gpu-15s` maps to the Qwen3-ASR 0.6B profile** because no
CPU export of the 1.7B model exists for sherpa-onnx (HF search of csukuangfj, csukuangfj2 and k2-fsa on
2026-11-19); the 1.7B model should make fewer errors (published FLEURS ru WER 5.99). Whether the NPU
builds make the same errors as the CPU graphs is a device check (Device-only, below).

The final run used all 12 models with 0 errors. The `UnavailableDriver` path (model stays
registered, `load()` raises with the reason, the runner reports it once and exits 1) is
unit-tested.

### TTS drivers

One interface (`ml/roundtrip/tts/base.py`): system id, `voices()` (no model load), `load()`,
`prepare(voice)`, `unload()`, `synthesize(text, voice, lang, seed) -> 16 kHz mono float32`
(peak-normalised to 0.9), `synthesize_batch(items)` (GPU drivers batch, CPU loops). Each voice
declares `latin_in_ru` (`respell` voices cannot read Latin inside Russian, `native` ones can) and
`reads_digits`, the languages in which the engine's own normaliser reads digits.

**What each voice reads** (`choose_tts_input`):

- `en` and `ru`: `tts_text` whenever the probe has one. It differs from `text` only where a speaker
  says something else than the spelling ("kubectl" -> "kube control", "20 минут" -> "двадцать
  минут"). Until 2026-11-19 English `tts_text` was never used, so espeak read names such as nginx and
  kubectl as spelled and the mispronunciation counted as an ASR error.
- `mixed`: `respell` voices (Piper's Russian voices) read `tts_text`. `native` voices (Supertonic,
  GPU cloning) read `text` with the Latin terms as written; if the voice cannot read digits in
  Russian, the numbers are taken from `tts_text`, declined as said (`probe/spoken.py`).
- Digits left in a language the voice cannot read them in are spelled in the nominative; a number
  right after a Latin name ("RTX 5090", "Llama 4") is left alone.

Why: checked 2026-11-19 by synthesising and transcribing with GigaAM / Parakeet. Supertonic under
`lang=ru` read "через 20 минут" correctly but dropped "80%" and "11:30" ("Кэш занял 40 г, это
тридцать"); in English it read "9:30", "80%", "60 GB" correctly. Piper (espeak-ng) read all of them
in both languages. So `reads_digits` is {ru, en} for Piper's voices, {en} for Supertonic and Kokoro
(espeak phonemes), and empty for the GPU systems (UNVERIFIED, spelled to be safe). In the final run the
TTS read: Piper `tts_text` for all 26 mixed, 2 ru and 6 en clips, `text` otherwise; Supertonic `text`
for 68 clips, `tts_text` for 8 (6 en, 2 ru) and `text_numbers` for 4.

| system | engine | voices used | languages | licence (checked 2026-11-18) | Latin words in a Russian sentence |
|---|---|---|---|---|---|
| `piper` | `piper-tts` 1.8.0 (VITS, espeak-ng phonemiser), voices from `rhasspy/piper-voices` @ 0ca208af | ru: denis, dmitri (medium); en: joe, john, kristin, norman, alba, cori (medium), libritts_r medium speakers 0, 117, 263, 509, 771 | ru or en per voice | per MODEL_CARD: denis, dmitri, joe CC0; john, kristin, norman public domain; alba, cori, libritts_r CC-BY-4.0 | espeak-ng switches to English phonemes the Russian voices never saw and garbles the term and its neighbours, so `respell`: mixed probes use `tts_text` |
| `supertonic3` | sherpa-onnx `OfflineTts` Supertonic, archive `sherpa-onnx-supertonic-3-tts-int8-2026-05-11` (k2-fsa `tts-models` release), 8 flow steps | sid0..sid9 | one multilingual model, `lang` per call | code MIT; **model weights BigScience OpenRAIL-M** (`Supertone/supertonic-3` LICENSE @ 3cadd1ee): commercial use allowed, with the use-based restrictions of Attachment A. Synthesising training audio is not a restricted use | character-level model; under `lang=ru` it reads Latin words with a Russian accent. `native`: reads `text` |
| `kokoro1` | sherpa-onnx `OfflineTts` Kokoro, archive `kokoro-multi-lang-v1_0` (hexgrad/Kokoro-82M) | 28 English voices af_*, am_*, bf_*, bm_* | en only | Apache-2.0 (archive LICENSE) | not used for ru/mixed; text phonemised with espeak-ng because sherpa's lexicon path drops unknown words (`OOV word skipped: 'Kubernetes'`). Not in the final run (measured only) |
| `qwen3tts-1.7b` (GPU) | `qwen-tts` 0.1.1, `Qwen/Qwen3-TTS-12Hz-1.7B-Base` @ fd4b2543 | one voice per reference clip | ru, en | package Apache-2.0 (wheel METADATA), model apache-2.0 (HF API) | `native` |
| `chatterbox-mtl` (GPU) | `chatterbox-tts` 0.1.7, `ResembleAI/chatterbox` @ 5bb1f6ee | one voice per reference clip | ru, en (`language_id`) | package MIT (wheel METADATA), model mit (HF API) | `native` |

Rejected voices (from each MODEL_CARD at the pinned piper-voices revision): `ru_RU-ruslan-medium`
(CC BY-NC-SA 4.0), `ru_RU-irina-medium` (licence "Unknown"), `en_US-ryan-*`,
`en_US-hfc_female/hfc_male-medium` (CC BY-NC-SA 4.0), `en_US-lessac-*` (Blizzard 2013 Lessac
licence, non-commercial terms), `en_US-amy-*`, `en_GB-alan-*`, `en_GB-jenny_dioco-*` (MODEL_CARD
only says "See URL"; not verifiable), `en_GB-northern_english_male-medium` (CC-BY-SA, share-alike,
avoided). That leaves Piper with only two Russian voices, both male; Supertonic adds ten
multilingual voices, and the GPU cloning systems are where Russian speaker variety has to come from.

**GPU systems (code complete, dry-run tested only).** Facts read from the wheels
(`pip download --no-deps qwen-tts==0.1.1 chatterbox-tts==0.1.7`) and the HF API:

- Qwen3-TTS: `Qwen3TTSModel.from_pretrained(local_snapshot, device_map="cuda:0",
  dtype=torch.bfloat16, attn_implementation="flash_attention_2")` (pass `"sdpa"` without
  flash-attn); `create_voice_clone_prompt(ref_audio, ref_text, x_vector_only_mode=False)` is cached
  per voice; `generate_voice_clone(text=[...], language=[...], voice_clone_prompt=[...])` returns
  `(list of arrays, sr)`, one prompt item per text, so one call batches different voices
  (`synthesize_batch`). `qwen-tts` pins transformers==4.57.3: own venv
  (`ml/roundtrip/tts/requirements-gpu-qwen3tts.txt`).
- Chatterbox: `ChatterboxMultilingualTTS.from_pretrained(device)` would download from `main`;
  the driver instead downloads the same six files at the pinned revision and calls
  `from_local(ckpt_dir, device)`; `generate(text, language_id, audio_prompt_path, exaggeration=0.5,
  cfg_weight=0.5)` returns a (1, N) tensor at `model.sr`; for cross-language cloning the driver
  sets `cfg_weight=0` (README advice). Pins transformers==5.2.0: own venv
  (`requirements-gpu-chatterbox.txt`). Outputs carry Resemble's Perth watermark. The README says
  the project is archived.
- Reference voices (`ml/roundtrip/tts/refvoices.py`): a directory (runner `--ref-dir`, which sets env
  `MURMUR_REFVOICE_DIR` for the workers) with clips and a `manifest.jsonl` line per clip
  (`file, text, lang, licence, source, attribution`). Only CC0 / public domain / CC-BY clips of
  2-20 s are accepted. `ml/roundtrip/tts/fetch_refvoices.py` fills one from Google FLEURS (CC-BY-4.0
  per the dataset card, `google/fleurs` @ 70bb2e84): it downloads the dev split's tsv and audio archive
  (ru 187 MB, en 171 MB) into a temporary folder, keeps 4-12 s clips with distinct transcripts and
  alternating speaker gender (deterministic by seed), and deletes the archive. Done 2026-11-19: 6 ru
  (3 female, 3 male) + 4 en clips, 1.2 MB, all 10 accepted by `load_ref_voices`. FLEURS has no speaker
  ids, so distinct speakers are likely but UNVERIFIED. Not used yet: only the GPU systems clone.
- On the pod: one process per GPU (`CUDA_VISIBLE_DEVICES=<i>`), `--workers 1`, batched clean
  clips with `--tts-batch 8 --tts-shard 32` (Qwen3-TTS batches inside one `generate_voice_clone`
  call; Chatterbox has no batch API and loops), `--skip-asr` to only synthesise, then the CPU ASR
  stage on the same `--out` (it reuses every clip that exists). The batched path is unit-tested with
  a fake GPU driver.
- UNVERIFIED until the first pod run: actual model loading, CUDA/flash-attn availability, generation
  speed, batching memory, output quality and Russian accent of cloned voices.

### Channel simulation (`channel.py`)

numpy/scipy only, deterministic per seed. `clean` is the identity. Chain for the other presets:
diffuse reverb -> room noise at a given SNR -> Butterworth band-pass -> slow AGC -> soft clipping
(noise is added before the band limit so it goes through the same microphone path as the voice).
`phone`: RT60 0.25 s / 12% wet, pink noise at 25 dB SNR, 120 Hz-7 kHz, drive 1.6. `phone_noisy`:
RT60 0.4 s / 20% wet, pink noise at 12 dB, 150 Hz-7 kHz, drive 2.0. `far`: RT60 0.6 s / 35% wet,
white noise at 18 dB, 200 Hz-5 kHz, drive 1.3. `python -m ml.roundtrip.channel IN OUT --preset P`
applies a preset to a file; `python -m ml.roundtrip.channel stats FILE... [--preset P]` prints a
channel fingerprint per file (`rolloff99_hz`, `hf_db` = 6-8 kHz energy vs 300-3400 Hz, `lf_db` =
below 150 Hz vs 300-3400 Hz, frame-percentile `snr_db`, `clip_ratio`, `rms_dbfs`) so the presets can
be compared with real Fold7 recordings. On a Supertonic clip: clean rolloff 2281 Hz, hf -21.8 dB,
snr 59 dB; `phone` 6109 Hz, -17.2 dB, 25 dB; `phone_noisy` 6156 Hz, -17.0 dB, 16 dB; `far` 4578 Hz,
-39.1 dB, 16 dB. The presets are engineering guesses until fitted to device recordings.

### Final CPU run (2026-11-19)

**Input.** The reviewer's 80 seed probes (`ml/roundtrip/review/probe80.jsonl`: ru 27, en 27, mixed 26,
none of them in the earlier local proof), rebuilt from the fixed seed set (digits per rule 4, declined
Cyrillic names). TTS: Piper and Supertonic, 1 voice per sentence, `clean` channel. ASR: all 12 CPU
models. 8 workers x 2 threads, `--mem-gb 12`, through `tools/heavy` (16 GB scope).

**Result** (`ml/roundtrip/samples/roundtrip/final80.jsonl` and `final80.report.json`): 160 clips,
855.7 s of audio (Piper 380.4 s, Supertonic 475.3 s), **1546 rows, 0 errors, 0 pending, exit 0**.
Wall time 2076 s (34.6 min): TTS 66 s, ASR about 2010 s; 256 CPU-minutes, so 7.4 of 16 cores busy on
average. The ASR stage was memory-bound at the end (whisper-medium only, 3 at a time). Load average
from other users' jobs was 22 at the start and 13-17 later.

**Channel run** (`final30_phone_noisy.jsonl`): the first 30 of those probes (10 per language) through
the `phone_noisy` preset (RT60 0.4 s, pink noise at 12 dB SNR, 150 Hz-7 kHz), same voices, 60 clips,
580 rows, 0 errors, 786 s wall. Style-aware WER (only real recognition errors, see Mining), clean ->
phone_noisy on the same 30 probes:

| ASR model | style-aware WER clean | phone_noisy | term exact clean | phone_noisy |
|---|---:|---:|---:|---:|
| distil-large-v3.5 | 0.085 | 0.082 | 0.55 | 0.55 |
| gigaam-v3-e2e-ctc | 0.137 | 0.181 | 0.29 | 0.28 |
| gigaam-v3-e2e-rnnt | 0.137 | 0.172 | 0.31 | 0.31 |
| nemotron-3.5-asr-streaming (560 ms) | 0.203 | 0.293 | 0.31 | 0.26 |
| nemotron-speech-streaming-en (560 ms) | 0.133 | 0.153 | 0.53 | 0.35 |
| parakeet-tdt-0.6b-v3 | 0.143 | 0.197 | 0.40 | 0.37 |
| parakeet-ultra-0.6b | 0.126 | 0.193 | 0.40 | 0.35 |
| qwen3-asr-0.6b | 0.222 | 0.307 | 0.41 | 0.29 |
| t-one | 0.251 | 0.335 | 0.10 | 0.12 |
| whisper-large-v3-turbo | 0.107 | 0.148 | 0.52 | 0.42 |
| whisper-medium | 0.135 | 0.180 | 0.46 | 0.39 |
| whisper-small | 0.174 | 0.217 | 0.36 | 0.31 |

The preset adds 4-9 points of error-only WER for every model except distil-large-v3.5. It is an
engineering guess until fitted to Fold7 recordings (Device-only).

### Throughput (16-core CPU, no GPU)

Two measurements of audio-seconds per wall-second. **Run** = per 2-thread worker inside the final
8-worker run (contended by the other 7 workers and other users' jobs). **Measure** = `--measure`, one
process, 2 threads, 5 probes per language, load average 3-8, first call included
(`ml/roundtrip/samples/roundtrip/measure.json`). **16 cores** = 8 x Run, the machine's rate if that
system alone filled all 8 workers (CPU-bound estimate; whisper-medium and -turbo are memory-bound to
3-4 workers under a 12-16 GB budget).

| stage | system | Run (per worker) | Measure (1 process) | 16 cores (8 x Run) |
|---|---|---:|---:|---:|
| TTS | piper | 6.65 | 23.6 | 53 |
| TTS | supertonic3 | 1.38 | 5.82 | 11 |
| TTS | kokoro1 | - | 2.83 | - |
| ASR | gigaam-v3-e2e-ctc | 6.54 | 25.1 | 52 |
| ASR | gigaam-v3-e2e-rnnt | 5.53 | 24.1 | 44 |
| ASR | parakeet-tdt-0.6b-v3 | 4.36 | 14.3 | 35 |
| ASR | parakeet-ultra-0.6b | 4.21 | 16.5 | 34 |
| ASR | t-one | 2.87 | 16.9 | 23 |
| ASR | nemotron-speech-streaming-en (560 ms) | 1.15 | 5.18 | 9.2 |
| ASR | nemotron-3.5-asr-streaming (560 ms) | 1.09 | 5.37 | 8.7 |
| ASR | qwen3-asr-0.6b | 1.04 | 3.77 | 8.3 |
| ASR | whisper-small | 0.75 | 2.63 | 6.0 |
| ASR | distil-large-v3.5 | 0.64 | 2.01 | 5.1 |
| ASR | whisper-large-v3-turbo | 0.63 | 1.68 | 5.0 |
| ASR | whisper-medium | 0.31 | 0.75 | 2.5 |

Whole fan-out: the ASR stage turned 8275 audio-seconds of model input (855.7 s of clips, each sent to
9.7 models on average) into rows in about 2010 s, **4.1 audio-s per wall-s**, i.e. 2.35 s of wall time
per second of TTS audio through all 12 models. whisper-medium alone used 33% of the 8339 ASR
worker-seconds. The local proof's figures (3.5 audio-s per wall-s, loaded) and the earlier `--measure`
(load 21-36) are superseded by this table.

### Twenty examples (final run, raw hypotheses)

| lang | TTS | ASR | text | hypothesis |
|---|---|---|---|---|
| mixed | supertonic3/sid5 | t-one | Распознавание работает в потоковом режиме, VAD отрезает тишину до отправки в ASR. | распознавание работает в потоковом режиме видь отрезает тишину до отправки ваясар |
| mixed | supertonic3/sid2 | t-one | Обновил package.json и перешёл с Webpack на Vite, сборка стала в пять раз быстрее. | обновил пакедж сон и перешел вс впэк на вид сборка стала в пять раз быстрее |
| mixed | supertonic3/sid2 | parakeet-tdt-v3 | (same) | Обновил пакидж. Сон и перешел с вепэп на вид, сборка стала в 5 раз быстрее. |
| mixed | piper/dmitri (tts_text) | gigaam-v3 rnnt | Попробовать Qwen3.5 2B в Q4_K_M и сравнить с LFM3 по скорости на Fold7. | Попробовать КН352Б 4KM и сравнить с ЛВ 3 по скорости на Fall 7. |
| mixed | piper/denis (tts_text) | gigaam-v3 rnnt | Распознавание работает в потоковом режиме, VAD отрезает тишину до отправки в ASR. | Распознавание работает в потоковом режиме, ват отрезает тишину до отправки в. |
| mixed | supertonic3/sid0 | whisper-large-v3-turbo | Проверить, как FLUX работает с русскими подсказками, или придётся переводить. | Проверить, как Flux работает с русскими подсказками или придется переводить. |
| mixed | supertonic3/sid8 | whisper-large-v3-turbo | Напомни во вторник проверить Crashlytics после выкатки бета-версии. | Напомни, во вторник проверить CrashLitex после выкатки бета-версии. |
| mixed | piper/dmitri (tts_text) | qwen3-asr-0.6b | Сравнить Silero и Kokoro для озвучки тестовых фраз, нужно хотя бы пять голосов. | Сравните лера и какору для озвучки тестовых фраз можно хотя бы пять голосов. |
| mixed | piper/denis (tts_text) | qwen3-asr-0.6b | Сравнить пропускную способность LPDDR5X в новых телефонах, это важно для скорости prefill. | Самый привлекательный способности опии диапетик с новых телефонов это важно для скорости префил |
| mixed | supertonic3/sid1 | parakeet-tdt-v3 | Запусти npm install, потом make build и пришли, что упало. | Запусти MPM Install, потом make buildы пришли, что упало. |
| en | piper/libritts_r#771 | nemotron-speech-en | Does anyone remember why Patroni fails over every time etcd restarts? | Anyone remember why Petroni fails over every time Ectra starts |
| en | piper/libritts_r#509 | nemotron-speech-en | Upgrade the Gradle wrapper and fix deprecated Jetpack Compose calls. | Grade the Gradle wrapper and fixed deprecated jetpack compose calls |
| en | supertonic3/sid4 | distil-large-v3.5 | Open htop on the build server and sort the processes by memory. | Open Top on the build server and sort the processes by memory. |
| en | supertonic3/sid2 | distil-large-v3.5 | Add a WorkManager job that deletes history older than seven days. | Add a work manager job that deletes history older than 7 days. |
| en | piper/libritts_r#263 | whisper-medium | We are moving our builds from Bitbucket to GitLab at the end of the quarter. | We are moving our bills from BIP get to get lab at the end of the |
| en | piper/alba | whisper-medium | I'm learning Rust on weekends, and the borrow checker clearly hates me. | I'm learning Rust on weekends and the Borrower checker clearly hates me. |
| ru | piper/dmitri | t-one | Придумай скоринг для ответов модели: точность, полнота и вежливость по пятибалльной шкале. | придумать кримп для ответов модели точность фанатаи и лежевой сто пятибалльной шкале |
| ru | piper/dmitri (tts_text) | t-one | Лора-адаптер весит всего 40 мегабайт, его можно скачивать отдельно от базовой модели. | лор адаптер весит всего сорок мегабайт его можно скачивать отдельно от базовой модели |
| ru | supertonic3/sid8 | nemotron-3.5 streaming | Коллеги, напоминаю, что дедлайн по бэклогу на квартал — пятница, 12:00. | Коллеги напоминаю, что дедлайн по бэклогу на квартал пятница двенадцать |
| ru | supertonic3/sid2 | nemotron-3.5 streaming | Новый джун получает доступ к стейджингу в первый день, а к проду — после испытательного срока. | Новый джунг получает доступ к стейджингу в первый день, а к проду после испытательного срока |

What these show: Latin terms inside Russian speech become common Russian words (Vite -> "вид",
VAD -> "ват"/"видь", Silero -> "лера"), get split at their punctuation (package.json -> "пакедж сон"),
lose a neighbouring preposition ("в ASR" -> "ваясар"), or come back as half-Latin hybrids ("make
buildы"); model names collapse into code-like strings ("Qwen3.5 2B" -> "КН352Б"); English brand names
become ordinary English words (Patroni -> Petroni, htop -> Top, "Bitbucket to GitLab" -> "BIP get to
get lab"); Russian slang drifts by one letter ("джун" -> "джунг"); streaming models drop sentence ends
("at the end of the"); numbers flip between digits and words in both directions. Piper's Russian
voices make most of the worst Russian rows (see Mining, By TTS system).

### Earlier runs (kept as samples)

`ml/roundtrip/samples/roundtrip/local_proof.jsonl` (2026-11-18): 45 seed probes with the texts of that
day (numbers still spelled out, Latin person names), piper + supertonic3 + kokoro1, 960 rows, 11 models.
Mining uses it only for the cross-set calibration (tables mined on it, simulated on the final run's
texts). `channel_demo.jsonl`: its first 12 probes through `phone` (256 rows). `asr_verify.json`: the
11-model load-and-transcribe check of 2026-11-18. Their WER and throughput figures are superseded by
the final run.

### Disk

`.cache/ml/roundtrip/` is 8.9 GB at the end of this work: ASR + Piper models 7.7 GB (HF blobs; this
pass added Nemotron Speech Streaming EN, 662 MB), sherpa TTS archives 523 MB, shared venv 612 MB,
final-run audio and results 26 MB, mined tables 13 MB, reference clips 1.2 MB, the reviewer's run 23 MB.
FLEURS archives were deleted after extraction. Committed samples under `ml/roundtrip/samples/` are
4.4 MB (final80 rows 1.0 MB, phone_noisy rows 0.4 MB, mined real tables 1.2 MB). Nothing was installed;
no writes to `~/.cache/huggingface` remain (two Xet log files from this pass were removed and the cause
fixed).

### UNVERIFIED / limits

- GPU TTS (Qwen3-TTS, Chatterbox): only the call shapes are tested, with fakes; loading, CUDA,
  speed, batching memory, output quality and the Russian accent of cloned voices need the pod.
- Supertonic's Russian accent on Latin words and Piper's reading of `tts_text` are judged only through
  the ASR outputs, not by listening.
- Russian voices on CPU are 2 male Piper voices (much harder input: error-only WER on ru 0.27 against
  Supertonic's 0.07, averaged over models) plus 10 Supertonic voices; variety beyond that needs the
  GPU cloning systems.
- The channel presets are plausible engineering choices, not fitted to Fold7 recordings.
- The CPU int8 graphs are what the app runs in CPU mode; the NPU builds (QNN w8a16, LiteRT) may err
  differently (Device-only).
<!-- END SECTION: Round trip -->

<!-- BEGIN SECTION: Full run plan (owner: round-trip operator) -->
## Full run plan

The full CPU round trip over the probe set, sized from the measured throughput of the final 80-probe run.
Operating instructions (launch, progress, stop, resume, mining): `.cache/ml/roundtrip/out/full/RUN.md`.
Code: `ml/roundtrip/full_plan.py` (the plan), `ml/roundtrip/full_run.sh` (detached launch, status, stop),
`ml/roundtrip/progress.py`, runner options `--plan`, `--asr-lookahead`, `--stop-file`, `--max-wall-h`. Tests:
`test_roundtrip_full_plan.py` (5), `test_roundtrip_plan.py` (8), `test_roundtrip_progress.py` (1: weighted work
line, `--complete-waves`).

### Budget and the numbers it rests on

About 4 h of wall time on this 16-core server at `nice 15`, 8 workers x 2 threads, `--mem-gb 12` in a
16 GB `tools/heavy` scope (as in the final run). Speeds are the final run's per-worker x-realtime
(`samples/roundtrip/final80.report.json`, measured inside 8 contended workers): Piper 6.65, Supertonic 1.38,
Kokoro 0.67 (not in that run: Supertonic's figure scaled by the two systems' `--measure` ratio 2.83 / 5.82);
ASR from GigaAM-CTC 6.54 down to whisper-medium 0.31. Clip length = words x seconds per word of the same
TTS and language in the final and local-proof runs (Supertonic 0.40 / 0.49 / 0.53 s for en / ru / mixed,
Piper 0.32 / 0.39 / 0.44, Kokoro 0.36). The final run kept only **4.15 ASR workers busy on average**
(8339 worker-s in 2010 s; about 5 before its memory-bound whisper-medium tail): the 12 GB budget fits two
whisper-medium (4.0 GB each) or one whisper-turbo (3.4 GB) beside a few small models. The plan assumes
**5 busy workers**; `--max-wall-h 4` stops it at 4 h whatever the server load (the rest stays pending
and resumable).

The whole set does not fit: all 1,511 sentences with the same voice rules (1,736 clips) through all 12
models is 102,203 worker-s of ASR, **5.9 h** with TTS at 5 busy workers, and whisper-medium would be 34% of
it (`full_plan --n 1511 --medium-share 1.0`).

### Sentences: 1,000 of 1,511

1. **Cover**: a greedy set cover picks sentences with the most still-uncovered lexicon entries (ties go to
   the stratum furthest below its share): 344 sentences cover **all 725 entries** (term surfaces resolved to
   entries with `probe.lexicon.TermMatcher`, so `задеплоили` counts for `задеплоить`).
2. **Fill** to 1,000 by stratified sampling over (language, category of the first term, seed / generated),
   each stratum at its share of the full set.

Result: ru 308, en 268, mixed 424 (full set 465 / 406 / 640, i.e. 66% of each); seed 391, generated 609;
**1,972 term occurrences** of 725 entries.

| category | entries | sentences in the set (first term) | in the plan | term occurrences in the plan |
|---|---:|---:|---:|---:|
| acronyms | 72 | 143 | 94 | 169 |
| ai_ml_jargon | 82 | 148 | 97 | 185 |
| ai_ml_jargon_ru | 41 | 129 | 85 | 177 |
| ai_tools | 52 | 97 | 64 | 114 |
| cli_commands | 43 | 79 | 52 | 102 |
| cloud_services | 61 | 114 | 76 | 167 |
| databases_data | 41 | 69 | 46 | 95 |
| dev_slang_ru | 81 | 323 | 214 | 405 |
| devops_infra | 47 | 74 | 49 | 103 |
| files_config | 33 | 62 | 42 | 77 |
| hardware | 30 | 48 | 32 | 70 |
| languages_frameworks | 55 | 82 | 55 | 118 |
| mobile_android | 33 | 55 | 36 | 79 |
| model_names | 54 | 88 | 58 | 111 |

### Voices, and the Piper decision

Error-only WER per voice group, averaged over all models, from the existing rows (`mine.align`, clean channel;
script `.cache/ml/roundtrip/full_plan/scratch/per_voice2.py`):

| voices | lang | final run: clips / error-only WER | local proof (other texts): clips / error-only WER |
|---|---|---:|---:|
| piper ru_RU-denis (male) | ru | 11 / 0.242 | 7 / 0.217 |
| piper ru_RU-dmitri (male) | ru | 16 / 0.282 | 8 / 0.275 |
| supertonic3 (10 voices) | ru | 27 / 0.068 | 15 / 0.089 |
| piper ru_RU-denis | mixed | 13 / 0.335 | 11 / 0.394 |
| piper ru_RU-dmitri | mixed | 13 / 0.356 | 4 / 0.351 |
| supertonic3 | mixed | 26 / 0.207 | 15 / 0.183 |
| piper, 11 English voices | en | 27 / 0.108 | 15 / 0.117 |
| supertonic3 | en | 27 / 0.041 | 15 / 0.033 |
| kokoro1 | en | - | 15 / 0.042 |

Both Russian Piper voices give 3.5-4x Supertonic's Russian error rate on two independent text sets, and the
same recognisers make 5.3% error-only WER on real read Russian speech (enrich, Nemotron). Those errors are
mostly artefacts of the voices. **Decision: Piper's Russian voices never read a sentence's first clip.** They
read only the second clip of the paired sentences (below), so the primary set is Piper-ru-free, and the pairs
measure the voice effect on the same text. That caps them at 109 of 841 ru/mixed clips (13%). Piper's English
voices stay at a third of the English clips: they are worse than Supertonic and Kokoro too (0.108 vs 0.04),
but they come from real recorded speakers (LibriTTS-R, LibriVox), and the per-TTS tables keep the gap visible.

Assignment, in the plan order, by round-robin counters:
- `ru`, `mixed`: Supertonic, its ten voices rotated (83-84 clips each).
- `en`: rotated over Piper (11 voices, 9-10 clips each), Supertonic and Kokoro (27 voices; `am_santa`, a
  novelty voice, left out): 104 / 102 / 102 clips.
- **Second voice** for 15% of the sentences of each language (149 sentences): another system than the
  first; for ru/mixed that can only be Piper (denis / dmitri alternating; Kokoro has no Russian), for en one
  of the two other systems.

Clips per language and system: ru Supertonic 308 + Piper 46, mixed Supertonic 424 + Piper 63, en Piper 104,
Supertonic 102, Kokoro 102; **1,149 clips**. The Supertonic rotation runs over ru and mixed together, so each voice
has 83-84 clips in all but 24-38 of the ru clips and 33-50 of the mixed ones (audit, recomputed from `plan.jsonl`).

### Channel and models

- **Channel**: 20% of the sentences of each language (both clips of a pair) go through `phone_noisy`:
  247 clips; 902 clean. The two counters line up so that paired sentences are noisy more often: 33% of Piper's
  Russian clips (36 of 109) against 20% of Supertonic's (145 of 732); en Piper 21%. Compare TTS systems on the
  clean channel or within pairs (RUN.md has the commands).
- **ASR**: all 12 CPU models. whisper-medium is 33% of the ASR cost at full coverage and only two of it fit
  the 12 GB budget at once, so it runs on **50% of the clips (572)**, chosen evenly per (language, TTS
  system, channel); that brings it to 20% of the ASR work, below the 2-of-8 workers it can hold.

### Arithmetic

| item | value |
|---|---:|
| audio: Supertonic / Piper / Kokoro | 5,520 / 1,096 / 517 s = 7,134 s |
| TTS worker-s (audio / per-worker x-realtime) | 4,935 -> **617 s** on 8 workers |
| ASR worker-s | 56,588 -> **11,318 s** at 5 busy workers |
| expected wall | **11,935 s = 3.3 h** (the 4 h cap leaves 0.7 h for load) |
| expected rows | **10,605** |

| ASR model | rows | worker-s | | ASR model | rows | worker-s |
|---|---:|---:|---|---|---:|---:|
| whisper-medium (50%) | 572 | 11,357 | | parakeet-ultra-0.6b | 1,149 | 1,694 |
| whisper-large-v3-turbo | 1,149 | 11,292 | | parakeet-tdt-0.6b-v3 | 1,149 | 1,637 |
| whisper-small | 1,149 | 9,450 | | nemotron-speech-streaming-en | 308 | 1,406 |
| qwen3-asr-0.6b | 1,149 | 6,874 | | gigaam-v3-e2e-rnnt | 841 | 997 |
| nemotron-3.5-asr-streaming | 1,149 | 6,572 | | gigaam-v3-e2e-ctc | 841 | 843 |
| distil-large-v3.5 | 308 | 2,549 | | t-one | 841 | 1,917 |

### Order for early cut-off

The run can be stopped at any moment (the cap, `full_run.sh stop`, a crash) and the rows written so far are
a balanced sample up to the look-ahead: the cheap models may be up to 2 waves ahead of whisper-medium / -turbo, so
for equal sentence sets per model keep only the complete waves (`python -m ml.roundtrip.progress --complete-waves
OUT`):
- **Sentence order**: within a language, round-robin over categories (each shuffled); languages merged in
  proportion to their counts. Voices, second voices, channel and the whisper-medium share are assigned along
  this order by counters, so every prefix has the same mix. Tested: in the first 1, 3 and 6 waves each
  language is within 6 points of its overall share and all ten Supertonic voices are present.
- **Waves** of 40 sentences (25 waves of ~46 clips, ~424 jobs each, 4% of the run).
- **TTS** first, wave by wave (about 10 minutes at the final run's speed).
- **ASR**: a shard never spans two waves; shards go out wave by wave and, inside a wave, round-robin over the
  models (the model with the most work in that wave first). The previous order (the critical-path model
  first, whole) would have finished whisper-medium before starting anything else. The memory gate is
  unchanged, so a capped heavy model lets later shards go first, but only within `--asr-lookahead` (2) waves
  of the oldest unfinished wave (added after the 5% check, below). Tested
  (`test_interleaved_shards_cover_every_model_in_each_wave_before_the_next`,
  `test_plan_rows_are_written_wave_by_wave_with_models_interleaved`).

### Launch, detachment, resume

`ml/roundtrip/full_run.sh start` builds the plan if missing (`python -m ml.roundtrip.full_plan --n 1000
--out .cache/ml/roundtrip/out/full/plan.jsonl`, deterministic), then starts `setsid nohup tools/heavy nice -n 15
full_run.sh _inner` (MURMUR_MEM=16G): a transient systemd user scope whose parent is `systemd --user` (the
user has linger enabled), not the calling shell. `_inner` writes `runner.pid`, runs the runner and then
`DONE` with `exit=<code>`. Rerunning `start` continues: the runner skips rows already written (key
probe/system/voice/channel/model) and reuses audio. `stop` creates `STOP`; the runner submits no new shard,
lets running shards finish and exits with the rest pending. Errors go to `roundtrip/full.errors.jsonl` as they
happen, so `full_run.sh status` can show them while the run goes on. Checked on a 3-clip smoke plan: detached
(PPID = systemd --user, own session and scope, nice 15), 5 rows, `DONE` = `exit=0`, rerun "0 pending ASR jobs (5
already done)", no duplicates.

### Measured at launch (2026-11-19)

Launched 01:56 MSK (`full_run.sh start`, `--max-wall-h 4`). The server was shared with other users' builds and
LLM jobs at nice 0 (load average 40-110 on 16 cores); at nice 15 the run got about 1.6 cores at first.

- **TTS**: 1,149 / 1,149 clips in 3,976 s (66 min, planned 10 min), 0 errors. Per-worker x-realtime
  Supertonic 0.23, Piper 0.95, Kokoro 0.12 (final run: 1.38 / 6.65 / 0.67), so 5-7x slower than planned.
- **ASR, first 23 min**: 753 rows, 0 errors. The check at 5% showed the cheap models two waves ahead of
  whisper-medium / -turbo (wave 0: medium 5 of 19, turbo 22 of 44, GigaAM already done with wave 2): the memory
  gate let small shards of later waves overtake. Fix: `--asr-lookahead` (default 2): no shard starts more than
  2 waves after the oldest wave with a queued or running shard
  (`test_lookahead_keeps_cheap_models_within_k_waves_of_a_memory_bound_one`, which also shows the old
  scheduler breaking the bound). The run was stopped with `full_run.sh stop` (03:22; running shards drained by
  03:25:49, `DONE` = `exit=1`, report `stopped: stop file`, 0 errors) and resumed at 03:26 with
  `MAX_WALL_H=2.5`, so both invocations together stay within about 4 h (cap at about 05:56). The resume found
  9,852 pending and 753 done jobs; no duplicates.
- Measured per-worker ASR x-realtime in the first invocation (final run in brackets): whisper-medium 0.09 (0.31),
  turbo 0.16 (0.63), small 0.29 (0.75), distil 0.20 (0.64), qwen3-asr 0.40 (1.04), Nemotron 0.37 (1.09),
  Parakeet 1.18-1.46 (4.2-4.4), GigaAM 1.7 (5.5-6.5), T-one 1.06 (2.87): 2.7-3.9x slower.
- **Rate after the resume**: 594 jobs in 26 min = **1,376 jobs/h** (03:52: 1,347 / 10,605 jobs done, 12.7%).
  **Corrected by the audit (04:03)**: jobs are not a unit of work (a whisper-medium row costs ~20x a GigaAM row)
  and the cheap models ran 2 waves ahead, so jobs/h overstated progress. Weighted by the plan's expected
  worker-seconds per row, 12.2% of the work was done when 14.5% of the jobs were; at the work rate since 03:26 the
  cap reaches about 33-36% of the work (~3,600 jobs at the plan's mix, not ~4,200), the uncapped end would be
  about 11:30-11:45, and the rest after the cap needs about 5 h at that load. Measured per-row worker time in this
  invocation was 2.0-3.6x the plan's (whisper-medium 47.9 s vs 19.9 s); the scope got 1-2 of the 16 cores at load
  45-55. `full_run.sh status` now prints this weighted `work` line and the `cap` projection.
- **Row checks** (03:52, `.cache/ml/roundtrip/full_plan/scratch/check_rows.py`): 1,347 rows, 1,347 unique keys,
  0 empty hypotheses, 0 errors; waves 0-1 complete or nearly (403/403, 400/435), wave 3 started, nothing beyond;
  all 12 models, en 300 / ru 439 / mixed 608 rows, Supertonic 1,019 / Piper 235 / Kokoro 93, clean 1,079 /
  phone_noisy 268, 39 voices so far. Every model routed only to its languages (no English-only model on ru/mixed,
  no GigaAM/T-one on en).
- **What the voice read** (`tts_input`): Supertonic on ru/mixed sentences with digits read `text_numbers` (11) or
  `tts_text` (5) whenever the text has a number token, and plain `text` (10) only when the digits are part of a
  name (H100, Gemini 2.5, Битрикс24). **Audit correction**: that is not "0 clips read raw digits". Over the plan,
  72 of the 732 Supertonic ru/mixed clips still have digits in what the voice reads: inside a Latin name (H100,
  A100, LPDDR5X, Qwen3-ASR, R8) or right after one, which the rule leaves alone ("RTX 5090", "Pixel 9", "Wi-Fi 7",
  "Llama 4", "Material 3", "Gemini 2.5": 13 clips). In the rows so far Supertonic drops or garbles many of them:
  "H100" -> "Hitsun", "A100" -> "Ace of Cloud", "B200" -> "бидвиг ста", "R8" -> "arocium", "Gemini 2.5" -> "Gemini
  2. Pass", with the digits in 0 of 8-10 models' hypotheses (K2, S3, Битрикс24 survive in 6-7 of 9). They are listed
  in `.cache/ml/roundtrip/out/full/flags_supertonic_digit_names.jsonl` for the miner. For the next run: respell
  digit-bearing Latin names for Supertonic under `lang=ru` (not during this run: `tts_input` is recomputed on every
  resume while the audio is reused). Times come back right ("Поставь на 15:30 ..."
  -> GigaAM "Поставь на 15:30 ..."). "Битрикс24" read as written is heard as "Bitrix 24" / "битрик двадцать
  четыре". **Known artefact**: "1С" read as written is garbled by Supertonic ("с 1С декодер" -> "Adrices
  Decoder", "адиса с декодер"); 4 planned clips contain it. Respelling it ("один эс") is for the probe set's
  `tts_text` rules; it was not changed during the run.

### GPU pod probe (`ml/roundtrip/pod_probe.sh`) — UNVERIFIED-ON-GPU

The hook `ml/pod/probe.sh` runs when `MURMUR_PROBE_CMD` is unset. GPU TTS only (Qwen3-TTS 1.7B Base and
Chatterbox multilingual, cloning the 10 FLEURS reference clips); ASR runs later on this server's CPU.
1. Own venv per system on the volume (`$W/venv-rt-qwen3tts`, `$W/venv-rt-chatterbox`, uv, Python 3.12), torch
   checked against the GPU.
2. Reference clips fetched on the pod (`fetch_refvoices --ru 6 --en 4`, same seed and FLEURS revision as here)
   unless `RT_REF_DIR` points to a copy.
3. Plans (`full_plan --gpu-system S --gpu-n N --ref-dir ...`): the first N sentences of the CPU run's order
   (so they pair with CPU clips of the same text), clean, each voice cloned from a clip of the sentence's
   language; ASR model lists as in the CPU plan.
4. **Sizing**: 100 sentences per system = 200 utterances (about 1,230 s of audio at Supertonic's length per
   word), `--skip-asr`, Qwen3-TTS `--tts-batch 8 --tts-shard 32`, Chatterbox unbatched; timed.
5. **Main**: up to 300 sentences per system (the sizing 100 are reused), cut to what the remaining budget
   allows at the per-sentence time measured in step 4.
6. Summary `$W/roundtrip/out/pod_probe.summary.json` (clips per voice, runner reports with x-realtime).

Budget: **30 pod minutes in total** (`POD_PROBE_BUDGET_MIN`), counted from the script start over all
invocations (setup included) in `$W/roundtrip/.pod_probe_used_s`; every step checks it, synthesis gets
`--max-wall-h` for the remainder plus a hard `timeout`; when it runs out the script exits 0 with what it has.
Resumable: existing clips are reused. Output layout = the runner's (`$W/roundtrip/out/audio/<system>/<voice>/
<probe>.clean.flac`), pulled with `WITH_PROBE=1 ml/pod/sync_results.sh`; then on this server
`run_roundtrip --plan <pulled>/roundtrip/out/pod_plan_<system>_<N>.jsonl --tts <system> --ref-dir
<pulled>/roundtrip/refvoices --out <pulled>/roundtrip/out --asr all ...` transcribes them (the GPU drivers list
their voices without torch, and existing audio is never re-synthesised).

Checked here: `bash -n`; argument checks (unknown argument, unknown system, sizing > max: exit 2);
`--dry-run` builds the four plans from the local reference clips (100 and 300 clips per system, 10 voices),
validates them with the runner's `load_plan`, prints every command with its budget, exits 0; a 1-minute
budget stops at the first step. The dry run found and fixed one bug (a `find` on a missing directory under
`pipefail` ended the script). Audit fixes (2026-11-19): every step now runs under `timeout` for the remaining
budget (before, `uv venv`, `uv pip install` and the FLEURS fetch had no time limit, so the 30 minutes were not a hard
stop); a failed step exits with its code instead of 0 (before, a failed install exited 0 and `ml/pod/probe.sh` then
marked the probe done); the torch check launches a real CUDA kernel and prints the GPU's arch and torch's arch list
(`is_available()` alone is true for a build without sm_120 kernels); the plan check uses a venv python unless the
host has numpy, scipy and soundfile (it imports the runner). Checked here: dry run exit 0 with 4 plans; a step cut by
the budget exits 0 in 3 s, a failing step exits 1 (function-level test). `POD_PROBE_MAX_N` can be raised above
300 by env; 300 is the default, not a hard cap. Added for the pod: Qwen3-TTS falls back from `flash_attention_2` to `sdpa` when
flash-attn is not installed (the venv has none; transformers would raise at load), `MURMUR_QWEN_ATTN`
overrides (`gpu_tts.choose_attn`, tested). `shellcheck` is not installed here.

UNVERIFIED-ON-GPU: venv installs and their torch/CUDA build for the 5090 (chatterbox-tts 0.1.7 pins its own
torch; whether that build has sm_120 kernels is not checked), model downloads (~8 GB) within the budget, load,
speed, batching memory, output quality, the 30-minute fit.
<!-- END SECTION: Full run plan -->

<!-- BEGIN SECTION: Mining (owner: mining agent) -->
## Mining

Mining turns round-trip rows (interface 2) into per-model noise tables (interface 3). The noise sampler
replays those tables over clean text, and PLAN_V2 stage C calls it to build the cleanup LLM's synthetic
inputs. All of it is standard-library Python except the dataset adapter, which needs `pyarrow`. No GPU,
no model and no teacher is involved.

### Files

| path | what |
|---|---|
| `ml/roundtrip/mine/norm.py` | tokeniser, style folding (case, punctuation, ё/е, apostrophes), number words ↔ digits for ru and en (cardinals, ordinals, times, years, %, scale words, fraction denominators, unit words and abbreviations: `60GB`, `40 ГБ`, `200 мс`) |
| `ml/roundtrip/mine/translit.py` | Latin → Cyrillic reader, Cyrillic → Latin, phonetic key, and `faithful_translit` (the style-vs-error test for Latin terms in Cyrillic, using the probe lexicon's readings) |
| `ml/roundtrip/mine/align.py` | style-aware word/phrase alignment; every pair gets a kind: style (`match case yo punct number translit`) or error (`sub_near sub del ins`); term hits with their covering span |
| `ml/roundtrip/mine/mine_errors.py` | rows → `profiles.json`, `confusions.jsonl`, `term_manglings.jsonl`, `REPORT.md` (CLI; `--models`, `--tts`, `--channels` filters) |
| `ml/roundtrip/mine/noise.py` | mined tables → simulated raw hypothesis; `sample`, `simulate` and `calibrate` subcommands |
| `ml/roundtrip/mine/hub_map.json` | every hub catalog id → the profile id that stands in for it, with a `proxy` flag |
| `ml/roundtrip/mine/adapters.py` | public data that already holds real ASR hypotheses → interface-2 rows (`enrich`) |
| `ml/roundtrip/mine/regen_outputs.sh` | regenerates every output below with the current code; skips inputs that are absent |
| `ml/roundtrip/mine/clean_rows.py` | documented row-selection rules R1-R6 for the primary tables (Piper ru, TTS-flagged clips, empty, loops, clip-level TTS failure, skipped words) |
| `ml/roundtrip/mine/mine_full.sh` | full-run mining: clean, mine (primary, all, per TTS, per channel), calibrate, publish `samples/mined/full/` |
| `ml/roundtrip/samples/mined/full/` | mined **full CPU run** (8,878 primary rows, 968 clips, 12 models): the generator's `--mined` tables, report, per-TTS/per-channel reports, calibrations (see Full run results) |
| `ml/roundtrip/samples/mined/real/` | mined **final run** (1546 rows, 12 models): the full tables (usable as `--mined`), the report and in-sample, held-out and cross-set calibrations |
| `ml/roundtrip/samples/mined/fixture_roundtrip.jsonl`, `fixture/` | hand-made fixture (140 rows, 6 models), **not real ASR output**, and its mining |
| `ml/roundtrip/samples/mined/enrich/` | mined real Russian speech (the enrich adapter): report, profiles, top 150 confusions per model, calibrations |
| `ml/roundtrip/tests/test_mine_*.py` | 258 tests including a 146-pair gold table, about 30 s |

### Alignment

A single weighted edit-distance DP over word tokens. Besides 1:1 pairs it allows phrase blocks: one
reference word onto up to 6 hypothesis words (`ClickHouse` → `клик хаус`, `CI/CD` → `си ай си ди`),
up to 4 (6 for spoken numbers) reference words onto one (`Hugging Face` → `хаггингфейс`), and
many-to-many blocks for number spans (`сорок гигабайт` → `40 ГБ`). Each pair is classified:

- **Style:** not a recognition error, but the cleanup LLM still has to undo it.
  - `case`, `yo` (ё/е and apostrophes), `punct` (separators or word splitting).
  - `number`: the same value as digits or words, in either direction, with units (`sixty gigabytes` /
    `60GB`, `двести миллисекунд` / `200 мс`), spoken decimals (`девяносто девять и девять десятых
    процента` / `99,9%`) and digits inside a term (`FP16` / `FP sixteen`, `Apple M4` / `Apple M Four`).
  - `translit`: the term written the way it is said. For a Latin term in Cyrillic this is
    `translit.faithful_translit`: the hypothesis (a Russian case ending allowed: `лору`, `Марком`,
    `кубернетисе`) must be within a small weighted edit distance of one of the term's readings, taken
    from the probe lexicon (`ru_spoken`), an English-style and a letter-by-letter transliteration and the
    spelled-acronym readings. Vowel changes cost 0.5 except in the first syllable (usually the stressed
    one in a loan word), consonant changes 1, so voicing must be kept; acronyms must match exactly.
    The hypothesis may also be the term's spoken form in Latin (`journalctl` / `journal control`,
    from `en_spoken`), or Cyrillic slang written as the English word (`пулреквест` / `pull request`).
- **Errors:** `sub_near` (close but wrong), `sub`, `del`, `ins`.

The transliteration test was the reviewer's finding 5: the old rule (phonetic key ≥ 0.8 and spelling
≥ 0.65) called `Kafka` → `кофка`, `Slack` → `слаг`, `Sentry` → `центре` and `Heroku` → `гироку` style and
`YouTrack` → `ютрек`, `ONNX` → `онникс`, `Laura` → `лорой` errors. `test_mine_translit_gold.py` now holds
146 labelled pairs, mostly from real output (81 style, 65 error), and all pass. One judgement call
differs from the reviewer: `FLUX` → `Флэкс` stays an error (the lexicon reading is "флакс"/"флюкс";
"флэкс" is "flex").

A cross-script block that also swallows a Russian neighbour (`в Slack` → `вслаг`) is always an error.
When a term's covering op reaches beyond the term (`кину в Slack` → `киновслаг`), the term hit records
the whole span (`TermHit.merged`): the miner stores it as a phrase-level mangling of `кину в Slack`, not
as a rendering of `Slack` (finding 4). In the final run that is 102 of 2898 term hits (3.5%).

Two WERs are reported: conventional normalised **WER** (case, punctuation and ё folded, nothing else
forgiven) and **error-only WER** (`wer_style_aware`, counts only error kinds).

### Mined artefacts (interface 3)

- **`profiles.json`**, per model:
  - WER, error-only WER and CER, overall, by language and by channel; term WER against non-term WER;
  - **`by_tts`**: per TTS system and language, WER, error-only WER, term exact and error rates, Latin
    kept, and which probe field the TTS read (`inputs`);
  - casing, punctuation, digits, Latin kept against transliterated, ё kept;
  - hallucination: tails, leading runs, empty output, over-long output, and **output in another script**
    (`foreign_script_rate` with examples; Qwen3-ASR answered 2 of 160 Russian rows in Polish and in Ukrainian);
  - `terms`: exact, folded, translit and error rates per language, and `split_rate` (one written word
    heard as several);
  - a `sampler` block: per-word sub/del/ins rates over non-term words, the near-miss share, the
    insertion vocabulary and confusable words, pooled and **`by_lang`** (finding 3).
- **`confusions.jsonl`**: `{"asr_model","lang","ref","hyp","count","kind","in_term"}`, word and phrase level.
- **`term_manglings.jsonl`**: `{"term","category","lang_context","asr_model","variants":[{"hyp","count"}],
  "merged":[{"ref","hyp","count"}],"n","error_n","exact_rate","folded_rate","translit_rate","error_rate"}`.
  `variants` hold only renderings of the term itself; `merged` the phrase-level ones.
- **`REPORT.md`**: per-model, per-language, per-channel and **per-TTS** tables; a cross-model
  **hardest terms** table (errors summed over models); per model the "what the cleanup LLM must learn
  to undo" list, the top term manglings **ranked by error count** (finding: alphabetical order at n=2
  was uninformative) and the top non-term errors.

The miner is deterministic: the same rows give byte-identical outputs, with any `--jobs`.

### Noise sampler

```
P=.cache/ml/roundtrip/venv/bin/python
$P -m ml.roundtrip.mine.noise sample --mined ml/roundtrip/samples/mined/real --asr-model t-one@onnx --lang mixed \
    --text "Созвон с Лорой по Kubernetes перенесли на 10:30, ссылку кину в Slack." --terms Лорой Kubernetes Slack
$P -m ml.roundtrip.mine.noise simulate --mined DIR --in probes.jsonl --out sim.jsonl --all-models --repeats 3
$P -m ml.roundtrip.mine.noise calibrate --rows rows.jsonl --mined DIR --out OUT              # in-sample
$P -m ml.roundtrip.mine.noise calibrate --rows rows.jsonl --holdout-frac 0.3 --out OUT       # held-out probes
$P -m ml.roundtrip.mine.noise calibrate --rows rows.jsonl --mined OTHER --cross --out OUT    # other run's tables
```

In code: `NoiseModel.load(dir).simulate(text, asr_model, terms=None, lang=None, seed=0, severity=1.0)` returns
`Simulation(hypothesis, events)`. `asr_model` may be a profile id or a hub catalog id (`profile_for`
resolves it through `hub_map.json`). The sampler works in five steps.

0. **Language gate.** A model is simulated only on languages it was mined on: `simulate` raises
   `UnsupportedLanguage` (the CLI prints the reason), and `simulate_rows` / `simulate --all-models` skip
   such pairs and report them (finding 1). On the 80 review probes `--all-models` now writes 773 rows and
   skips 187 pairs (distil and Nemotron EN on ru/mixed, GigaAM and T-one on en); before, 134 of 880 rows
   were impossible pairs such as T-one transcribing English.
1. **Terms**, left to right before anything else.
   - Error probability: `(observed errors + 3 × model term error rate in this language) / (observations + 3)`
     (finding 2). A term seen once or twice without error still fails at about the model's usual rate on
     new text. An error reuses an observed error rendering with probability `err / (err + 3 × rate)`, else
     the garbler makes a new one.
   - A phrase-level mangling applies only where the whole phrase occurs around the term, and then replaces
     the whole phrase (`кину в Slack` → `киновслаг`), so neighbours are never repeated.
   - Without an error: an observed style variant (exact, case, transliteration), or for an unseen term the
     model's habits: Latin kept at the observed rate, otherwise the lexicon reading (`JSON` → `джейсон`)
     or a transliteration, written as one word or split at the mined `split_rate`; digits inside it are
     spelled for models that never write digits (T-one).
   - Variants are borrowed only from the same spoken language; unannotated Latin words in Russian text
     are treated as terms.
2. **Numbers.** Digits are spelled out at the mined rate; spoken-number runs are written as digits at the
   mined rate (with a prior from what the model wrote when it saw no digits).
3. **Words.** Substitutions, deletions and insertions at the mined per-word rates **of the text's
   language**, smoothed toward the pooled rate with 50 pseudo-words. Words with observed confusions use their
   own rates. A substitution is an observed confusion first, else an unrelated word from the model's own
   output or a near miss. Models with a language-model decoder (Whisper family, Qwen3-ASR) take near misses
   from real words seen in the tables, never character soup ("голосоем"); CTC/transducer models get
   character-level near misses.
4. **Style and failures.** Casing, punctuation, commas, terminal marks (corrected for rows that end in a
   tail or are empty, which the sampler adds separately) and ё loss follow the mined rates. Empty output,
   hallucinated tails and the rare wrong-script answer (a real observed example, event `foreign_script`, so
   stage C can filter it) follow theirs.

`severity` scales only the error part. The same (text, model, seed, severity) always gives the same output.

### Calibration

Calibration simulates real rows' texts, mines the simulated rows with the same miner and compares 14
pooled statistics per model (WER, error-only WER, CER, term exact and error rates, casing, punctuation,
digits, Latin kept, tails, empty) and 3 per (model, language) (error-only WER, term exact and error rates).
A gap is flagged when it exceeds its tolerance **and** twice the standard error of the real value
(binomial; rows as the sample for WER-like rates): with 26 English terms a real rate is itself uncertain
by ±0.18, so a smaller gap is shown (`~`) but cannot be called a sampler error. Each held-out or cross
entry also shows the "sampler gap": simulated minus the rows the tables were mined from, which separates
sampler fidelity from differences between the two sets.

| check (final run, 4 repeats) | pooled flagged | per-language flagged | above tolerance but within noise |
|---|---:|---:|---:|
| in-sample: 1546 rows | 2 / 154 | 0 / 87 | 2 |
| held-out: mined on 70% of the 80 probes (1000 rows), compared on 30% (546 rows) | 8 / 144 | 11 / 87 | 51 |
| cross-set: mined on the local proof (960 rows, other texts, kokoro1 included), compared on the final run | 8 / 142 | 12 / 84 | 19 |
| enrich (real speech, 2 models) in-sample / held-out | 0 / 20, 0 / 18 | 0 / 2 | 0 |

What the remaining flags are:
- **In-sample** (sampler fidelity): only the any-punctuation rate of GigaAM-CTC and Nemotron (0.86 → 0.77,
  0.58 → 0.49). Per language everything is within tolerance. The reviewer's same-text per-language check
  (error-only WER real → simulated) is now en qwen3 0.074 → 0.095 (was 0.101 → 0.183), whisper-small 0.077 →
  0.084 (0.073 → 0.135), parakeet-tdt 0.073 → 0.089 (0.074 → 0.118); mixed qwen3 0.386 → 0.375 (0.384 →
  0.292), parakeet-ultra 0.251 → 0.242 (0.220 → 0.188).
- **Held-out and cross-set**: almost every flag has a small sampler gap (simulation within 0.02-0.08 of the
  rows it was mined from); the sets themselves differ. Example, English term error rate, cross-set, real →
  simulated (mined-on rows): whisper-turbo 0.206 → 0.110 (0.126), parakeet-tdt 0.235 → 0.145 (0.149),
  distil 0.275 → 0.201 (0.195), whisper-medium 0.226 → 0.181 (0.161). Before the smoothing the reviewer
  measured whisper-turbo 0.284 → 0.020. The local proof's English rows were easier (a third read by
  Kokoro, older texts), so its tables under-state the final run's term errors; the sampler reproduces
  its own tables. The largest swings are Latin-kept rates of GigaAM (held-out 0.33 real vs 0.78
  simulated, cross-set 0.59 vs 0.31): they depend on which TTS read which terms (next section).
- Stage C should use tables mined on the full probe run (1,511 sentences) with the TTS mix chosen below,
  not the 80-probe tables, and re-run the cross-set check against a held-out probe slice.

### Data mined: the final run (2026-11-19)

`ml/roundtrip/samples/mined/real/`, 1546 rows (80 probes x Piper + Supertonic x all compatible models,
clean channel). Error-only WER counts only recognition errors; term WER is error-only WER over the
annotated terms.

| ASR model | rows | WER | error-only WER | term WER | non-term WER | term exact | WER en / ru / mixed | Latin kept (mixed) | style |
|---|---:|---:|---:|---:|---:|---:|---|---:|---|
| distil-large-v3.5 | 54 | 11.1% | 8.2% | 30.8% | 4.0% | 57.8% | 11.1% / – / – | – | cased, punctuated, digits |
| gigaam-v3-e2e-ctc | 106 | 21.2% | 18.6% | 79.4% | 5.4% | 25.8% | – / 14.4% / 28.1% | 59.3% | cased, partial punctuation, digits |
| gigaam-v3-e2e-rnnt | 106 | 21.1% | 18.4% | 77.6% | 5.5% | 26.3% | – / 13.9% / 28.4% | 54.2% | cased, punctuated, digits |
| nemotron-3.5-asr-streaming (560 ms) | 160 | 25.5% | 20.8% | 60.5% | 12.6% | 30.3% | 18.0% / 24.5% / 36.4% | 38.9% | cased, partial punctuation, numbers as words |
| nemotron-speech-streaming-en (560 ms) | 54 | 13.3% | 7.7% | 20.0% | 5.4% | 58.8% | 13.3% / – / – | – | cased, partial punctuation, numbers as words |
| parakeet-tdt-0.6b-v3 | 160 | 19.5% | 14.6% | 50.6% | 7.3% | 40.7% | 12.1% / 17.4% / 31.1% | 62.2% | cased, punctuated, numbers mixed |
| parakeet-ultra-0.6b | 160 | 16.8% | 13.2% | 48.2% | 6.0% | 43.3% | 7.4% / 14.7% / 31.0% | 59.1% | cased, punctuated, digits |
| qwen3-asr-0.6b | 160 | 28.5% | 23.8% | 67.7% | 14.8% | 34.7% | 12.8% / 32.8% / 44.6% | 55.9% | cased, punctuated, numbers as words |
| t-one | 106 | 31.5% | 26.7% | 91.1% | 12.7% | 16.2% | – / 23.7% / 39.4% | 0.0% | lowercase, no punctuation, numbers as words |
| whisper-large-v3-turbo | 160 | 14.8% | 11.2% | 38.6% | 5.6% | 49.0% | 9.6% / 13.9% / 22.4% | 83.3% | cased, punctuated, digits |
| whisper-medium | 160 | 17.1% | 14.3% | 45.2% | 8.0% | 43.3% | 8.2% / 19.9% / 25.8% | 92.6% | cased, punctuated, digits |
| whisper-small | 160 | 21.2% | 18.0% | 55.7% | 10.3% | 36.0% | 10.0% / 21.7% / 35.2% | 64.3% | cased, punctuated, digits |

The hard terms are where the errors are: term WER is 4 to 15 times non-term WER for every model, and
mixed ru+en sentences have 1.3 to 2.1 times the WER of Russian alone. The earlier claim "GigaAM writes 80%
of Latin terms in Cyrillic" (45-probe snapshot) did not hold: on the final run GigaAM keeps 54-59% of
mixed-sentence Latin words in Latin.

**By TTS system** (finding 6). Averaged over models, error-only WER is en 0.119 with Piper vs 0.049 with
Supertonic, ru 0.270 vs 0.072, mixed 0.351 vs 0.210. Piper's two Russian voices roughly triple Russian
errors; for mixed sentences Piper reads Cyrillic respellings, so Latin kept drops (parakeet-tdt 0.37 vs
0.81, whisper-small 0.47 vs 0.74, GigaAM-RNNT 0.42 vs 0.67). Every per-model figure above is a 50/50
mixture of the two systems. Real Russian speech is easier than either for Nemotron (5.3% error-only WER on
2500 enrich utterances of read speech, though without IT terms). Recommendation for stage C: mine the
tables per TTS (`mine_errors --tts supertonic3`, `--tts piper`) and mix the two deliberately per example
(for example 70% Supertonic-like, 30% Piper-like noise), or replace Piper's Russian voices with GPU-cloned
FLEURS voices once the pod run exists, then re-mine. The `by_tts` block and the REPORT table make the
choice measurable.

**Twenty instructive term manglings** (final run; "×n" counts rows over all models unless a model is named):

| term | context | rendering | what the cleanup LLM must do |
|---|---|---|---|
| Vite | mixed | `вид` ×12 (7 of 10 models) | a common Russian word: recover "Vite" only when the context names it |
| VAD | mixed | `ват`, `ведь`, `вид`, `видь` | same, for a 3-letter acronym |
| Silero | mixed | `лера`, `Лера` ×8, `селеро` ×3 | looks like a person's name; must not be "kept" as a name |
| Patroni | en | `Petroni` ×15 (8 of 9 models) | one consistent misspelling: a dictionary term fixes all models |
| etcd | en | `it CD` ×6, `a CD` ×2, `Ector` | spoken letters merged with an article |
| htop | en | `top` ×6, `Top` ×3, `H stop` ×3 | a real neighbouring command: only context tells |
| REST | en | phrase `a REST` → `arrest` | the article swallowed into a real word |
| API | mixed | `APE` ×6; phrase `по API` → `Папи` | Latin/Cyrillic hybrids and merged prepositions |
| package.json | mixed | `пакет Jason` ×3, `пакедж сон`, `пакидж. Сон` | half-translated file name split at the dot |
| adapter_config.json | mixed | `адаптер config, сон`, `адаптер Канфи Джейсон` | snake_case and extension lost |
| schema.sql | mixed | `SQL` ×5, `схема` ×5 | one half translated, the other dropped |
| build.gradle.kts | mixed | `Bild Gradel Katias`, `Bill Gravel KTS` | multi-part file name into English-ish words |
| Qwen3.5 2B | mixed | `QN3-352B`, `КН352Б`, `QWEN 3. BEST VW` | version and size glued or split |
| Fold7 | mixed | `флот семь`, `Fall 7`, `Fault 7` | product + digit, with the digit spelled |
| LLM | mixed | `LM` ×5, `LLV`, `ЛЛВ` | a letter dropped or substituted in an acronym |
| Nginx | mixed | `Vengeance`, `InJinks`, `Engent` | an English word where Russian speech said "энджинкс" |
| Pydantic | mixed | `педантик` ×6, `падотик` | a real Russian word ("педант") |
| Crashlytics | mixed | `Crash Litex` ×3, `кашлитекс` | CamelCase split plus a vowel change |
| черрипикнул | ru | `черепикнул` ×10 | Russian IT slang misspelled; keep it Cyrillic, fix the spelling |
| ретро | ru | phrase `ретро и` → `ретвей` ×4 | slang merged with the next word |

What the cleanup LLM must learn to undo (from the per-model REPORT lists): restore casing and all
punctuation for T-one; convert spoken numbers to digits for T-one, both Nemotrons and Qwen3-ASR (93-100% spelled
out; Parakeet TDT 50%); turn Cyrillic transliterations back into Latin terms for GigaAM (41-46% of Latin words) and T-one (100%); repair garbled
terms from context only (19-70% of terms are recognition errors, by model); never invent a term the
context does not support.

### Full run results (2026-11-19)

The full CPU round trip (`.cache/ml/roundtrip/out/full/`, `DONE exit=0`: 10,605 rows, 1,149 clips of 1,000
sentences, 3 TTS systems, 12 ASR models, 21% `phone_noisy`) mined by `ml/roundtrip/mine/mine_full.sh`.
**`ml/roundtrip/samples/mined/full/` is the directory stage C should pass as `--mined`** (it replaces
`samples/mined/real`, which rests on 80 probes; `ml/data/Makefile` `MINED` still points there).

**Row selection** (`clean_rows.py`, rules applied in order, every dropped row logged with its rule in
`.cache/ml/roundtrip/mined/full/primary_excluded.jsonl`; counts in `samples/mined/full/clean_summary.json`):

| rule | what | primary rows dropped |
|---|---|---:|
| R1 `piper_ru` | Piper on ru/mixed sentences (its two Russian voices triple Russian errors, see By TTS below) | 1,034 |
| R2 `tts_flag` | the 72 Supertonic clips in `out/full/flags_supertonic_digit_names.jsonl` (digits inside Latin names the voice did not say: H100, A100, RTX 5090, 1С); whole clip | 691 |
| R3 `empty` | empty hypothesis | 0 |
| R4 `loop` | one 1-4 word n-gram repeated ≥ 4 times in a row, or one non-word character ≥ 5 times, absent from the reference (`через с с с с с сентенс`, `at 14::::::::::::`) | 2 |
| R5 `tts_fail` | clip with ≥ 3 rows whose *best* hypothesis has WER ≥ 0.5 (no recogniser recovered the audio); whole clip | 0 (3 Piper-ru clips in the all-voices set) |
| R6 `tts_skip` | ≥ 3 consecutive reference words deleted by ≥ 2/3 of the clip's recognisers; whole clip | 0 |

Kept: **8,878 rows, 968 clips**. The all-voices tables (`--keep-piper-ru`) keep 9,882 rows. Spot check of
the result: 40 randomly sampled error renderings from `term_manglings.jsonl` and 40 error confusions
(sub/sub_near/del/ins) from `confusions.jsonl` were each found in a raw row of the same model and language
(reference words in `text`, rendering in `hypothesis`): 40/40 and 40/40. A further probe found 52 of 1,949
(clip, term) occurrences that no model wrote exactly while other voices' clips of the same term were
recognised ≥ 60% of the time; most are faithful transliterations (`Grafana` / `графана`), the rest could be
TTS mispronunciations (`CI/CD` / `сидии`) but that needs listening, so they stay (UNVERIFIED).

**Committed copy.** `profiles.json`, `term_manglings.jsonl` and `REPORT.md` are the miner's output as is;
`confusions.jsonl` keeps only the 3,771 non-term rows (of 12,668), because the noise sampler skips in-term
rows (term errors come from `term_manglings.jsonl`). Checked: `NoiseModel` built from the committed copy
equals the one built from the full cache directory in every table, and simulations are identical.
`ml.data.synth.channel.Channel` loads it (`source mined:full`; 9 en / 10 ru / 10 mixed models), the three
mined-table checks of `ml/data/tests/test_realism.py` pass on it, and an offline generator run
(`ml.data.generate --teacher stub --screens fixture --n 120 --mined ml/roundtrip/samples/mined/full`) ends with
`noise_source: mined:full` and uses all 12 models. `reports/` holds the all-voices, per-TTS and
per-channel reports. Total size 4.67 MB.

**Per model, primary tables** (probe = the 80-probe `samples/mined/real` tables, 50/50 Piper/Supertonic,
clean only; full = Supertonic for ru/mixed, Supertonic + Piper + Kokoro for en, 21% phone_noisy):

| ASR model | rows probe → full | WER | error-only WER | term WER | non-term WER | term exact | full WER en / ru / mixed | full Latin kept (mixed) |
|---|---:|---:|---:|---:|---:|---:|---|---:|
| distil-large-v3.5 | 54 → 307 | 11.1% → 9.6% | 8.2% → 6.3% | 30.8% → 26.7% | 4.0% → 1.9% | 57.8% → 52.5% | 9.6% / – / – | – |
| gigaam-v3-e2e-ctc | 106 → 660 | 21.2% → 14.7% | 18.6% → 12.4% | 79.4% → 59.5% | 5.4% → 2.2% | 25.8% → 32.1% | – / 8.5% / 19.7% | 69.8% |
| gigaam-v3-e2e-rnnt | 106 → 660 | 21.1% → 14.3% | 18.4% → 11.8% | 77.6% → 57.3% | 5.5% → 1.9% | 26.3% → 32.6% | – / 8.2% / 19.2% | 63.2% |
| nemotron-3.5-asr-streaming-0.6b | 160 → 967 | 25.5% → 18.9% | 20.8% → 13.5% | 60.5% → 47.5% | 12.6% → 6.2% | 30.3% → 33.7% | 18.9% / 12.8% / 23.7% | 49.3% |
| nemotron-speech-streaming-en-0.6b | 54 → 308 | 13.3% → 16.6% | 7.7% → 10.6% | 20.0% → 34.1% | 5.4% → 5.6% | 58.8% → 42.4% | 16.6% / – / – | – |
| parakeet-tdt-0.6b-v3 | 160 → 968 | 19.5% → 13.3% | 14.6% → 9.3% | 50.6% → 37.5% | 7.3% → 3.2% | 40.7% → 43.4% | 12.1% / 10.0% / 17.2% | 81.1% |
| parakeet-ultra-0.6b | 160 → 968 | 16.8% → 12.0% | 13.2% → 8.2% | 48.2% → 35.7% | 6.0% → 2.3% | 43.3% → 45.5% | 10.2% / 9.1% / 15.9% | 80.6% |
| qwen3-asr-0.6b | 160 → 968 | 28.5% → 22.1% | 23.8% → 16.6% | 67.7% → 52.6% | 14.8% → 8.9% | 34.7% → 33.4% | 15.0% / 19.9% / 30.6% | 60.1% |
| t-one | 106 → 660 | 31.5% → 21.1% | 26.7% → 16.0% | 91.1% → 70.1% | 12.7% → 4.3% | 16.2% → 20.5% | – / 13.3% / 27.4% | 0.0% |
| whisper-large-v3-turbo | 160 → 968 | 14.8% → 9.8% | 11.2% → 6.6% | 38.6% → 28.0% | 5.6% → 2.0% | 49.0% → 53.5% | 9.4% / 6.5% / 12.8% | 85.5% |
| whisper-medium | 160 → 476 | 17.1% → 11.2% | 14.3% → 7.3% | 45.2% → 29.4% | 8.0% → 2.5% | 43.3% → 51.2% | 9.1% / 10.4% / 13.7% | 88.4% |
| whisper-small | 160 → 968 | 21.2% → 13.9% | 18.0% → 10.2% | 55.7% → 40.6% | 10.3% → 3.7% | 36.0% → 41.7% | 10.9% / 11.9% / 18.3% | 79.1% |

Most of the drop from probe to full is the voice mix, not the models: without Piper's Russian voices the
ru/mixed rows are easier. Like for like (Supertonic, clean channel, same models) the mean error-only WER
goes en 0.041 → 0.057, ru 0.068 → 0.081, mixed 0.207 → 0.146 and the term error rate en 0.161 → 0.243,
ru 0.271 → 0.309, mixed 0.613 → 0.508: the 80 probes over-sampled the hardest mixed sentences and
under-sampled hard English and Russian ones. Term WER is 6 to 30 times non-term WER (GigaAM highest: its non-term WER is about 2%).

**By TTS** (clean channel, mean over models): error-only WER / term error rate is en Supertonic 0.057 / 0.243,
Kokoro 0.055 / 0.161, Piper 0.108 / 0.363; ru Supertonic 0.081 / 0.309, Piper 0.267 / 0.663; mixed Supertonic
0.146 / 0.508, Piper 0.290 / 0.738. Piper's Russian voices stay out of the primary tables; their tables are
in `reports/tts-piper*.md` and `.cache/ml/roundtrip/mined/full/tts-piper*/` for a deliberate harder mix.

**By channel** (primary): `phone_noisy` adds 0.5-3.4 points of error-only WER per model (whisper-turbo
0.062 → 0.081, parakeet-ultra 0.077 → 0.105, T-one 0.153 → 0.187, whisper-medium 0.072 → 0.077).

**Style** (unchanged in kind, better measured): T-one lowercase, no punctuation, every number and every Latin
term in words/Cyrillic; both Nemotrons and Qwen3-ASR spell 87-100% of numbers; Parakeet TDT now 13% (the 50%
of the probe came from Piper's mixed rows); GigaAM keeps 63-70% of Latin words in mixed sentences (54-59% in
the probe) and writes 30-37% of Latin terms in Cyrillic.

**Coverage and new mangling types.** Distinct terms with a mangling entry 143 → 896 (129 shared, 767 new,
14 probe terms not in the 1,000 sentences); distinct error renderings 900 → 4,294; phrase-level manglings
103 → 495; confusion rows 2,931 → 12,668. Error renderings by type (a rendering can have several), probe →
full: misspelling 40.6% → 44.6%, Latin term written in Cyrillic 27.2% → 22.6%, split into more words
16.8% → 15.8%, acronym garbled 16.3% → 10.6%, phrase merged with a neighbour 7.6% → 7.1%, separator lost
(`.`, `_`, `/`) 6.5% → 5.9%, merged or truncated 1.3% → 3.4%, **mixed-script word** 1.3% → 2.4%
(`Traefik` → `тrфик`, `APK` → `APк`, `.bashrc` → `malыbashk`), **Cyrillic term written in Latin** 0.6% → 1.3%
(`Авито` → `Аvito`, `Т-Банк` → `Тбанk`), term dropped 1.6% → 1.3%, digits lost 4.2% → 0.8% (the probe's
were mostly the TTS's, now excluded by R2), and a type the probe did not have, **digits invented**
0.2% (`Weaviate` → `WeV8`, `vLLM` → `V8 LLM`, `GDPR` → `G2DR`, `Trino` → `3`). Wrong-script output now
also appears for Nemotron (0.5% of rows) and the Parakeets (0.1%), besides Qwen3-ASR (1.5%).

**Twenty term manglings from the full run** (counts over all models; "m" = number of models):

| term | context | errors / seen | most frequent wrong renderings |
|---|---|---:|---|
| отребейзить | ru | 36 / 38 | `отребезить` ×33 (all 10 models) |
| дообучение | ru | 34 / 39 | `до обучения` ×27 (9 m): a real phrase, only context tells |
| прод | ru | 41 / 57 | `прот` ×25 (8 m); phrase `на прод` → `напрод` |
| Авито | ru | 24 / 46 | phrase `с Авито` → `Совета` ×7 (4 m), `савита`, `Савито` |
| LiteLLM | mixed | 29 / 29 | `Light LM` ×5, `Alone` ×3, `Little m` |
| kubectl logs | mixed | 28 / 29 | `Quebec Logs`, `кубек локс`, `Bэck Loк` |
| ls -la | mixed | 27 / 27 | `Лесла` ×3, `ESL` ×3, phrase `ls -la из` → `лес, лайз` |
| llama.cpp | mixed | 27 / 29 | `лама. CPP` ×5, `Лама CPP`, `лама` |
| JNI | mixed | 25 / 28 | `GNI` ×9 (7 m); phrase `JNI и версию` → `GNI-версию` |
| .gitignore | mixed | 25 / 28 | `гитнёр`, `Гетенёра`, `дитигнёр` |
| values.yaml | mixed | 19 / 20 | `Яму` ×3, `Values? Я мол` ×2: the file name split into a question |
| C++ | mixed | 17 / 18 | `Plus`, `set++`, `сеплас` |
| Next.js | mixed | 10 / 10 | `Next` ×4, `Next. Есть` ×3, `Next, если`: the extension heard as Russian words |
| Traefik | mixed | 15 / 20 | `Трифик` ×4, `трейфик`, `Trifik` |
| CI/CD | mixed | 11 / 18 | `CCD` ×6 (6 m), `СИСД`, `с Си Си Ди` |
| pyproject.toml | en | 24 / 24 | `pipreject.toml`, `piproject.toml`, `Pipproject.toml` |
| asyncio | en | 25 / 26 | `Asincio`, `Essencio`, `Asccio` |
| vLLM | en | 14 / 24 | `VLM` ×8 (7 m), `V8 LLM` ×3: digits invented |
| PagerDuty | en | 5 / 16 | `Page your duty`, `Paid your duty`, `Major duty` |
| Weaviate | en / mixed | 7 / 8, 16 / 19 | `V8`, `WeV8`, `Wi V eight`; mixed `WeViet` ×3 |

**Calibration** of the sampler on the full tables (4 repeats, same method as above):

| check (full tables, 4 repeats) | pooled flagged | per-language flagged | above tolerance but within noise |
|---|---:|---:|---:|
| in-sample: 8,878 rows | 1 / 156 | 0 / 87 | 0 |
| held-out: mined on 70% of the probes, compared on 30% | 2 / 155 | 5 / 87 | 1 |
| cross-set, like for like: full tables vs the 80-probe run's Supertonic rows (773) | 4 / 144 | 13 / 87 | 33 |
| cross-set: full tables vs all 1,546 rows of the 80-probe run (half of them Piper, incl. Piper ru) | 32 / 154 | 37 / 87 | 30 |

- In-sample the only flag left is GigaAM-CTC's any-punctuation rate (0.886 → 0.801), as on the 80-probe
  tables. The first in-sample pass also flagged English Nemotron's digit rate (0.000 → 0.118): the sampler
  kept digits inside Latin terms it left in Latin (`Gemini 2.5 Pro`, `GLM-4.5`, `S3`) for a recogniser never
  seen writing a digit. Fixed in `noise.py` (`_fallback_term` spells them for models with `hyp_digit_rate` 0;
  test `test_model_that_never_writes_digits_spells_them_inside_kept_latin_terms`); the table above is after it.
- Held-out: Russian term rates of GigaAM-RNNT, Parakeet Ultra and T-one (simulated term exact 0.10 above the
  held-out rows) and whisper-small's English term rates; sampler gaps 0.00-0.09.
- Cross-set: the median sampler gap of the flagged entries is 0.017 (like for like) and 0.018 (all rows), so
  the sampler reproduces its own tables and the flags are set differences: the 80 probes' English and Russian
  terms were easier (Nemotron EN term error 0.118 real vs 0.260 simulated), their mixed terms harder
  (whisper-turbo 0.463 vs 0.278), and against all 1,546 rows Piper's Russian voices make the real rows harder
  across the board (Parakeet Ultra WER 0.168 vs 0.104).

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

`.cache/ml/roundtrip/venv/bin/python -m pytest -q ml/roundtrip/tests/test_mine_*.py`: 258 passed (about 3 min on the loaded server). They cover:

- **Alignment:** style-only differences; multi-word manglings merged into one translit block; numbers both
  ways, with scale words, units and fraction denominators, and a wrong number; case, ё and separators;
  garbled splits and merges with a Russian neighbour (errors); near against unrelated substitution;
  empty hypothesis or reference; hallucinated tail; term spans and merged spans; determinism.
- **Gold table** (`test_mine_translit_gold.py`): 146 (term, hypothesis, style-or-error) pairs.
- **Mining:** fixture profiles; the WER split; interface fields; null hypotheses skipped; byte-identical
  output; the TTS breakdown; foreign-script output; hardest-terms ranking.
- **Noise:** determinism; style replay; severity; in-sample reproduction of every pooled metric on the
  fixture; the language gate; smoothed term errors on a term seen once; per-language word rates; phrase
  manglings replace the whole phrase and only in their own context; real-word substitutions for LM decoders;
  noise-aware calibration flags; the hub map covers every catalog id.
- **Adapter:** interface fields, deterministic sampling, unstyled references excluded from style statistics.
- **Row selection** (`test_mine_clean_rows.py`): n-gram and character loops, each rule R1-R6 in order,
  whole-clip drops, small clips never judged, the CLI keeps kept lines byte-identical.

### UNVERIFIED and limits

- The final tables rest on 80 probes and two CPU TTS systems; per-language term rates have ±0.1-0.2
  sampling uncertainty (see Calibration). Superseded for stage C by the full-run tables (`samples/mined/full/`, Full run results).
- The fixture hypotheses are hand-written from documented behaviour, not recogniser output.
- The style-vs-error line for transliterations is a rule plus 146 hand-labelled pairs from one reviewer;
  rare forms (Russian ordinals in every case, fractions other than tenths/hundredths/thousandths) may
  still come out as `sub_near` where a human would call them style.
- Wrong-script output is replayed from observed examples only (2 Qwen3-ASR rows); its rate is unreliable.
- The enrich recognisers' exact builds are not known, so those profiles are not attached to any catalog id.
- Proxy profiles (`hub_map.json`, `proxy: true`) assume the NPU/GPU builds err like the CPU int8 graphs;
  Qwen3-ASR 1.7B is represented by the 0.6B model.
<!-- END SECTION: Mining -->
