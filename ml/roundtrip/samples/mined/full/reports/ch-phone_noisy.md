# Error mining — full CPU run, primary, phone_noisy

Rows mined: 1764 (skipped with null hypothesis: 0). Sources: primary_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 66 | 11.3% | 8.1% | 3.6% | 33.3% | 3.2% | 50.0% | sentence | full | mixed | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 127 | 15.0% | 13.1% | 6.6% | 65.6% | 2.1% | 29.6% | sentence | partial | digits | latin | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 127 | 14.9% | 13.2% | 7.4% | 66.3% | 2.1% | 29.2% | sentence | full | digits | mixed | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 193 | 20.8% | 15.5% | 9.9% | 52.3% | 8.0% | 30.7% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 66 | 16.4% | 11.4% | 6.4% | 37.3% | 6.3% | 43.0% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 193 | 15.0% | 11.4% | 6.4% | 46.8% | 4.1% | 37.3% | sentence | full | digits | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 193 | 14.0% | 10.5% | 6.2% | 46.5% | 3.1% | 37.8% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 193 | 24.3% | 18.9% | 11.7% | 58.3% | 10.8% | 29.1% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 127 | 22.9% | 18.7% | 13.4% | 77.7% | 6.4% | 17.8% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 193 | 10.9% | 8.1% | 4.1% | 33.6% | 2.9% | 49.1% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 93 | 11.3% | 7.7% | 4.6% | 33.8% | 2.3% | 46.1% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 193 | 15.6% | 12.0% | 6.2% | 46.3% | 4.9% | 36.5% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 66 | 11.3% | 8.1% | 3.6% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 69 | 20.8% | 18.3% | 10.8% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 58 | 8.0% | 6.8% | 1.8% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 69 | 20.7% | 18.5% | 11.8% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 58 | 7.8% | 6.8% | 2.3% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 66 | 21.6% | 15.7% | 9.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 69 | 25.9% | 18.5% | 15.6% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 58 | 13.7% | 11.8% | 3.4% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 66 | 16.4% | 11.4% | 6.4% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 66 | 14.8% | 10.7% | 5.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 69 | 19.7% | 14.8% | 10.9% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 58 | 9.6% | 8.1% | 2.5% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 66 | 12.6% | 9.3% | 4.5% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 69 | 18.8% | 13.9% | 10.7% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 58 | 9.9% | 7.7% | 2.7% |
| `qwen3-asr-0.6b@onnx-int8` | en | 66 | 16.6% | 10.2% | 5.8% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 69 | 35.9% | 29.0% | 21.6% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 58 | 20.0% | 17.4% | 6.6% |
| `t-one@onnx` | mixed | 69 | 30.0% | 24.7% | 21.3% |
| `t-one@onnx` | ru | 58 | 14.2% | 11.5% | 4.2% |
| `whisper-large-v3-turbo@onnx-int8` | en | 66 | 10.4% | 6.7% | 3.2% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 69 | 15.5% | 12.0% | 7.2% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 58 | 5.9% | 5.3% | 1.5% |
| `whisper-medium@onnx-int8` | en | 33 | 10.4% | 5.7% | 2.8% |
| `whisper-medium@onnx-int8` | mixed | 32 | 14.3% | 11.0% | 6.7% |
| `whisper-medium@onnx-int8` | ru | 28 | 8.8% | 6.2% | 4.0% |
| `whisper-small@onnx-int8` | en | 66 | 13.2% | 9.6% | 4.4% |
| `whisper-small@onnx-int8` | mixed | 69 | 21.2% | 16.1% | 10.7% |
| `whisper-small@onnx-int8` | ru | 58 | 11.9% | 9.9% | 2.9% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 98.5% | 93.9% | 80.2% | 98.5% | 98.5% | 78.3% | 66.7% | 99.8% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 99.2% | 94.1% | 89.0% | 15.8% | 78.9% | 91.7% | 70.3% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 96.9% | 100.0% | 100.0% | 77.7% | 91.7% | 61.8% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 100.0% | 99.5% | 81.0% | 69.4% | 24.9% | 80.4% | 25.0% | 95.8% | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 81.1% | 39.4% | 1.5% | 52.2% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 99.5% | 83.1% | 100.0% | 100.0% | 83.1% | 86.7% | 97.8% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 100.0% | 100.0% | 82.1% | 100.0% | 100.0% | 90.5% | 80.0% | 97.6% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 85.0% | 100.0% | 100.0% | 88.9% | 0.0% | 96.6% | 0.0% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 99.5% | 87.1% | 99.5% | 97.9% | 80.4% | 93.8% | 98.6% | 3.9% |
| `whisper-medium@onnx-int8` | 98.9% | 93.5% | 84.1% | 98.9% | 98.9% | 70.8% | 100.0% | 98.5% | 10.0% |
| `whisper-small@onnx-int8` | 99.0% | 96.4% | 85.8% | 99.5% | 97.9% | 82.5% | 86.7% | 97.9% | 19.2% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | kokoro1 | en | 22 | 10.7% | 5.5% | 56.1% | 19.5% | 99.6% |
| `distil-large-v3.5@onnx-int8` | piper | en | 22 | 15.5% | 14.6% | 45.2% | 38.1% | 100.0% |
| `distil-large-v3.5@onnx-int8` | supertonic3 | en | 22 | 7.7% | 4.2% | 48.9% | 20.0% | 99.7% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | mixed | 69 | 20.8% | 18.3% | 11.3% | 75.3% | 70.3% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | ru | 58 | 8.0% | 6.8% | 53.1% | 36.9% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | mixed | 69 | 20.7% | 18.5% | 12.7% | 77.5% | 61.8% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | ru | 58 | 7.8% | 6.8% | 50.4% | 37.8% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | kokoro1 | en | 22 | 21.7% | 13.1% | 29.3% | 36.6% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | en | 22 | 26.5% | 23.6% | 28.6% | 45.2% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | en | 22 | 16.7% | 10.3% | 37.8% | 28.9% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | mixed | 69 | 25.9% | 18.5% | 15.5% | 61.3% | 43.5% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | ru | 58 | 13.7% | 11.8% | 48.6% | 37.8% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | kokoro1 | en | 22 | 16.6% | 9.3% | 46.3% | 21.9% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | piper | en | 22 | 22.0% | 18.8% | 33.3% | 42.9% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | supertonic3 | en | 22 | 10.6% | 6.1% | 48.9% | 24.4% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | kokoro1 | en | 22 | 12.1% | 6.2% | 46.3% | 26.8% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | en | 22 | 23.0% | 19.4% | 30.9% | 42.9% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | en | 22 | 9.3% | 6.1% | 48.9% | 24.4% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | mixed | 69 | 19.7% | 14.8% | 19.0% | 55.6% | 71.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | ru | 58 | 9.6% | 8.1% | 54.9% | 31.5% | – |
| `parakeet-ultra-0.6b@onnx-int8` | kokoro1 | en | 22 | 12.4% | 6.6% | 43.9% | 29.3% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | en | 22 | 16.2% | 13.9% | 35.7% | 38.1% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | en | 22 | 9.3% | 7.4% | 44.4% | 31.1% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | mixed | 69 | 18.8% | 13.9% | 19.7% | 52.1% | 70.3% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | ru | 58 | 9.9% | 7.7% | 56.8% | 30.6% | – |
| `qwen3-asr-0.6b@onnx-int8` | kokoro1 | en | 22 | 14.1% | 5.2% | 46.3% | 19.5% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | piper | en | 22 | 23.3% | 18.1% | 30.9% | 45.2% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | en | 22 | 12.2% | 7.1% | 40.0% | 28.9% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | mixed | 69 | 35.9% | 29.0% | 11.3% | 66.9% | 43.4% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | ru | 58 | 20.0% | 17.4% | 40.5% | 48.6% | – |
| `t-one@onnx` | supertonic3 | mixed | 69 | 30.0% | 24.7% | 2.1% | 83.1% | 0.0% |
| `t-one@onnx` | supertonic3 | ru | 58 | 14.2% | 11.5% | 37.8% | 45.1% | – |
| `whisper-large-v3-turbo@onnx-int8` | kokoro1 | en | 22 | 6.9% | 1.4% | 61.0% | 7.3% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | piper | en | 22 | 15.2% | 13.6% | 40.5% | 38.1% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | en | 22 | 9.0% | 4.8% | 44.4% | 17.8% | 99.7% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | mixed | 69 | 15.5% | 12.0% | 37.3% | 42.2% | 86.5% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | ru | 58 | 5.9% | 5.3% | 64.9% | 23.4% | – |
| `whisper-medium@onnx-int8` | kokoro1 | en | 11 | 6.7% | 1.3% | 59.1% | 4.5% | 100.0% |
| `whisper-medium@onnx-int8` | piper | en | 11 | 14.6% | 11.8% | 54.2% | 29.2% | 100.0% |
| `whisper-medium@onnx-int8` | supertonic3 | en | 11 | 10.1% | 4.0% | 38.1% | 19.1% | 99.3% |
| `whisper-medium@onnx-int8` | supertonic3 | mixed | 32 | 14.3% | 11.0% | 42.0% | 40.6% | 85.0% |
| `whisper-medium@onnx-int8` | supertonic3 | ru | 28 | 8.8% | 6.2% | 45.6% | 29.8% | – |
| `whisper-small@onnx-int8` | kokoro1 | en | 22 | 9.7% | 5.5% | 56.1% | 19.5% | 100.0% |
| `whisper-small@onnx-int8` | piper | en | 22 | 18.1% | 16.2% | 35.7% | 42.9% | 100.0% |
| `whisper-small@onnx-int8` | supertonic3 | en | 22 | 11.6% | 6.8% | 44.4% | 24.4% | 99.7% |
| `whisper-small@onnx-int8` | supertonic3 | mixed | 69 | 21.2% | 16.1% | 21.1% | 57.0% | 73.5% |
| `whisper-small@onnx-int8` | supertonic3 | ru | 58 | 11.9% | 9.9% | 46.0% | 40.5% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| багфикс | ru | 10 | 29 | 25 | 86.2% | бакфикс ×14; Бакфикс ×9; баксикс ×1; бакфис ×1 |
| лейтенси | ru | 9 | 27 | 20 | 74.1% | лейтинси ×3; лейтензии ×2; лейтенци ×2; лейтенция ×2 |
| Google Colab | mixed | 9 | 18 | 18 | 100.0% | Google Call Up ×3; Google Call up ×3; Google Colap ×3; Google Call ×2 |
| ребейз | ru | 10 | 19 | 17 | 89.5% | ребейс ×8; ребей ×2; ребе из ×1; ребез ×1 |
| Kubernetes | mixed | 10 | 28 | 17 | 60.7% | кубернец ×3; Кубернекс ×2; Кубернет ×2; Kubernetes с → кубернек ×1 |
| pyproject.toml | en | 8 | 16 | 16 | 100.0% | piproject.toml ×3; Pipproject.toml ×2; pipproject.toml ×2; Pip Project Tamil ×1 |
| .gitignore | mixed | 10 | 19 | 16 | 84.2% | гитнёр ×2; Get Ignore ×1; GitNure ×1; Gitignora ×1 |
| Traefik | mixed | 10 | 20 | 15 | 75.0% | Трифик ×4; трейфик ×2; трифик ×2; 3.5 ×1 |
| BERT | en | 8 | 16 | 14 | 87.5% | bird ×6; The BERT → Bird ×3; Bird ×2; ∅ ×1 |
| Vercel | mixed | 9 | 18 | 14 | 77.8% | Версал ×3; Versailles ×1; Versal ×1; Versell ×1 |
| батч | ru | 9 | 18 | 14 | 77.8% | патч ×8; бач ×3; БАч ×1; батч документов → бачдокумента ×1 |
| Heroku | mixed | 10 | 20 | 14 | 70.0% | Героку ×2; Гироку ×2; Хироку ×2; гироку ×2 |
| RoPE | en | 9 | 17 | 13 | 76.5% | ropey ×5; Ropey ×2; ROPI ×1; Rhope E ×1 |
| Ирой | ru | 10 | 20 | 13 | 65.0% | героей ×2; с Ирой → Сируэй ×2; Евой ×1; Ерои ×1 |
| Kotlin | mixed | 10 | 19 | 11 | 57.9% | Kotlin и → котлани ×3; котла ×2; Kotlin и → котлений ×1; Котлын ×1 |
| Олей | mixed | 10 | 20 | 11 | 55.0% | Алей ×5; алей ×3; с Олей → солей ×3; ALAE ×1 |
| .github/workflows | mixed | 10 | 10 | 10 | 100.0% | GitHub Workflow ×4; Get Up Worklow ×1; Git Ap Workflow ×1; GitAб Workflow ×1 |
| Argo CD | mixed | 10 | 10 | 10 | 100.0% | в Argo CD → Fargo CD ×3; в Argo CD → Pargo CD ×2; Argos CD ×1; в Argo CD → PargoSD ×1 |
| CoT | mixed | 10 | 10 | 10 | 100.0% | с CoT → Скот ×2; с CoT → Скотт ×2; с CoT → скал ×2; с CoT → СКАУТ ×1 |
| GGUF | mixed | 10 | 10 | 10 | 100.0% | GG ×4; G ×2; GGF ×2; GGO ×1 |
| Jaeger | mixed | 10 | 10 | 10 | 100.0% | Jager ×2; джейгер ×2; G ×1; J ×1 |
| KEDA | mixed | 10 | 10 | 10 | 100.0% | кида ×4; Kida ×2; KID ×1; KIDA ×1 |
| Linear | mixed | 10 | 10 | 10 | 100.0% | лыния ×2; Линер ×1; Лынье ×1; Лынья ×1 |
| LiteLLM | mixed | 10 | 10 | 10 | 100.0% | Light LM ×5; Light L M ×1; LightLM ×1; LikeM ×1 |
| OkHttp | mixed | 10 | 10 | 10 | 100.0% | ОКТ ×3; OCOTDA ×1; OT ×1; Okrte ×1 |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (33.3% spelled out)
- repair garbled terms (25.8% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| RoPE | en | 2 | 2 | 0.0% | ropey ×1; RoPE scaling → rope-e-scaling ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | pipproject.toml ×1; pipreject.toml ×1 |
| Alertmanager | en | 1 | 1 | 0.0% | alert integer ×1 |
| Ceph | en | 1 | 1 | 0.0% | SUF ×1 |
| ClickHouse | en | 1 | 1 | 0.0% | click-out ×1 |
| ETA | en | 1 | 1 | 0.0% | EDA ×1 |
| Hetzner | en | 1 | 1 | 0.0% | Hetsner ×1 |
| Jaeger | en | 1 | 1 | 0.0% | ∅ ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | slash | 2 |
| sub_near | drop | draw up | 2 |
| ins | ∅ | hot | 1 |
| ins | ∅ | ssh | 1 |
| ins | ∅ | tilde | 1 |
| ins | ∅ | u | 1 |
| sub | a | the | 1 |
| sub_near | add | adeh | 1 |

Number style examples: 2 → two

## `gigaam-v3-e2e-ctc@onnx-int8`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 78.9% of reference)
- repair garbled terms (58.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 3 | 3 | 0.0% | бакфикс ×2; Бакфикс ×1 |
| .gitignore | mixed | 2 | 2 | 0.0% | Гетенёра ×1; гитнёр ×1 |
| Docker Compose | mixed | 2 | 2 | 0.0% | Doctor Compos ×1; Door Compose ×1 |
| Google Colab | mixed | 2 | 2 | 0.0% | Google Colap ×2 |
| Heroku | mixed | 2 | 2 | 0.0% | Героку ×1; Хeроk ×1 |
| NVIDIA | mixed | 2 | 2 | 0.0% | MVdia ×1; на NVIDIA → nMVideo ×1 |
| Terraform | mixed | 2 | 2 | 0.0% | теrform ×1; трапорм ×1 |
| Traefik | mixed | 2 | 2 | 0.0% | Trifik ×1; тrфик ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | к | 1 |
| del | в | ∅ | 1 |
| sub | в | из | 1 |
| sub_near | в файл | файла | 1 |
| sub_near | вебхуки | ветхи | 1 |
| sub_near | вывод | выводы | 1 |
| sub_near | демо | дема | 1 |
| sub_near | домена | домина | 1 |

Number style examples: 3000 → трёх тысячах

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (38.2% of Latin words)
- repair garbled terms (60.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 3 | 3 | 0.0% | Бакфикс ×3 |
| лейтенси | ru | 3 | 3 | 0.0% | лейтенсы ×1; лейтинси ×1; рейтинги ×1 |
| .gitignore | mixed | 2 | 2 | 0.0% | Гетера ×1; гитнёр ×1 |
| Docker Compose | mixed | 2 | 2 | 0.0% | Doctorcompose ×1; доктор ×1 |
| Google Colab | mixed | 2 | 2 | 0.0% | Google Colap ×1; Google Colop ×1 |
| Kotlin | mixed | 2 | 2 | 0.0% | котла ×1; котлани ×1 |
| NVIDIA | mixed | 2 | 2 | 0.0% | MVDIA ×1; videa ×1 |
| Traefik | mixed | 2 | 2 | 0.0% | трейфик ×2 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 3 |
| ins | ∅ | john | 1 |
| ins | ∅ | к | 1 |
| sub | вебхуки | ветку | 1 |
| sub_near | вывод | выводы | 1 |
| sub_near | исходниками | ходниками | 1 |
| sub_near | копятся | копится | 1 |
| sub_near | места | место | 1 |

Number style examples: 3000 → трёх тысячах

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 80.4% of reference)
- convert spoken numbers, times and dates to digits (75.0% spelled out)
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (46.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Tesseract | en | 3 | 3 | 0.0% | Tess ×1; Tesserte ×1; ract ×1 |
| багфикс | ru | 3 | 3 | 0.0% | Бакфикс ×3 |
| лейтенси | ru | 3 | 3 | 0.0% | лейтенсии ×1; лейтенци ×1; летенси ×1 |
| .gitignore | mixed | 2 | 2 | 0.0% | Гетегнера ×1; Гитнер ×1 |
| BERT | en | 2 | 2 | 0.0% | ∅ ×1; bird ×1 |
| Google Colab | mixed | 2 | 2 | 0.0% | Google Callup ×1; Google Cola ×1 |
| RLHF | en | 2 | 2 | 0.0% | RLH ×1; Rally Chef ×1 |
| inference | mixed | 2 | 2 | 0.0% | инфранс ×1; инфраренс ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 5 |
| del | could | ∅ | 4 |
| del | the | ∅ | 4 |
| del | you | ∅ | 3 |
| del | и | ∅ | 3 |
| ins | ∅ | a | 2 |
| del | can | ∅ | 2 |
| sub | the | a | 2 |

Number style examples: 11:30 → одиннадцать-тридцать; 100 → сто; 10:30 → десять тридцать; 11:30 → одиннадцать тридцать

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 52.2% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (29.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| BERT | en | 2 | 2 | 0.0% | Burke ×1; Pred ×1 |
| RoPE | en | 2 | 2 | 0.0% | Rhope E ×1; Ropey ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | Pip Project Tamil ×1; PipReject. Toml ×1 |
| Adreno | en | 1 | 1 | 0.0% | adrenal ×1 |
| Ceph | en | 1 | 1 | 0.0% | sub ×1 |
| ETA | en | 1 | 1 | 0.0% | EDA ×1 |
| Jaeger | en | 1 | 1 | 0.0% | It ×1 |
| KV cache | en | 1 | 1 | 0.0% | Kivikash ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | could | ∅ | 3 |
| del | the | ∅ | 3 |
| del | can | ∅ | 2 |
| del | hi | ∅ | 2 |
| sub | in | and | 2 |
| sub_near | rewrite | write | 2 |
| sub | the | a | 2 |
| del | you | ∅ | 2 |

Number style examples: 2 → two; 200 → two hundred; 5.0 → five; 95% → ninety five percent

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (40.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 3 | 3 | 0.0% | бакфикс ×3 |
| BERT | en | 2 | 2 | 0.0% | Bird ×1; The BERT → Bird ×1 |
| Google Colab | mixed | 2 | 2 | 0.0% | Google Call Up ×1; Google Call up ×1 |
| Heroku | mixed | 2 | 2 | 0.0% | Гироку ×1; Хировку ×1 |
| Kotlin | mixed | 2 | 2 | 0.0% | котла ×1; котлы ×1 |
| Traefik | mixed | 2 | 2 | 0.0% | Трифик ×2 |
| Vercel | mixed | 2 | 2 | 0.0% | Версал ×2 |
| pyproject.toml | en | 2 | 2 | 0.0% | Pipproject.toml ×2 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | the | 2 |
| sub_near | contexts | context | 2 |
| ins | ∅ | is | 1 |
| ins | ∅ | until | 1 |
| ins | ∅ | use | 1 |
| sub_near | a | at | 1 |
| sub | a | the | 1 |
| sub_near | add | added | 1 |

Number style examples: 11:30 → 11.30; 15:30 → 15.30; 10:30 → 10.30; 14:30 → 14.30

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (39.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 3 | 3 | 0.0% | бакфикс ×2; Бакфикс ×1 |
| BERT | en | 2 | 2 | 0.0% | Bird ×1; The BERT → Bird ×1 |
| Google Colab | mixed | 2 | 2 | 0.0% | Google Call up ×2 |
| Heroku | mixed | 2 | 2 | 0.0% | Hero ×1; Гироку ×1 |
| Kotlin | mixed | 2 | 2 | 0.0% | Котлын ×1; Kotlin и → котлани ×1 |
| Traefik | mixed | 2 | 2 | 0.0% | Trific ×1; Трифик ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | Pitproject.toml ×1; pipproject.toml ×1 |
| ребейз | ru | 2 | 2 | 0.0% | ребейс ×2 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | off | 1 |
| ins | ∅ | until | 1 |
| ins | ∅ | use | 1 |
| sub_near | a | at | 1 |
| sub_near | add | added | 1 |
| sub_near | block | lock | 1 |
| sub | block | walk | 1 |
| sub_near | contexts | context | 1 |

Number style examples: 11:30 → 11.30; 10:30 → 10.30; 11:30 → 11 тридцать; 11:30 → одиннадцать тридцать

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (49.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| лейтенси | ru | 3 | 3 | 0.0% | Летинсия ×1; лейт-нс и ×1; лейтенси на → Лейтенсена ×1 |
| Apache Iceberg | en | 2 | 2 | 0.0% | apocalyptic bird ×1; the patchy icebergs ×1 |
| BERT | en | 2 | 2 | 0.0% | bird ×1; The BERT → Bird ×1 |
| Google Colab | mixed | 2 | 2 | 0.0% | Google Call Up ×1; Google Кола ×1 |
| NVIDIA | mixed | 2 | 2 | 0.0% | МВД ×1; мебель ×1 |
| RoPE | en | 2 | 2 | 0.0% | ROPI ×1; east ×1 |
| Traefik | mixed | 2 | 2 | 0.0% | Трифик ×1; трифиг ×1 |
| Vercel | mixed | 2 | 2 | 0.0% | в рвсел ×1; вирсоль ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | s | 2 |
| sub_near | contexts | contacts | 2 |
| sub_near | drop | draw up | 2 |
| sub_near | во | в | 2 |
| sub_near | запусти | запустить | 2 |
| del | в | ∅ | 2 |
| ins | ∅ | a | 1 |
| ins | ∅ | h | 1 |

Number style examples: 11:30 → одиннадцать тридцать; 15:30 → пятнадцать тридцать; 10:30 → десять-тридцать; 14:30 → четырнадцать тридцать

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)
- repair garbled terms (66.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Kubernetes | mixed | 3 | 3 | 0.0% | куборнет ×1; куборный тес ×1; Kubernetes с → кубернек ×1 |
| MCP | mixed | 3 | 3 | 0.0% | мс ×1; мсп ×1; мцп ×1 |
| багфикс | ru | 3 | 3 | 0.0% | бакфикс ×2; баксикс ×1 |
| лейтенси | ru | 3 | 3 | 0.0% | лейтинси ×1; лейтинти ×1; лекинси ×1 |
| .gitignore | mixed | 2 | 2 | 0.0% | гипнер ×1; гитенера ×1 |
| Docker Compose | mixed | 2 | 2 | 0.0% | докоркомпоус ×1; захаркомфа ×1 |
| Google Colab | mixed | 2 | 2 | 0.0% | гугл каб ×1; Google Colab работает → гугл обработает ×1 |
| Heroku | mixed | 2 | 2 | 0.0% | гироку ×1; с Heroku → сироку ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 4 |
| del | и | ∅ | 4 |
| del | в | ∅ | 2 |
| ins | ∅ | в | 1 |
| ins | ∅ | пей | 1 |
| ins | ∅ | пепе | 1 |
| ins | ∅ | сатин | 1 |
| ins | ∅ | шоу | 1 |

Number style examples: 11:30 → одиннадцать тридцать; 15:30 → пятнадцать тридцать; 10:30 → десять тридцать; 14:30 → четырнадцать тридцать

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (29.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| .gitignore | mixed | 2 | 2 | 0.0% | GitNure ×1; гитингора ×1 |
| BERT | en | 2 | 2 | 0.0% | bird ×2 |
| Google Colab | mixed | 2 | 2 | 0.0% | Google Call ×1; Google Call Up ×1 |
| RoPE | en | 2 | 2 | 0.0% | Rokey's ×1; ropey ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | Piproject.toml ×1; piproject.toml ×1 |
| Олей | mixed | 2 | 2 | 0.0% | Алей ×1; аллей ×1 |
| батч | ru | 2 | 2 | 0.0% | бач ×1; патч ×1 |
| лейтенси | ru | 3 | 2 | 33.3% | лейтенси ×1; лейтенци ×1; лейтенция ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | in | an | 2 |
| del | в | ∅ | 2 |
| del | и | ∅ | 2 |
| ins | ∅ | tilde | 1 |
| ins | ∅ | using | 1 |
| sub | a | the | 1 |
| sub_near | add | ad | 1 |
| sub | add | i | 1 |

Number style examples: 11:30 → 11.30; 15:30 → 15.30; 10:30 → 10.30; 14:30 → 14.30

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (29.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Олей | mixed | 2 | 2 | 0.0% | ALAE ×1; с Олей → салей ×1 |
| .github/workflows | mixed | 1 | 1 | 0.0% | GitHub workflow ×1 |
| .gitignore | mixed | 1 | 1 | 0.0% | Gitignora ×1 |
| Argo CD | mixed | 1 | 1 | 0.0% | в Argo CD → Fargo CD ×1 |
| Ceph | en | 1 | 1 | 0.0% | SUF ×1 |
| CoT | mixed | 1 | 1 | 0.0% | с CoT → СКАУТ ×1 |
| GGUF | mixed | 1 | 1 | 0.0% | GG ×1 |
| GigaChat | mixed | 1 | 1 | 0.0% | GigaShot ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | add | ∅ | 1 |
| sub_near | block | lock | 1 |
| sub_near | hi | i | 1 |
| sub_near | outdated | our dated | 1 |
| sub_near | pickle | fickle | 1 |
| sub_near | pods | paws | 1 |
| sub_near | spans | spawns | 1 |
| sub_near | team | teamed | 1 |

Number style examples: 11:30 → 11.30; 10:30 → 10.30; 14:30 → 14.30; 15:00 → 15.00

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (42.8% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| багфикс | ru | 3 | 3 | 0.0% | бакфикс ×3 |
| лейтенси | ru | 3 | 3 | 0.0% | лейтензии ×2; лейтенция ×1 |
| .gitignore | mixed | 2 | 2 | 0.0% | Get Ignore ×1; гетинер ×1 |
| Apache Iceberg | en | 2 | 2 | 0.0% | Apache Icebird ×1; Apakya, iceberg ×1 |
| Google Colab | mixed | 2 | 2 | 0.0% | Google Call ×1; Google Call-а ×1 |
| Heroku | mixed | 2 | 2 | 0.0% | Героку ×1; Хироку ×1 |
| RoPE | en | 2 | 2 | 0.0% | ropey ×2 |
| Traefik | mixed | 2 | 2 | 0.0% | трифик ×2 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | alert | 1 |
| ins | ∅ | and | 1 |
| ins | ∅ | the | 1 |
| ins | ∅ | until | 1 |
| sub | a | the | 1 |
| del | add | ∅ | 1 |
| sub | block | walk | 1 |
| sub_near | contexts | context | 1 |

Number style examples: 11:30 → 11.30; 10:30 → 10.30; 14:30 → 14.30; 15:00 → 15.00
