# Error mining — hand-made fixture (not real ASR output)

Rows mined: 140 (skipped with null hypothesis: 0). Sources: fixture_roundtrip.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 18 | 21.4% | 1.2% | 19.1% | 3.6% | 0.0% | 46.0% | sentence | full | digits | cyrillic | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 26 | 28.4% | 5.9% | 23.1% | 18.7% | 1.0% | 40.9% | sentence | full | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 26 | 34.3% | 4.4% | 29.8% | 14.7% | 0.5% | 40.9% | sentence | full | words | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 26 | 8.1% | 4.4% | 1.6% | 14.7% | 0.5% | 74.2% | sentence | full | digits | latin | 0.0% |
| `t-one@onnx` | 18 | 38.7% | 4.0% | 33.6% | 10.9% | 0.9% | 26.0% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 26 | 9.6% | 8.1% | 9.1% | 10.7% | 7.1% | 79.0% | sentence | full | digits | latin | 11.5% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 9 | 39.8% | 2.3% | 37.5% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 9 | 2.4% | 0.0% | 2.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 8 | 21.4% | 5.1% | 12.4% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 9 | 40.9% | 6.8% | 39.5% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 9 | 23.5% | 5.9% | 18.3% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 8 | 22.4% | 4.1% | 17.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 9 | 55.7% | 4.5% | 53.2% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 9 | 25.9% | 4.7% | 20.4% |
| `qwen3-asr-0.6b@onnx-int8` | en | 8 | 1.0% | 1.0% | 0.2% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 9 | 3.4% | 1.1% | 1.8% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 9 | 21.2% | 11.8% | 2.8% |
| `t-one@onnx` | mixed | 9 | 51.1% | 2.3% | 50.9% |
| `t-one@onnx` | ru | 9 | 25.9% | 5.9% | 17.8% |
| `whisper-large-v3-turbo@onnx-int8` | en | 8 | 5.1% | 5.1% | 4.7% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 9 | 13.6% | 12.5% | 16.5% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 9 | 10.6% | 7.1% | 6.4% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | – | 100.0% | 100.0% | 110.0% | 100.0% | 0.0% | 60.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 79.0% | 96.2% | 96.2% | 100.0% | 5.9% | 84.2% | 60.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 69.2% | 100.0% | 100.0% | 100.0% | 0.0% | 76.1% | 60.0% |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 94.1% | 99.2% | 50.0% |
| `t-one@onnx` | 0.0% | 0.0% | – | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 92.3% | 100.0% | 92.3% | 100.0% | 93.8% | 99.2% | 25.0% |

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| API | mixed | 1 | 0.0% | АПИ ×1 |
| CI/CD | mixed | 1 | 0.0% | сиайсиди ×1 |
| ClickHouse | mixed | 1 | 0.0% | КликХаус ×1 |
| GitHub | mixed | 1 | 0.0% | гитхабе ×1 |
| Grafana | mixed | 1 | 0.0% | Графаны ×1 |
| H100 | mixed | 1 | 0.0% | аш сто ×1 |
| JAX | mixed | 1 | 0.0% | Джакс ×1 |
| Kubernetes | mixed | 1 | 0.0% | кубернетису ×1 |

Number style examples: 128 тысяч → 128 000

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 79.0%)
- convert spoken numbers, times and dates to digits (94.1% spelled out)
- repair garbled terms (19.7% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| CI/CD | mixed | 1 | 0.0% | CI CD ×1 |
| ClickHouse | mixed | 1 | 0.0% | Клик Хаус ×1 |
| GitHub Actions | en | 1 | 0.0% | GitHub actions ×1 |
| Grafana | mixed | 1 | 0.0% | Графаны ×1 |
| H100 | mixed | 1 | 0.0% | H сто ×1 |
| Helm chart | en | 1 | 0.0% | home chart ×1 |
| JAX | mixed | 1 | 0.0% | Jax ×1 |
| Kafka | en | 1 | 0.0% | cafe ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | sync | sink | 1 |
| sub | мс | миллисекунд | 1 |

Number style examples: 10:30 → десять тридцать; 0,35 → ноль тридцать пять; 1.2 → one point two; 12 → двенадцать

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 69.2%)
- convert spoken numbers, times and dates to digits (100.0% spelled out)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| API | mixed | 1 | 0.0% | эй пи ай ×1 |
| CI/CD | mixed | 1 | 0.0% | си ай си ди ×1 |
| ClickHouse | mixed | 1 | 0.0% | клик хаус ×1 |
| GitHub | mixed | 1 | 0.0% | гитхабе ×1 |
| GitHub Actions | en | 1 | 0.0% | GitHub actions ×1 |
| Grafana | mixed | 1 | 0.0% | графаны ×1 |
| H100 | mixed | 1 | 0.0% | эйч сто ×1 |
| JAX | mixed | 1 | 0.0% | джакс ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | мс | миллисекунд | 1 |

Number style examples: 10:30 → десять тридцать; 0,35 → ноль целых тридцати пяти; 0.0003 → zero point zero zero zero three; 1.2 → one point two

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (16.7% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Node.js | en | 1 | 0.0% | NodeJS ×1 |
| cron | en | 1 | 0.0% | crown ×1 |
| stack trace | mixed | 1 | 0.0% | стек trace ×1 |
| Бэкенд | ru | 1 | 0.0% | Бекенд ×1 |
| Ревьюер | ru | 1 | 0.0% | Ревьюр ×1 |
| валидации | ru | 1 | 0.0% | валидацию ×1 |
| задеплоим | ru | 1 | 0.0% | за деплоим ×1 |
| инференс | ru | 1 | 0.0% | инфренс ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | переполнено | переполнена | 1 |

Number style examples: 0,35 → 0.35; 3 → три

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| API | mixed | 1 | 0.0% | апи ×1 |
| CI/CD | mixed | 1 | 0.0% | сиай сиди ×1 |
| ClickHouse | mixed | 1 | 0.0% | клик хаус ×1 |
| GitHub | mixed | 1 | 0.0% | гитхаб ×1 |
| Grafana | mixed | 1 | 0.0% | графаны ×1 |
| H100 | mixed | 1 | 0.0% | аш сто ×1 |
| JAX | mixed | 1 | 0.0% | джакс ×1 |
| Kubernetes | mixed | 1 | 0.0% | кубернетис ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | мс | миллисекунд | 1 |

Number style examples: 10:30 → десять тридцать; 0,35 → ноль тридцать пять; 12 → двенадцать; 128 → сто двадцать восемь

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- drop hallucinated tails (11.5% of utterances)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| CI/CD | mixed | 1 | 0.0% | CI-CD ×1 |
| Dmitri | en | 1 | 0.0% | Dmitry ×1 |
| Helm chart | en | 1 | 0.0% | helm chart ×1 |
| Laura | mixed | 1 | 0.0% | Лаурой ×1 |
| docker compose up | mixed | 1 | 0.0% | Docker Compose Up ×1 |
| p99 | mixed | 1 | 0.0% | P99 ×1 |
| pytest | en | 1 | 0.0% | PyTest ×1 |
| vLLM | en | 1 | 0.0% | VLLM ×1 |

Number style examples: 10:30 → 10.30; 18:00 → 18.00; 2 → два; 4:15 → 4.15

Hallucinated tails: “Thank you for watching.” ×1; “Продолжение следует...” ×1; “Субтитры сделал DimaTorzok” ×1
