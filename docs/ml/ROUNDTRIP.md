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

Text -> TTS (several systems and voices) -> optional phone-mic channel -> every ASR model the app
ships, run on the same CPU ONNX graphs the phone uses in CPU mode. Nothing is trained; TTS and ASR
are noise sources for the cleanup LLM's training inputs. Code: `ml/roundtrip/tts/`,
`ml/roundtrip/asr/`, `ml/roundtrip/channel.py`, `ml/roundtrip/run_roundtrip.py`; tests
`ml/roundtrip/tests/test_roundtrip_runner.py`, `ml/roundtrip/tests/test_roundtrip_drivers.py`.

### How to run

```bash
# shared venv .cache/ml/roundtrip/venv (pins in ml/roundtrip/requirements.txt)
P=.cache/ml/roundtrip/venv/bin/python
$P -m ml.roundtrip.asr list                         # registered ASR models and their status
$P -m ml.roundtrip.tts list --systems all           # TTS systems, voices, licences, Latin policy
tools/heavy $P -m ml.roundtrip.asr verify --models all --ru ru.flac --en en.flac --json report.json
tools/heavy $P -m ml.roundtrip.tts say --systems cpu --lang mixed --text "..." --out /tmp/say

# the round trip; resumable: rerun the same command after an interruption
MURMUR_MEM=16G tools/heavy $P -m ml.roundtrip.run_roundtrip \
    --probe ml/roundtrip/probe/seed.jsonl --run local_proof --limit 45 \
    --tts piper,supertonic3,kokoro1 --voices-per-sentence 1 --channels clean --asr all \
    --workers 8 --threads 2 --mem-gb 12

# throughput only: audio-seconds per wall-second for every TTS system and ASR model
tools/heavy $P -m ml.roundtrip.run_roundtrip --measure --probe ml/roundtrip/probe/seed.jsonl \
    --tts cpu --asr all --threads 2 --measure-n 3

$P -m pytest -q ml/roundtrip/tests/test_roundtrip_runner.py ml/roundtrip/tests/test_roundtrip_drivers.py
```

Main flags: `--tts cpu|gpu|all|<ids>`, `--voices <ids>` (also `system/voice`),
`--voices-per-sentence K`, `--channels clean,phone,...`, `--asr all|<ids or unique substrings>`,
`--limit N` (first N probes, round-robin over ru/en/mixed), `--seed`, `--workers`, `--threads`,
`--tts-shard`, `--asr-shard`, `--mem-gb`, `--skip-asr`, `--measure`, `--measure-n`.

Outputs go to `--out` (default `.cache/ml/roundtrip/out`, gitignored): results
`<out>/roundtrip/<run>.jsonl` (interface 2), a report `<out>/roundtrip/<run>.report.json`
(throughput, errors, args of the last invocation) and audio
`<out>/audio/<tts_system>/<voice>/<probe_id>.<channel>.flac` (16 kHz, 16-bit FLAC). `audio` in each
row is relative to `--out`. Passing `--out ml/roundtrip/out` gives the layout named in the
interface, but that directory is not gitignored today, so the default stays in `.cache`.

Each row carries one field beyond interface 2: `tts_input` (`"text"` or `"tts_text"`), which says
whether the TTS read the probe's `text` or its Cyrillic respelling `tts_text`. The reference for
mining is always `text`.

### Matrix, routing, execution

**Matrix.** For each probe and each TTS system the runner samples `--voices-per-sentence` voices
from the voices that speak the probe's language (`random.Random(crc32(seed, probe_id, system))`, so
the same command always picks the same voices). Each (probe, system, voice) is synthesised once
(the `clean` clip, TTS seed shared by all channels); every other `--channels` preset is derived
from that clip with its own seed. Each clip then goes to every selected ASR model that accepts its
language:

| probe lang | ASR models | language hint |
|---|---|---|
| `en` | models that know English (multilingual + distil-large-v3.5) | `en` |
| `ru` | models that know Russian (multilingual + GigaAM rnnt/ctc + T-one) | `ru` |
| `mixed` | same as `ru` (never the English-only distil) | `ru` |

The hint matters for Whisper (forced language) and Nemotron (per-stream `language` option);
Parakeet, GigaAM, T-one and Qwen3-ASR ignore it.

**Execution.** Spawn-context process pools (`--workers`, each with `--threads` ONNX threads;
8 x 2 fills the 16 cores). TTS work goes out in shards of `--tts-shard` sentences (a voice's clean
clip and its channel variants stay in one shard). ASR work goes out in shards of `--asr-shard` clips
for one model and one language hint, interleaved round-robin across models so the slow Whisper
shards start early. Two memory rules came from two OOM kills of the first local run (journal:
`oom-kill` at 24 GB and at 16 GB):

- ASR workers exit after one shard (`max_tasks_per_child=1`). onnxruntime does not give a freed
  model's arena back to the OS (whisper-medium: 3.5 GB peak, still 2.0 GB after `unload()`), so
  long-lived workers that switch models only grow.
- Shards are admitted only while the summed peak RSS of the models in flight stays under
  `--mem-gb` (per-model peaks in `asr/registry.py:MEM_MB`, measured here with `ru_maxrss` over 10
  real clips: whisper-medium 3499 MB, turbo 2985, qwen3-asr 1715, distil 1724, whisper-small 1302,
  parakeet 1183, nemotron 991, t-one 615, gigaam 410). The Whisper driver keeps one recogniser
  (one forced language) at a time; with an en and a ru recogniser both loaded, whisper-medium
  peaked at 4544 MB.

Results are appended and flushed per shard. Resume skips result keys
`(probe_id, tts_system, tts_voice, channel, asr_model)` already in the JSONL and audio files that
exist (a torn last line from a killed run is ignored and re-done). Verified for real: the local run
below was OOM-killed at 837/960 rows and again at 846/960; each rerun of the same command picked
up exactly the missing jobs (`123 pending ASR jobs (837 already done)`, then `114 pending (846
already done)`) and finished at 960 unique keys. A model that cannot load is reported once in the
report's `errors` and skipped; a clip that fails is logged and retried on the next run.

### ASR drivers

One interface (`ml/roundtrip/asr/base.py`): `name` = catalog id, `load()`, `unload()`, `loaded`,
`transcribe(wav_path | 16 kHz float32 samples, lang_hint) -> str` (raw recogniser text, untouched;
loads lazily). All go through the `sherpa-onnx` 2.0.1 Python package. Files are resolved from
`docs/research/asr-catalog.json` (repo, path and 40-char revision per file) and downloaded lazily
into `.cache/ml/roundtrip/models/hf` with a file lock (several workers can ask at once).

| id | sherpa-onnx recogniser | languages | notes |
|---|---|---|---|
| parakeet-tdt-0.6b-v3@onnx-int8 | `OfflineRecognizer.from_transducer` (nemo_transducer) | ru, en | |
| parakeet-ultra-0.6b@onnx-int8 | same | ru, en | a CPU entry in the catalog, so registered too |
| nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms | `OnlineRecognizer.from_transducer`, `stream.set_option("language", hint)` | ru, en | 1.2 s of silence appended to flush the last 560 ms chunk |
| gigaam-v3-e2e-rnnt@onnx-int8 | `OfflineRecognizer.from_transducer` (nemo_transducer) | ru | punctuated e2e export |
| gigaam-v3-e2e-ctc@onnx-int8 | `OfflineRecognizer.from_nemo_ctc` | ru | punctuated e2e export |
| t-one@onnx | `OnlineRecognizer.from_t_one_ctc` | ru | 8 kHz model: we resample 16k -> 8k, pad 0.3 s left (upstream example) and 1.2 s right |
| qwen3-asr-0.6b@onnx-int8 | `OfflineRecognizer.from_qwen3_asr` (max_new_tokens=256) | ru, en | auto language |
| whisper-large-v3-turbo@onnx-int8 | `OfflineRecognizer.from_whisper(language=hint)` | ru, en | one recogniser per forced language, one loaded at a time |
| distil-large-v3.5@onnx-int8 | `from_whisper(language="en")` | en | English-only |
| whisper-small@onnx-int8 | `from_whisper` | ru, en | the catalog has only the SM8750 NPU build (`whisper-small@qnn-sm8750`); the CPU path uses `csukuangfj/sherpa-onnx-whisper-small` @ 8f3c18b3 (int8 files), same OpenAI checkpoint. Not a catalog id. |
| whisper-medium@onnx-int8 | `from_whisper` | ru, en | same situation: `csukuangfj/sherpa-onnx-whisper-medium` @ 8c31d285 |

Catalog entries with no CPU ONNX variant are out of scope here (NPU/GPU only):
`nemotron-3.5-asr-streaming-0.6b@qnn-sm8750-560ms`, `parakeet-tdt-0.6b-v3@qnn-sm8750-20s`,
`nemotron-speech-streaming-en-0.6b@qnn-sm8750-560ms` (English Nemotron has no CPU entry),
`whisper-small@qnn-sm8750`, `whisper-medium@qnn-sm8750`, `whisper-large-v3-turbo@qaihub-sm8750-w8a16`,
`qwen3-asr-0.6b@litert-npu-sm8750`, `qwen3-asr-1.7b@litert-gpu-15s`.

Verification (`python -m ml.roundtrip.asr verify`, 4 threads, through tools/heavy, saved in
`ml/roundtrip/samples/roundtrip/asr_verify.json`): **all 11 models loaded and transcribed both a
Russian and an English clip on this machine**, and all 11 then ran in the 960-row local round trip
with 0 errors. None had to be registered as unavailable; the `UnavailableDriver` path (model stays
registered, `load()` raises with the reason, the runner reports it once) is unit-tested.

### TTS drivers

One interface (`ml/roundtrip/tts/base.py`): system id, `voices()` (no model load), `load()`,
`prepare(voice)`, `unload()`, `synthesize(text, voice, lang, seed) -> 16 kHz mono float32`
(peak-normalised to 0.9), `synthesize_batch(items)` (GPU drivers batch, CPU loops). Each voice
declares `latin_in_ru`: `respell` voices read the probe's `tts_text` for ru/mixed probes when it
exists, `native` voices read `text` as written.

| system | engine | voices used | languages | licence (checked 2026-11-18) | Latin words in a Russian sentence |
|---|---|---|---|---|---|
| `piper` | `piper-tts` 1.8.0 (VITS, espeak-ng phonemiser), voices from `rhasspy/piper-voices` @ 0ca208af | ru: denis, dmitri (medium); en: joe, john, kristin, norman, alba, cori (medium), libritts_r medium speakers 0, 117, 263, 509, 771 | ru or en per voice | per MODEL_CARD: denis, dmitri, joe CC0; john, kristin, norman public domain; alba, cori, libritts_r CC-BY-4.0 | espeak-ng switches to English phonemes the Russian voices never saw, which garbles the term and its neighbours: the ru clip in `asr_verify.json` was read from `text` ("Созвон с Laura по Kubernetes...") and came back as "Создался покупан эти..." from Parakeet. So `respell`: mixed probes use `tts_text` |
| `supertonic3` | sherpa-onnx `OfflineTts` Supertonic, archive `sherpa-onnx-supertonic-3-tts-int8-2026-05-11` (k2-fsa `tts-models` release), 8 flow steps | sid0..sid9 | one multilingual model, `lang` per call | code MIT; **model weights BigScience OpenRAIL-M** (`Supertone/supertonic-3` LICENSE @ 3cadd1ee): commercial use allowed, with the use-based restrictions of Attachment A. Synthesising training audio is not a restricted use | character-level model; under `lang=ru` it reads Latin words with a Russian accent, recognisably ("Laura", "Kubernetes" came back as "Слаура", "Кубернец"). `native`: reads `text` |
| `kokoro1` | sherpa-onnx `OfflineTts` Kokoro, archive `kokoro-multi-lang-v1_0` (hexgrad/Kokoro-82M) | 28 English voices af_*, am_*, bf_*, bm_* (speaker ids 0-27, checked against the model's `id2speaker` metadata) | en only (no Russian voice) | Apache-2.0 (archive LICENSE) | not used for ru/mixed. Text is phonemised with espeak-ng (the piper-tts phonemiser) and passed as phoneme code points, because sherpa's lexicon path drops unknown words: verified here, `OOV word skipped: 'Kubernetes'`, `'10'`, `'30'` |
| `qwen3tts-1.7b` (GPU) | `qwen-tts` 0.1.1, `Qwen/Qwen3-TTS-12Hz-1.7B-Base` @ fd4b2543 | one voice per reference clip | ru, en (`language="Russian"/"English"`) | package Apache-2.0 (wheel METADATA), model apache-2.0 (HF API) | `native` |
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
  2-20 s are accepted; others are listed as rejected with the reason. Nothing is downloaded.
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

### Local proof (2026-11-18)

`ml/roundtrip/samples/roundtrip/local_proof.jsonl`: 45 probes from `probe/seed.jsonl` (15 ru, 15 en,
15 mixed) x {piper, supertonic3, kokoro1 (en only)} x 1 voice per sentence x `clean` x every
language-compatible ASR model = 105 clips (539 s of audio), **960 rows, all 11 ASR models, 0
errors**. `ml/roundtrip/samples/roundtrip/channel_demo.jsonl`: the first 12 probes again through
the `phone` preset (28 clips x language-compatible models = 256 rows, 0 errors). On those 12
probes the word error rate over all models went from 0.237 (clean) to 0.245 (phone): the `phone`
preset at 25 dB SNR is mild; `phone_noisy` (12 dB) is the one to use for robustness data.

Quick sanity numbers (word error rate after lower-casing and stripping punctuation, and the share
of probe terms that appear verbatim in the hypothesis; the mining stage computes the real
profiles). Over all three TTS systems; the term columns give the share and, in brackets, the
number of term occurrences; `x realtime` is audio-s per wall-s of the model's calls inside the
8-worker run (contended, so lower than `--measure`):

| ASR model | WER en | WER ru | WER mixed | term exact en | term exact ru | term exact mixed | x realtime |
|---|---|---|---|---|---|---|---|
| gigaam-v3-e2e-ctc@onnx-int8 | - | 0.16 | 0.31 | - | 0.40 (68) | 0.07 (68) | 2.59 |
| gigaam-v3-e2e-rnnt@onnx-int8 | - | 0.13 | 0.31 | - | 0.49 (68) | 0.09 (68) | 2.14 |
| parakeet-ultra-0.6b@onnx-int8 | 0.08 | 0.16 | 0.30 | 0.69 (87) | 0.51 (68) | 0.22 (68) | 1.43 |
| t-one@onnx | - | 0.24 | 0.44 | - | 0.43 (68) | 0.01 (68) | 1.48 |
| parakeet-tdt-0.6b-v3@onnx-int8 | 0.09 | 0.19 | 0.31 | 0.71 (87) | 0.44 (68) | 0.19 (68) | 1.47 |
| nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms | 0.17 | 0.23 | 0.40 | 0.33 (87) | 0.49 (68) | 0.06 (68) | 0.46 |
| qwen3-asr-0.6b@onnx-int8 | 0.13 | 0.28 | 0.45 | 0.57 (87) | 0.29 (68) | 0.07 (68) | 0.39 |
| whisper-small@onnx-int8 | 0.12 | 0.22 | 0.38 | 0.59 (87) | 0.41 (68) | 0.15 (68) | 0.36 |
| distil-large-v3.5@onnx-int8 | 0.09 | - | - | 0.62 (87) | - | - | 0.26 |
| whisper-large-v3-turbo@onnx-int8 | 0.08 | 0.16 | 0.26 | 0.69 (87) | 0.57 (68) | 0.34 (68) | 0.26 |
| whisper-medium@onnx-int8 | 0.10 | 0.19 | 0.31 | 0.69 (87) | 0.50 (68) | 0.29 (68) | 0.18 |

By TTS system, WER over all models: supertonic3 en 0.07 / ru 0.13 / mixed 0.27; kokoro1 en 0.09;
piper en 0.16 / ru 0.26 / mixed 0.43 (mixed read from `tts_text`). Piper's two Russian voices are
clearly the harder input.

### Throughput

`--measure` (saved as `ml/roundtrip/samples/roundtrip/measure.json`; one process, 2 threads, 3 probes per language, one voice per system, including each
model's first call) on 2026-11-18 22:35-22:45, **with the server heavily shared (load average
21-36 on 16 cores from other workloads)**, so these are pessimistic. The `verify` column is the
earlier 4-thread single-clip check from `asr_verify.json` (~6 s clips, lighter load).

| stage | system | x realtime, `--measure` (2 thr, loaded) | x realtime, `verify` (4 thr) |
|---|---|---|---|
| tts | piper | 7.97 | - |
| tts | supertonic3 | 2.15 | - |
| tts | kokoro1 | 0.87 | - |
| asr | parakeet-tdt-0.6b-v3@onnx-int8 | 4.73 | 19.6 ru/18.3 en |
| asr | parakeet-ultra-0.6b@onnx-int8 | 4.12 | 20.0 ru/19.2 en |
| asr | nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms | 1.19 | 5.5 ru/5.7 en |
| asr | gigaam-v3-e2e-rnnt@onnx-int8 | 4.94 | 29.6 ru |
| asr | gigaam-v3-e2e-ctc@onnx-int8 | 6.64 | 30.3 ru |
| asr | t-one@onnx | 2.72 | 16.2 ru |
| asr | qwen3-asr-0.6b@onnx-int8 | 0.77 | 3.1 ru/4.4 en |
| asr | whisper-small@onnx-int8 | 0.53 | 2.1 ru/1.8 en |
| asr | whisper-medium@onnx-int8 | 0.35 | 0.6 ru/0.5 en |
| asr | whisper-large-v3-turbo@onnx-int8 | 0.63 | 1.9 ru/1.4 en |
| asr | distil-large-v3.5@onnx-int8 | 0.45 | 2.9 en |

Whole-run figure: the 960-row local proof transcribed 4936 audio-seconds in 11274 worker-seconds
of ASR calls; with 8 workers that is about 3.5 audio-s per wall-s on this loaded machine. Whisper
models dominate: in the final resume, whisper-medium alone used 1798 of the 874 s x 8 worker-seconds. For pod sizing:
these are CPU numbers for the CPU graphs; the GPU TTS systems are UNVERIFIED (above).

### Fifteen examples (local proof, raw hypotheses)

| lang | TTS | ASR | text | hypothesis |
|---|---|---|---|---|
| mixed | supertonic3/sid5 | nemotron-3.5 streaming | Созвон с Laura по Kubernetes перенесли на 10:30, ссылку кину в Slack. | Со звон Слаура по Кубернец перенесли на 1030, ссылку кину в слак |
| mixed | supertonic3/sid3 | parakeet-tdt-v3 | Greenplum не тянет такие запросы, предлагаю вынести аналитику в ClickHouse. | Гримплум не тянет такие запросы, предлагаю вынести аналитику в Клыкхаус. |
| mixed | piper/denis (tts_text) | gigaam-v3 rnnt | Обучение через LoRA на H100 заняло двадцать минут, eval loss вышел ниже, чем у прошлого запуска. | Обучение через Лора нашто заняло 20 минут. Эволос вышел ниже, чем у прошлого запуска. |
| mixed | piper/denis (tts_text) | whisper-small | Кто трогал values.yaml в Helm chart бэкенда? После деплоя поды не стартуют. | Кто трогал вылезьям в Хомчак Бекенда? После диплора падыни стартуют |
| mixed | supertonic3/sid8 | gigaam-v3 ctc | Переехали с Heroku на DigitalOcean, деплой стал дольше, зато счёт меньше вдвое. | Переехали с Herok на Digitalotion, деplй стал дольше, зато счёт меньше вдвое |
| mixed | supertonic3/sid1 | gigaam-v3 rnnt | Кто-нибудь пробовал SGLang? Говорят, structured output там быстрее, чем в vLLM. | Кто-нибудь пробовал SGLANG? Говорят, strocchurd Out путь там быстрее, чем в |
| mixed | supertonic3/sid6 | whisper-medium | Kafka отстаёт на миллион сообщений, консьюмеры не успевают, добавляю партиции. | Кавка отстает на миллион сообщений. Консюмеры не успевают. Добавляю партиции. |
| en | supertonic3/sid5 | whisper-large-v3-turbo | Anyone tried SGLang instead of vLLM? I heard structured decoding is faster there. | Anyone tried SG Lang instead of VLM? I heard structured decoding is faster there. |
| en | piper/libritts_r#117 | whisper-large-v3-turbo | The Jupyter server on the shared box is using sixty gigabytes of RAM again. | The Dubbiter server on the shared box is using 60GB of our RAM again. |
| en | supertonic3/sid3 | parakeet-tdt-v3 | Can someone with IAM admin rights give me read access to the S3 bucket? | Can someone with I am admin write give me red access to the S3 bucket? |
| en | kokoro1/am_liam | nemotron-3.5 streaming | Linear tickets are now synced with GitHub, so please stop updating Jira. | Your tickets are now synced with GitHub, so please stop updating Gira |
| en | supertonic3/sid0 | qwen3-asr-0.6b | Sentry is flooding us with the same JWT expiry error from the mobile app. | Century is flooding us with the same JWT expiry error from the mobile app. |
| ru | supertonic3/sid3 | whisper-medium | Слушай, а как у вас в Яндексе устроен онбординг для джунов? | Слушай, а как у вас в Яндексе устроен анбординг для женов? |
| ru | piper/dmitri | parakeet-ultra | Скинь мне конфиг от воркера, у меня локально всё падает с таймаутом. | В скин мне конфликт от вакера у меня локально все падает с Темлтом. |
| ru | supertonic3/sid3 | whisper-large-v3-turbo | Кинь, пожалуйста, ссылку на дашборд, хочу посмотреть, что там с лейтенси после вчерашнего. | Кинь, пожалуйста, ссылку на дашборд. Хочу посмотреть, что там с latency после вчерашнего. |

What these show: Latin terms in Russian speech come back Cyrillised (Кавка, Клыкхаус, Слаура),
split (SG Lang, Со звон), or as half-Latin hybrids (`деplй`, `Herok`); English homophones replace
acronyms and brands (IAM -> "I am", Sentry -> Century, Jira -> Gira); Russian IT slang turns into
ordinary words (джунов -> женов, конфиг -> конфликт); words are dropped at the end of streaming
and CTC output (`чем в` with vLLM gone); and some models write the English word where the speaker
used Cyrillic slang (лейтенси -> latency), which the cleanup model must not "fix" back blindly.
Numbers flip between digits and words in both directions (двадцать -> 20, 10:30 -> 1030).

### Disk

Under `.cache/ml/roundtrip/` (2026-11-18, end of the work): models 7.5 GB (HF ASR + Piper files 7.0 GB: whisper-turbo 989 MB, qwen3-asr 942, distil 939, whisper-medium 903, nemotron 651, parakeet v3 640, parakeet ultra 640, piper voices 438, whisper-small 359, gigaam rnnt 222, gigaam ctc 215, t-one 138; sherpa TTS archives 523 MB), audio and results 15 MB, shared venv 612 MB. Within the 14 GB budget. No GPU wheels or torch are installed here.

### UNVERIFIED / limits

- GPU TTS (Qwen3-TTS, Chatterbox): see above; only the call shapes are tested, with fakes.
- Throughput was measured on a shared, heavily loaded server; expect better numbers on an idle box.
- Supertonic's Russian accent on Latin words and Piper's handling of `tts_text` respellings are
  judged only through the ASR outputs above, not by listening.
- The phone channel presets are plausible engineering choices, not fitted to recordings from the
  Fold7 microphone; fitting them needs the device (see the DEVICE line in the orchestrator report).
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
