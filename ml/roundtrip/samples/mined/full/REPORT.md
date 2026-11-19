# Error mining — full CPU run, primary voices (no Piper ru, TTS-garbled clips dropped)

Rows mined: 8878 (skipped with null hypothesis: 0). Sources: primary_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 307 | 9.6% | 6.3% | 2.8% | 26.7% | 1.9% | 52.5% | sentence | full | digits | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 660 | 14.7% | 12.4% | 6.4% | 59.5% | 2.2% | 32.1% | sentence | partial | digits | mixed | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 660 | 14.3% | 11.8% | 7.0% | 57.3% | 1.9% | 32.6% | sentence | full | digits | mixed | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 967 | 18.9% | 13.5% | 9.3% | 47.5% | 6.2% | 33.7% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 308 | 16.6% | 10.6% | 7.0% | 34.1% | 5.6% | 42.4% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 968 | 13.3% | 9.3% | 5.2% | 37.5% | 3.2% | 43.4% | sentence | full | digits | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 968 | 12.0% | 8.2% | 4.7% | 35.7% | 2.3% | 45.5% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 968 | 22.1% | 16.6% | 10.5% | 52.6% | 8.9% | 33.4% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 660 | 21.1% | 16.0% | 12.8% | 70.1% | 4.3% | 20.5% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 968 | 9.8% | 6.6% | 3.7% | 28.0% | 2.0% | 53.5% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 476 | 11.2% | 7.3% | 4.4% | 29.4% | 2.5% | 51.2% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 968 | 13.9% | 10.2% | 5.5% | 40.6% | 3.7% | 41.7% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 307 | 9.6% | 6.3% | 2.8% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 359 | 19.7% | 16.7% | 9.6% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 301 | 8.5% | 7.1% | 2.6% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 359 | 19.2% | 16.1% | 10.4% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 301 | 8.2% | 6.4% | 2.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 308 | 18.9% | 12.8% | 8.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 358 | 23.7% | 16.9% | 15.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 301 | 12.8% | 10.0% | 3.5% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 308 | 16.6% | 10.6% | 7.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 308 | 12.1% | 7.4% | 3.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 359 | 17.2% | 12.9% | 8.2% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 301 | 10.0% | 7.1% | 3.1% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 308 | 10.2% | 6.4% | 2.9% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 359 | 15.9% | 11.8% | 7.8% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 301 | 9.1% | 6.0% | 2.7% |
| `qwen3-asr-0.6b@onnx-int8` | en | 308 | 15.0% | 8.2% | 5.3% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 359 | 30.6% | 24.4% | 18.4% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 301 | 19.9% | 17.0% | 6.3% |
| `t-one@onnx` | mixed | 359 | 27.4% | 20.8% | 20.5% |
| `t-one@onnx` | ru | 301 | 13.3% | 10.0% | 3.6% |
| `whisper-large-v3-turbo@onnx-int8` | en | 308 | 9.4% | 6.0% | 2.7% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 359 | 12.8% | 8.9% | 6.1% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 301 | 6.5% | 4.5% | 2.0% |
| `whisper-medium@onnx-int8` | en | 154 | 9.1% | 5.6% | 2.5% |
| `whisper-medium@onnx-int8` | mixed | 174 | 13.7% | 9.8% | 6.0% |
| `whisper-medium@onnx-int8` | ru | 148 | 10.4% | 6.2% | 4.4% |
| `whisper-small@onnx-int8` | en | 308 | 10.9% | 7.5% | 3.4% |
| `whisper-small@onnx-int8` | mixed | 359 | 18.3% | 13.9% | 9.0% |
| `whisper-small@onnx-int8` | ru | 301 | 11.9% | 8.9% | 3.6% |

## WER by channel

| ASR model | channel | rows | WER | style-aware WER |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | clean | 241 | 9.2% | 5.8% |
| `distil-large-v3.5@onnx-int8` | phone_noisy | 66 | 11.3% | 8.1% |
| `gigaam-v3-e2e-ctc@onnx-int8` | clean | 533 | 14.6% | 12.3% |
| `gigaam-v3-e2e-ctc@onnx-int8` | phone_noisy | 127 | 15.0% | 13.1% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | clean | 533 | 14.2% | 11.4% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | phone_noisy | 127 | 14.9% | 13.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | clean | 774 | 18.4% | 12.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | phone_noisy | 193 | 20.8% | 15.5% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | clean | 242 | 16.6% | 10.4% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | phone_noisy | 66 | 16.4% | 11.4% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | clean | 775 | 12.9% | 8.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | phone_noisy | 193 | 15.0% | 11.4% |
| `parakeet-ultra-0.6b@onnx-int8` | clean | 775 | 11.5% | 7.7% |
| `parakeet-ultra-0.6b@onnx-int8` | phone_noisy | 193 | 14.0% | 10.5% |
| `qwen3-asr-0.6b@onnx-int8` | clean | 775 | 21.6% | 16.1% |
| `qwen3-asr-0.6b@onnx-int8` | phone_noisy | 193 | 24.3% | 18.9% |
| `t-one@onnx` | clean | 533 | 20.7% | 15.3% |
| `t-one@onnx` | phone_noisy | 127 | 22.9% | 18.7% |
| `whisper-large-v3-turbo@onnx-int8` | clean | 775 | 9.5% | 6.2% |
| `whisper-large-v3-turbo@onnx-int8` | phone_noisy | 193 | 10.9% | 8.1% |
| `whisper-medium@onnx-int8` | clean | 383 | 11.2% | 7.2% |
| `whisper-medium@onnx-int8` | phone_noisy | 93 | 11.3% | 7.7% |
| `whisper-small@onnx-int8` | clean | 775 | 13.4% | 9.8% |
| `whisper-small@onnx-int8` | phone_noisy | 193 | 15.6% | 12.0% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 98.7% | 95.8% | 83.5% | 98.7% | 97.4% | 116.0% | 95.7% | 99.9% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 99.7% | 94.5% | 88.6% | 23.9% | 84.8% | 87.7% | 69.8% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 94.9% | 100.0% | 100.0% | 85.6% | 90.0% | 63.2% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 99.8% | 99.1% | 79.6% | 66.5% | 28.3% | 80.8% | 12.7% | 95.8% | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 81.3% | 28.2% | 0.3% | 51.0% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 99.9% | 99.8% | 89.1% | 99.9% | 99.9% | 92.8% | 86.8% | 98.2% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 99.9% | 99.7% | 88.2% | 100.0% | 100.0% | 94.7% | 85.9% | 98.1% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 99.6% | 99.4% | 82.7% | 99.8% | 99.7% | 84.2% | 4.9% | 97.0% | 7.3% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 99.8% | 99.5% | 89.3% | 99.5% | 98.0% | 86.6% | 97.7% | 98.2% | 15.3% |
| `whisper-medium@onnx-int8` | 99.6% | 98.1% | 89.0% | 99.2% | 98.3% | 85.6% | 97.9% | 98.6% | 7.7% |
| `whisper-small@onnx-int8` | 99.6% | 98.7% | 86.6% | 99.7% | 98.2% | 86.6% | 92.7% | 98.0% | 10.1% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | kokoro1 | en | 102 | 8.0% | 4.0% | 57.6% | 15.2% | 99.9% |
| `distil-large-v3.5@onnx-int8` | piper | en | 104 | 12.2% | 9.8% | 44.9% | 33.2% | 99.9% |
| `distil-large-v3.5@onnx-int8` | supertonic3 | en | 101 | 8.5% | 4.8% | 54.7% | 23.6% | 99.9% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | mixed | 359 | 19.7% | 16.7% | 16.8% | 67.5% | 69.8% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | ru | 301 | 8.5% | 7.1% | 51.5% | 36.4% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | mixed | 359 | 19.2% | 16.1% | 16.8% | 66.6% | 63.2% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | ru | 301 | 8.2% | 6.4% | 52.6% | 32.6% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | kokoro1 | en | 102 | 18.4% | 10.3% | 35.5% | 23.5% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | en | 104 | 23.5% | 18.6% | 26.3% | 45.9% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | en | 102 | 14.8% | 9.1% | 40.2% | 28.5% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | mixed | 358 | 23.7% | 16.9% | 19.5% | 55.0% | 49.3% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | ru | 301 | 12.8% | 10.0% | 51.0% | 34.8% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | kokoro1 | en | 102 | 16.8% | 9.5% | 44.2% | 18.9% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | piper | en | 104 | 21.8% | 15.8% | 31.2% | 40.5% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | supertonic3 | en | 102 | 10.9% | 6.2% | 51.4% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | kokoro1 | en | 102 | 10.2% | 4.5% | 54.4% | 16.1% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | en | 104 | 16.1% | 11.5% | 36.1% | 38.0% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | en | 102 | 9.7% | 5.9% | 53.3% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | mixed | 359 | 17.2% | 12.9% | 28.9% | 47.9% | 81.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | ru | 301 | 10.0% | 7.1% | 56.5% | 26.4% | – |
| `parakeet-ultra-0.6b@onnx-int8` | kokoro1 | en | 102 | 9.4% | 4.4% | 52.1% | 18.0% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | en | 104 | 11.9% | 8.7% | 42.9% | 33.2% | 99.9% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | en | 102 | 9.3% | 5.9% | 50.9% | 26.2% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | mixed | 359 | 15.9% | 11.8% | 31.6% | 44.8% | 80.6% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | ru | 301 | 9.1% | 6.0% | 59.5% | 23.4% | – |
| `qwen3-asr-0.6b@onnx-int8` | kokoro1 | en | 102 | 13.7% | 5.9% | 50.2% | 17.1% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | piper | en | 104 | 20.3% | 13.1% | 31.2% | 39.0% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | en | 102 | 10.9% | 5.5% | 50.5% | 24.8% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | mixed | 359 | 30.6% | 24.4% | 20.1% | 57.7% | 60.1% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | ru | 301 | 19.9% | 17.0% | 38.3% | 48.8% | – |
| `t-one@onnx` | supertonic3 | mixed | 359 | 27.4% | 20.8% | 2.9% | 73.4% | 0.0% |
| `t-one@onnx` | supertonic3 | ru | 301 | 13.3% | 10.0% | 42.8% | 39.8% | – |
| `whisper-large-v3-turbo@onnx-int8` | kokoro1 | en | 102 | 7.5% | 3.3% | 60.4% | 12.4% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | piper | en | 104 | 12.6% | 10.0% | 44.4% | 33.7% | 99.9% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | en | 102 | 7.9% | 4.5% | 56.1% | 20.1% | 99.8% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | mixed | 359 | 12.8% | 8.9% | 43.1% | 33.0% | 85.5% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | ru | 301 | 6.5% | 4.5% | 66.2% | 19.1% | – |
| `whisper-medium@onnx-int8` | kokoro1 | en | 51 | 7.4% | 3.3% | 60.6% | 11.9% | 100.0% |
| `whisper-medium@onnx-int8` | piper | en | 52 | 11.7% | 9.2% | 46.5% | 33.7% | 99.9% |
| `whisper-medium@onnx-int8` | supertonic3 | en | 51 | 8.1% | 4.2% | 61.7% | 21.5% | 99.7% |
| `whisper-medium@onnx-int8` | supertonic3 | mixed | 174 | 13.7% | 9.8% | 44.9% | 35.8% | 88.4% |
| `whisper-medium@onnx-int8` | supertonic3 | ru | 148 | 10.4% | 6.2% | 53.2% | 22.6% | – |
| `whisper-small@onnx-int8` | kokoro1 | en | 102 | 9.5% | 5.0% | 53.9% | 19.4% | 100.0% |
| `whisper-small@onnx-int8` | piper | en | 104 | 13.7% | 11.2% | 40.0% | 36.6% | 99.9% |
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
| asyncio | en | 9 | 26 | 25 | 96.2% | Asincio ×3; Essencio ×3; Asccio ×2; Asensio ×2 |
| .gitignore | mixed | 10 | 28 | 25 | 89.3% | гитнёр ×2; Digner ×1; Get Ignore ×1; GitNure ×1 |
| JNI | mixed | 10 | 28 | 25 | 89.3% | GNI ×9; JNI и → GNI ×4; ∅ ×1; GNA ×1 |
| хотфикс | ru | 10 | 48 | 25 | 52.1% | Хатфикс ×7; хатфикс ×6; ходфикс ×4; их отфикс ×2 |
| pyproject.toml | en | 8 | 24 | 24 | 100.0% | pipreject.toml ×3; piproject.toml ×3; Pipproject.toml ×2; pipproject.toml ×2 |
| коммит | ru | 10 | 28 | 24 | 85.7% | комет ×7; комит ×5; комед ×2; коми ×2 |
| BLEU | mixed | 10 | 29 | 24 | 82.8% | BLU ×6; BAL-EU ×1; BU ×1; Ball и U ×1 |
| Авито | ru | 10 | 46 | 24 | 52.2% | с Авито → Совета ×7; Авита ×4; с Авито → Савито ×3; с Авито → савита ×3 |
| systemctl restart | en | 9 | 25 | 23 | 92.0% |  |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (23.8% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| asyncio | en | 3 | 3 | 0.0% | Asccio ×2; essensio ×1 |
| pyproject.toml | en | 3 | 3 | 0.0% | pipreject.toml ×2; pipproject.toml ×1 |
| systemctl restart | en | 3 | 3 | 0.0% | system control restart ×2; System Control Restart ×1 |
| API | en | 2 | 2 | 0.0% | APE ×1; APLI ×1 |
| Axolotl | en | 2 | 2 | 0.0% | Axel Owl ×1; Axel-Lawal ×1 |
| Cilium | en | 2 | 2 | 0.0% | psyllium ×2 |
| Exynos | en | 2 | 2 | 0.0% | Xenose ×1; xeno's ×1 |
| Golang | en | 2 | 2 | 0.0% | Galang ×2 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | slash | 4 |
| sub_near | drop | draw up | 2 |
| sub | in | and | 2 |
| ins | ∅ | at | 1 |
| ins | ∅ | dot | 1 |
| ins | ∅ | hot | 1 |
| ins | ∅ | o'clock | 1 |
| ins | ∅ | out | 1 |

Number style examples: 3:30 → 330; 18:00 → 18; 2 → two

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
- restore capitalisation of names/terms (kept only 79.6%)
- restore missing punctuation (commas at 80.8% of reference)
- convert spoken numbers, times and dates to digits (87.3% spelled out)
- spell out again numbers the writer wanted in words (66.7% of spelled-out numbers come back as digits)
- repair garbled terms (41.6% of terms are recognition errors) — from context only

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
| del | the | ∅ | 15 |
| del | could | ∅ | 14 |
| del | can | ∅ | 10 |
| del | you | ∅ | 10 |
| del | и | ∅ | 9 |
| del | run | ∅ | 5 |
| del | в | ∅ | 5 |
| del | в | ∅ | 5 |

Number style examples: 11:30 → одиннадцать-тридцать; 14:30 → четырнадцать тридцать; 30 → thirty; 100 → сто

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 51.0% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (27.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×2; Sharp ×1 |
| asyncio | en | 3 | 3 | 0.0% | Asincio ×2; essentio ×1 |
| pyproject.toml | en | 3 | 3 | 0.0% | Pip Project Tamil ×1; PipReject. Toml ×1; PipRject Toml ×1 |
| systemctl restart | en | 3 | 3 | 0.0% | system control restart ×3 |
| AMD | en | 2 | 2 | 0.0% | MD ×2 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLI ×1 |
| BERT | en | 2 | 2 | 0.0% | Burke ×1; Pred ×1 |
| CLIP | en | 2 | 2 | 0.0% | CLAP ×1; Kalapi ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | could | ∅ | 14 |
| del | the | ∅ | 11 |
| del | can | ∅ | 10 |
| del | you | ∅ | 8 |
| sub | in | and | 4 |
| del | run | ∅ | 4 |
| del | use | ∅ | 4 |
| del | add | ∅ | 3 |

Number style examples: 30 → thirty; 3 → three; 3:30 → three thirty; 5.0 → five

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (75.0% of spelled-out numbers come back as digits)
- repair garbled terms (34.3% of terms are recognition errors) — from context only

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
| ins | ∅ | sharp | 3 |
| del | и | ∅ | 3 |
| ins | ∅ | d | 2 |
| ins | ∅ | the | 2 |
| ins | ∅ | to | 2 |
| sub | a | the | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14.30; 15:30 → 15.30; 10:00 → 10

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (32.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×5; Бакфикс ×1 |
| постмортем | ru | 5 | 5 | 0.0% | постмартем ×3; по смартем ×1; с Мартем ×1 |
| ребейз | ru | 5 | 5 | 0.0% | ребейс ×5 |
| эстимейт | ru | 5 | 5 | 0.0% | стимейт ×3; и стимейт ×1; из тимейт ×1 |
| дейлик | ru | 4 | 4 | 0.0% | делик ×2; делек ×1; дерек ×1 |
| отребейзить | ru | 4 | 4 | 0.0% | отребезить ×4 |
| C# | en | 3 | 3 | 0.0% | C ×3 |
| HAProxy | mixed | 3 | 3 | 0.0% | H-Proxy ×1; H-прокси ×1; А прокси ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | sharp | 3 |
| ins | ∅ | slash | 2 |
| ins | ∅ | to | 2 |
| del | how | ∅ | 2 |
| sub | in | and | 2 |
| sub | through | to | 2 |
| del | в | ∅ | 2 |
| sub_near | вывод | выводку | 2 |

Number style examples: 14:30 → 14.30; 11:30 → 11.30; 15:30 → 15.30; 15:30 → пятнадцать тридцать

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (95.1% spelled out)
- repair garbled terms (45.0% of terms are recognition errors) — from context only

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
| ins | ∅ | slash | 3 |
| ins | ∅ | в | 3 |

Number style examples: 14:30 → четырнадцать тридцать; 11:30 → одиннадцать тридцать; 15:30 → пятнадцать тридцать; 30 → thirty

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
- repair garbled terms (25.2% of terms are recognition errors) — from context only

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
| sub | a | the | 2 |
| sub | and | in | 2 |
| sub_near | finished | finish | 2 |
| sub_near | in | an | 2 |
| sub | in | and | 2 |
| sub_near | запасной | запасный | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14.30; 15:30 → 15.30; 10:00 → 10

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (27.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| дообучение | ru | 3 | 3 | 0.0% | до обучения ×3 |
| передеплоить | ru | 3 | 3 | 0.0% | передиплать ×2; передиплеить ×1 |
| API | en | 2 | 2 | 0.0% | APE ×1; APLI ×1 |
| BLEU | mixed | 2 | 2 | 0.0% | BAL-EU ×1; BLU ×1 |
| Golang | en | 2 | 2 | 0.0% | Galang ×2 |
| LiteLLM | mixed | 2 | 2 | 0.0% | Light LM ×1; LitAloom ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low rA ×1; low rA ×1 |
| asyncio | en | 2 | 2 | 0.0% | Essencio ×2 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | правок | справок | 2 |
| sub_near | проверил | проверила | 2 |
| sub_near | описание | описании | 2 |
| ins | ∅ | o'clock | 1 |
| ins | ∅ | shen | 1 |
| sub_near | 99.9% | 90 9.9% | 1 |
| sub | a | led | 1 |
| del | add | ∅ | 1 |

Number style examples: 14:30 → 14.30; 11:30 → 11.30; 12:00 → 12; 15:00 → 15.00

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (36.7% of terms are recognition errors) — from context only

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
| sub_near | cache | cash | 2 |
| sub_near | rerun | run | 2 |
| sub_near | запасной | запасный | 2 |
| sub_near | мне | не | 2 |
| sub_near | проверил | проверила | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14-30; 14:30 → 14.30; 15:30 → 15-30
