# Mining of simulated hypotheses

Rows mined: 280 (skipped with null hypothesis: 0). Sources: simulated.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 40 | 20.3% | 3.0% | 16.1% | 10.5% | 0.0% | 50.5% | sentence | full | digits | cyrillic | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 50 | 18.4% | 3.0% | 16.0% | 6.7% | 1.6% | 58.3% | sentence | full | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 50 | 24.4% | 3.4% | 21.1% | 10.4% | 0.8% | 48.3% | sentence | full | words | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 50 | 4.4% | 2.8% | 1.4% | 10.4% | 0.0% | 85.8% | sentence | full | digits | latin | 0.0% |
| `t-one@onnx` | 40 | 34.6% | 4.9% | 27.8% | 13.3% | 1.5% | 28.4% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 50 | 12.8% | 11.6% | 12.6% | 8.2% | 12.9% | 87.9% | sentence | full | digits | latin | 20.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 20 | 44.1% | 6.5% | 35.2% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 20 | 0.0% | 0.0% | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 10 | 2.3% | 2.3% | 0.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 20 | 34.1% | 3.5% | 29.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 20 | 15.5% | 3.0% | 13.3% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 10 | 3.1% | 3.1% | 0.6% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 20 | 49.4% | 2.4% | 43.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 20 | 17.0% | 4.5% | 13.5% |
| `qwen3-asr-0.6b@onnx-int8` | en | 10 | 0.0% | 0.0% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 20 | 2.4% | 0.0% | 0.2% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 20 | 9.0% | 7.0% | 3.2% |
| `t-one@onnx` | mixed | 20 | 52.9% | 2.9% | 43.9% |
| `t-one@onnx` | ru | 20 | 19.0% | 6.5% | 14.1% |
| `whisper-large-v3-turbo@onnx-int8` | en | 10 | 4.6% | 4.6% | 0.9% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 20 | 15.3% | 11.8% | 15.8% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 20 | 16.0% | 16.0% | 16.2% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | – | 100.0% | 100.0% | 100.0% | 100.0% | 0.0% | 90.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 52.0% | 98.0% | 98.0% | 100.0% | 5.0% | 80.9% | 60.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 62.5% | 100.0% | 100.0% | 100.0% | 0.0% | 70.8% | 40.0% |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 97.8% | 100.0% | 100.0% | 100.0% | 90.0% | 100.0% | 70.0% |
| `t-one@onnx` | 0.0% | 0.0% | – | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 89.7% | 97.9% | 92.0% | 86.7% | 100.0% | 100.0% | 40.0% |

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| CI/CD | mixed | 5 | 0.0% | цэ и цэ дэ ×2; се ой си де ×1; си ай си ди ×1; це е цэ де ×1 |
| ClickHouse | mixed | 5 | 0.0% | кликхаус ×3; клик хаус ×2 |
| GitHub | mixed | 5 | 0.0% | гитхаб ×4; гит хаб ×1 |
| Kubernetes | mixed | 5 | 0.0% | кубернетес ×5 |
| Laura | mixed | 5 | 0.0% | лора ×5 |
| git pull | mixed | 5 | 0.0% | гит пул ×5 |
| issue | mixed | 5 | 0.0% | ишью ×5 |
| main | mixed | 5 | 0.0% | мейн ×4; ми ×1 |

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 52.0%)
- convert spoken numbers, times and dates to digits (95.0% spelled out)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Laura | mixed | 5 | 0.0% | лора ×5 |
| ClickHouse | mixed | 5 | 20.0% | кликхаус ×3; ClickHouse ×1; клик хаус ×1 |
| GitHub | mixed | 5 | 20.0% | гит хаб ×3; GitHub ×1; гитхаб ×1 |
| Kubernetes | mixed | 5 | 20.0% | Kubenetes ×1; Kubernetes ×1; kubernetes ×1; кубернееой ×1 |
| Rust | en | 5 | 20.0% | rust ×4; Rust ×1 |
| issue | mixed | 5 | 20.0% | ишью ×4; issue ×1 |
| main | mixed | 5 | 20.0% | мейн ×4; main ×1 |
| stack trace | mixed | 5 | 20.0% | стек трейс ×3; stack trace ×1; stck trace ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | the | a | 1 |
| ins | ∅ | се | 1 |
| sub_near | сервис | серви | 1 |
| sub_near | бит | бид | 1 |
| sub_near | ошибкой | ошибкое | 1 |
| sub_near | скинь | ские | 1 |

Number style examples: 10:30 → десять тридцать; 2 → два; 502 → пятьсот два

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 62.5%)
- convert spoken numbers, times and dates to digits (100.0% spelled out)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| CI/CD | mixed | 5 | 0.0% | си ай си ди ×5 |
| ClickHouse | mixed | 5 | 0.0% | кликхаус ×3; клекхаа ×1; клик хаус ×1 |
| GitHub | mixed | 5 | 0.0% | гитхаб ×3; гит хаб ×2 |
| Kubernetes | mixed | 5 | 0.0% | кубернетес ×4; кубиретею ×1 |
| Laura | mixed | 5 | 0.0% | лора ×5 |
| git pull | mixed | 5 | 0.0% | гит пул ×5 |
| issue | mixed | 5 | 0.0% | ишью ×5 |
| main | mixed | 5 | 0.0% | мейн ×5 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | в | и | 1 |
| sub_near | ошибкой | ашибкой | 1 |
| sub_near | пустой | пстой | 1 |

Number style examples: 10:30 → десять тридцать; 2 → два; 502 → пятьсот два

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- little beyond light punctuation fixes

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| эмбеддинги | ru | 5 | 20.0% | эмбедднги ×2; эмбеддинге ×1; эмбеддинги ×1; эмбединги ×1 |
| GitHub | mixed | 5 | 60.0% | GitHub ×3; Git Hub ×2 |
| Квантизация | ru | 5 | 60.0% | Квантизация ×3; Квантизция ×1; Квонтизация ×1 |
| воркер | ru | 5 | 60.0% | воркер ×3; воркем ×1; воркр ×1 |
| прода | ru | 5 | 60.0% | прода ×3; продо ×1; прою ×1 |
| фронт | ru | 5 | 60.0% | фронт ×3; франт ×1; фронд ×1 |
| Helm chart | en | 5 | 80.0% | Helm chart ×4; helm chart ×1 |
| инференс | ru | 5 | 80.0% | инференс ×4; инферены ×1 |

Number style examples: 502 → пятьсот два

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| CI/CD | mixed | 5 | 0.0% | си ай си ди ×5 |
| ClickHouse | mixed | 5 | 0.0% | клик хаус ×3; кликхаус ×2 |
| GitHub | mixed | 5 | 0.0% | гит хаб ×5 |
| Kubernetes | mixed | 5 | 0.0% | кубернетес ×5 |
| Laura | mixed | 5 | 0.0% | лора ×5 |
| git pull | mixed | 5 | 0.0% | гит пул ×4; гит ×1 |
| issue | mixed | 5 | 0.0% | ишью ×5 |
| main | mixed | 5 | 0.0% | мейн ×3; ме ×1; ми ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | ветку | веткть | 1 |
| sub_near | задеплой | задеплай | 1 |
| sub_near | бит | бид | 1 |
| sub_near | скинь | скень | 1 |

Number style examples: 10:30 → десять тридцать; 2 → два; 502 → пятьсот два

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- drop hallucinated tails (20.0% of utterances)

Top term manglings:

| term | context | n | exact | variants |
|---|---|---:|---:|---|
| Terraform | en | 5 | 40.0% | Terraform ×2; Terrafom ×1; Terrafurm ×1; Terraorm ×1 |
| ClickHouse | mixed | 5 | 60.0% | ClickHouse ×3; Click House ×2 |
| Helm chart | en | 5 | 60.0% | Helm chart ×3; Halm chart ×1; helm chart ×1 |
| Kubernetes | mixed | 5 | 60.0% | Kubernetes ×3; kubernetes ×2 |
| Priya | en | 5 | 60.0% | Priya ×3; preya ×1; priya ×1 |
| GitHub | mixed | 5 | 80.0% | GitHub ×4; Git Hub ×1 |
| Rust | en | 5 | 80.0% | Rust ×4; rost ×1 |
| фронт | ru | 5 | 80.0% | фронт ×4; фронд ×1 |

Hallucinated tails: “Thank you for watching.” ×5; “Продолжение следует...” ×5
