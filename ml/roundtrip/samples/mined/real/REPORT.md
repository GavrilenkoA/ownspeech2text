# Error mining — real round trip (1546 rows, TTS -> ASR over the probe set)

Rows mined: 1546 (skipped with null hypothesis: 0). Sources: rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 54 | 11.1% | 8.2% | 3.6% | 30.8% | 4.0% | 57.8% | sentence | full | digits | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 106 | 21.2% | 18.6% | 9.6% | 79.4% | 5.4% | 25.8% | sentence | partial | digits | mixed | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 106 | 21.1% | 18.4% | 9.6% | 77.6% | 5.5% | 26.3% | sentence | full | digits | mixed | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 160 | 25.5% | 20.8% | 13.5% | 60.5% | 12.6% | 30.3% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 54 | 13.3% | 7.7% | 7.2% | 20.0% | 5.4% | 58.8% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 160 | 19.5% | 14.6% | 9.8% | 50.6% | 7.3% | 40.7% | sentence | full | mixed | latin | 0.6% |
| `parakeet-ultra-0.6b@onnx-int8` | 160 | 16.8% | 13.2% | 7.9% | 48.2% | 6.0% | 43.3% | sentence | full | digits | latin | 0.6% |
| `qwen3-asr-0.6b@onnx-int8` | 160 | 28.5% | 23.8% | 14.7% | 67.7% | 14.8% | 34.7% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 106 | 31.5% | 26.7% | 17.7% | 91.1% | 12.7% | 16.2% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 160 | 14.8% | 11.2% | 7.2% | 38.6% | 5.6% | 49.0% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 160 | 17.1% | 14.3% | 7.6% | 45.2% | 8.0% | 43.3% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 160 | 21.2% | 18.0% | 9.4% | 55.7% | 10.3% | 36.0% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 54 | 11.1% | 8.2% | 3.6% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 52 | 28.1% | 25.1% | 15.6% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 54 | 14.4% | 12.2% | 4.1% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 52 | 28.4% | 26.1% | 15.6% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 54 | 13.9% | 10.8% | 4.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 54 | 18.0% | 12.1% | 8.3% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 52 | 36.4% | 31.1% | 23.7% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 54 | 24.5% | 21.5% | 9.4% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 54 | 13.3% | 7.7% | 7.2% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 54 | 12.1% | 7.3% | 4.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 52 | 31.1% | 24.4% | 18.9% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 54 | 17.4% | 14.4% | 7.1% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 54 | 7.4% | 5.1% | 2.2% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 52 | 31.0% | 25.1% | 16.3% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 54 | 14.7% | 11.8% | 6.2% |
| `qwen3-asr-0.6b@onnx-int8` | en | 54 | 12.8% | 7.4% | 5.1% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 52 | 44.6% | 38.6% | 27.5% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 54 | 32.8% | 30.0% | 13.0% |
| `t-one@onnx` | mixed | 52 | 39.4% | 33.3% | 28.0% |
| `t-one@onnx` | ru | 54 | 23.7% | 20.2% | 8.2% |
| `whisper-large-v3-turbo@onnx-int8` | en | 54 | 9.6% | 5.3% | 3.1% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 52 | 22.4% | 19.2% | 12.2% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 54 | 13.9% | 10.9% | 6.9% |
| `whisper-medium@onnx-int8` | en | 54 | 8.2% | 6.1% | 2.9% |
| `whisper-medium@onnx-int8` | mixed | 52 | 25.8% | 22.6% | 10.8% |
| `whisper-medium@onnx-int8` | ru | 54 | 19.9% | 16.6% | 9.7% |
| `whisper-small@onnx-int8` | en | 54 | 10.0% | 7.7% | 3.4% |
| `whisper-small@onnx-int8` | mixed | 52 | 35.2% | 30.6% | 17.2% |
| `whisper-small@onnx-int8` | ru | 54 | 21.7% | 18.7% | 8.7% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 96.3% | 94.4% | 87.6% | 98.2% | 96.3% | 109.1% | 100.0% | 99.9% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 100.0% | 84.0% | 85.9% | 20.8% | 76.8% | 83.3% | 59.3% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 87.0% | 100.0% | 100.0% | 74.1% | 91.7% | 54.2% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 99.4% | 99.4% | 78.3% | 57.5% | 18.1% | 62.3% | 0.0% | 97.0% | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 82.1% | 33.3% | 0.0% | 50.0% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 98.8% | 98.8% | 87.0% | 100.0% | 100.0% | 80.0% | 50.0% | 97.8% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 99.4% | 98.8% | 86.4% | 100.0% | 100.0% | 83.1% | 72.2% | 97.7% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 99.4% | 99.4% | 85.7% | 98.1% | 98.1% | 75.4% | 7.1% | 98.0% | 7.7% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 98.8% | 98.8% | 85.4% | 94.4% | 83.8% | 76.9% | 100.0% | 98.6% | 26.7% |
| `whisper-medium@onnx-int8` | 97.5% | 96.9% | 90.1% | 95.0% | 86.2% | 83.9% | 88.9% | 99.4% | 13.3% |
| `whisper-small@onnx-int8` | 97.5% | 96.9% | 84.5% | 96.2% | 85.6% | 76.9% | 94.4% | 98.0% | 15.4% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | piper | en | 27 | 15.4% | 11.5% | 49.0% | 35.3% | 100.0% |
| `distil-large-v3.5@onnx-int8` | supertonic3 | en | 27 | 6.8% | 5.0% | 66.7% | 19.6% | 99.7% |
| `gigaam-v3-e2e-ctc@onnx-int8` | piper | mixed | 26 | 30.3% | 26.6% | 9.3% | 77.8% | 53.8% |
| `gigaam-v3-e2e-ctc@onnx-int8` | piper | ru | 27 | 17.5% | 16.6% | 37.8% | 57.8% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | mixed | 26 | 25.9% | 23.6% | 13.0% | 72.2% | 64.3% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | ru | 27 | 11.3% | 8.0% | 48.9% | 40.0% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | piper | mixed | 26 | 31.0% | 28.6% | 9.3% | 79.6% | 41.7% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | piper | ru | 27 | 16.9% | 13.9% | 37.8% | 53.3% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | mixed | 26 | 25.9% | 23.6% | 14.8% | 75.9% | 66.7% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | ru | 27 | 10.9% | 7.6% | 48.9% | 37.8% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | en | 27 | 24.0% | 18.5% | 31.4% | 41.2% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | mixed | 26 | 47.1% | 42.4% | 5.6% | 81.5% | 25.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | ru | 27 | 40.1% | 37.1% | 17.8% | 77.8% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | en | 27 | 12.0% | 5.7% | 45.1% | 21.6% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | mixed | 26 | 25.6% | 19.9% | 16.7% | 59.3% | 45.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | ru | 27 | 8.9% | 6.0% | 71.1% | 17.8% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | piper | en | 27 | 18.0% | 12.2% | 51.0% | 25.5% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | supertonic3 | en | 27 | 8.6% | 3.1% | 66.7% | 11.8% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | en | 27 | 18.2% | 10.7% | 43.1% | 33.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | mixed | 26 | 38.7% | 30.6% | 11.1% | 66.7% | 36.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | ru | 27 | 25.5% | 22.5% | 37.8% | 51.1% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | en | 27 | 6.0% | 3.9% | 68.6% | 13.7% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | mixed | 26 | 23.6% | 18.2% | 27.8% | 57.4% | 80.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | ru | 27 | 9.3% | 6.3% | 60.0% | 22.2% | – |
| `parakeet-ultra-0.6b@onnx-int8` | piper | en | 27 | 8.8% | 6.0% | 56.9% | 23.5% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | mixed | 26 | 38.4% | 32.3% | 7.4% | 70.4% | 33.3% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | ru | 27 | 23.2% | 20.5% | 42.2% | 48.9% | – |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | en | 27 | 6.0% | 4.2% | 66.7% | 15.7% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | mixed | 26 | 23.6% | 17.8% | 24.1% | 57.4% | 76.9% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | ru | 27 | 6.3% | 3.0% | 68.9% | 13.3% | – |
| `qwen3-asr-0.6b@onnx-int8` | piper | en | 27 | 15.1% | 9.9% | 52.9% | 29.4% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | piper | mixed | 26 | 54.5% | 51.5% | 9.3% | 81.5% | 50.0% |
| `qwen3-asr-0.6b@onnx-int8` | piper | ru | 27 | 46.4% | 44.4% | 15.6% | 84.4% | – |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | en | 27 | 10.4% | 5.0% | 66.7% | 19.6% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | mixed | 26 | 34.7% | 25.6% | 18.5% | 64.8% | 59.1% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | ru | 27 | 19.2% | 15.6% | 46.7% | 42.2% | – |
| `t-one@onnx` | piper | mixed | 26 | 45.5% | 39.4% | 0.0% | 85.2% | 0.0% |
| `t-one@onnx` | piper | ru | 27 | 33.4% | 30.5% | 22.2% | 73.3% | – |
| `t-one@onnx` | supertonic3 | mixed | 26 | 33.3% | 27.3% | 1.8% | 77.8% | 0.0% |
| `t-one@onnx` | supertonic3 | ru | 27 | 13.9% | 9.9% | 46.7% | 40.0% | – |
| `whisper-large-v3-turbo@onnx-int8` | piper | en | 27 | 13.3% | 7.8% | 51.0% | 27.5% | 99.7% |
| `whisper-large-v3-turbo@onnx-int8` | piper | mixed | 26 | 26.6% | 23.9% | 24.1% | 63.0% | 77.3% |
| `whisper-large-v3-turbo@onnx-int8` | piper | ru | 27 | 21.5% | 18.9% | 44.4% | 46.7% | – |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | en | 27 | 6.0% | 2.9% | 70.6% | 13.7% | 99.7% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | mixed | 26 | 18.2% | 14.5% | 37.0% | 46.3% | 87.5% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | ru | 27 | 6.3% | 3.0% | 71.1% | 13.3% | – |
| `whisper-medium@onnx-int8` | piper | en | 27 | 11.2% | 9.1% | 52.9% | 31.4% | 100.0% |
| `whisper-medium@onnx-int8` | piper | mixed | 26 | 32.0% | 28.6% | 16.7% | 63.0% | 87.5% |
| `whisper-medium@onnx-int8` | piper | ru | 27 | 31.1% | 29.1% | 24.4% | 62.2% | – |
| `whisper-medium@onnx-int8` | supertonic3 | en | 27 | 5.2% | 3.1% | 72.5% | 13.7% | 99.7% |
| `whisper-medium@onnx-int8` | supertonic3 | mixed | 26 | 19.5% | 16.5% | 33.3% | 50.0% | 96.7% |
| `whisper-medium@onnx-int8` | supertonic3 | ru | 27 | 8.6% | 4.0% | 62.2% | 20.0% | – |
| `whisper-small@onnx-int8` | piper | en | 27 | 13.3% | 11.2% | 41.2% | 35.3% | 100.0% |
| `whisper-small@onnx-int8` | piper | mixed | 26 | 45.5% | 41.1% | 11.1% | 74.1% | 46.7% |
| `whisper-small@onnx-int8` | piper | ru | 27 | 33.8% | 32.5% | 26.7% | 64.4% | – |
| `whisper-small@onnx-int8` | supertonic3 | en | 27 | 6.8% | 4.2% | 60.8% | 15.7% | 100.0% |
| `whisper-small@onnx-int8` | supertonic3 | mixed | 26 | 24.9% | 20.2% | 25.9% | 51.8% | 74.1% |
| `whisper-small@onnx-int8` | supertonic3 | ru | 27 | 9.6% | 5.0% | 53.3% | 24.4% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| LLM | mixed | 10 | 20 | 20 | 100.0% | LM ×5; LLV ×2; Lm ×2; ЛЛВ ×2 |
| LangChain | mixed | 10 | 20 | 20 | 100.0% | Line Chain ×2; лайнчейн ×2; LAMCIN ×1; LampChin ×1 |
| Nginx | mixed | 10 | 20 | 20 | 100.0% | InJinks ×2; Vengeance ×2; Angent ×1; Engent ×1 |
| PEFT | mixed | 10 | 20 | 20 | 100.0% | пифт ×5; PFT ×2; пет ×2; P ×1 |
| Qwen3.5 2B | mixed | 10 | 20 | 20 | 100.0% | N3, пейст ×1; Q три, Пист вьюб ×1; QN3-352B ×1; QWEN 3. BEST VW ×1 |
| Silero | mixed | 10 | 20 | 20 | 100.0% | лера ×5; Лера ×3; селеро ×3; Cillero ×2 |
| VAD | mixed | 10 | 20 | 20 | 100.0% | ведь ×4; Ват ×3; ват ×3; вид ×3 |
| schema.sql | mixed | 10 | 20 | 20 | 100.0% | SQL ×5; схема ×5; Хама SQF ×2; ∅ ×1 |
| черрипикнул | ru | 10 | 20 | 20 | 100.0% | черепикнул ×10; черепитнул ×3; черепицнул ×2; репетировала ×1 |
| API | mixed | 10 | 20 | 19 | 95.0% | APE ×6; по API → Папи ×2; ∅ ×1; АПЕ ×1 |
| CORS | mixed | 10 | 20 | 19 | 95.0% | COS ×2; CoreS ×2; Cores ×2; кос ×2 |
| Fold7 | mixed | 10 | 20 | 19 | 95.0% | флот семь ×2; фолдесенем ×2; Fall 7 ×1; Fault 7 ×1 |
| Vite | mixed | 10 | 20 | 19 | 95.0% | вид ×12; на Vite → обновить ×2; VIT ×1; Webpack на Vite → WEP-окнавид ×1 |
| Webpack | mixed | 10 | 20 | 19 | 95.0% | ∅ ×1; Syp ×1; Vpap ×1; Weback ×1 |
| adapter_config.json | mixed | 10 | 20 | 19 | 95.0% | адаптер config, сон ×2; адаптер Канфи Джейсон ×2; adapter config, сон ×1; adapter-config, с он ×1 |
| build.gradle.kts | mixed | 10 | 20 | 19 | 95.0% | BellCral KTS ×1; Bild Gradel Katias ×1; Bild, Greadle KitYes ×1; Bill Gravel KTS ×1 |
| стейджинге | ru | 10 | 20 | 19 | 95.0% | стиджинге ×6; Джейне ×1; Джинги ×1; Джинни ×1 |
| Patroni | en | 9 | 18 | 18 | 100.0% | Petroni ×15; Patrone ×3 |
| etcd | en | 9 | 18 | 18 | 100.0% | it CD ×6; Ektra ×2; a CD ×2; Ector ×1 |
| htop | en | 9 | 18 | 18 | 100.0% | top ×6; H stop ×3; Top ×3; H-DOP ×2 |
| LPDDR5X | mixed | 10 | 20 | 18 | 90.0% | LP DDR ×2; LPDDA ×2; ∅ ×1; ALPDRPDX ×1 |
| Pydantic | mixed | 10 | 20 | 18 | 90.0% | педантик ×6; падотик ×2; PDotic ×1; PadaTik ×1 |
| package.json | mixed | 10 | 20 | 18 | 90.0% | пакет Jason ×3; Package ×1; Pккш Джейсоn ×1; пакедж ×1 |
| tmux | mixed | 10 | 20 | 18 | 90.0% | Макс ×3; TMOX ×1; Temok ×1; Tmacs ×1 |
| Crashlytics | mixed | 10 | 20 | 17 | 85.0% | Crash Litex ×3; кашлитекс ×2; CashLitex ×1; Crash Letex ×1 |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (27.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Axolotl | en | 2 | 2 | 0.0% | Axelotl ×1; Axolotl and → Axelotlan ×1 |
| CUDA | en | 2 | 2 | 0.0% | Coda ×1; gamma ×1 |
| OOM | en | 2 | 2 | 0.0% | OEM ×1; OM ×1 |
| Patroni | en | 2 | 2 | 0.0% | Petroni ×2 |
| Raspberry Pi | en | 2 | 2 | 0.0% | raspberry pie ×2 |
| etcd | en | 2 | 2 | 0.0% | Xtruth ×1; a CD ×1 |
| htop | en | 2 | 2 | 0.0% | H. Stop ×1; Top ×1 |
| AWS Lambda | en | 2 | 1 | 50.0% | AWS Lambda ×1; AWS lander ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | u | 2 |
| sub | the | a | 2 |
| ins | ∅ | coup | 1 |
| ins | ∅ | it | 1 |
| ins | ∅ | see | 1 |
| sub | add | at | 1 |
| sub | borrow | borough | 1 |
| sub_near | builds | bills | 1 |

## `gigaam-v3-e2e-ctc@onnx-int8`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 76.8% of reference)
- turn Cyrillic transliterations back into Latin terms (40.7% of Latin words)
- repair garbled terms (63.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | mixed | 2 | 2 | 0.0% | ∅ ×1; APE ×1 |
| ASR | mixed | 2 | 2 | 0.0% | SR ×1; в ASR → вЭСР ×1 |
| Android Studio | mixed | 2 | 2 | 0.0% | Andred Studio ×1; Android Studi ×1 |
| CoreDNS | mixed | 2 | 2 | 0.0% | корDNS ×1; кордиНС ×1 |
| Crashlytics | mixed | 2 | 2 | 0.0% | Crash Letex ×1; каш-лиитикс ×1 |
| FLUX | mixed | 2 | 2 | 0.0% | Fлоoк ×1; флак ×1 |
| Fold7 | mixed | 2 | 2 | 0.0% | Foll7 ×1; Fоll сiнем ×1 |
| Kafka Connect | mixed | 2 | 2 | 0.0% | Кавка Коннект ×2 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 2 |
| del | от | ∅ | 2 |
| sub_near | тесты | тесто | 2 |
| sub_near | возвращаю | возвращая | 2 |
| sub_near | исправление | исправления | 2 |
| sub_near | бета-версии | бито-версии | 1 |
| sub | в | op | 1 |
| sub | в | tt | 1 |

Number style examples: 12:00 → 12; 3000 → трёх тысячах; 40 мегабайт → 40 МБ

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- turn Cyrillic transliterations back into Latin terms (45.8% of Latin words)
- repair garbled terms (63.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | mixed | 2 | 2 | 0.0% | APE ×1; по API → Apape ×1 |
| ASR | mixed | 2 | 2 | 0.0% | ∅ ×1; в ASR → ВСР ×1 |
| Android Studio | mixed | 2 | 2 | 0.0% | Android Studier ×1; Androide Studio ×1 |
| CORS | mixed | 2 | 2 | 0.0% | Cores ×1; код ×1 |
| CoreDNS | mixed | 2 | 2 | 0.0% | кордин ×1; кордис ×1 |
| Crashlytics | mixed | 2 | 2 | 0.0% | Crash Litex ×1; кэш-литекс ×1 |
| FLUX | mixed | 2 | 2 | 0.0% | FLOX ×1; флагс ×1 |
| Fold7 | mixed | 2 | 2 | 0.0% | Fall 7 ×1; Foldinem ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | напомни | напомню | 3 |
| sub | в | с | 2 |
| del | от | ∅ | 2 |
| sub_near | тесты | тесто | 2 |
| sub_near | возвращаю | возвращая | 2 |
| sub_near | исправление | исправления | 2 |
| sub_near | режет | режут | 2 |
| sub_near | бета-версии | бето-версии | 1 |

Number style examples: 12:00 → 12; 40 мегабайт → 40 МБ; 3000 → 3 000; 3000 → трёх тысячах

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 78.3%)
- restore missing punctuation (commas at 62.3% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (50.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Axolotl | en | 2 | 2 | 0.0% | Axelot ×1; Axelotle ×1 |
| CORS | mixed | 2 | 2 | 0.0% | користь ×1; косо ×1 |
| CUDA | en | 2 | 2 | 0.0% | cup de ×1; dumb ×1 |
| CoreDNS | mixed | 2 | 2 | 0.0% | координс ×1; корденс ×1 |
| Crashlytics | mixed | 2 | 2 | 0.0% | Crashlitex ×1; кошлитикс ×1 |
| Fold7 | mixed | 2 | 2 | 0.0% | фолдицине ×1; фон 7 ×1 |
| Gradio | en | 2 | 2 | 0.0% | Radio ×2 |
| Kafka Connect | mixed | 2 | 2 | 0.0% | Кавка коннект ×1; Кафка Кнект ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | it | 2 |
| del | add | ∅ | 2 |
| del | our | ∅ | 2 |
| del | the | ∅ | 2 |
| sub | the | a | 2 |
| sub_near | а не | они | 2 |
| del | и | ∅ | 2 |
| sub_near | тесты | тесто | 2 |

Number style examples: 10:00 → десять; 12:00 → двенадцать; 200 → two hundred; 30 → thirty

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 50.0% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (18.6% of terms are recognition errors) — from context only
- tolerate dropped words (3.1% of non-term words) without inventing content

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Gradio | en | 2 | 2 | 0.0% | radio ×2 |
| OOM | en | 2 | 2 | 0.0% | OM ×1; Oum ×1 |
| Patroni | en | 2 | 2 | 0.0% | Petroni ×2 |
| TensorRT-LLM | en | 2 | 2 | 0.0% | Tensor RTLLLLLLLLLLLLM ×1; tensor TLM ×1 |
| etcd | en | 2 | 2 | 0.0% | Ectra ×1; its CD ×1 |
| htop | en | 2 | 2 | 0.0% | H stop ×1; top ×1 |
| AWS Lambda | en | 2 | 1 | 50.0% | AWS Lambda ×1; AWS lander ×1 |
| Bitbucket | en | 2 | 1 | 0.0% | Bipkit ×1; BitBucket ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | add | ∅ | 2 |
| del | our | ∅ | 2 |
| sub_near | rerunning | we're running | 2 |
| del | the | ∅ | 2 |
| sub | the | a | 2 |
| ins | ∅ | coup | 1 |
| ins | ∅ | it | 1 |
| del | a | ∅ | 1 |

Number style examples: 200 → two hundred; 30 → thirty; 99.9% → ninety nine point nine percent

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (50.0% spelled out)
- repair garbled terms (41.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | mixed | 2 | 2 | 0.0% | APE ×1; Папе ×1 |
| CORS | mixed | 2 | 2 | 0.0% | CoreS ×1; CORS живут → косвегут ×1 |
| Crashlytics | mixed | 2 | 2 | 0.0% | Crash Litex ×1; кашлитекс ×1 |
| Fold7 | mixed | 2 | 2 | 0.0% | флот семь ×1; фолдесенем ×1 |
| Kokoro | mixed | 2 | 2 | 0.0% | Coco ×1; Кокор ×1 |
| LLM | mixed | 2 | 2 | 0.0% | Lm ×1; Лвм ×1 |
| LPDDR5X | mixed | 2 | 2 | 0.0% | LP DDR ×1; LP DRPDX ×1 |
| LangChain | mixed | 2 | 2 | 0.0% | Line Chain ×1; Лэмчин ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | а не | они | 2 |
| sub_near | которая | которые | 2 |
| sub_near | тесты | тесто | 2 |
| ins | ∅ | bip | 1 |
| ins | ∅ | it | 1 |
| ins | ∅ | see | 1 |
| sub | add | at | 1 |
| sub | add | ed | 1 |

Number style examples: 12:00 → 12; 10:00 → 10; 10:00 → десять; 12:00 → двенадцать

Hallucinated tails: “нол нот.” ×1

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (39.0% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | mixed | 2 | 2 | 0.0% | APE ×1; по API → папе ×1 |
| CORS | mixed | 2 | 2 | 0.0% | Cores ×1; кос ×1 |
| CUDA | en | 2 | 2 | 0.0% | coda ×1; dumbest ×1 |
| Crashlytics | mixed | 2 | 2 | 0.0% | Crash Litex ×1; кашлитекс ×1 |
| Fold7 | mixed | 2 | 2 | 0.0% | флот семь ×1; фолдесенем ×1 |
| Kokoro | mixed | 2 | 2 | 0.0% | Coco ×1; кокор ×1 |
| LLM | mixed | 2 | 2 | 0.0% | Lm ×1; ЛЛВ ×1 |
| LPDDR5X | mixed | 2 | 2 | 0.0% | LP DDR ×1; LPD DRPDX ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | которая | которые | 2 |
| sub_near | моделей | модели | 2 |
| sub_near | на | наш | 2 |
| sub | шлюзе | люди | 2 |
| ins | ∅ | coup | 1 |
| ins | ∅ | it | 1 |
| sub | add | at | 1 |
| sub | borrow | borough | 1 |

Number style examples: 12:00 → двенадцать; 10:00 → 10; 10:00 → 10.00; 12:00 → 12

Hallucinated tails: “нол нот.” ×1

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (92.9% spelled out)
- repair garbled terms (53.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | mixed | 2 | 2 | 0.0% | ЕПИ ×1; девяносто ×1 |
| CORS | mixed | 2 | 2 | 0.0% | Корис ×1; косы ×1 |
| Crashlytics | mixed | 2 | 2 | 0.0% | кошлетик ×1; крошлитьекс ×1 |
| FLUX | mixed | 2 | 2 | 0.0% | флагс ×1; флок ×1 |
| Fold7 | mixed | 2 | 2 | 0.0% | Фолдинсем ×1; се ×1 |
| Kafka Connect | mixed | 2 | 2 | 0.0% | Кавка Конект ×1; кавказка неёт ×1 |
| LFM3 | mixed | 2 | 2 | 0.0% | CFM ×1; ф м три ×1 |
| LLM | mixed | 2 | 2 | 0.0% | LM ×1; Ллам ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | the | a | 2 |
| sub_near | а не | они | 2 |
| sub_near | защита | засчита | 2 |
| sub_near | моделей | модели | 2 |
| sub_near | опять | пять | 2 |
| ins | ∅ | и | 2 |
| sub_near | возвращаю | возвращая | 2 |
| sub_near | дольше | больше | 2 |

Number style examples: 200 → two hundred; 30 → thirty; 10:00 → десять; 10:00 → десять ноль ноль

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)
- repair garbled terms (70.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | mixed | 2 | 2 | 0.0% | айпи ×1; папы ×1 |
| ASR | mixed | 2 | 2 | 0.0% | ареса ×1; в ASR → ваясар ×1 |
| CORS | mixed | 2 | 2 | 0.0% | корред ×1; CORS живут → косивут ×1 |
| CoreDNS | mixed | 2 | 2 | 0.0% | коди нс ×1; корб инс ×1 |
| Crashlytics | mixed | 2 | 2 | 0.0% | кашлитикс ×1; крашлетекс ×1 |
| DPO | mixed | 2 | 2 | 0.0% | депио ×1; петь ×1 |
| FLUX | mixed | 2 | 2 | 0.0% | факс ×1; флок ×1 |
| Fold7 | mixed | 2 | 2 | 0.0% | фол семь ×1; фолди сенем ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 2 |
| del | и | ∅ | 2 |
| sub_near | которая | которые | 2 |
| sub_near | тесты | тесто | 2 |
| del | в | ∅ | 2 |
| ins | ∅ | да | 1 |
| ins | ∅ | девядесятых | 1 |
| ins | ∅ | девяносто | 1 |

Number style examples: 10:00 → десять; 12:00 → двенадцать; 12:00 → двенадцать ноль ноль; 40 → сорок

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (35.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | mixed | 2 | 2 | 0.0% | APE ×1; по API → A-PAPI ×1 |
| CORS | mixed | 2 | 2 | 0.0% | COS ×1; корр ×1 |
| CUDA | en | 2 | 2 | 0.0% | Ku-Damas ×1; coda ×1 |
| Crashlytics | mixed | 2 | 2 | 0.0% | CashLitex ×1; CrashLitex ×1 |
| LLM | mixed | 2 | 2 | 0.0% | LLVM ×1; LM ×1 |
| LangChain | mixed | 2 | 2 | 0.0% | LAMCIN ×1; Lanchain ×1 |
| LlamaIndex | mixed | 2 | 2 | 0.0% | LAMINDEX ×1; LamaIndex ×1 |
| NDK | mixed | 2 | 2 | 0.0% | NDCAR ×1; Ndiki ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | the | a | 2 |
| sub_near | а не | они | 2 |
| sub | пятисотые | 500 | 2 |
| ins | ∅ | at | 1 |
| ins | ∅ | see | 1 |
| sub | a | agea | 1 |
| sub_near | borrow checker | borrachek | 1 |
| sub | bump | umpie | 1 |

Number style examples: 12:00 → 12; 10:00 → 10; 10:00 → 10.00; 12:00 → 12.00

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (40.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | mixed | 2 | 2 | 0.0% | APE ×1; по API → Папи ×1 |
| ASR | mixed | 2 | 2 | 0.0% | AESA ×1; ISR ×1 |
| CORS | mixed | 2 | 2 | 0.0% | COS ×1; Core S ×1 |
| Fold7 | mixed | 2 | 2 | 0.0% | Fault 7 ×1; Fold 10 ×1 |
| Jaeger | mixed | 2 | 2 | 0.0% | JIGR ×1; Ягере ×1 |
| Kokoro | mixed | 2 | 2 | 0.0% | Cocoro ×1; Kakor ×1 |
| LLM | mixed | 2 | 2 | 0.0% | LLV ×1; LM ×1 |
| LPDDR5X | mixed | 2 | 2 | 0.0% | LPDDA ×1; LPDDR-PDX ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | напомни | напомню | 3 |
| sub | the | a | 2 |
| sub | пятисотые | 500 | 2 |
| ins | ∅ | at | 1 |
| ins | ∅ | bip | 1 |
| ins | ∅ | gia | 1 |
| ins | ∅ | in | 1 |
| sub | add | at | 1 |

Number style examples: 12:00 → 12.00; 10:00 → 10; 10:00 → 10.00; 12:00 → 12

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (44.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | mixed | 2 | 2 | 0.0% | АПЕ ×1; по API → Папи ×1 |
| ASR | mixed | 2 | 2 | 0.0% | ISR ×1; RA/SR ×1 |
| AWS Lambda | en | 2 | 2 | 0.0% | AWS Lander ×1; AidaWiz Lambda ×1 |
| CORS | mixed | 2 | 2 | 0.0% | CoreS ×1; козы ×1 |
| CUDA | en | 2 | 2 | 0.0% | Dama ×1; Kuda ×1 |
| Fold7 | mixed | 2 | 2 | 0.0% | фолдиси ним ×1; фолте ×1 |
| JSON | mixed | 2 | 2 | 0.0% | JSON от → Jsonat ×1; JSON от LLM → jason.lm ×1 |
| LLM | mixed | 2 | 2 | 0.0% | LM ×1; JSON от LLM → jason.lm ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | hi | high | 2 |
| sub_near | rerunning | we're running | 2 |
| sub | the | a | 2 |
| sub | в | с | 2 |
| sub_near | которая | которые | 2 |
| sub_near | напомни | напомню | 2 |
| sub_near | тесты | тесто | 2 |
| sub_near | чистит | чистят | 2 |

Number style examples: 12:00 → 12; 10:00 → 10; 10:00 → 10.00; 12:00 → 12.00
