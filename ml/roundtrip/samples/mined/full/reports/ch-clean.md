# Error mining — full CPU run, primary, clean

Rows mined: 7114 (skipped with null hypothesis: 0). Sources: primary_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 241 | 9.2% | 5.8% | 2.5% | 25.2% | 1.6% | 53.2% | sentence | full | digits | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 533 | 14.6% | 12.3% | 6.3% | 58.1% | 2.3% | 32.7% | sentence | partial | digits | mixed | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 533 | 14.2% | 11.4% | 6.9% | 55.2% | 1.9% | 33.4% | sentence | full | digits | mixed | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 774 | 18.4% | 12.9% | 9.1% | 46.4% | 5.7% | 34.4% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 242 | 16.6% | 10.4% | 7.1% | 33.4% | 5.4% | 42.3% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 775 | 12.9% | 8.8% | 5.0% | 35.4% | 3.0% | 44.9% | sentence | full | digits | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 775 | 11.5% | 7.7% | 4.3% | 33.2% | 2.1% | 47.4% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 775 | 21.6% | 16.1% | 10.2% | 51.3% | 8.4% | 34.4% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 533 | 20.7% | 15.3% | 12.7% | 68.4% | 3.8% | 21.1% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 775 | 9.5% | 6.2% | 3.6% | 26.7% | 1.8% | 54.5% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 383 | 11.2% | 7.2% | 4.3% | 28.4% | 2.5% | 52.4% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 775 | 13.4% | 9.8% | 5.4% | 39.2% | 3.4% | 42.9% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 241 | 9.2% | 5.8% | 2.5% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 290 | 19.4% | 16.3% | 9.3% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 243 | 8.6% | 7.2% | 2.7% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 290 | 18.9% | 15.5% | 10.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 243 | 8.2% | 6.3% | 3.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 242 | 18.3% | 12.0% | 7.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 289 | 23.2% | 16.6% | 14.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 243 | 12.6% | 9.5% | 3.5% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 242 | 16.6% | 10.4% | 7.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 242 | 11.4% | 6.5% | 3.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 290 | 16.7% | 12.4% | 7.6% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 243 | 10.1% | 6.9% | 3.3% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 242 | 9.6% | 5.6% | 2.5% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 290 | 15.2% | 11.3% | 7.1% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 243 | 8.9% | 5.5% | 2.7% |
| `qwen3-asr-0.6b@onnx-int8` | en | 242 | 14.6% | 7.7% | 5.2% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 290 | 29.4% | 23.3% | 17.7% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 243 | 19.9% | 16.9% | 6.2% |
| `t-one@onnx` | mixed | 290 | 26.8% | 19.9% | 20.3% |
| `t-one@onnx` | ru | 243 | 13.1% | 9.6% | 3.4% |
| `whisper-large-v3-turbo@onnx-int8` | en | 242 | 9.2% | 5.9% | 2.5% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 290 | 12.2% | 8.2% | 5.9% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 243 | 6.7% | 4.3% | 2.1% |
| `whisper-medium@onnx-int8` | en | 121 | 8.8% | 5.6% | 2.4% |
| `whisper-medium@onnx-int8` | mixed | 142 | 13.6% | 9.5% | 5.8% |
| `whisper-medium@onnx-int8` | ru | 120 | 10.8% | 6.2% | 4.5% |
| `whisper-small@onnx-int8` | en | 242 | 10.3% | 7.0% | 3.1% |
| `whisper-small@onnx-int8` | mixed | 290 | 17.6% | 13.3% | 8.6% |
| `whisper-small@onnx-int8` | ru | 243 | 11.9% | 8.6% | 3.8% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 98.8% | 96.3% | 84.3% | 98.8% | 97.1% | 127.3% | 100.0% | 99.9% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 99.8% | 94.6% | 88.6% | 25.9% | 86.2% | 86.7% | 69.7% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 94.5% | 100.0% | 100.0% | 87.6% | 89.6% | 63.4% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 99.7% | 99.0% | 79.3% | 65.8% | 29.2% | 80.8% | 9.5% | 95.8% | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 81.3% | 25.2% | 0.0% | 50.6% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 99.9% | 99.9% | 90.3% | 99.9% | 99.9% | 95.2% | 86.8% | 98.4% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 99.9% | 99.6% | 89.5% | 100.0% | 100.0% | 95.8% | 87.1% | 98.2% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 99.5% | 99.2% | 82.3% | 99.7% | 99.6% | 83.0% | 6.0% | 97.0% | 8.9% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 99.7% | 99.5% | 89.8% | 99.5% | 98.1% | 88.2% | 98.6% | 98.1% | 17.7% |
| `whisper-medium@onnx-int8` | 99.7% | 99.2% | 90.1% | 99.2% | 98.2% | 89.4% | 97.4% | 98.6% | 7.3% |
| `whisper-small@onnx-int8` | 99.7% | 99.2% | 86.8% | 99.7% | 98.3% | 87.6% | 94.0% | 98.0% | 8.2% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | kokoro1 | en | 80 | 7.3% | 3.5% | 58.0% | 14.2% | 100.0% |
| `distil-large-v3.5@onnx-int8` | piper | en | 82 | 11.4% | 8.6% | 44.8% | 31.9% | 99.8% |
| `distil-large-v3.5@onnx-int8` | supertonic3 | en | 79 | 8.7% | 5.0% | 56.3% | 24.6% | 99.9% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | mixed | 290 | 19.4% | 16.3% | 18.1% | 65.6% | 69.7% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | ru | 243 | 8.6% | 7.2% | 51.2% | 36.2% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | mixed | 290 | 18.9% | 15.5% | 17.8% | 64.0% | 63.4% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | ru | 243 | 8.2% | 6.3% | 53.1% | 31.3% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | kokoro1 | en | 80 | 17.5% | 9.6% | 36.9% | 20.4% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | en | 82 | 22.7% | 17.3% | 25.8% | 46.0% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | en | 80 | 14.3% | 8.7% | 40.8% | 28.4% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | mixed | 289 | 23.2% | 16.6% | 20.4% | 53.5% | 50.5% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | ru | 243 | 12.6% | 9.5% | 51.6% | 34.1% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | kokoro1 | en | 80 | 16.9% | 9.5% | 43.8% | 18.2% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | piper | en | 82 | 21.7% | 15.1% | 30.7% | 39.9% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | supertonic3 | en | 80 | 11.0% | 6.2% | 52.1% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | kokoro1 | en | 80 | 9.7% | 4.1% | 56.2% | 13.6% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | en | 82 | 14.3% | 9.5% | 37.4% | 36.8% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | en | 80 | 9.8% | 5.8% | 54.4% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | mixed | 290 | 16.7% | 12.4% | 31.3% | 46.0% | 83.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | ru | 243 | 10.1% | 6.9% | 56.9% | 25.2% | – |
| `parakeet-ultra-0.6b@onnx-int8` | kokoro1 | en | 80 | 8.7% | 3.9% | 54.0% | 15.3% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | en | 82 | 10.9% | 7.4% | 44.8% | 31.9% | 99.9% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | en | 80 | 9.2% | 5.5% | 52.7% | 24.9% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | mixed | 290 | 15.2% | 11.3% | 34.5% | 43.0% | 82.6% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | ru | 243 | 8.9% | 5.5% | 60.1% | 21.8% | – |
| `qwen3-asr-0.6b@onnx-int8` | kokoro1 | en | 80 | 13.6% | 6.1% | 51.1% | 16.5% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | piper | en | 82 | 19.5% | 11.8% | 31.3% | 37.4% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | en | 80 | 10.5% | 5.0% | 53.2% | 23.7% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | mixed | 290 | 29.4% | 23.3% | 22.2% | 55.5% | 63.1% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | ru | 243 | 19.9% | 16.9% | 37.7% | 48.8% | – |
| `t-one@onnx` | supertonic3 | mixed | 290 | 26.8% | 19.9% | 3.0% | 71.1% | 0.0% |
| `t-one@onnx` | supertonic3 | ru | 243 | 13.1% | 9.6% | 43.9% | 38.6% | – |
| `whisper-large-v3-turbo@onnx-int8` | kokoro1 | en | 80 | 7.6% | 3.8% | 60.2% | 13.6% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | piper | en | 82 | 12.0% | 9.1% | 45.4% | 32.5% | 99.8% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | en | 80 | 7.6% | 4.5% | 59.2% | 20.7% | 99.8% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | mixed | 290 | 12.2% | 8.2% | 44.5% | 30.8% | 85.3% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | ru | 243 | 6.7% | 4.3% | 66.5% | 18.1% | – |
| `whisper-medium@onnx-int8` | kokoro1 | en | 40 | 7.6% | 3.8% | 60.9% | 13.8% | 100.0% |
| `whisper-medium@onnx-int8` | piper | en | 41 | 11.0% | 8.5% | 44.2% | 35.1% | 99.8% |
| `whisper-medium@onnx-int8` | supertonic3 | en | 40 | 7.5% | 4.3% | 67.4% | 22.1% | 99.8% |
| `whisper-medium@onnx-int8` | supertonic3 | mixed | 142 | 13.6% | 9.5% | 45.6% | 34.7% | 89.0% |
| `whisper-medium@onnx-int8` | supertonic3 | ru | 120 | 10.8% | 6.2% | 55.0% | 20.8% | – |
| `whisper-small@onnx-int8` | kokoro1 | en | 80 | 9.4% | 4.9% | 53.4% | 19.3% | 100.0% |
| `whisper-small@onnx-int8` | piper | en | 82 | 12.6% | 9.9% | 41.1% | 35.0% | 99.9% |
| `whisper-small@onnx-int8` | supertonic3 | en | 80 | 8.7% | 6.0% | 53.8% | 26.0% | 100.0% |
| `whisper-small@onnx-int8` | supertonic3 | mixed | 290 | 17.6% | 13.3% | 33.8% | 44.0% | 80.2% |
| `whisper-small@onnx-int8` | supertonic3 | ru | 243 | 11.9% | 8.6% | 47.1% | 33.7% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| передеплоить | ru | 10 | 57 | 52 | 91.2% | передеплеить ×8; передеплейть ×7; передеплейт ×6; передиплеить ×6 |
| эстимейт | ru | 10 | 47 | 42 | 89.4% | стимейт ×9; и стимейт ×3; имеет ×2; истемей ×2 |
| дообучение | ru | 10 | 39 | 34 | 87.2% | до обучения ×27; дабычение ×2; до обучеениея ×1; до обучениея ×1 |
| постмортем | ru | 9 | 36 | 33 | 91.7% | постмартем ×10; по смартем ×2; постмартём ×2; с Мартем ×2 |
| прод | ru | 10 | 47 | 32 | 68.1% | прот ×21; Прот ×2; на прод вечером → напродвечером ×2; на прод → напрод ×2 |
| отребейзить | ru | 10 | 28 | 26 | 92.9% | отребезить ×25; отрибейзить ×1 |
| asyncio | en | 9 | 26 | 25 | 96.2% | Asincio ×3; Essencio ×3; Asccio ×2; Asensio ×2 |
| хотфикс | ru | 10 | 48 | 25 | 52.1% | Хатфикс ×7; хатфикс ×6; ходфикс ×4; их отфикс ×2 |
| багфикс | ru | 10 | 28 | 24 | 85.7% | бакфикс ×18; Бакфикс ×3; бакфис ×2; бакфиск ×1 |
| коммит | ru | 10 | 28 | 24 | 85.7% | комет ×7; комит ×5; комед ×2; коми ×2 |
| BLEU | mixed | 10 | 29 | 24 | 82.8% | BLU ×6; BAL-EU ×1; BU ×1; Ball и U ×1 |
| Авито | ru | 10 | 46 | 24 | 52.2% | с Авито → Совета ×7; Авита ×4; с Авито → Савито ×3; с Авито → савита ×3 |
| тимлид | ru | 9 | 27 | 23 | 85.2% | Тимлет ×11; тимлет ×6; Тимлит ×2; Темлет ×1 |
| дейлик | ru | 10 | 28 | 23 | 82.1% | делик ×10; деелик ×2; делек ×2; делег ×1 |
| ребейз | ru | 10 | 28 | 23 | 82.1% | ребейс ×19; Рибейс ×2; ребей ×1; рибейс ×1 |
| эвал | ru | 10 | 29 | 23 | 79.3% | его ×3; вал ×2; ивал ×2; на эвал → навал ×2 |
| легаси | ru | 10 | 47 | 23 | 48.9% | легоси ×4; Легасе ×3; Легоси ×3; легосе ×2 |
| тикет | ru | 10 | 59 | 23 | 39.0% | этикет ×22; тикеты ×1 |
| MoE | mixed | 10 | 28 | 22 | 78.6% | мои ×10; Мои ×5; мой ×3; МО ×1 |
| заапрувить | ru | 10 | 28 | 22 | 78.6% | заопровить ×5; заопрувить ×4; запровить ×3; заапроить ×2 |
| закоммитил | ru | 10 | 28 | 22 | 78.6% | закометил ×9; закомитил ×7; закаметил ×1; закамитил ×1 |
| C# | en | 8 | 24 | 21 | 87.5% | C ×10; C-chart ×2; Sharp ×2; C-Tarped ×1 |
| Svelte | mixed | 10 | 28 | 21 | 75.0% | свалте ×4; Swelta ×1; Vue на Svelte → Woensvelt ×1; Vue на Svelte → Woinasvelt ×1 |
| Hadoop | mixed | 10 | 29 | 21 | 72.4% | Хадуб ×6; Hadub ×3; Hadob ×2; хадуб ×2 |
| pgBouncer | mixed | 10 | 20 | 20 | 100.0% | бы баунсер ×4; B-Bouncer ×1; B-баунсера ×1; Bigi-bounter ×1 |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (23.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| asyncio | en | 3 | 3 | 0.0% | Asccio ×2; essensio ×1 |
| API | en | 2 | 2 | 0.0% | APE ×1; APLI ×1 |
| Axolotl | en | 2 | 2 | 0.0% | Axel Owl ×1; Axel-Lawal ×1 |
| Cilium | en | 2 | 2 | 0.0% | psyllium ×2 |
| Exynos | en | 2 | 2 | 0.0% | Xenose ×1; xeno's ×1 |
| Golang | en | 2 | 2 | 0.0% | Galang ×2 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| LoRA | en | 2 | 2 | 0.0% | low ra ×1; q low ra ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | slash | 2 |
| ins | ∅ | at | 1 |
| ins | ∅ | dot | 1 |
| ins | ∅ | o'clock | 1 |
| ins | ∅ | out | 1 |
| ins | ∅ | the | 1 |
| ins | ∅ | to | 1 |
| ins | ∅ | z | 1 |

Number style examples: 3:30 → 330; 18:00 → 18

## `gigaam-v3-e2e-ctc@onnx-int8`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 86.2% of reference)
- spell out again numbers the writer wanted in words (50.0% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (30.3% of Latin words)
- repair garbled terms (52.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 6 | 6 | 0.0% | передеплейть ×3; передеплеить ×2; передиплейть ×1 |
| тикет | ru | 6 | 6 | 0.0% | этикет ×6 |
| Авито | ru | 5 | 5 | 0.0% | Авита ×2; Аvito ×1; с Авито → Совета ×2 |
| эстимейт | ru | 5 | 5 | 0.0% | Estmat ×1; Stmat ×1; истемей ×1; стимейт ×1 |
| постмортем | ru | 4 | 4 | 0.0% | Смартом ×1; пастмортям ×1; пост-мортм ×1; постмартём ×1 |
| хотфикс | ru | 5 | 4 | 0.0% | хэтфикс ×2; Hetfix ×1; Хатфикс ×1; хатфикс ×1 |
| BLEU | mixed | 3 | 3 | 0.0% | BLU ×1; BlU ×1; БЛU ×1 |
| Hadoop | mixed | 3 | 3 | 0.0% | Hadub ×1; HoDUbe ×1; Hoдуб ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | исправление | исправления | 4 |
| del | в | ∅ | 2 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| del | с | ∅ | 2 |
| sub_near | уведомление | уведомления | 2 |
| ins | ∅ | setploss | 1 |
| ins | ∅ | гляньте | 1 |

Number style examples: 100 → сто; 10:00 → 10; 11:00 → 11; 11:00 → одиннадцати

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (50.0% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (36.6% of Latin words)
- repair garbled terms (49.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 6 | 6 | 0.0% | передеплеить ×4; передеплейть ×2 |
| тикет | ru | 6 | 6 | 0.0% | этикет ×6 |
| прод | ru | 5 | 5 | 0.0% | прот ×4; пруд ×1 |
| эстимейт | ru | 5 | 5 | 0.0% | исмейт ×1; истемей ×1; истюмейт ×1; стимейд ×1; эстимейт в задаче → мейдзадаче ×1 |
| постмортем | ru | 4 | 4 | 0.0% | пост мартём ×1; постмартям ×1; постмартём ×1; смартём ×1 |
| юзкейс | ru | 4 | 4 | 0.0% | юскейс ×3; юзкайс ×1 |
| Hadoop | mixed | 3 | 3 | 0.0% | Hadub ×2; Хадуб ×1 |
| MoE | mixed | 3 | 3 | 0.0% | Мои ×1; Мой ×1; мой ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | исправление | исправления | 4 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| del | с | ∅ | 2 |
| sub_near | уведомление | уведомления | 2 |
| ins | ∅ | setploss | 1 |
| ins | ∅ | в | 1 |
| ins | ∅ | гляньте | 1 |

Number style examples: 100 → сто; 10:00 → 10; 12:00 → 12; 11:00 → одиннадцати

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 79.3%)
- restore missing punctuation (commas at 80.8% of reference)
- convert spoken numbers, times and dates to digits (90.5% spelled out)
- spell out again numbers the writer wanted in words (50.0% of spelled-out numbers come back as digits)
- repair garbled terms (40.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 6 | 6 | 0.0% | передиплеить ×2; передиплеть ×2; передеплыть ×1; передиплэть ×1 |
| Авито | ru | 5 | 5 | 0.0% | Авита ×2; с Авито → Совета ×2; с Авито → Совита ×1 |
| хотфикс | ru | 5 | 5 | 0.0% | ходфикс ×2; их отфикс ×1; ход Фикс ×1; ходикс ×1 |
| эстимейт | ru | 5 | 5 | 0.0% | стиммейт ×2; и стеметь ×1; из стиммейт ×1; имеет ×1 |
| датасет | ru | 4 | 4 | 0.0% | Dataсеt ×1; датасеты ×1; дотосет ×1; датасет с → дотосет ×1 |
| дообучение | ru | 4 | 4 | 0.0% | до обучения ×4 |
| реранкер | ru | 4 | 4 | 0.0% | Риранкер ×3; а реранкер → Ариранкер ×1 |
| легаси | ru | 5 | 4 | 0.0% | Легасе ×2; Легаси ×1; Лего осе ×1; Легосе ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | the | ∅ | 11 |
| del | could | ∅ | 10 |
| del | can | ∅ | 8 |
| del | you | ∅ | 7 |
| del | и | ∅ | 6 |
| del | run | ∅ | 5 |
| del | add | ∅ | 3 |
| del | i | ∅ | 3 |

Number style examples: 14:30 → четырнадцать тридцать; 30 → thirty; 11:30 → одиннадцать-тридцать; 3 → three

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 50.6% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (27.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×2; Sharp ×1 |
| asyncio | en | 3 | 3 | 0.0% | Asincio ×2; essentio ×1 |
| AMD | en | 2 | 2 | 0.0% | MD ×2 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLI ×1 |
| CLIP | en | 2 | 2 | 0.0% | CLAP ×1; Kalapi ×1 |
| Cilium | en | 2 | 2 | 0.0% | pilium ×1; psilium ×1 |
| DeepSeek-V3 | en | 2 | 2 | 0.0% | deep seeked V three ×1; deepseq V three ×1 |
| Exynos | en | 2 | 2 | 0.0% | X and Os ×1; XNOS ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | could | ∅ | 11 |
| del | can | ∅ | 8 |
| del | the | ∅ | 8 |
| del | you | ∅ | 6 |
| del | run | ∅ | 4 |
| del | for | ∅ | 3 |
| del | i | ∅ | 3 |
| del | please | ∅ | 3 |

Number style examples: 30 → thirty; 3 → three; 3:30 → three thirty; 14:30 → fourteen thirty

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (66.7% of spelled-out numbers come back as digits)
- repair garbled terms (32.8% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| эстимейт | ru | 5 | 5 | 0.0% | стимейт ×3; и стимейт ×1; истимей ×1 |
| передеплоить | ru | 6 | 5 | 16.7% | передиплейт ×2; передиплоить ×2; передеплоит ×1; передеплоить ×1 |
| постмортем | ru | 4 | 4 | 0.0% | постмартем ×2; по смартем ×1; с Мартем ×1 |
| C# | en | 3 | 3 | 0.0% | C ×3 |
| Hadoop | mixed | 3 | 3 | 0.0% | Хадуб ×2; Hadob ×1 |
| SQLite | mixed | 3 | 3 | 0.0% | Esqula ×1; в SQLite → VSQLite ×1; в SQLite → Vescualait ×1 |
| asyncio | en | 3 | 3 | 0.0% | ACICEO ×1; Acinsi O ×1; Essentio ×1 |
| багфикс | ru | 3 | 3 | 0.0% | бакфикс ×3 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | правки | прав | 4 |
| ins | ∅ | sharp | 3 |
| del | в | ∅ | 3 |
| ins | ∅ | d | 2 |
| ins | ∅ | to | 2 |
| sub | through | to | 2 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |

Number style examples: 14:30 → 14.30; 15:30 → 15.30; 11:30 → 11.30; 10:00 → 10

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (30.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| эстимейт | ru | 5 | 5 | 0.0% | стимейт ×3; и стимейт ×1; из тимейт ×1 |
| постмортем | ru | 4 | 4 | 0.0% | постмартем ×2; по смартем ×1; с Мартем ×1 |
| C# | en | 3 | 3 | 0.0% | C ×3 |
| Hadoop | mixed | 3 | 3 | 0.0% | Hadob ×1; Хаду ×1; Хадуб ×1 |
| Zabbix | mixed | 3 | 3 | 0.0% | The Bex ×1; Забег ×1; забекс ×1 |
| asyncio | en | 3 | 3 | 0.0% | Acinsio ×1; Asincio ×1; essentio ×1 |
| багфикс | ru | 3 | 3 | 0.0% | бакфикс ×3 |
| дейлик | ru | 3 | 3 | 0.0% | делик ×2; делек ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | sharp | 3 |
| ins | ∅ | slash | 2 |
| ins | ∅ | to | 2 |
| del | how | ∅ | 2 |
| sub | through | to | 2 |
| del | в | ∅ | 2 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |

Number style examples: 14:30 → 14.30; 11:30 → 11.30; 15:30 → 15.30; 14:30 → четырнадцать тридцать

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (94.0% spelled out)
- repair garbled terms (43.8% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 6 | 6 | 0.0% | передеплейт ×3; передеплать ×1; передеплейить ×1; передиплейт ×1 |
| легаси | ru | 5 | 5 | 0.0% | легесси ×1; легкой ×1; легосе ×1; легости ×1; легаси код → легосекот ×1 |
| прод | ru | 5 | 5 | 0.0% | прот ×3; прототип ×1; пруд ×1 |
| дообучение | ru | 4 | 4 | 0.0% | дабычение ×2; до обучения ×2 |
| реранкер | ru | 4 | 4 | 0.0% | Риранкер ×2; риранкер ×1; а реранкер → Ариранкер ×1 |
| юзкейс | ru | 4 | 4 | 0.0% | усケース ×1; юс кейс ×1; юс-кейс ×1; юскусей ×1 |
| Авито | ru | 5 | 4 | 0.0% | ОВТО ×1; авито ×1; сайта ×1; с Авито → Савита ×1; с Авито → совета ×1 |
| хотфикс | ru | 5 | 4 | 0.0% | Hotfix ×1; Хатфикс ×1; их отфикс ×1; хатфикс ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 5 |
| sub_near | пятницу | пяточку | 5 |
| del | и | ∅ | 4 |
| sub_near | объясни | объясните | 4 |
| sub_near | пятницу | пяточку | 4 |
| sub_near | скинь | скин | 4 |
| ins | ∅ | в | 3 |
| sub_near | объясни | объяснение | 3 |

Number style examples: 14:30 → четырнадцать тридцать; 15:30 → пятнадцать тридцать; 30 → thirty; 11:30 → одиннадцать тридцать

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)
- repair garbled terms (56.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 6 | 6 | 0.0% | передиплеить ×3; передеплеить ×2; передиплыть ×1 |
| тикет | ru | 6 | 6 | 0.0% | этикет ×6 |
| хотфикс | ru | 5 | 5 | 0.0% | хатфикс ×3; отфик ×1; ходфикс ×1 |
| эстимейт | ru | 5 | 5 | 0.0% | из темей ×1; имеет ×1; истимеет ×1; с тимейт ×1; Скинь эстимейт → скинестемеет ×1 |
| API | mixed | 4 | 4 | 0.0% | ∅ ×1; айпи ×1; апиай ×1; эй пьяй ×1 |
| дообучение | ru | 4 | 4 | 0.0% | до обучения ×4 |
| постмортем | ru | 4 | 4 | 0.0% | постмартем ×2; по смартям ×1; смартьем ×1 |
| юзкейс | ru | 4 | 4 | 0.0% | юскейс ×4 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 6 |
| del | и | ∅ | 5 |
| ins | ∅ | и | 3 |
| sub_near | запасной | запасный | 2 |
| sub_near | на | но | 2 |
| sub_near | похоже | похожий | 2 |
| sub_near | проверил | проверила | 2 |
| del | с | ∅ | 2 |

Number style examples: 14:30 → четырнадцать тридцать; 11:30 → одиннадцать тридцать; 15:30 → пятнадцать тридцать; 100 → сто

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (24.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 6 | 6 | 0.0% | передеплейт ×3; передеплейить ×1; передеплейть ×1; передеплэйть ×1 |
| дообучение | ru | 4 | 4 | 0.0% | до обучения ×4 |
| постмортем | ru | 4 | 4 | 0.0% | постмартем ×2; Мартем ×1; по смартём ×1 |
| прод | ru | 5 | 4 | 20.0% | прот ×4; прод ×1 |
| BLEU | mixed | 3 | 3 | 0.0% | BellyU ×1; Беллиу ×1; бэллиу ×1 |
| asyncio | en | 3 | 3 | 0.0% | Ascensio ×1; Essencio ×1; essencia ×1 |
| отребейзить | ru | 3 | 3 | 0.0% | отребезить ×3 |
| эстимейт | ru | 5 | 3 | 40.0% | эстимейт ×2; Тиммейт ×1; истимей ×1; стимейт ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | and | in | 2 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| sub_near | правок | справок | 2 |
| sub_near | проверил | проверила | 2 |
| del | у | ∅ | 2 |
| sub_near | уведомление | уведомления | 2 |
| ins | ∅ | on | 1 |

Number style examples: 14:30 → 14.30; 15:30 → 15.30; 11:30 → 11.30; 10:00 → 10

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (26.8% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| дообучение | ru | 3 | 3 | 0.0% | до обучения ×3 |
| передеплоить | ru | 3 | 3 | 0.0% | передиплать ×2; передиплеить ×1 |
| API | en | 2 | 2 | 0.0% | APE ×1; APLI ×1 |
| BLEU | mixed | 2 | 2 | 0.0% | BAL-EU ×1; BLU ×1 |
| Golang | en | 2 | 2 | 0.0% | Galang ×2 |
| LoRA | en | 2 | 2 | 0.0% | Q low rA ×1; low rA ×1 |
| asyncio | en | 2 | 2 | 0.0% | Essencio ×2 |
| docker compose up | mixed | 2 | 2 | 0.0% | Docker Compose App ×1; Docker Composer App ×1 |

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
| sub | and | in | 1 |

Number style examples: 14:30 → 14.30; 11:30 → 11.30; 12:00 → 12; 3:30 → 330

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (35.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 6 | 6 | 0.0% | передеплодить ×2; передиплейть ×2; передеплывать ×1; передеплэйд ×1 |
| прод | ru | 5 | 5 | 0.0% | Прот ×2; прот ×2; прод, я → Продья ×1 |
| дообучение | ru | 4 | 4 | 0.0% | до обучения ×3; добычение ×1 |
| постмортем | ru | 4 | 4 | 0.0% | постмартем ×2; в SmartTem ×1; постмартим ×1 |
| легаси | ru | 5 | 4 | 0.0% | Легаси ×1; Легоси ×1; легосе ×1; легоси ×1; легаси код → Легосикот ×1 |
| хотфикс | ru | 5 | 4 | 0.0% | Хатфикс ×4; hotfix ×1 |
| эстимейт | ru | 5 | 4 | 0.0% | Steamate ×1; и стемеет ×1; и стимейд ×1; стимеет ×1 |
| Neo4j | en | 3 | 3 | 0.0% | Neo for ×1; the O4J ×1; Add Neo4j → ADD-Ni04J ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | cache | cash | 2 |
| sub_near | rerun | run | 2 |
| ins | ∅ | и | 2 |
| del | в | ∅ | 2 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| sub_near | мне | не | 2 |
| sub_near | проверил | проверила | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14-30; 14:30 → 14.30; 10:00 → 10
