# Mining of simulated hypotheses

Rows mined: 700 (skipped with null hypothesis: 0). Sources: simulated.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 90 | 20.8% | 0.0% | 18.4% | 0.0% | 0.0% | 46.0% | sentence | full | digits | cyrillic | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 130 | 28.9% | 7.1% | 22.6% | 22.9% | 1.0% | 38.8% | sentence | full | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 130 | 31.6% | 3.3% | 27.7% | 12.0% | 0.0% | 43.0% | sentence | full | words | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 130 | 6.8% | 4.5% | 1.6% | 14.9% | 0.5% | 77.3% | sentence | full | digits | latin | 0.0% |
| `t-one@onnx` | 90 | 38.0% | 1.0% | 32.9% | 3.3% | 0.0% | 26.4% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 130 | 7.8% | 5.0% | 7.4% | 5.1% | 5.0% | 80.3% | sentence | full | digits | latin | 9.2% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 45 | 39.8% | 0.0% | 37.5% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 45 | 1.2% | 0.0% | 0.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 40 | 23.5% | 6.3% | 15.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 45 | 37.0% | 6.4% | 33.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 45 | 26.8% | 8.7% | 18.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 40 | 20.4% | 3.7% | 15.6% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 45 | 53.4% | 2.7% | 51.3% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 45 | 21.9% | 3.5% | 17.6% |
| `qwen3-asr-0.6b@onnx-int8` | en | 40 | 0.4% | 0.4% | 0.1% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 45 | 1.8% | 1.4% | 0.9% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 45 | 19.3% | 12.5% | 3.8% |
| `t-one@onnx` | mixed | 45 | 50.0% | 0.0% | 49.1% |
| `t-one@onnx` | ru | 45 | 25.7% | 2.1% | 18.1% |
| `whisper-large-v3-turbo@onnx-int8` | en | 40 | 5.7% | 4.1% | 6.3% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 45 | 6.4% | 2.7% | 6.0% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 45 | 11.8% | 8.5% | 9.8% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | – | 100.0% | 100.0% | 100.0% | 100.0% | 0.0% | 32.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 83.3% | 98.5% | 97.7% | 92.0% | 5.9% | 86.2% | 52.4% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 70.3% | 100.0% | 100.0% | 90.0% | 0.0% | 76.2% | 45.8% |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 97.7% | 99.8% | 39.1% |
| `t-one@onnx` | 0.0% | 0.0% | – | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 95.0% | 100.0% | 96.2% | 96.0% | 90.4% | 99.0% | 17.4% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | sim | mixed | 45 | 39.8% | 0.0% | 7.1% | 0.0% | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | sim | ru | 45 | 1.2% | 0.0% | 95.5% | 0.0% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | sim | en | 40 | 23.5% | 6.3% | 33.8% | 31.2% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | sim | mixed | 45 | 37.0% | 6.4% | 28.6% | 16.4% | 40.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | sim | ru | 45 | 26.8% | 8.7% | 55.5% | 30.9% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | sim | en | 40 | 20.4% | 3.7% | 57.5% | 17.5% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | sim | mixed | 45 | 53.4% | 2.7% | 6.4% | 8.6% | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | sim | ru | 45 | 21.9% | 3.5% | 79.1% | 12.7% | – |
| `qwen3-asr-0.6b@onnx-int8` | sim | en | 40 | 0.4% | 0.4% | 91.2% | 2.5% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | sim | mixed | 45 | 1.8% | 1.4% | 95.7% | 3.6% | 99.3% |
| `qwen3-asr-0.6b@onnx-int8` | sim | ru | 45 | 19.3% | 12.5% | 43.6% | 43.6% | – |
| `t-one@onnx` | sim | mixed | 45 | 50.0% | 0.0% | 3.6% | 0.0% | 0.0% |
| `t-one@onnx` | sim | ru | 45 | 25.7% | 2.1% | 55.5% | 6.4% | – |
| `whisper-large-v3-turbo@onnx-int8` | sim | en | 40 | 5.7% | 4.1% | 75.0% | 6.2% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | sim | mixed | 45 | 6.4% | 2.7% | 80.7% | 0.0% | 96.0% |
| `whisper-large-v3-turbo@onnx-int8` | sim | ru | 45 | 11.8% | 8.5% | 83.8% | 8.6% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| инференс | ru | 6 | 30 | 9 | 30.0% | интерфейс ×4; инфренс ×3; инферинс ×1; инфернс ×1 |
| смёржим | ru | 6 | 30 | 9 | 30.0% | смерджим ×2; смержем ×2; смёрже ×1; смёржиой ×1 |
| фронт | ru | 6 | 30 | 9 | 30.0% | фон ×4; фронд ×3; франт ×1; фронем ×1 |
| эмбеддинги | ru | 6 | 29 | 8 | 27.6% | эмбединги ×4; имбеддинги ×2; эмбедденги ×1; эмбединге ×1 |
| пулл-реквесту | ru | 6 | 30 | 8 | 26.7% | пул-реквесту ×3; пул реквесту ×2; пулреквесту ×2; пулл-реквисту ×1 |
| pytest | en | 4 | 20 | 6 | 30.0% | pie test ×4; ptest ×1; pytast ×1 |
| Бэкенд | ru | 6 | 30 | 6 | 20.0% | Бекенд ×2; Бэкент ×2; Бэкинд ×1; Бэкэнд ×1 |
| воркер | ru | 6 | 30 | 6 | 20.0% | рокер ×2; ворер ×1; воркеем ×1; воркть ×1 |
| легаси-код | ru | 6 | 30 | 6 | 20.0% | легаси-кд ×2; легаси-кад ×1; легаси-кот ×1; легасий код ×1 |
| Kafka | en | 4 | 20 | 5 | 25.0% | cafe ×2; Kafke ×1; kafa ×1; kaka ×1 |
| learning rate | en | 4 | 20 | 5 | 25.0% | learning rata ×2; learing rate ×1; learning rete ×1; learnng rate ×1 |
| валидации | ru | 6 | 29 | 5 | 17.2% | валедации ×2; валидаци ×1; валидацие ×1; волидации ×1 |
| Промпт | ru | 6 | 30 | 5 | 16.7% | Помпт ×1; Промом ×1; Промпд ×1; Промт ×1 |
| Ревьюер | ru | 6 | 30 | 5 | 16.7% | Ревьюее ×2; Ревьюе ×1; Ревьюр ×1; Реьюер ×1 |
| ребейзом | mixed | 6 | 30 | 5 | 16.7% | ребейсом ×3; реейзом ×1; рибейзом ×1 |
| Jenkins | en | 4 | 20 | 4 | 20.0% | Jenkin ×3; jekins ×1 |
| cron | en | 4 | 20 | 4 | 20.0% | crun ×2; Kron ×1; crown ×1 |
| переобучилась | ru | 6 | 29 | 4 | 13.8% | пееобучилась ×1; переобучилас ×1; переобучилсь ×1; пиреобучилась ×1 |
| Qdrant | mixed | 6 | 30 | 4 | 13.3% | кудранд ×2; Qdrat ×1; Кидрант ×1 |
| rank | mixed | 6 | 30 | 4 | 13.3% | ранг ×3; renk ×1 |
| Квантизация | ru | 6 | 30 | 4 | 13.3% | Кванизация ×1; Квантизаия ×1; Квантизция ×1; Квонтизация ×1 |
| отрефакторить | ru | 6 | 30 | 4 | 13.3% | отрефакториой ×1; отрефакторит ×1; отрефакторити ×1; отрефаторить ×1 |
| прода | ru | 6 | 30 | 4 | 13.3% | прада ×1; продой ×1; прое ×1; проы ×1 |
| GitHub Actions | en | 4 | 20 | 3 | 15.0% | Gihub Actions ×1; GitHub Action ×1; GitHub Actiuns ×1 |
| Node.js | en | 4 | 20 | 3 | 15.0% | Noda js ×1; Nude.js ×1; noda.js ×1 |

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (94.1% spelled out)
- repair garbled terms (24.9% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| инференс | ru | 5 | 4 | 20.0% | интерфейс ×4; инференс ×1 |
| смёржим | ru | 5 | 4 | 0.0% | смержим ×1; смёрже ×1; смёржить ×1; смёржм ×1 |
| фронт | ru | 5 | 4 | 20.0% | фон ×4; фронт ×1 |
| Kafka | en | 5 | 3 | 20.0% | cafe ×2; Kafka ×1; kafa ×1; kafka ×1 |
| Qdrant | mixed | 5 | 3 | 0.0% | Qdrat ×1; Кидрант ×1; кдрант ×1; кудранд ×1 |
| cron | en | 5 | 3 | 40.0% | cron ×2; crun ×2; Kron ×1 |
| learning rate | en | 5 | 3 | 40.0% | learning rata ×2; learning rate ×2; learing rate ×1 |
| эмбеддинги | ru | 5 | 3 | 40.0% | имбеддинги ×2; эмбеддинги ×2; эмбедденги ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | bump | bomp | 1 |
| sub_near | check | chack | 1 |
| sub_near | pipeline | pipelie | 1 |
| sub_near | sync | sink | 1 |
| sub | the | a | 1 |
| ins | ∅ | дэ | 1 |
| ins | ∅ | минус | 1 |
| sub_near | длинный | длинныу | 1 |

Number style examples: 10:30 → десять тридцать; 12 → двенадцать; 128 → сто двадцать восемь; 18:00 → восемнадцать

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 70.3%)
- convert spoken numbers, times and dates to digits (100.0% spelled out)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| pytest | en | 5 | 5 | 0.0% | pie test ×4; pytast ×1 |
| PyTorch | mixed | 5 | 2 | 0.0% | пайторча ×3; пайорч ×1; пайтоем ×1 |
| Rust | en | 5 | 2 | 60.0% | Rust ×3; Rost ×1; rost ×1 |
| issue | mixed | 5 | 2 | 0.0% | ишью ×3; ешю ×2 |
| learning rate | en | 5 | 2 | 60.0% | learning rate ×3; learning rete ×1; learnng rate ×1 |
| rank | mixed | 5 | 2 | 0.0% | ранк ×3; ранг ×2 |
| пулл-реквесту | ru | 5 | 2 | 60.0% | пулл-реквесту ×3; пул-реквесту ×1; пулреквесту ×1 |
| GitHub Actions | en | 5 | 1 | 0.0% | GitHub actions ×4; GitHub Actiuns ×1 |

Number style examples: 10:30 → десять тридцать; 12 → двенадцать; 128 → сто двадцать восемь; 16 → шестнадцать

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (16.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| инференс | ru | 5 | 5 | 0.0% | инфренс ×3; инферинс ×1; инфернс ×1 |
| фронт | ru | 5 | 4 | 20.0% | фронд ×3; фронем ×1; фронт ×1 |
| Бэкенд | ru | 5 | 3 | 40.0% | Бэкенд ×2; Бэкент ×2; Бекенд ×1 |
| воркер | ru | 5 | 3 | 40.0% | воркер ×2; ворер ×1; воркеем ×1; воркть ×1 |
| лосс | ru | 5 | 3 | 40.0% | лосс ×2; лас ×1; лоз ×1; лосз ×1 |
| ребейзом | mixed | 5 | 3 | 40.0% | ребейсом ×3; ребейзом ×2 |
| токенизацию | ru | 5 | 3 | 40.0% | токенизацию ×2; тоенизацию ×1; токенезацию ×1; токинизацию ×1 |
| эмбеддинги | ru | 5 | 3 | 40.0% | эмбединги ×3; эмбеддинги ×2 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | длинный | тысяч | 1 |
| sub_near | к | ко | 1 |
| sub_near | на | а | 1 |
| sub | падает | тысяч | 1 |
| sub_near | переполнено | переполнена | 1 |

Number style examples: 128 → сто двадцать восемь; 16 → шестнадцать

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| пулл-реквесту | ru | 5 | 2 | 0.0% | пулл реквесту ×3; пул реквесту ×2 |
| Промпт | ru | 5 | 1 | 0.0% | промпт ×4; промпд ×1 |
| валидации | ru | 5 | 1 | 80.0% | валидации ×4; валидаци ×1 |
| воркер | ru | 5 | 1 | 80.0% | воркер ×4; воркю ×1 |
| прода | ru | 5 | 1 | 80.0% | прода ×4; прада ×1 |
| эмбеддинги | ru | 5 | 1 | 80.0% | эмбеддинги ×4; эмбединге ×1 |

Number style examples: 10:30 → десять тридцать; 12 → двенадцать; 128 → сто двадцать восемь; 16 → шестнадцать

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- drop hallucinated tails (9.2% of utterances)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Dmitri | en | 5 | 2 | 60.0% | Dmitri ×3; Dmitry ×2 |
| Jenkins | en | 5 | 2 | 60.0% | Jenkins ×3; Jenkin ×2 |
| легаси-код | ru | 5 | 2 | 0.0% | легаси код ×3; легаси-кот ×1; легоси-код ×1 |
| смёржим | ru | 5 | 2 | 40.0% | смерджим ×2; смёржим ×2; смержим ×1 |
| Terraform | en | 5 | 1 | 80.0% | Terraform ×4; teraform ×1 |
| Бэкенд | ru | 5 | 1 | 80.0% | Бэкенд ×4; Бэкэнд ×1 |
| Промпт | ru | 5 | 1 | 80.0% | Промпт ×4; Промпд ×1 |
| Ревьюер | ru | 5 | 1 | 80.0% | Ревьюер ×4; Ревьюе ×1 |

Number style examples: 128 → сто двадцать восемь; 800 → восемьсот; 0.0003 → zero point zero zero zero three; 12 → двенадцать

Hallucinated tails: “Thank you for watching.” ×5; “Продолжение следует...” ×4; “Субтитры сделал DimaTorzok” ×3
