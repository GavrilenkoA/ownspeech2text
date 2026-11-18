# Error mining — hand-made fixture (not real ASR output)

Rows mined: 140 (skipped with null hypothesis: 0). Sources: fixture_roundtrip.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 18 | 21.4% | 0.0% | 19.1% | 0.0% | 0.0% | 46.0% | sentence | full | digits | cyrillic | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 26 | 28.4% | 5.5% | 23.1% | 18.7% | 0.5% | 40.9% | sentence | full | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 26 | 34.3% | 3.7% | 29.8% | 13.3% | 0.0% | 40.9% | sentence | full | words | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 26 | 8.1% | 4.4% | 1.6% | 14.7% | 0.5% | 74.2% | sentence | full | digits | latin | 0.0% |
| `t-one@onnx` | 18 | 38.7% | 2.3% | 33.6% | 7.3% | 0.0% | 26.0% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 26 | 9.6% | 8.1% | 9.1% | 10.7% | 7.1% | 79.0% | sentence | full | digits | latin | 11.5% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 9 | 39.8% | 0.0% | 37.5% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 9 | 2.4% | 0.0% | 2.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 8 | 21.4% | 5.1% | 12.4% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 9 | 40.9% | 6.8% | 39.5% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 9 | 23.5% | 4.7% | 18.3% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 8 | 22.4% | 4.1% | 17.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 9 | 55.7% | 2.3% | 53.2% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 9 | 25.9% | 4.7% | 20.4% |
| `qwen3-asr-0.6b@onnx-int8` | en | 8 | 1.0% | 1.0% | 0.2% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 9 | 3.4% | 1.1% | 1.8% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 9 | 21.2% | 11.8% | 2.8% |
| `t-one@onnx` | mixed | 9 | 51.1% | 0.0% | 50.9% |
| `t-one@onnx` | ru | 9 | 25.9% | 4.7% | 17.8% |
| `whisper-large-v3-turbo@onnx-int8` | en | 8 | 5.1% | 5.1% | 4.7% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 9 | 13.6% | 12.5% | 16.5% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 9 | 10.6% | 7.1% | 6.4% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | – | 100.0% | 100.0% | 110.0% | 100.0% | 0.0% | 60.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 79.0% | 96.2% | 96.2% | 100.0% | 5.9% | 86.6% | 60.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 69.2% | 100.0% | 100.0% | 100.0% | 0.0% | 75.4% | 60.0% |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 94.1% | 99.2% | 50.0% |
| `t-one@onnx` | 0.0% | 0.0% | – | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 92.3% | 100.0% | 92.3% | 100.0% | 93.8% | 99.2% | 25.0% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | fixture | mixed | 9 | 39.8% | 0.0% | 7.1% | 0.0% | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | fixture | ru | 9 | 2.4% | 0.0% | 95.5% | 0.0% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | fixture | en | 8 | 21.4% | 5.1% | 31.2% | 25.0% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | fixture | mixed | 9 | 40.9% | 6.8% | 25.0% | 21.4% | 40.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | fixture | ru | 9 | 23.5% | 4.7% | 68.2% | 18.2% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | fixture | en | 8 | 22.4% | 4.1% | 56.2% | 18.8% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | fixture | mixed | 9 | 55.7% | 2.3% | 3.6% | 7.1% | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | fixture | ru | 9 | 25.9% | 4.7% | 77.3% | 13.6% | – |
| `qwen3-asr-0.6b@onnx-int8` | fixture | en | 8 | 1.0% | 1.0% | 87.5% | 6.2% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | fixture | mixed | 9 | 3.4% | 1.1% | 92.9% | 3.6% | 96.7% |
| `qwen3-asr-0.6b@onnx-int8` | fixture | ru | 9 | 21.2% | 11.8% | 40.9% | 40.9% | – |
| `t-one@onnx` | fixture | mixed | 9 | 51.1% | 0.0% | 3.6% | 0.0% | 0.0% |
| `t-one@onnx` | fixture | ru | 9 | 25.9% | 4.7% | 54.5% | 13.6% | – |
| `whisper-large-v3-turbo@onnx-int8` | fixture | en | 8 | 5.1% | 5.1% | 75.0% | 6.2% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | fixture | mixed | 9 | 13.6% | 12.5% | 83.3% | 0.0% | 96.2% |
| `whisper-large-v3-turbo@onnx-int8` | fixture | ru | 9 | 10.6% | 7.1% | 77.3% | 13.6% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| пулл-реквесту | ru | 6 | 6 | 4 | 66.7% | пулреквесту ×2; пул реквесту ×1; пул-реквесту ×1 |
| эмбеддинги | ru | 6 | 6 | 3 | 50.0% | имбеддинги ×1; эмбединге ×1; эмбединги ×1 |
| Priya | en | 4 | 4 | 2 | 50.0% | Freya ×1; Pria ×1 |
| cron | en | 4 | 4 | 2 | 50.0% | Kron ×1; crown ×1 |
| Бэкенд | ru | 6 | 6 | 2 | 33.3% | Бекенд ×1; Бэкэнд ×1 |
| Промпт | ru | 6 | 6 | 2 | 33.3% | Промт ×1; промт ×1 |
| инференс | ru | 6 | 6 | 2 | 33.3% | интерфейс ×1; инфренс ×1 |
| ребейзом | mixed | 6 | 6 | 2 | 33.3% | ребейсом ×1; рибейзом ×1 |
| смёржим | ru | 6 | 6 | 2 | 33.3% | смерджим ×1; смержем ×1 |
| Dmitri | en | 4 | 4 | 1 | 25.0% | Dmitry ×1 |
| Helm chart | en | 4 | 4 | 1 | 25.0% | home chart ×1 |
| Kafka | en | 4 | 4 | 1 | 25.0% | cafe ×1 |
| pytest | en | 4 | 4 | 1 | 25.0% | pie test ×1 |
| vLLM | en | 4 | 4 | 1 | 25.0% | VLM ×1 |
| rank | mixed | 5 | 5 | 1 | 20.0% | ранг ×1 |
| Qdrant | mixed | 6 | 6 | 1 | 16.7% | Кидрант ×1 |
| docker compose up | mixed | 6 | 6 | 1 | 16.7% | докер компос ап ×1; докер компост ап ×1 |
| git pull | mixed | 6 | 6 | 1 | 16.7% | гид пул ×1 |
| issue | mixed | 6 | 6 | 1 | 16.7% | иш ×1 |
| latency | mixed | 6 | 6 | 1 | 16.7% | лейтенси ×1 |
| Закоммить | mixed | 6 | 6 | 1 | 16.7% | Закомить ×1 |
| Ревьюер | ru | 6 | 6 | 1 | 16.7% | Ревьюр ×1 |
| валидации | ru | 6 | 6 | 1 | 16.7% | валидацию ×1 |
| воркер | ru | 6 | 6 | 1 | 16.7% | рокер ×1 |
| легаси-код | ru | 6 | 6 | 1 | 16.7% | легасий код ×1 |

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

Number style examples: 128 тысяч → 128 000

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 79.0%)
- convert spoken numbers, times and dates to digits (94.1% spelled out)
- repair garbled terms (21.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Helm chart | en | 1 | 1 | 0.0% | home chart ×1 |
| Kafka | en | 1 | 1 | 0.0% | cafe ×1 |
| Priya | en | 1 | 1 | 0.0% | Freya ×1 |
| Qdrant | mixed | 1 | 1 | 0.0% | Кидрант ×1 |
| cron | en | 1 | 1 | 0.0% | Kron ×1 |
| docker compose up | mixed | 1 | 1 | 0.0% | докер компост ап ×1 |
| git pull | mixed | 1 | 1 | 0.0% | гид пул ×1 |
| issue | mixed | 1 | 1 | 0.0% | иш ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | sync | sink | 1 |

Number style examples: 10:30 → десять тридцать; 0,35 → ноль тридцать пять; 1.2 → one point two; 12 → двенадцать

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 69.2%)
- convert spoken numbers, times and dates to digits (100.0% spelled out)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Priya | en | 1 | 1 | 0.0% | Pria ×1 |
| pytest | en | 1 | 1 | 0.0% | pie test ×1 |
| rank | mixed | 1 | 1 | 0.0% | ранг ×1 |
| vLLM | en | 1 | 1 | 0.0% | VLM ×1 |
| Промпт | ru | 1 | 1 | 0.0% | Промт ×1 |
| легаси-код | ru | 1 | 1 | 0.0% | легасий код ×1 |
| пулл-реквесту | ru | 1 | 1 | 0.0% | пулреквесту ×1 |
| ребейзом | mixed | 1 | 1 | 0.0% | рибейзом ×1 |

Number style examples: 10:30 → десять тридцать; 0,35 → ноль целых тридцати пяти; 0.0003 → zero point zero zero zero three; 1.2 → one point two

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (16.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| cron | en | 1 | 1 | 0.0% | crown ×1 |
| Бэкенд | ru | 1 | 1 | 0.0% | Бекенд ×1 |
| Ревьюер | ru | 1 | 1 | 0.0% | Ревьюр ×1 |
| валидации | ru | 1 | 1 | 0.0% | валидацию ×1 |
| инференс | ru | 1 | 1 | 0.0% | инфренс ×1 |
| лосс | ru | 1 | 1 | 0.0% | лоз ×1 |
| прода | ru | 1 | 1 | 0.0% | продо ×1 |
| пулл-реквесту | ru | 1 | 1 | 0.0% | пулреквесту ×1 |

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

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Промпт | ru | 1 | 1 | 0.0% | промт ×1 |
| пулл-реквесту | ru | 1 | 1 | 0.0% | пул реквесту ×1 |
| эмбеддинги | ru | 1 | 1 | 0.0% | эмбединге ×1 |

Number style examples: 10:30 → десять тридцать; 0,35 → ноль тридцать пять; 12 → двенадцать; 128 → сто двадцать восемь

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- drop hallucinated tails (11.5% of utterances)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Dmitri | en | 1 | 1 | 0.0% | Dmitry ×1 |
| Бэкенд | ru | 1 | 1 | 0.0% | Бэкэнд ×1 |
| пулл-реквесту | ru | 1 | 1 | 0.0% | пул-реквесту ×1 |
| смёржим | ru | 1 | 1 | 0.0% | смерджим ×1 |

Number style examples: 10:30 → 10.30; 18:00 → 18.00; 2 → два; 4:15 → 4.15

Hallucinated tails: “Thank you for watching.” ×1; “Продолжение следует...” ×1; “Субтитры сделал DimaTorzok” ×1
