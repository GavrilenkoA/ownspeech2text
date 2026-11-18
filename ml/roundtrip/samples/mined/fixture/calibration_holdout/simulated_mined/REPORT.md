# Mining of simulated hypotheses

Rows mined: 280 (skipped with null hypothesis: 0). Sources: simulated.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 40 | 14.9% | 0.0% | 15.4% | 0.0% | 0.0% | 52.6% | sentence | full | digits | cyrillic | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 50 | 17.6% | 2.0% | 16.6% | 5.2% | 0.8% | 55.8% | sentence | full | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 50 | 20.6% | 3.2% | 20.5% | 11.8% | 0.0% | 47.5% | sentence | full | words | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 50 | 6.6% | 3.8% | 1.4% | 11.1% | 1.1% | 79.2% | sentence | full | digits | latin | 0.0% |
| `t-one@onnx` | 40 | 25.9% | 0.3% | 26.0% | 0.9% | 0.0% | 35.8% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 50 | 11.0% | 10.2% | 10.9% | 7.4% | 11.2% | 83.6% | sentence | full | digits | latin | 16.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 20 | 32.4% | 0.0% | 33.6% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 20 | 0.0% | 0.0% | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 10 | 1.5% | 1.5% | 0.3% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 20 | 35.3% | 2.9% | 32.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 20 | 13.0% | 1.5% | 12.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 10 | 2.3% | 2.3% | 0.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 20 | 38.2% | 1.8% | 41.4% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 20 | 17.5% | 5.0% | 13.8% |
| `qwen3-asr-0.6b@onnx-int8` | en | 10 | 0.8% | 0.8% | 0.9% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 20 | 8.2% | 0.0% | 0.7% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 20 | 9.0% | 9.0% | 2.3% |
| `t-one@onnx` | mixed | 20 | 41.2% | 0.0% | 41.8% |
| `t-one@onnx` | ru | 20 | 13.0% | 0.5% | 12.8% |
| `whisper-large-v3-turbo@onnx-int8` | en | 10 | 1.5% | 1.5% | 0.3% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 20 | 14.1% | 11.8% | 15.7% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 20 | 14.5% | 14.5% | 12.8% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | – | 100.0% | 100.0% | 100.0% | 100.0% | 0.0% | 90.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 40.0% | 94.0% | 94.0% | 100.0% | 5.0% | 79.4% | 50.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 70.6% | 100.0% | 100.0% | 100.0% | 0.0% | 71.0% | 60.0% |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 93.3% | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 60.0% |
| `t-one@onnx` | 0.0% | 0.0% | – | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 74.4% | 100.0% | 96.0% | 86.7% | 100.0% | 100.0% | 40.0% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | sim | mixed | 20 | 32.4% | 0.0% | 18.2% | 0.0% | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | sim | ru | 20 | 0.0% | 0.0% | 100.0% | 0.0% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | sim | en | 10 | 1.5% | 1.5% | 40.0% | 8.0% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | sim | mixed | 20 | 35.3% | 2.9% | 30.9% | 9.1% | 28.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | sim | ru | 20 | 13.0% | 1.5% | 100.0% | 0.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | sim | en | 10 | 2.3% | 2.3% | 68.0% | 12.0% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | sim | mixed | 20 | 38.2% | 1.8% | 18.2% | 5.5% | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | sim | ru | 20 | 17.5% | 5.0% | 75.0% | 25.0% | – |
| `qwen3-asr-0.6b@onnx-int8` | sim | en | 10 | 0.8% | 0.8% | 88.0% | 0.0% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | sim | mixed | 20 | 8.2% | 0.0% | 87.3% | 0.0% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | sim | ru | 20 | 9.0% | 9.0% | 62.5% | 37.5% | – |
| `t-one@onnx` | sim | mixed | 20 | 41.2% | 0.0% | 9.1% | 0.0% | 0.0% |
| `t-one@onnx` | sim | ru | 20 | 13.0% | 0.5% | 72.5% | 2.5% | – |
| `whisper-large-v3-turbo@onnx-int8` | sim | en | 10 | 1.5% | 1.5% | 64.0% | 8.0% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | sim | mixed | 20 | 14.1% | 11.8% | 89.1% | 0.0% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | sim | ru | 20 | 14.5% | 14.5% | 88.9% | 11.1% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| эмбеддинги | ru | 6 | 29 | 6 | 20.7% | эмбединги ×4; ембеддинги ×1; эмбиддинги ×1 |
| Квантизация | ru | 6 | 30 | 5 | 16.7% | Кванизация ×1; Квантизацея ×1; Квантизаця ×1; Квантизция ×1 |
| воркер | ru | 6 | 29 | 4 | 13.8% | вокер ×1; ворер ×1; воркеем ×1; воркть ×1 |
| инференс | ru | 6 | 30 | 4 | 13.3% | инфиренс ×2; инференз ×1; иференс ×1 |
| фронт | ru | 6 | 30 | 4 | 13.3% | фронд ×3; фронем ×1 |
| Rust | en | 4 | 20 | 3 | 15.0% | Rost ×2; rost ×1 |
| прода | ru | 6 | 29 | 3 | 10.3% | прада ×1; продой ×1; проем ×1 |
| токенизацию | ru | 6 | 29 | 3 | 10.3% | тоенизацию ×1; токенезацию ×1; токинизацию ×1 |
| Priya | en | 4 | 20 | 2 | 10.0% | preya ×1; prya ×1 |
| issue | mixed | 6 | 30 | 2 | 6.7% | ешю ×2 |
| ребейзом | mixed | 6 | 30 | 2 | 6.7% | реейзом ×2 |
| Terraform | en | 4 | 20 | 1 | 5.0% | Terrafom ×1 |
| cron | en | 4 | 20 | 1 | 5.0% | crun ×1 |
| Kubernetes | mixed | 6 | 30 | 1 | 3.3% | кубернетиз ×1 |
| Laura | mixed | 6 | 30 | 1 | 3.3% | Laua ×1 |
| main | mixed | 6 | 30 | 1 | 3.3% | мийн ×1 |
| stack trace | mixed | 6 | 30 | 1 | 3.3% | стег трейс ×1 |
| Бэкенд | ru | 6 | 30 | 1 | 3.3% | Бекенд ×1 |

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 40.0%)
- convert spoken numbers, times and dates to digits (95.0% spelled out)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| ребейзом | mixed | 5 | 2 | 60.0% | ребейзом ×3; реейзом ×2 |
| Kubernetes | mixed | 5 | 1 | 20.0% | кубернетес ×3; Kubernetes ×1; кубернетиз ×1 |
| Laura | mixed | 5 | 1 | 20.0% | лора ×3; Laua ×1; Laura ×1 |
| Priya | en | 5 | 1 | 20.0% | priya ×3; Priya ×1; prya ×1 |
| cron | en | 5 | 1 | 80.0% | cron ×4; crun ×1 |
| stack trace | mixed | 5 | 1 | 20.0% | стек трейс ×3; stack trace ×1; стег трейс ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | и | а | 1 |
| sub_near | ошибкой | ошибкое | 1 |
| sub_near | раза | роза | 1 |

Number style examples: 10:30 → десять тридцать; 2 → два; 502 → пятьсот два

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 70.6%)
- convert spoken numbers, times and dates to digits (100.0% spelled out)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Rust | en | 5 | 3 | 20.0% | Rost ×2; Rust ×1; rost ×1; rust ×1 |
| Квантизация | ru | 5 | 3 | 40.0% | Квантизация ×2; Кванизация ×1; Квантизацея ×1; Квантизаця ×1 |
| issue | mixed | 5 | 2 | 0.0% | ишью ×3; ешю ×2 |
| прода | ru | 5 | 2 | 60.0% | прода ×3; продой ×1; проем ×1 |
| эмбеддинги | ru | 5 | 2 | 60.0% | эмбеддинги ×3; эмбединги ×2 |
| main | mixed | 5 | 1 | 0.0% | мейн ×4; мийн ×1 |
| Бэкенд | ru | 5 | 1 | 80.0% | Бэкенд ×4; Бекенд ×1 |
| инференс | ru | 5 | 1 | 80.0% | инференс ×4; инференз ×1 |

Number style examples: 10:30 → десять тридцать; 2 → два; 502 → пятьсот два

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- little beyond light punctuation fixes

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| воркер | ru | 5 | 3 | 40.0% | воркер ×2; ворер ×1; воркеем ×1; воркть ×1 |
| токенизацию | ru | 5 | 3 | 40.0% | токенизацию ×2; тоенизацию ×1; токенезацию ×1; токинизацию ×1 |
| эмбеддинги | ru | 5 | 3 | 40.0% | эмбеддинги ×2; эмбединги ×2; эмбиддинги ×1 |
| Квантизация | ru | 5 | 2 | 60.0% | Квантизация ×3; Квантизция ×1; Квонтизация ×1 |
| инференс | ru | 5 | 2 | 60.0% | инференс ×3; инфиренс ×1; иференс ×1 |
| фронт | ru | 5 | 2 | 60.0% | фронт ×3; фронд ×2 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | review | sync | 1 |
| sub | до | мс | 1 |
| sub | и | в | 1 |
| sub | падает | тысяч | 1 |

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| прода | ru | 5 | 1 | 80.0% | прода ×4; прада ×1 |

Number style examples: 10:30 → десять тридцать; 2 → два; 502 → пятьсот два

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 74.4%)
- drop hallucinated tails (16.0% of utterances)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| воркер | ru | 4 | 1 | 75.0% | воркер ×3; вокер ×1 |
| эмбеддинги | ru | 4 | 1 | 75.0% | эмбеддинги ×3; ембеддинги ×1 |
| Priya | en | 5 | 1 | 60.0% | Priya ×3; preya ×1; priya ×1 |
| Terraform | en | 5 | 1 | 40.0% | Terraform ×2; terraform ×2; Terrafom ×1 |
| инференс | ru | 5 | 1 | 80.0% | инференс ×4; инфиренс ×1 |
| фронт | ru | 5 | 1 | 80.0% | фронт ×4; фронд ×1 |

Hallucinated tails: “Thank you for watching.” ×4; “Продолжение следует...” ×4
