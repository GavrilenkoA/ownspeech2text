# Mining of simulated hypotheses

Rows mined: 700 (skipped with null hypothesis: 0). Sources: simulated.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 90 | 20.8% | 1.2% | 18.4% | 3.6% | 0.0% | 46.0% | sentence | full | digits | cyrillic | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 130 | 29.0% | 6.0% | 23.6% | 18.7% | 1.1% | 40.3% | sentence | full | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 130 | 33.2% | 4.3% | 28.2% | 14.7% | 0.4% | 40.9% | sentence | full | words | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 130 | 8.9% | 4.3% | 2.7% | 14.7% | 0.4% | 74.2% | sentence | full | digits | latin | 0.0% |
| `t-one@onnx` | 90 | 39.0% | 3.9% | 33.0% | 10.9% | 0.7% | 26.0% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 130 | 8.1% | 5.5% | 7.2% | 6.7% | 5.1% | 78.8% | sentence | full | digits | latin | 10.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 45 | 39.8% | 2.3% | 37.5% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 45 | 1.2% | 0.0% | 0.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 40 | 24.1% | 4.9% | 16.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 45 | 40.2% | 6.8% | 37.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 45 | 23.1% | 6.3% | 17.4% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 40 | 22.2% | 4.5% | 16.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 45 | 54.5% | 3.4% | 51.3% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 45 | 23.8% | 5.2% | 18.1% |
| `qwen3-asr-0.6b@onnx-int8` | en | 40 | 2.0% | 1.0% | 1.2% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 45 | 2.7% | 1.4% | 1.7% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 45 | 23.1% | 11.3% | 5.1% |
| `t-one@onnx` | mixed | 45 | 50.4% | 1.6% | 49.2% |
| `t-one@onnx` | ru | 45 | 27.1% | 6.3% | 18.2% |
| `whisper-large-v3-turbo@onnx-int8` | en | 40 | 5.5% | 4.7% | 6.6% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 45 | 6.1% | 2.7% | 5.6% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 45 | 13.2% | 9.4% | 9.1% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | – | 100.0% | 100.0% | 100.0% | 100.0% | 0.0% | 32.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 84.2% | 98.5% | 98.5% | 90.0% | 8.2% | 84.2% | 56.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 69.2% | 100.0% | 100.0% | 90.0% | 0.0% | 76.0% | 36.0% |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 98.0% | 92.9% | 99.2% | 50.0% |
| `t-one@onnx` | 0.0% | 0.0% | – | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 95.0% | 97.7% | 90.8% | 96.0% | 92.8% | 99.2% | 10.0% |

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| API | mixed | 5 | 0.0% | АПИ ×5 |
| CI/CD | mixed | 5 | 0.0% | сиайсиди ×5 |
| ClickHouse | mixed | 5 | 0.0% | КликХаус ×5 |
| GitHub | mixed | 5 | 0.0% | гитхабе ×5 |
| Grafana | mixed | 5 | 0.0% | Графаны ×5 |
| H100 | mixed | 5 | 0.0% | аш сто ×5 |
| JAX | mixed | 5 | 0.0% | Джакс ×5 |
| Kubernetes | mixed | 5 | 0.0% | кубернетису ×5 |

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (91.8% spelled out)
- repair garbled terms (19.7% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| CI/CD | mixed | 5 | 0.0% | CI CD ×5 |
| ClickHouse | mixed | 5 | 0.0% | Клик Хаус ×5 |
| GitHub Actions | en | 5 | 0.0% | GitHub actions ×5 |
| Grafana | mixed | 5 | 0.0% | Графаны ×5 |
| H100 | mixed | 5 | 0.0% | H сто ×5 |
| Helm chart | en | 5 | 0.0% | home chart ×5 |
| JAX | mixed | 5 | 0.0% | Jax ×5 |
| Kafka | en | 5 | 0.0% | cafe ×5 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | fails | fais | 1 |
| sub_near | sync | sink | 1 |
| sub | the | a | 1 |
| sub_near | to | do | 1 |
| sub | мс | миллисекунд | 1 |
| sub_near | сделай | сделой | 1 |
| sub_near | сервис | серви | 1 |
| sub_near | через | чирез | 1 |

Number style examples: 10:30 → десять тридцать; 12 → двенадцать; 128 → сто двадцать восемь; 22 → twenty two

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 69.2%)
- convert spoken numbers, times and dates to digits (100.0% spelled out)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| API | mixed | 5 | 0.0% | эй пи ай ×5 |
| CI/CD | mixed | 5 | 0.0% | си ай си ди ×5 |
| ClickHouse | mixed | 5 | 0.0% | клик хаус ×5 |
| GitHub | mixed | 5 | 0.0% | гитхабе ×5 |
| GitHub Actions | en | 5 | 0.0% | GitHub actions ×5 |
| Grafana | mixed | 5 | 0.0% | графаны ×5 |
| H100 | mixed | 5 | 0.0% | эйч сто ×5 |
| JAX | mixed | 5 | 0.0% | джакс ×5 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | chunks | chunk | 1 |
| sub_near | with | weth | 1 |
| sub | в | и | 1 |
| sub_near | токенов | токену | 1 |

Number style examples: 10:30 → десять тридцать; 12 → двенадцать; 128 → сто двадцать восемь; 16 → шестнадцать

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (16.7% of terms are recognition errors) — from context only

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Node.js | en | 5 | 0.0% | NodeJS ×5 |
| cron | en | 5 | 0.0% | crown ×5 |
| stack trace | mixed | 5 | 0.0% | стек trace ×5 |
| Бэкенд | ru | 5 | 0.0% | Бекенд ×5 |
| Ревьюер | ru | 5 | 0.0% | Ревьюр ×5 |
| валидации | ru | 5 | 0.0% | валидацию ×5 |
| задеплоим | ru | 5 | 0.0% | за деплоим ×5 |
| инференс | ru | 5 | 0.0% | инфренс ×5 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | поставь | постаь | 1 |
| sub | в | на | 1 |
| sub_near | до | да | 1 |
| sub_near | переполнено | переполнена | 1 |

Number style examples: 0,35 → ноль и тридцать пять; 0.0003 → zero zero zero zero three; 128 → сто двадцать восемь; 16 → шестнадцать

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| API | mixed | 5 | 0.0% | апи ×5 |
| CI/CD | mixed | 5 | 0.0% | сиай сиди ×5 |
| ClickHouse | mixed | 5 | 0.0% | клик хаус ×5 |
| GitHub | mixed | 5 | 0.0% | гитхаб ×5 |
| Grafana | mixed | 5 | 0.0% | графаны ×5 |
| H100 | mixed | 5 | 0.0% | аш сто ×5 |
| JAX | mixed | 5 | 0.0% | джакс ×5 |
| Kubernetes | mixed | 5 | 0.0% | кубернетис ×5 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | задеплой | задеплай | 1 |
| sub_near | пятницы | пятниы | 1 |
| sub_near | с | со | 1 |
| sub_near | скинь | скень | 1 |

Number style examples: 10:30 → десять тридцать; 12 → двенадцать; 128 → сто двадцать восемь; 16 → шестнадцать

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- drop hallucinated tails (10.0% of utterances)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| CI/CD | mixed | 5 | 0.0% | CI-CD ×5 |
| Dmitri | en | 5 | 0.0% | Dmitry ×5 |
| Helm chart | en | 5 | 0.0% | helm chart ×5 |
| Laura | mixed | 5 | 0.0% | Лаурой ×5 |
| docker compose up | mixed | 5 | 0.0% | Docker Compose Up ×5 |
| p99 | mixed | 5 | 0.0% | P99 ×5 |
| pytest | en | 5 | 0.0% | PyTest ×5 |
| vLLM | en | 5 | 0.0% | VLLM ×5 |

Number style examples: 128 → сто двадцать восемь; 800 → восемьсот; 4:15 → four fifteen; 64 → sixty four

Hallucinated tails: “Продолжение следует...” ×5; “Thank you for watching.” ×4; “Субтитры сделал DimaTorzok” ×4
