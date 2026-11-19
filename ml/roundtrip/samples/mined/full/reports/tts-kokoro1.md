# Error mining — full CPU run, kokoro1

Rows mined: 867 (skipped with null hypothesis: 0). Sources: all_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 102 | 8.0% | 4.0% | 1.7% | 17.3% | 0.9% | 57.6% | sentence | full | digits | latin | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 102 | 18.4% | 10.3% | 7.4% | 28.6% | 6.2% | 35.5% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 102 | 16.8% | 9.5% | 6.7% | 27.4% | 5.4% | 44.2% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 102 | 10.2% | 4.5% | 3.2% | 19.2% | 1.2% | 54.4% | sentence | full | mixed | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 102 | 9.4% | 4.4% | 2.1% | 19.6% | 1.0% | 52.1% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 102 | 13.7% | 5.9% | 4.4% | 27.4% | 1.0% | 50.2% | sentence | full | words | latin | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 102 | 7.5% | 3.3% | 1.6% | 16.2% | 0.4% | 60.4% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 51 | 7.4% | 3.3% | 1.4% | 13.6% | 0.9% | 60.6% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 102 | 9.5% | 5.0% | 2.2% | 22.9% | 0.9% | 53.9% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 102 | 8.0% | 4.0% | 1.7% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 102 | 18.4% | 10.3% | 7.4% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 102 | 16.8% | 9.5% | 6.7% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 102 | 10.2% | 4.5% | 3.2% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 102 | 9.4% | 4.4% | 2.1% |
| `qwen3-asr-0.6b@onnx-int8` | en | 102 | 13.7% | 5.9% | 4.4% |
| `whisper-large-v3-turbo@onnx-int8` | en | 102 | 7.5% | 3.3% | 1.6% |
| `whisper-medium@onnx-int8` | en | 51 | 7.4% | 3.3% | 1.4% |
| `whisper-small@onnx-int8` | en | 102 | 9.5% | 5.0% | 2.2% |

## WER by channel

| ASR model | channel | rows | WER | style-aware WER |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | clean | 80 | 7.3% | 3.5% |
| `distil-large-v3.5@onnx-int8` | phone_noisy | 22 | 10.7% | 5.5% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | clean | 80 | 17.5% | 9.6% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | phone_noisy | 22 | 21.7% | 13.1% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | clean | 80 | 16.9% | 9.5% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | phone_noisy | 22 | 16.6% | 9.3% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | clean | 80 | 9.7% | 4.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | phone_noisy | 22 | 12.1% | 6.2% |
| `parakeet-ultra-0.6b@onnx-int8` | clean | 80 | 8.7% | 3.9% |
| `parakeet-ultra-0.6b@onnx-int8` | phone_noisy | 22 | 12.4% | 6.6% |
| `qwen3-asr-0.6b@onnx-int8` | clean | 80 | 13.6% | 6.1% |
| `qwen3-asr-0.6b@onnx-int8` | phone_noisy | 22 | 14.1% | 5.2% |
| `whisper-large-v3-turbo@onnx-int8` | clean | 80 | 7.6% | 3.8% |
| `whisper-large-v3-turbo@onnx-int8` | phone_noisy | 22 | 6.9% | 1.4% |
| `whisper-medium@onnx-int8` | clean | 40 | 7.6% | 3.8% |
| `whisper-medium@onnx-int8` | phone_noisy | 11 | 6.7% | 1.3% |
| `whisper-small@onnx-int8` | clean | 80 | 9.4% | 4.9% |
| `whisper-small@onnx-int8` | phone_noisy | 22 | 9.7% | 5.5% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 97.1% | 96.1% | 84.4% | 99.0% | 99.0% | 100.0% | 88.9% | 99.9% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 100.0% | 99.0% | 77.1% | 35.3% | 0.0% | 75.0% | 0.0% | 100.0% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 80.6% | 33.3% | 1.0% | 53.1% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 99.0% | 82.8% | 100.0% | 100.0% | 90.6% | 44.4% | 100.0% | – |
| `parakeet-ultra-0.6b@onnx-int8` | 100.0% | 100.0% | 83.2% | 100.0% | 100.0% | 93.8% | 77.8% | 100.0% | – |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 85.6% | 100.0% | 100.0% | 131.2% | 10.0% | 100.0% | – |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 87.6% | 100.0% | 100.0% | 93.8% | 90.0% | 100.0% | – |
| `whisper-medium@onnx-int8` | 100.0% | 92.2% | 84.3% | 100.0% | 100.0% | 81.2% | 100.0% | 100.0% | – |
| `whisper-small@onnx-int8` | 99.0% | 97.1% | 85.7% | 100.0% | 100.0% | 87.5% | 88.9% | 100.0% | – |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | kokoro1 | en | 102 | 8.0% | 4.0% | 57.6% | 15.2% | 99.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | kokoro1 | en | 102 | 18.4% | 10.3% | 35.5% | 23.5% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | kokoro1 | en | 102 | 16.8% | 9.5% | 44.2% | 18.9% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | kokoro1 | en | 102 | 10.2% | 4.5% | 54.4% | 16.1% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | kokoro1 | en | 102 | 9.4% | 4.4% | 52.1% | 18.0% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | kokoro1 | en | 102 | 13.7% | 5.9% | 50.2% | 17.1% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | kokoro1 | en | 102 | 7.5% | 3.3% | 60.4% | 12.4% | 100.0% |
| `whisper-medium@onnx-int8` | kokoro1 | en | 51 | 7.4% | 3.3% | 60.6% | 11.9% | 100.0% |
| `whisper-small@onnx-int8` | kokoro1 | en | 102 | 9.5% | 5.0% | 53.9% | 19.4% | 100.0% |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| LoRA | en | 9 | 18 | 18 | 100.0% | Q low RA ×7; low RA ×7; Q low rA ×1; low rA ×1 |
| pyproject.toml | en | 8 | 16 | 16 | 100.0% | pipreject.toml ×3; piproject.toml ×2; PipReject. Toml ×1; PipRject Toml ×1 |
| asyncio | en | 9 | 17 | 16 | 94.1% | Asincio ×3; Asccio ×2; ACICEO ×1; Acinsi O ×1 |
| Golang | en | 9 | 18 | 16 | 88.9% | Galang ×6; Galing ×2; Gallang ×2; Galling ×2 |
| BM25 | en | 9 | 18 | 12 | 66.7% | M25 ×5; ∅ ×3; M twenty five ×3; speak M twenty five ×1 |
| /etc/hosts | en | 9 | 9 | 9 | 100.0% | etc host ×4; etc. host ×4; etc/host ×1 |
| Deno | en | 9 | 9 | 9 | 100.0% | Dino ×9 |
| prefill | en | 9 | 9 | 9 | 100.0% | Profil ×7; perfil ×1; profil ×1 |
| Sofia | en | 8 | 8 | 8 | 100.0% | Sophia ×7; Hi Sofia → Isofia ×1 |
| Tarantool | en | 8 | 8 | 8 | 100.0% | Terran tool ×7; Terrentool ×1 |
| Vercel | en | 8 | 8 | 8 | 100.0% | versal ×3; Versal ×2; VERSAL ×1; Versailles ×1 |
| W&B | en | 8 | 8 | 8 | 100.0% | WANB ×3; 1B ×2; B ×1; WOANB ×1 |
| huggingface-cli download | en | 8 | 8 | 8 | 100.0% | Hugging Face Glide download ×2; Hugging Face Gly download ×2; Hugging Face Glide Download ×1; Hugging Face Gly Download ×1 |
| Selectel | en | 9 | 9 | 8 | 88.9% | Selectal ×5; SelectAll ×1; Selectl ×1; select all ×1 |
| systemctl restart | en | 9 | 9 | 8 | 88.9% |  |
| logits | en | 8 | 8 | 7 | 87.5% | logics ×6; lodgets ×1 |
| safetensors | en | 8 | 8 | 7 | 87.5% | Safetensa's ×2; SFA tensor's ×1; Safa-Tensas ×1; Safatenser's ×1 |
| softmax | en | 9 | 9 | 7 | 77.8% | softness ×6; soft math ×1 |
| Phi-4 | en | 9 | 9 | 6 | 66.7% | 5-4 ×2; four ×2; 5 ×1; five ×1 |
| Hetzner | en | 9 | 17 | 6 | 35.3% | Headstar ×2; Hetsner ×2; Hertzner ×1; Hetschner ×1 |
| ETA | en | 8 | 8 | 5 | 62.5% | EDA ×4; IDA ×1 |
| PagerDuty | en | 8 | 8 | 5 | 62.5% | Major duty ×1; Page or duty ×1; Page your duty ×1; Paid your duty ×1 |
| PyTorch | en | 8 | 8 | 5 | 62.5% | Pi Torch ×3; Pi torch ×1; PiTorch ×1 |
| Kotlin coroutines | en | 9 | 9 | 5 | 55.6% | Kotlin core routines ×4; kotland car routines ×1 |
| Linear | en | 9 | 9 | 5 | 55.6% | ∅ ×2; Liner ×2; Linner ×1 |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (15.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Golang | en | 2 | 2 | 0.0% | Galang ×2 |
| LoRA | en | 2 | 2 | 0.0% | low ra ×1; q low ra ×1 |
| asyncio | en | 2 | 2 | 0.0% | Asccio ×2 |
| pyproject.toml | en | 2 | 2 | 0.0% | pipreject.toml ×2 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc. host ×1 |
| Debezium | en | 1 | 1 | 0.0% | Debesium ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| ETA | en | 1 | 1 | 0.0% | EDA ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | slash | 1 |
| ins | ∅ | u | 1 |
| sub_near | backend | backhand | 1 |
| sub_near | baselines | bass lines | 1 |
| sub_near | block | lock | 1 |
| sub_near | drop | draw up | 1 |
| sub | fix | fixspeak | 1 |
| sub_near | root | route | 1 |

Number style examples: 2 → two; 3:30 → 330

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 77.1%)
- restore missing punctuation (commas at 75.0% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (23.5% of terms are recognition errors) — from context only
- tolerate dropped words (4.0% of non-term words) without inventing content

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| BM25 | en | 2 | 2 | 0.0% | ∅ ×1; M twenty five ×1 |
| Golang | en | 2 | 2 | 0.0% | Galing ×1; Galng ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| Tesseract | en | 2 | 2 | 0.0% | Tesserte ×1; ract ×1 |
| asyncio | en | 2 | 2 | 0.0% | Asinsio ×1; acinco ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | PipperjecTOML ×1; Piproject Toml ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc host ×1 |
| B2B | en | 1 | 1 | 0.0% | B to be ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | can | ∅ | 5 |
| del | could | ∅ | 4 |
| del | the | ∅ | 4 |
| del | i | ∅ | 3 |
| del | list | ∅ | 3 |
| del | block | ∅ | 2 |
| del | for | ∅ | 2 |
| del | run | ∅ | 2 |

Number style examples: 3 → three; 2 → two; 30 → Thirty; 30 → thirty

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 53.1% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (18.9% of terms are recognition errors) — from context only
- tolerate dropped words (3.7% of non-term words) without inventing content

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Golang | en | 2 | 2 | 0.0% | Gallang ×1; galling ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| asyncio | en | 2 | 2 | 0.0% | Asincio ×2 |
| pyproject.toml | en | 2 | 2 | 0.0% | PipReject. Toml ×1; PipRject Toml ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc host ×1 |
| AndroidManifest.xml | en | 1 | 1 | 0.0% | Android Manifest dot XML ×1 |
| Cargo.toml | en | 1 | 1 | 0.0% | Cargo dot Toml ×1 |
| Debezium | en | 1 | 1 | 0.0% | Debesium ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | can | ∅ | 5 |
| del | could | ∅ | 5 |
| del | i | ∅ | 3 |
| del | list | ∅ | 3 |
| del | the | ∅ | 3 |
| del | block | ∅ | 2 |
| del | for | ∅ | 2 |
| sub_near | rewrite | write | 2 |

Number style examples: 2 → two; 3 → Three; 3 → three; 30 → Thirty

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (55.6% spelled out)
- repair garbled terms (16.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| BM25 | en | 2 | 2 | 0.0% | ∅ ×1; speak M twenty five ×1 |
| Golang | en | 2 | 2 | 0.0% | Galling ×1; in Golang → ingalling ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| asyncio | en | 2 | 2 | 0.0% | ACICEO ×1; Acinsi O ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | Pipproject.toml ×1; piperject.taml ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc. host ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| ETA | en | 1 | 1 | 0.0% | EDA ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | slash | 1 |
| sub | backend | bekhand | 1 |
| sub | block | walk | 1 |
| del | build | ∅ | 1 |
| sub_near | cache | cash | 1 |
| sub_near | drop | draw up | 1 |
| sub_near | print the installed | prestalled | 1 |
| sub_near | sync | syn | 1 |

Number style examples: 2 → two; 3 → three; 300 → three hundred; 3:30 → three thirty

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (18.0% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| BM25 | en | 2 | 2 | 0.0% | ∅ ×1; M25 ×1 |
| Golang | en | 2 | 2 | 0.0% | Gallang ×1; Galling ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| asyncio | en | 2 | 2 | 0.0% | Acinsio ×1; Asincio ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | piperject.toml ×1; pipproject.toml ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc. host ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| EC2 | en | 1 | 1 | 0.0% | EEC2 ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | off | 1 |
| ins | ∅ | slash | 1 |
| sub_near | backend | backhand | 1 |
| sub | block | walk | 1 |
| sub_near | cache | cash | 1 |
| sub_near | drop | draw | 1 |
| sub | fix | fixbeak | 1 |
| del | how | ∅ | 1 |

Number style examples: 2 → two; 3 → three; 3:30 → 330

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (90.0% spelled out)
- repair garbled terms (17.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| nginx.conf | en | 2 | 2 | 0.0% | Engine X Confor ×1; Engine X Conv ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | pipproject.dottoml ×1; pipregect.toml ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc. host ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| Gradle | en | 1 | 1 | 0.0% | great old ×1 |
| Jetpack Compose | en | 1 | 1 | 0.0% | jetpack composure ×1 |
| Kotlin coroutines | en | 1 | 1 | 0.0% | Kotlin core routines ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | five | 1 |
| ins | ∅ | slash | 1 |
| sub_near | cache | cash | 1 |
| sub_near | card | car | 1 |
| sub_near | drop | draw up | 1 |
| sub_near | fix | fixbee | 1 |
| sub_near | i'm | i am | 1 |
| sub | set | said | 1 |

Number style examples: 30 → thirty; 2 → two; 3 → Three; 3 → three

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- little beyond light punctuation fixes

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Golang | en | 2 | 2 | 0.0% | Galang ×2 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| asyncio | en | 2 | 2 | 0.0% | Ascensio ×1; essencia ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | pipreject.toml ×1; piproject.toml ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc host ×1 |
| AndroidManifest.xml | en | 1 | 1 | 0.0% | android manifest dot xml ×1 |
| Cargo.toml | en | 1 | 1 | 0.0% | cargo dot tommel ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | and | in | 1 |
| sub_near | backend | backhand | 1 |
| sub | block | walk | 1 |
| sub | fix | fixbeat | 1 |
| sub_near | sync | sink | 1 |

Number style examples: 2 → two; 5.0 → 5

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- little beyond light punctuation fixes

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Golang | en | 2 | 2 | 0.0% | Galang ×2 |
| LoRA | en | 2 | 2 | 0.0% | Q low rA ×1; low rA ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc host ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| NumPy | en | 1 | 1 | 0.0% | num-pi ×1 |
| Selectel | en | 1 | 1 | 0.0% | Selectl ×1 |
| asyncio | en | 1 | 1 | 0.0% | Essencio ×1 |
| prefill | en | 1 | 1 | 0.0% | Profil ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | backend | bakken's | 1 |
| sub_near | baselines | basslines | 1 |
| sub_near | block | lock | 1 |
| sub | fix | fixbeak | 1 |
| sub_near | sync | sink | 1 |

Number style examples: 3:30 → 330

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (19.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Golang | en | 2 | 2 | 0.0% | Galing ×1; Gulleng ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| Silero | en | 2 | 2 | 0.0% | Cylero ×1; Silero VAD → CyleroVAD ×1 |
| asyncio | en | 2 | 2 | 0.0% | Asensio ×1; essenseo ×1 |
| pyproject.toml | en | 2 | 2 | 0.0% | pipperject.toml ×1; piproject.toml ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc/host ×1 |
| BF16 | en | 1 | 1 | 0.0% | beat F16 ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | as | forrest | 1 |
| sub_near | backend | backhand | 1 |
| sub | block | walk | 1 |
| sub_near | cache | cash | 1 |
| sub_near | drop | draw up | 1 |
| sub | fix | fixbeat | 1 |
| sub_near | hi | i | 1 |
| sub_near | rebalance | balance | 1 |

Number style examples: 2 → two; 3:30 → 330
