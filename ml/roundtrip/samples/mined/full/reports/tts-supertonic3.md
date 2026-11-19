# Error mining — full CPU run, supertonic3

Rows mined: 7127 (skipped with null hypothesis: 0). Sources: all_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 101 | 8.5% | 4.8% | 2.4% | 22.9% | 0.8% | 54.7% | sentence | full | digits | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 660 | 14.7% | 12.4% | 6.4% | 59.5% | 2.2% | 32.1% | sentence | partial | digits | mixed | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 660 | 14.3% | 11.8% | 7.0% | 57.3% | 1.9% | 32.6% | sentence | full | digits | mixed | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 761 | 18.2% | 13.1% | 9.2% | 49.2% | 5.3% | 34.4% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 102 | 10.9% | 6.2% | 3.8% | 27.5% | 1.5% | 51.4% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 762 | 13.4% | 9.7% | 5.4% | 39.4% | 3.2% | 42.8% | sentence | full | digits | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 762 | 12.3% | 8.7% | 5.0% | 38.1% | 2.3% | 44.9% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 762 | 23.6% | 18.7% | 11.7% | 56.5% | 10.5% | 31.2% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 660 | 21.1% | 16.0% | 12.8% | 70.1% | 4.3% | 20.5% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 762 | 9.7% | 6.6% | 3.9% | 28.4% | 1.8% | 53.7% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 373 | 11.7% | 7.6% | 4.8% | 30.8% | 2.5% | 50.5% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 762 | 14.5% | 10.8% | 6.0% | 42.8% | 3.9% | 40.1% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 101 | 8.5% | 4.8% | 2.4% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 359 | 19.7% | 16.7% | 9.6% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 301 | 8.5% | 7.1% | 2.6% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 359 | 19.2% | 16.1% | 10.4% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 301 | 8.2% | 6.4% | 2.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 102 | 14.8% | 9.1% | 5.3% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 358 | 23.7% | 16.9% | 15.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 301 | 12.8% | 10.0% | 3.5% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 102 | 10.9% | 6.2% | 3.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 102 | 9.7% | 5.9% | 2.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 359 | 17.2% | 12.9% | 8.2% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 301 | 10.0% | 7.1% | 3.1% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 102 | 9.3% | 5.9% | 2.3% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 359 | 15.9% | 11.8% | 7.8% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 301 | 9.1% | 6.0% | 2.7% |
| `qwen3-asr-0.6b@onnx-int8` | en | 102 | 10.9% | 5.5% | 3.6% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 359 | 30.6% | 24.4% | 18.4% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 301 | 19.9% | 17.0% | 6.3% |
| `t-one@onnx` | mixed | 359 | 27.4% | 20.8% | 20.5% |
| `t-one@onnx` | ru | 301 | 13.3% | 10.0% | 3.6% |
| `whisper-large-v3-turbo@onnx-int8` | en | 102 | 7.9% | 4.5% | 2.0% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 359 | 12.8% | 8.9% | 6.1% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 301 | 6.5% | 4.5% | 2.0% |
| `whisper-medium@onnx-int8` | en | 51 | 8.1% | 4.2% | 2.1% |
| `whisper-medium@onnx-int8` | mixed | 174 | 13.7% | 9.8% | 6.0% |
| `whisper-medium@onnx-int8` | ru | 148 | 10.4% | 6.2% | 4.4% |
| `whisper-small@onnx-int8` | en | 102 | 9.3% | 6.2% | 2.6% |
| `whisper-small@onnx-int8` | mixed | 359 | 18.3% | 13.9% | 9.0% |
| `whisper-small@onnx-int8` | ru | 301 | 11.9% | 8.9% | 3.6% |

## WER by channel

| ASR model | channel | rows | WER | style-aware WER |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | clean | 79 | 8.7% | 5.0% |
| `distil-large-v3.5@onnx-int8` | phone_noisy | 22 | 7.7% | 4.2% |
| `gigaam-v3-e2e-ctc@onnx-int8` | clean | 533 | 14.6% | 12.3% |
| `gigaam-v3-e2e-ctc@onnx-int8` | phone_noisy | 127 | 15.0% | 13.1% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | clean | 533 | 14.2% | 11.4% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | phone_noisy | 127 | 14.9% | 13.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | clean | 612 | 17.8% | 12.7% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | phone_noisy | 149 | 19.8% | 14.6% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | clean | 80 | 11.0% | 6.2% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | phone_noisy | 22 | 10.6% | 6.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | clean | 613 | 13.2% | 9.3% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | phone_noisy | 149 | 14.2% | 10.9% |
| `parakeet-ultra-0.6b@onnx-int8` | clean | 613 | 12.0% | 8.3% |
| `parakeet-ultra-0.6b@onnx-int8` | phone_noisy | 149 | 13.9% | 10.5% |
| `qwen3-asr-0.6b@onnx-int8` | clean | 613 | 23.0% | 18.2% |
| `qwen3-asr-0.6b@onnx-int8` | phone_noisy | 149 | 26.0% | 21.1% |
| `t-one@onnx` | clean | 533 | 20.7% | 15.3% |
| `t-one@onnx` | phone_noisy | 127 | 22.9% | 18.7% |
| `whisper-large-v3-turbo@onnx-int8` | clean | 613 | 9.4% | 6.2% |
| `whisper-large-v3-turbo@onnx-int8` | phone_noisy | 149 | 10.8% | 8.3% |
| `whisper-medium@onnx-int8` | clean | 302 | 11.7% | 7.5% |
| `whisper-medium@onnx-int8` | phone_noisy | 71 | 11.6% | 8.1% |
| `whisper-small@onnx-int8` | clean | 613 | 14.1% | 10.5% |
| `whisper-small@onnx-int8` | phone_noisy | 149 | 16.1% | 12.2% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 100.0% | 100.0% | 82.7% | 100.0% | 100.0% | 116.7% | 100.0% | 99.9% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 99.7% | 94.5% | 88.6% | 23.9% | 84.8% | 87.7% | 69.8% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 94.9% | 100.0% | 100.0% | 85.6% | 90.0% | 63.2% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 99.9% | 99.6% | 80.8% | 75.9% | 36.0% | 81.6% | 15.6% | 89.6% | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 82.2% | 24.5% | 0.0% | 55.6% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 92.5% | 99.9% | 99.9% | 91.9% | 95.6% | 95.6% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 100.0% | 100.0% | 91.0% | 100.0% | 100.0% | 94.0% | 85.5% | 95.3% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 99.5% | 99.2% | 82.1% | 99.7% | 99.6% | 80.4% | 3.1% | 92.2% | 7.3% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 92.3% | 99.5% | 98.3% | 86.1% | 98.6% | 95.8% | 15.3% |
| `whisper-medium@onnx-int8` | 99.7% | 99.2% | 91.3% | 99.2% | 98.1% | 85.3% | 97.5% | 96.6% | 7.7% |
| `whisper-small@onnx-int8` | 99.7% | 99.3% | 88.4% | 99.7% | 98.3% | 85.8% | 92.7% | 95.0% | 10.1% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | supertonic3 | en | 101 | 8.5% | 4.8% | 54.7% | 23.6% | 99.9% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | mixed | 359 | 19.7% | 16.7% | 16.8% | 67.5% | 69.8% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | ru | 301 | 8.5% | 7.1% | 51.5% | 36.4% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | mixed | 359 | 19.2% | 16.1% | 16.8% | 66.6% | 63.2% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | ru | 301 | 8.2% | 6.4% | 52.6% | 32.6% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | en | 102 | 14.8% | 9.1% | 40.2% | 28.5% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | mixed | 358 | 23.7% | 16.9% | 19.5% | 55.0% | 49.3% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | ru | 301 | 12.8% | 10.0% | 51.0% | 34.8% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | supertonic3 | en | 102 | 10.9% | 6.2% | 51.4% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | en | 102 | 9.7% | 5.9% | 53.3% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | mixed | 359 | 17.2% | 12.9% | 28.9% | 47.9% | 81.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | ru | 301 | 10.0% | 7.1% | 56.5% | 26.4% | – |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | en | 102 | 9.3% | 5.9% | 50.9% | 26.2% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | mixed | 359 | 15.9% | 11.8% | 31.6% | 44.8% | 80.6% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | ru | 301 | 9.1% | 6.0% | 59.5% | 23.4% | – |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | en | 102 | 10.9% | 5.5% | 50.5% | 24.8% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | mixed | 359 | 30.6% | 24.4% | 20.1% | 57.7% | 60.1% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | ru | 301 | 19.9% | 17.0% | 38.3% | 48.8% | – |
| `t-one@onnx` | supertonic3 | mixed | 359 | 27.4% | 20.8% | 2.9% | 73.4% | 0.0% |
| `t-one@onnx` | supertonic3 | ru | 301 | 13.3% | 10.0% | 42.8% | 39.8% | – |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | en | 102 | 7.9% | 4.5% | 56.1% | 20.1% | 99.8% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | mixed | 359 | 12.8% | 8.9% | 43.1% | 33.0% | 85.5% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | ru | 301 | 6.5% | 4.5% | 66.2% | 19.1% | – |
| `whisper-medium@onnx-int8` | supertonic3 | en | 51 | 8.1% | 4.2% | 61.7% | 21.5% | 99.7% |
| `whisper-medium@onnx-int8` | supertonic3 | mixed | 174 | 13.7% | 9.8% | 44.9% | 35.8% | 88.4% |
| `whisper-medium@onnx-int8` | supertonic3 | ru | 148 | 10.4% | 6.2% | 53.2% | 22.6% | – |
| `whisper-small@onnx-int8` | supertonic3 | en | 102 | 9.3% | 6.2% | 51.9% | 25.7% | 99.9% |
| `whisper-small@onnx-int8` | supertonic3 | mixed | 359 | 18.3% | 13.9% | 31.4% | 46.5% | 79.1% |
| `whisper-small@onnx-int8` | supertonic3 | ru | 301 | 11.9% | 8.9% | 46.9% | 35.0% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| передеплоить | ru | 10 | 57 | 52 | 91.2% | передеплеить ×8; передеплейть ×7; передеплейт ×6; передиплеить ×6 |
| багфикс | ru | 10 | 57 | 49 | 86.0% | бакфикс ×32; Бакфикс ×12; бакфис ×3; баксикс ×1 |
| постмортем | ru | 9 | 45 | 42 | 93.3% | постмартем ×12; постмартём ×3; по смартем ×2; по смартём ×2 |
| эстимейт | ru | 10 | 47 | 42 | 89.4% | стимейт ×9; и стимейт ×3; имеет ×2; истемей ×2 |
| прод | ru | 10 | 57 | 41 | 71.9% | прот ×25; Прот ×2; на прод вечером → напродвечером ×2; на прод → напрод ×2 |
| ребейз | ru | 10 | 47 | 40 | 85.1% | ребейс ×27; ребей ×3; Рибейс ×2; рибейс ×2 |
| отребейзить | ru | 10 | 38 | 36 | 94.7% | отребезить ×33; отревезить ×1; отреть изить ×1; отрибейзить ×1 |
| дообучение | ru | 10 | 39 | 34 | 87.2% | до обучения ×27; дабычение ×2; до обучеениея ×1; до обучениея ×1 |
| лейтенси | ru | 10 | 56 | 33 | 58.9% | лейтинси ×7; лейтензии ×3; лейтенци ×3; лейтенсии ×2 |
| LiteLLM | mixed | 10 | 29 | 29 | 100.0% | Light LM ×5; Alone ×3; Light Alarm ×1; Light L M ×1 |
| kubectl logs | mixed | 10 | 29 | 28 | 96.5% | Quebec Logs ×2; кубек локс ×2; Back to Locs ×1; Back to Lox ×1 |
| дейлик | ru | 10 | 37 | 28 | 75.7% | делик ×12; делек ×3; деелик ×2; дельегат ×2 |
| ls -la | mixed | 9 | 27 | 27 | 100.0% | ESL ×3; Лесла ×3; ∅ ×2; ASLA ×2 |
| llama.cpp | mixed | 10 | 29 | 27 | 93.1% | лама. CPP ×5; Лама CPP ×2; лама ×2; лама.  CPP ×2 |
| дашборд | ru | 10 | 47 | 27 | 57.5% | дашбор ×5; дашборт ×5; борт ×4; наш борт ×3 |
| техлид | ru | 10 | 28 | 26 | 92.9% | тех лет ×10; техлет ×5; Техлет ×4; Техлит ×4 |
| .gitignore | mixed | 10 | 28 | 25 | 89.3% | гитнёр ×2; Digner ×1; Get Ignore ×1; GitNure ×1 |
| JNI | mixed | 10 | 28 | 25 | 89.3% | GNI ×9; JNI и → GNI ×4; ∅ ×1; GNA ×1 |
| хотфикс | ru | 10 | 48 | 25 | 52.1% | Хатфикс ×7; хатфикс ×6; ходфикс ×4; их отфикс ×2 |
| коммит | ru | 10 | 28 | 24 | 85.7% | комет ×7; комит ×5; комед ×2; коми ×2 |
| BLEU | mixed | 10 | 29 | 24 | 82.8% | BLU ×6; BAL-EU ×1; BU ×1; Ball и U ×1 |
| Авито | ru | 10 | 46 | 24 | 52.2% | с Авито → Совета ×7; Авита ×4; с Авито → Савито ×3; с Авито → савита ×3 |
| тимлид | ru | 9 | 27 | 23 | 85.2% | Тимлет ×11; тимлет ×6; Тимлит ×2; Темлет ×1 |
| Vercel | mixed | 10 | 28 | 23 | 82.1% | Варсал ×3; Версал ×3; версал ×2; Varsal ×1 |
| эвал | ru | 10 | 29 | 23 | 79.3% | его ×3; вал ×2; ивал ×2; на эвал → навал ×2 |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (23.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Milvus | en | 2 | 2 | 0.0% | Milvas ×1; Milvos ×1 |
| systemctl restart | en | 2 | 2 | 0.0% | system control restart ×2 |
| top-k | en | 2 | 2 | 0.0% | top ×2 |
| Axolotl | en | 1 | 1 | 0.0% | Axel-Lawal ×1 |
| Cilium | en | 1 | 1 | 0.0% | psyllium ×1 |
| ClickHouse | en | 1 | 1 | 0.0% | click-out ×1 |
| Consul | en | 1 | 1 | 0.0% | console ×1 |
| GLM-4.5 | en | 1 | 1 | 0.0% | GM 4.5 ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | at | 1 |
| ins | ∅ | o'clock | 1 |
| ins | ∅ | out | 1 |
| sub_near | caching | catching | 1 |
| sub_near | ffmpeg | fefenpeg | 1 |
| del | idea | ∅ | 1 |
| sub_near | in | an | 1 |
| sub_near | retry | rittri | 1 |

Number style examples: 18:00 → 18

## `gigaam-v3-e2e-ctc@onnx-int8`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 84.8% of reference)
- spell out again numbers the writer wanted in words (33.3% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (30.2% of Latin words)
- repair garbled terms (53.8% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×5; Бакфикс ×1 |
| передеплоить | ru | 6 | 6 | 0.0% | передеплейть ×3; передеплеить ×2; передиплейть ×1 |
| тикет | ru | 6 | 6 | 0.0% | этикет ×6 |
| Авито | ru | 5 | 5 | 0.0% | Авита ×2; Аvito ×1; с Авито → Совета ×2 |
| постмортем | ru | 5 | 5 | 0.0% | Смартом ×1; паспортём ×1; пастмортям ×1; пост-мортм ×1 |
| эстимейт | ru | 5 | 5 | 0.0% | Estmat ×1; Stmat ×1; истемей ×1; стимейт ×1 |
| лейтенси | ru | 6 | 5 | 16.7% | лейтинси ×3; Лэйтенси ×1; лейтенси ×1; рейтинси ×1 |
| конфиг | ru | 4 | 4 | 0.0% | конфликт ×2; конфик ×1; конфлик ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | исправление | исправления | 4 |
| del | в | ∅ | 3 |
| del | и | ∅ | 3 |
| sub_near | запасной | запасный | 2 |
| del | с | ∅ | 2 |
| sub_near | уведомление | уведомления | 2 |
| ins | ∅ | setploss | 1 |
| ins | ∅ | гляньте | 1 |

Number style examples: 100 → сто; 10:00 → 10; 11:00 → 11; 11:00 → одиннадцати

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (66.7% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (36.8% of Latin words)
- repair garbled terms (51.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 6 | 6 | 0.0% | Бакфикс ×4; бакфикс ×2 |
| передеплоить | ru | 6 | 6 | 0.0% | передеплеить ×4; передеплейть ×2 |
| прод | ru | 6 | 6 | 0.0% | прот ×5; пруд ×1 |
| тикет | ru | 6 | 6 | 0.0% | этикет ×6 |
| дашборд | ru | 5 | 5 | 0.0% | дашборт ×2; борт ×1; дашбот ×1; дош борт ×1 |
| постмортем | ru | 5 | 5 | 0.0% | пастмартём ×1; пост мартём ×1; постмартям ×1; постмартём ×1 |
| ребейз | ru | 5 | 5 | 0.0% | ребейс ×4; Рибейс ×1 |
| эстимейт | ru | 5 | 5 | 0.0% | исмейт ×1; истемей ×1; истюмейт ×1; стимейд ×1; эстимейт в задаче → мейдзадаче ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 4 |
| sub_near | исправление | исправления | 4 |
| del | с | ∅ | 3 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| sub_near | уведомление | уведомления | 2 |
| ins | ∅ | john | 1 |
| ins | ∅ | setploss | 1 |

Number style examples: 100 → сто; 10:00 → 10; 12:00 → 12; 11:00 → одиннадцати

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 81.6% of reference)
- convert spoken numbers, times and dates to digits (84.4% spelled out)
- spell out again numbers the writer wanted in words (66.7% of spelled-out numbers come back as digits)
- repair garbled terms (43.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 6 | 6 | 0.0% | Бакфикс ×5; бакфикс ×1 |
| передеплоить | ru | 6 | 6 | 0.0% | передиплеить ×2; передиплеть ×2; передеплыть ×1; передиплэть ×1 |
| Авито | ru | 5 | 5 | 0.0% | Авита ×2; с Авито → Совета ×2; с Авито → Совита ×1 |
| ребейз | ru | 5 | 5 | 0.0% | ребейс ×3; Рибейс ×1; ребе из ×1 |
| хотфикс | ru | 5 | 5 | 0.0% | ходфикс ×2; их отфикс ×1; ход Фикс ×1; ходикс ×1 |
| эстимейт | ru | 5 | 5 | 0.0% | стиммейт ×2; и стеметь ×1; из стиммейт ×1; имеет ×1 |
| лейтенси | ru | 6 | 5 | 0.0% | Лейтинси ×1; лейтенсии ×1; лейтенци ×1; лейтинси ×1; с лейтенси → Слейтенси ×1 |
| датасет | ru | 4 | 4 | 0.0% | Dataсеt ×1; датасеты ×1; дотосет ×1; датасет с → дотосет ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | и | ∅ | 9 |
| del | в | ∅ | 5 |
| del | в | ∅ | 5 |
| del | с | ∅ | 3 |
| sub_near | запасной | запасный | 2 |
| sub_near | записей | записи | 2 |
| sub_near | используется | используются | 2 |
| sub_near | коллеги | коллегии | 2 |

Number style examples: 11:30 → одиннадцать-тридцать; 14:30 → четырнадцать тридцать; 100 → сто; 30 → thirty

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 55.6% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (24.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| CLIP | en | 2 | 2 | 0.0% | CLAP ×1; Kalapi ×1 |
| systemctl restart | en | 2 | 2 | 0.0% | system control restart ×2 |
| top-k | en | 2 | 2 | 0.0% | top ×2 |
| uv sync | en | 2 | 2 | 0.0% | of sync ×1; youve sync ×1 |
| Apache Iceberg | en | 1 | 1 | 0.0% | Apaka iceberg ×1 |
| BERT | en | 1 | 1 | 0.0% | Burke ×1 |
| CHANGELOG | en | 1 | 1 | 0.0% | Chanalog ×1 |
| Cilium | en | 1 | 1 | 0.0% | pilium ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | five | 1 |
| ins | ∅ | four | 1 |
| ins | ∅ | gm | 1 |
| ins | ∅ | it | 1 |
| ins | ∅ | o'clock | 1 |
| ins | ∅ | point | 1 |
| ins | ∅ | wv | 1 |
| sub_near | and tail | untail | 1 |

Number style examples: 30 → thirty; 14:30 → fourteen thirty; 18:00 → eighteen; 2.5 → two point five

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (36.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×6 |
| постмортем | ru | 5 | 5 | 0.0% | постмартем ×2; по смарт<unk>м ×1; по смартем ×1; с Мартем ×1 |
| ребейз | ru | 5 | 5 | 0.0% | ребейс ×5 |
| эстимейт | ru | 5 | 5 | 0.0% | стимейт ×3; и стимейт ×1; истимей ×1 |
| передеплоить | ru | 6 | 5 | 16.7% | передиплейт ×2; передиплоить ×2; передеплоит ×1; передеплоить ×1 |
| дейлик | ru | 4 | 4 | 0.0% | делик ×2; деелик ×1; деяк ×1 |
| отребейзить | ru | 4 | 4 | 0.0% | отребезить ×4 |
| Олей | mixed | 6 | 4 | 16.7% | Алей ×3; Олей ×1; Солей ×1; с Олей → солей ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 4 |
| sub_near | правки | прав | 4 |
| del | и | ∅ | 3 |
| ins | ∅ | и | 2 |
| sub_near | запасной | запасный | 2 |
| sub | и | sony | 2 |
| sub | из | с | 2 |
| sub_near | пришли | приш | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14.30; 15:30 → 15.30; 10:00 → 10

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (34.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×5; Бакфикс ×1 |
| постмортем | ru | 5 | 5 | 0.0% | постмартем ×3; по смартем ×1; с Мартем ×1 |
| ребейз | ru | 5 | 5 | 0.0% | ребейс ×5 |
| эстимейт | ru | 5 | 5 | 0.0% | стимейт ×3; и стимейт ×1; из тимейт ×1 |
| дейлик | ru | 4 | 4 | 0.0% | делик ×2; делек ×1; дерек ×1 |
| отребейзить | ru | 4 | 4 | 0.0% | отребезить ×4 |
| HAProxy | mixed | 3 | 3 | 0.0% | H-Proxy ×1; H-прокси ×1; А прокси ×1 |
| Hadoop | mixed | 3 | 3 | 0.0% | Hadob ×1; Хаду ×1; Хадуб ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 2 |
| sub_near | вывод | выводку | 2 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| sub | и | sony | 2 |
| sub | из | с | 2 |
| sub_near | правки | прав | 2 |
| del | в | ∅ | 2 |

Number style examples: 14:30 → 14.30; 11:30 → 11.30; 15:30 → 15.30; 15:30 → пятнадцать тридцать

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (96.9% spelled out)
- repair garbled terms (49.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| лейтенси | ru | 6 | 6 | 0.0% | Летинсия ×1; лейт-нс и ×1; лейтинга ×1; лейтинги ×1; лейтенси на → Лейтенсена ×1; с лейтенси → слайденте ×1 |
| передеплоить | ru | 6 | 6 | 0.0% | передеплейт ×3; передеплать ×1; передеплейить ×1; передиплейт ×1 |
| прод | ru | 6 | 6 | 0.0% | прот ×3; прототип ×1; пруд ×1; прод опять → протопять ×1 |
| легаси | ru | 5 | 5 | 0.0% | легесси ×1; легкой ×1; легосе ×1; легости ×1; легаси код → легосекот ×1 |
| ребейз | ru | 5 | 5 | 0.0% | ребейс ×2; рибейс ×2; ребеис ×1 |
| реранкер | ru | 5 | 5 | 0.0% | Риранкер ×2; риранкер ×2; а реранкер → Ариранкер ×1 |
| багфикс | ru | 6 | 5 | 16.7% | бакфис ×3; багфикс ×1; бакфикс ×1; бакфиск ×1 |
| дейлик | ru | 4 | 4 | 0.0% | дельегат ×2; делик ×1; на дейлик → надейли ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 6 |
| del | и | ∅ | 5 |
| sub_near | пятницу | пяточку | 5 |
| sub_near | объясни | объясните | 4 |
| sub_near | пятницу | пяточку | 4 |
| sub_near | скинь | скин | 4 |
| ins | ∅ | в | 3 |
| ins | ∅ | и | 3 |

Number style examples: 14:30 → четырнадцать тридцать; 11:30 → одиннадцать тридцать; 15:30 → пятнадцать тридцать; 15:00 → пятнадцати

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)
- repair garbled terms (58.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×5; баксикс ×1 |
| передеплоить | ru | 6 | 6 | 0.0% | передиплеить ×3; передеплеить ×2; передиплыть ×1 |
| тикет | ru | 6 | 6 | 0.0% | этикет ×6 |
| дашборд | ru | 5 | 5 | 0.0% | дашбор ×2; дашборт ×2; шборт ×1 |
| постмортем | ru | 5 | 5 | 0.0% | постмартем ×2; по смартям ×1; посмартем ×1; смартьем ×1 |
| ребейз | ru | 5 | 5 | 0.0% | ребей ×2; ребейс ×2; ребей ис ×1 |
| хотфикс | ru | 5 | 5 | 0.0% | хатфикс ×3; отфик ×1; ходфикс ×1 |
| эстимейт | ru | 5 | 5 | 0.0% | из темей ×1; имеет ×1; истимеет ×1; с тимейт ×1; Скинь эстимейт → скинестемеет ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 10 |
| del | и | ∅ | 9 |
| ins | ∅ | и | 3 |
| del | а | ∅ | 2 |
| sub_near | запасной | запасный | 2 |
| sub_near | на | но | 2 |
| sub_near | похоже | похожий | 2 |
| sub_near | проверил | проверила | 2 |

Number style examples: 11:30 → одиннадцать тридцать; 14:30 → четырнадцать тридцать; 15:30 → пятнадцать тридцать; 100 → сто

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (25.9% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 6 | 6 | 0.0% | передеплейт ×3; передеплейить ×1; передеплейть ×1; передеплэйть ×1 |
| постмортем | ru | 5 | 5 | 0.0% | по смартём ×2; постмартем ×2; Мартем ×1 |
| прод | ru | 6 | 5 | 16.7% | прот ×5; прод ×1 |
| дообучение | ru | 4 | 4 | 0.0% | до обучения ×4 |
| отребейзить | ru | 4 | 4 | 0.0% | отребезить ×4 |
| Олей | mixed | 6 | 4 | 16.7% | Алей ×3; Олей ×1; аллей ×1; с Олей → солей ×1 |
| .gitignore | mixed | 3 | 3 | 0.0% | GitNure ×1; гитингора ×1; gitignore к → Детигнёрк ×1 |
| BLEU | mixed | 3 | 3 | 0.0% | BellyU ×1; Беллиу ×1; бэллиу ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | и | ∅ | 4 |
| del | в | ∅ | 3 |
| sub_near | запасной | запасный | 2 |
| sub_near | правок | справок | 2 |
| sub_near | проверил | проверила | 2 |
| del | у | ∅ | 2 |
| sub_near | покажи | пока же | 2 |
| sub_near | уведомление | уведомления | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14.30; 15:30 → 15.30; 10:00 → 10

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (28.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| дообучение | ru | 3 | 3 | 0.0% | до обучения ×3 |
| передеплоить | ru | 3 | 3 | 0.0% | передиплать ×2; передиплеить ×1 |
| BLEU | mixed | 2 | 2 | 0.0% | BAL-EU ×1; BLU ×1 |
| LiteLLM | mixed | 2 | 2 | 0.0% | Light LM ×1; LitAloom ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | Docker Compose App ×1; Docker Composer App ×1 |
| kubectl logs | mixed | 2 | 2 | 0.0% | кубек локс ×1; кубектл локс ×1 |
| llama.cpp | mixed | 2 | 2 | 0.0% | Lama. Cpp ×1; лама. CPP ×1 |
| pgBouncer | mixed | 2 | 2 | 0.0% | B-Bouncer ×1; piggy bouncer ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | правок | справок | 2 |
| sub_near | проверил | проверила | 2 |
| sub_near | описание | описании | 2 |
| ins | ∅ | o'clock | 1 |
| sub | and | in | 1 |
| ins | ∅ | млэй | 1 |
| ins | ∅ | сепипей | 1 |
| sub_near | 900 | 90 | 1 |

Number style examples: 14:30 → 14.30; 11:30 → 11.30; 12:00 → 12; 15:00 → 15.00

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (39.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×6 |
| передеплоить | ru | 6 | 6 | 0.0% | передеплодить ×2; передиплейть ×2; передеплывать ×1; передеплэйд ×1 |
| прод | ru | 6 | 6 | 0.0% | Прот ×2; прот ×2; прода ×1; прод, я → Продья ×1 |
| постмортем | ru | 5 | 5 | 0.0% | постмартем ×2; в SmartTem ×1; постмартим ×1; постмартём ×1 |
| ребейз | ru | 5 | 5 | 0.0% | ребейс ×4; ребей ×1 |
| лейтенси | ru | 6 | 5 | 0.0% | лейтензии ×3; Latency ×1; лейтенсии ×1; лейтенция ×1 |
| дейлик | ru | 4 | 4 | 0.0% | деелик ×1; делик ×1; деялик ×1; на дейлик → надейлика ×1 |
| дообучение | ru | 4 | 4 | 0.0% | до обучения ×3; добычение ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | и | 3 |
| del | в | ∅ | 3 |
| del | и | ∅ | 3 |
| sub_near | запасной | запасный | 2 |
| sub_near | мне | не | 2 |
| sub_near | проверил | проверила | 2 |
| sub_near | пятничного | петничного | 2 |
| sub_near | сохрани | сохраняя | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14-30; 14:30 → 14.30; 15:30 → 15-30
