# Error mining — real round trip (837 rows, TTS -> ASR over the probe set)

Rows mined: 837 (skipped with null hypothesis: 0). Sources: rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 45 | 8.3% | 5.6% | 3.1% | 20.4% | 3.1% | 62.1% | sentence | full | unknown | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 60 | 23.0% | 19.0% | 13.1% | 73.2% | 5.5% | 22.1% | sentence | partial | digits | cyrillic | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 60 | 21.6% | 17.6% | 12.9% | 65.5% | 5.6% | 27.9% | sentence | full | digits | cyrillic | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 105 | 24.4% | 18.9% | 12.5% | 58.3% | 10.6% | 27.4% | sentence | partial | mixed | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 105 | 17.3% | 13.5% | 8.7% | 48.1% | 6.3% | 46.6% | sentence | full | digits | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 105 | 15.3% | 12.1% | 7.9% | 40.8% | 6.0% | 48.9% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 105 | 25.1% | 19.7% | 12.3% | 61.7% | 10.8% | 31.4% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 60 | 33.4% | 25.5% | 17.6% | 75.3% | 13.0% | 21.3% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 72 | 14.3% | 11.5% | 8.1% | 33.1% | 6.2% | 56.8% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 24 | 18.4% | 15.7% | 10.1% | 46.5% | 7.7% | 51.6% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 96 | 21.0% | 16.6% | 10.3% | 45.4% | 10.4% | 40.7% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 45 | 8.3% | 5.6% | 3.1% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 30 | 31.0% | 23.7% | 20.3% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 30 | 15.5% | 14.7% | 5.4% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 30 | 31.3% | 24.3% | 20.3% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 30 | 12.5% | 11.4% | 5.1% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 45 | 17.0% | 14.2% | 7.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 30 | 39.8% | 26.0% | 25.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 30 | 23.1% | 20.4% | 7.4% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 45 | 9.3% | 6.2% | 3.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 30 | 31.0% | 23.4% | 18.9% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 30 | 18.5% | 17.1% | 7.0% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 45 | 7.9% | 5.6% | 3.2% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 30 | 28.9% | 21.3% | 17.9% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 30 | 15.5% | 14.7% | 5.5% |
| `qwen3-asr-0.6b@onnx-int8` | en | 45 | 12.6% | 7.8% | 3.5% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 30 | 45.0% | 35.4% | 27.5% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 30 | 28.3% | 25.8% | 11.2% |
| `t-one@onnx` | mixed | 30 | 43.9% | 33.3% | 27.5% |
| `t-one@onnx` | ru | 30 | 23.6% | 18.2% | 7.2% |
| `whisper-large-v3-turbo@onnx-int8` | en | 30 | 4.3% | 3.4% | 1.4% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 20 | 28.3% | 20.0% | 18.6% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 22 | 17.5% | 16.4% | 8.3% |
| `whisper-medium@onnx-int8` | en | 10 | 9.4% | 8.1% | 3.4% |
| `whisper-medium@onnx-int8` | mixed | 6 | 41.2% | 32.5% | 27.5% |
| `whisper-medium@onnx-int8` | ru | 8 | 14.0% | 14.0% | 5.6% |
| `whisper-small@onnx-int8` | en | 42 | 11.3% | 6.9% | 4.3% |
| `whisper-small@onnx-int8` | mixed | 26 | 38.8% | 31.3% | 20.5% |
| `whisper-small@onnx-int8` | ru | 28 | 22.2% | 20.5% | 10.2% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 97.8% | 97.8% | 87.5% | 97.8% | 97.8% | 140.0% | – | 99.5% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 100.0% | 100.0% | 96.7% | 33.3% | 77.0% | 100.0% | 20.0% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 100.0% | 100.0% | 98.3% | 70.0% | 100.0% | 20.0% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 97.1% | 97.1% | 78.1% | 79.0% | 15.2% | 80.0% | 50.0% | 95.0% | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 94.7% | 100.0% | 100.0% | 87.8% | 100.0% | 96.9% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 100.0% | 100.0% | 92.2% | 100.0% | 100.0% | 89.6% | 100.0% | 97.1% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 79.0% | 100.0% | 100.0% | 72.2% | 0.0% | 96.1% | 9.1% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 98.6% | 97.2% | 97.3% | 97.2% | 83.3% | 85.4% | 100.0% | 97.7% | 31.2% |
| `whisper-medium@onnx-int8` | 100.0% | 95.8% | 100.0% | 100.0% | 87.5% | 107.7% | 100.0% | 96.6% | 16.7% |
| `whisper-small@onnx-int8` | 97.9% | 96.9% | 88.8% | 97.9% | 85.4% | 83.5% | 100.0% | 97.5% | 33.3% |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (19.5% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Memcached | en | 3 | 0.0% | Memcatch ×2; Memcache ×1 |
| MinIO | en | 3 | 0.0% | Min-IO ×1; min-i-o ×1; minio ×1 |
| OpenRouter | en | 3 | 0.0% | open router ×3 |
| SGLang | en | 3 | 0.0% | SG-Lang ×3 |
| Sentry | en | 3 | 0.0% | Century ×2; Centry ×1 |
| vLLM | en | 3 | 0.0% | VLM ×2; VLLM ×1 |
| Datadog | en | 3 | 33.3% | DataDog ×2; Datadog ×1 |
| Elasticsearch | en | 3 | 33.3% | elastic search ×2; Elasticsearch ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | a | 1 |
| sub | admin | and | 1 |
| del | app | ∅ | 1 |
| sub_near | check | checked | 1 |
| sub_near | endpoint | and point | 1 |
| sub_near | endpoint | in point | 1 |
| sub_near | flaky | flick he | 1 |
| sub | is | flare's | 1 |

## `gigaam-v3-e2e-ctc@onnx-int8`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 77.0% of reference)
- spell out again numbers the writer wanted in words (57.1% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (80.0% of Latin words)
- repair garbled terms (61.8% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| ClickHouse | mixed | 2 | 0.0% | Clkouс ×1; клипхаус ×1 |
| Cloudflare | mixed | 2 | 0.0% | Клаудфлер ×1; Клаучлер ×1 |
| DigitalOcean | mixed | 2 | 0.0% | Digitalotion ×1; дидже Талошен ×1 |
| Elasticsearch | mixed | 2 | 0.0% | Илаaстиксерч ×1; Эластич ×1 |
| Greenplum | mixed | 2 | 0.0% | Гримплум ×1; Гринплум ×1 |
| H100 | mixed | 2 | 0.0% | хитсад ×1; что ×1 |
| Helm chart | mixed | 2 | 0.0% | Хелм Шарт ×1; хумчап ×1 |
| Heroku | mixed | 2 | 0.0% | Herok ×1; Хероку ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 3 |
| ins | ∅ | биджа | 1 |
| sub_near | добавляю | добавляют | 1 |
| sub_near | добавляю | добавляя | 1 |
| sub_near | задачу | задачи | 1 |
| sub_near | консьюмеры | и консьермеры | 1 |
| sub_near | копятся | копяться | 1 |
| sub_near | на | наш | 1 |

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (50.0% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (80.0% of Latin words)
- repair garbled terms (55.1% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| ClickHouse | mixed | 2 | 0.0% | Кликхаус ×1; клипхаус ×1 |
| Cloudflare | mixed | 2 | 0.0% | Cloud Fler ×1; Клаудлер ×1 |
| DigitalOcean | mixed | 2 | 0.0% | Digital Otion ×1; Tolotion ×1 |
| Elasticsearch | mixed | 2 | 0.0% | Эластик-серч ×1; Эластича ×1 |
| Greenplum | mixed | 2 | 0.0% | Гримплум ×1; Гринпум ×1 |
| H100 | mixed | 2 | 0.0% | нашто ×1; хитсад ×1 |
| Helm chart | mixed | 2 | 0.0% | Хельм Шард ×1; Хумчак ×1 |
| Heroku | mixed | 2 | 0.0% | Хероку ×1; Херроку ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 2 |
| sub_near | ноде | ноте | 2 |
| sub_near | сессий | сессии | 2 |
| ins | ∅ | dj | 1 |
| ins | ∅ | пиджа | 1 |
| sub | в | ям | 1 |
| sub_near | вебхуки | вебку | 1 |
| sub_near | добавляю | добавляют | 1 |

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 78.1%)
- restore missing punctuation (commas at 80.0% of reference)
- convert spoken numbers, times and dates to digits (50.0% spelled out)
- repair garbled terms (48.0% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Cloudflare | en | 3 | 0.0% | Cloud Flare ×1; cloud ×1; cloudflar ×1 |
| Elasticsearch | en | 3 | 0.0% | Elastic search ×2; elastic search ×1 |
| GitHub Actions | en | 3 | 0.0% | GitHub actions ×3 |
| Helm chart | en | 3 | 0.0% | helm chart ×3 |
| Jupyter | en | 3 | 0.0% | Jupiter ×2; ∅ ×1 |
| Memcached | en | 3 | 0.0% | Memcach ×1; Memcage ×1; Memcatch ×1 |
| MinIO | en | 3 | 0.0% | Min Io ×1; Minyo ×1; men I ×1 |
| OpenRouter | en | 3 | 0.0% | open router ×3 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | the | ∅ | 6 |
| sub_near | cache | cash | 3 |
| del | в | ∅ | 3 |
| del | can | ∅ | 2 |
| sub_near | disk | disc | 2 |
| sub_near | endpoint | in point | 2 |
| sub | rights | writes | 2 |
| del | we | ∅ | 2 |

Number style examples: 10:30 → 1030; 10:30 → десять тридцать

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (55.6% of spelled-out numbers come back as digits)
- repair garbled terms (37.2% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Memcached | en | 3 | 0.0% | Memcatch ×2; Memcage ×1 |
| MinIO | en | 3 | 0.0% | Min IO ×2; Minio ×1 |
| OpenRouter | en | 3 | 0.0% | open router ×3 |
| vLLM | en | 3 | 0.0% | VLM ×2; V L M ×1 |
| ClickHouse | mixed | 2 | 0.0% | ClipHouse ×1; Клыкхаус ×1 |
| Cloudflare | mixed | 2 | 0.0% | Клаудфлер ×1; Клаутлер ×1 |
| Greenplum | mixed | 2 | 0.0% | Гримплум ×1; Гринплом ×1 |
| H100 | mixed | 2 | 0.0% | Хитсат ×1; что ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | endpoint | in point | 2 |
| sub | gigabytes | 60gb | 2 |
| del | sixty | ∅ | 2 |
| sub_near | консьюмеры | консюмеры | 2 |
| sub_near | сессий | сессии | 2 |
| ins | ∅ | a | 1 |
| ins | ∅ | very | 1 |
| sub | admin | and | 1 |

Number style examples: 10:30 → 1030

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (66.7% of spelled-out numbers come back as digits)
- repair garbled terms (35.4% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Memcached | en | 3 | 0.0% | Memcatch ×2; Memcage ×1 |
| MinIO | en | 3 | 0.0% | Min IO ×1; Minio ×1; min I.O ×1 |
| OpenRouter | en | 3 | 0.0% | open router ×3 |
| vLLM | en | 3 | 0.0% | VLLM ×2; VLM ×1 |
| ClickHouse | mixed | 2 | 0.0% | Clickhaus ×1; Cliphaus ×1 |
| DigitalOcean | mixed | 2 | 0.0% | Digital Ocean ×2 |
| Greenplum | mixed | 2 | 0.0% | Гримплум ×1; Гринплом ×1 |
| H100 | mixed | 2 | 0.0% | Хитсат ×1; что ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | endpoint | in point | 2 |
| sub | gigabytes | 60gb | 2 |
| del | sixty | ∅ | 2 |
| sub_near | консьюмеры | консюмеры | 2 |
| sub_near | сессий | сессии | 2 |
| ins | ∅ | a | 1 |
| ins | ∅ | very | 1 |
| sub | admin | and | 1 |

Number style examples: 10:30 → 10.30; 10:30 → 1030

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 79.0%)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (47.5% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Elasticsearch | en | 3 | 0.0% | elastic search ×2; Elastix search ×1 |
| MinIO | en | 3 | 0.0% | min IO ×2; minion ×1 |
| OpenRouter | en | 3 | 0.0% | open router ×3 |
| PagerDuty | en | 3 | 0.0% | Judy ×1; Pager Duty ×1; Paternity ×1 |
| S3 | en | 3 | 0.0% | S three ×3 |
| vLLM | en | 3 | 0.0% | V L L M ×1; VLLM ×1; VLM ×1 |
| Cloudflare | mixed | 2 | 0.0% | Клод Флэр ×1; Плайотъля ×1 |
| DigitalOcean | mixed | 2 | 0.0% | Digital Ocean ×1; Диджит Аллоушен ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | endpoint | in point | 2 |
| ins | ∅ | с | 2 |
| sub_near | посоветуешь | посоветуй | 2 |
| ins | ∅ | a | 1 |
| ins | ∅ | of | 1 |
| ins | ∅ | peggy | 1 |
| sub | admin | and | 1 |
| sub | are | your | 1 |

Number style examples: 10:30 → десять тридцать

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)
- repair garbled terms (58.8% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| ClickHouse | mixed | 2 | 0.0% | клик хаус ×1; клыкаус ×1 |
| Cloudflare | mixed | 2 | 0.0% | клауд флеер ×1; плавацеля ×1 |
| DigitalOcean | mixed | 2 | 0.0% | диджита лоушен ×1; диджита лоушин ×1 |
| Elasticsearch | mixed | 2 | 0.0% | и ластик сэрч ×1; эластитеча ×1 |
| Greenplum | mixed | 2 | 0.0% | гринь плам ×1; римплом ×1 |
| H100 | mixed | 2 | 0.0% | хитсад ×1; что ×1 |
| Helm chart | mixed | 2 | 0.0% | мо чар ×1; ям хомчак ×1 |
| Heroku | mixed | 2 | 0.0% | сероку ×1; хэрокку ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | вебхуки | вепхуки | 2 |
| sub_near | сессий | сессии | 2 |
| del | в | ∅ | 2 |
| ins | ∅ | в | 1 |
| ins | ∅ | и | 1 |
| ins | ∅ | пиджет | 1 |
| ins | ∅ | радел | 1 |
| ins | ∅ | у | 1 |

Number style examples: 10:30 → десять тридцать

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (28.4% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| SGLang | en | 3 | 0.0% | SG Lang ×1; SG lang ×1; sglang ×1 |
| vLLM | en | 3 | 0.0% | VLLM ×1; VLM ×1; vllm ×1 |
| Elasticsearch | mixed | 2 | 0.0% | Elastic Search ×1; Эластича ×1 |
| H100 | mixed | 2 | 0.0% | Хитсад ×1; что ×1 |
| Kubernetes | mixed | 2 | 0.0% | кубернец ×1; кубернити ×1 |
| Laura | mixed | 2 | 0.0% | Лаура ×1; лорой ×1 |
| LoRA | mixed | 2 | 0.0% | Лора ×1; Лоран ×1 |
| Redis | mixed | 2 | 0.0% | Рейдис ×1; редис ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | сессий | сессии | 2 |
| ins | ∅ | a | 1 |
| sub | admin | and | 1 |
| sub_near | endpoint | and point | 1 |
| sub_near | endpoint | in point | 1 |
| del | read | ∅ | 1 |
| sub_near | read | red | 1 |
| sub_near | state | stake | 1 |

Number style examples: 10:30 → 10.30

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (40.6% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| H100 | mixed | 2 | 0.0% | Hitsat ×1; то ×1 |
| Kubernetes | mixed | 2 | 0.0% | Кубернетс ×1; кубернетис ×1 |
| Laura | mixed | 2 | 0.0% | Лаура ×1; лорой ×1 |
| LoRA | mixed | 2 | 0.0% | Лоран ×1; лора ×1 |
| eval loss | mixed | 2 | 0.0% | и волос ×2 |
| values.yaml | mixed | 2 | 0.0% | Values? Яму ×1; вы ×1 |
| бэкенда | mixed | 2 | 0.0% | Бекенда ×1; бекенда ×1 |
| деплоя | mixed | 2 | 0.0% | диплоя ×1; теплоя ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | endpoint | in point | 2 |
| sub_near | поды | пады | 2 |
| sub | опять | 5 | 2 |
| ins | ∅ | a | 1 |
| del | app | ∅ | 1 |
| sub | is | flares | 1 |
| sub | is | who's | 1 |
| sub_near | state | stake | 1 |

Number style examples: 10:30 → 10.30

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (57.1% of spelled-out numbers come back as digits)
- repair garbled terms (38.7% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Elasticsearch | en | 3 | 0.0% | elastic search ×2; Elastic Search ×1 |
| MinIO | en | 3 | 0.0% | Min I/O ×2; Minio ×1 |
| OpenRouter | en | 3 | 0.0% | open router ×3 |
| PagerDuty | en | 3 | 0.0% | Pager duty ×1; Paker duty ×1; duty ×1 |
| SGLang | en | 3 | 0.0% | SG Lang ×3 |
| vLLM | en | 3 | 0.0% | VLLM ×2; VLM ×1 |
| ClickHouse | mixed | 2 | 0.0% | Clickhouse ×2 |
| Elasticsearch | mixed | 2 | 0.0% | Elastic Search ×1; властиться ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | gigabytes | 60gb | 3 |
| del | sixty | ∅ | 3 |
| del | в | ∅ | 2 |
| sub_near | консьюмеры | консюмеры | 2 |
| sub_near | скинь | скин | 2 |
| ins | ∅ | jid | 1 |
| sub | admin | and | 1 |
| del | app | ∅ | 1 |

Number style examples: 10:30 → 10,30; 10:30 → 10.30
