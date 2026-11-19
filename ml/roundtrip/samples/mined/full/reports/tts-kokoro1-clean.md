# Error mining — full CPU run, kokoro1, clean

Rows mined: 680 (skipped with null hypothesis: 0). Sources: all_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 80 | 7.3% | 3.5% | 1.6% | 16.1% | 0.6% | 58.0% | sentence | full | digits | latin | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 80 | 17.5% | 9.6% | 7.1% | 25.4% | 6.0% | 36.9% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 80 | 16.9% | 9.5% | 6.9% | 27.2% | 5.4% | 43.8% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 80 | 9.7% | 4.1% | 3.3% | 17.1% | 1.1% | 56.2% | sentence | full | mixed | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 80 | 8.7% | 3.9% | 1.9% | 16.6% | 1.0% | 54.0% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 80 | 13.6% | 6.1% | 4.5% | 28.1% | 1.0% | 51.1% | sentence | full | words | latin | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 80 | 7.6% | 3.8% | 1.6% | 18.4% | 0.4% | 60.2% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 40 | 7.6% | 3.8% | 1.5% | 16.0% | 0.9% | 60.9% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 80 | 9.4% | 4.9% | 2.2% | 22.6% | 0.8% | 53.4% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 80 | 7.3% | 3.5% | 1.6% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 80 | 17.5% | 9.6% | 7.1% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 80 | 16.9% | 9.5% | 6.9% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 80 | 9.7% | 4.1% | 3.3% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 80 | 8.7% | 3.9% | 1.9% |
| `qwen3-asr-0.6b@onnx-int8` | en | 80 | 13.6% | 6.1% | 4.5% |
| `whisper-large-v3-turbo@onnx-int8` | en | 80 | 7.6% | 3.8% | 1.6% |
| `whisper-medium@onnx-int8` | en | 40 | 7.6% | 3.8% | 1.5% |
| `whisper-small@onnx-int8` | en | 80 | 9.4% | 4.9% | 2.2% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 97.5% | 97.5% | 85.6% | 98.8% | 98.8% | 108.3% | 100.0% | 100.0% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 100.0% | 98.8% | 77.4% | 32.5% | 0.0% | 75.0% | 0.0% | 100.0% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 81.5% | 28.7% | 0.0% | 45.8% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 85.4% | 100.0% | 100.0% | 87.5% | 42.9% | 100.0% | – |
| `parakeet-ultra-0.6b@onnx-int8` | 100.0% | 100.0% | 85.9% | 100.0% | 100.0% | 91.7% | 85.7% | 100.0% | – |
| `qwen3-asr-0.6b@onnx-int8` | 100.0% | 100.0% | 85.7% | 100.0% | 100.0% | 154.2% | 14.3% | 100.0% | – |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 89.4% | 100.0% | 100.0% | 87.5% | 100.0% | 100.0% | – |
| `whisper-medium@onnx-int8` | 100.0% | 100.0% | 87.3% | 100.0% | 100.0% | 91.7% | 100.0% | 100.0% | – |
| `whisper-small@onnx-int8` | 100.0% | 98.8% | 85.0% | 100.0% | 100.0% | 87.5% | 100.0% | 100.0% | – |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | kokoro1 | en | 80 | 7.3% | 3.5% | 58.0% | 14.2% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | kokoro1 | en | 80 | 17.5% | 9.6% | 36.9% | 20.4% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | kokoro1 | en | 80 | 16.9% | 9.5% | 43.8% | 18.2% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | kokoro1 | en | 80 | 9.7% | 4.1% | 56.2% | 13.6% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | kokoro1 | en | 80 | 8.7% | 3.9% | 54.0% | 15.3% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | kokoro1 | en | 80 | 13.6% | 6.1% | 51.1% | 16.5% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | kokoro1 | en | 80 | 7.6% | 3.8% | 60.2% | 13.6% | 100.0% |
| `whisper-medium@onnx-int8` | kokoro1 | en | 40 | 7.6% | 3.8% | 60.9% | 13.8% | 100.0% |
| `whisper-small@onnx-int8` | kokoro1 | en | 80 | 9.4% | 4.9% | 53.4% | 19.3% | 100.0% |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| LoRA | en | 9 | 18 | 18 | 100.0% | Q low RA ×7; low RA ×7; Q low rA ×1; low rA ×1 |
| asyncio | en | 9 | 17 | 16 | 94.1% | Asincio ×3; Asccio ×2; ACICEO ×1; Acinsi O ×1 |
| Golang | en | 9 | 18 | 16 | 88.9% | Galang ×6; Galing ×2; Gallang ×2; Galling ×2 |
| BM25 | en | 9 | 18 | 12 | 66.7% | M25 ×5; ∅ ×3; M twenty five ×3; speak M twenty five ×1 |
| /etc/hosts | en | 9 | 9 | 9 | 100.0% | etc host ×4; etc. host ×4; etc/host ×1 |
| Deno | en | 9 | 9 | 9 | 100.0% | Dino ×9 |
| prefill | en | 9 | 9 | 9 | 100.0% | Profil ×7; perfil ×1; profil ×1 |
| Tarantool | en | 8 | 8 | 8 | 100.0% | Terran tool ×7; Terrentool ×1 |
| Vercel | en | 8 | 8 | 8 | 100.0% | versal ×3; Versal ×2; VERSAL ×1; Versailles ×1 |
| W&B | en | 8 | 8 | 8 | 100.0% | WANB ×3; 1B ×2; B ×1; WOANB ×1 |
| huggingface-cli download | en | 8 | 8 | 8 | 100.0% | Hugging Face Glide download ×2; Hugging Face Gly download ×2; Hugging Face Glide Download ×1; Hugging Face Gly Download ×1 |
| pyproject.toml | en | 8 | 8 | 8 | 100.0% | pipreject.toml ×2; PipRject Toml ×1; PipperjecTOML ×1; piperject.taml ×1 |
| Selectel | en | 9 | 9 | 8 | 88.9% | Selectal ×5; SelectAll ×1; Selectl ×1; select all ×1 |
| systemctl restart | en | 9 | 9 | 8 | 88.9% |  |
| safetensors | en | 8 | 8 | 7 | 87.5% | Safetensa's ×2; SFA tensor's ×1; Safa-Tensas ×1; Safatenser's ×1 |
| Phi-4 | en | 9 | 9 | 6 | 66.7% | 5-4 ×2; four ×2; 5 ×1; five ×1 |
| PyTorch | en | 8 | 8 | 5 | 62.5% | Pi Torch ×3; Pi torch ×1; PiTorch ×1 |
| Linear | en | 9 | 9 | 5 | 55.6% | ∅ ×2; Liner ×2; Linner ×1 |
| NumPy | en | 9 | 9 | 5 | 55.6% | num pi ×3; num-pi ×1; numb Pi ×1 |
| build.gradle.kts | en | 8 | 8 | 4 | 50.0% | Kts ×1; Review build.gradle.kts → Viewbuildgradlekts ×1; build dot gradle dot kts ×1; build. dot. gradle. dot kts ×1 |
| eval set | en | 8 | 8 | 4 | 50.0% | Ival set ×4 |
| Silero | en | 8 | 16 | 4 | 25.0% | Cilero ×1; Cylaro ×1; Cylero ×1; Silero VAD → CyleroVAD ×1 |
| nginx.conf | en | 9 | 17 | 4 | 23.5% | Engine X Confor ×1; Engine X Conv ×1; Engine Xconv ×1; engine X-conv ×1 |
| Cargo.toml | en | 8 | 8 | 3 | 37.5% | Cargo dot Toml ×1; and Cargo.toml → andcargo ×1; cargo dot tommel ×1 |
| Ktor | en | 8 | 8 | 3 | 37.5% | K Tour ×1; K tour ×1; Kour ×1 |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- little beyond light punctuation fixes

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Golang | en | 2 | 2 | 0.0% | Galang ×2 |
| LoRA | en | 2 | 2 | 0.0% | low ra ×1; q low ra ×1 |
| asyncio | en | 2 | 2 | 0.0% | Asccio ×2 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc. host ×1 |
| Debezium | en | 1 | 1 | 0.0% | Debesium ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| Linear | en | 1 | 1 | 0.0% | Linner ×1 |
| ONNX | en | 1 | 1 | 0.0% | Onix ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | slash | 1 |
| sub_near | backend | backhand | 1 |
| sub_near | baselines | bass lines | 1 |
| sub | fix | fixspeak | 1 |
| sub_near | root | route | 1 |

Number style examples: 3:30 → 330

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 77.4%)
- restore missing punctuation (commas at 75.0% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (20.4% of terms are recognition errors) — from context only
- tolerate dropped words (4.0% of non-term words) without inventing content

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| BM25 | en | 2 | 2 | 0.0% | ∅ ×1; M twenty five ×1 |
| Golang | en | 2 | 2 | 0.0% | Galing ×1; Galng ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| asyncio | en | 2 | 2 | 0.0% | Asinsio ×1; acinco ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc host ×1 |
| B2B | en | 1 | 1 | 0.0% | B to be ×1 |
| Cargo.toml | en | 1 | 1 | 0.0% | and Cargo.toml → andcargo ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | can | ∅ | 4 |
| del | could | ∅ | 4 |
| del | i | ∅ | 3 |
| del | for | ∅ | 2 |
| del | list | ∅ | 2 |
| del | run | ∅ | 2 |
| del | the | ∅ | 2 |
| del | use | ∅ | 2 |

Number style examples: 3 → three; 30 → Thirty; 30 → thirty; 300 → three hundred

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 45.8% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (18.2% of terms are recognition errors) — from context only
- tolerate dropped words (3.7% of non-term words) without inventing content

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Golang | en | 2 | 2 | 0.0% | Gallang ×1; galling ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| asyncio | en | 2 | 2 | 0.0% | Asincio ×2 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc host ×1 |
| AndroidManifest.xml | en | 1 | 1 | 0.0% | Android Manifest dot XML ×1 |
| Cargo.toml | en | 1 | 1 | 0.0% | Cargo dot Toml ×1 |
| Debezium | en | 1 | 1 | 0.0% | Debesium ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | could | ∅ | 5 |
| del | can | ∅ | 4 |
| del | i | ∅ | 3 |
| del | for | ∅ | 2 |
| del | list | ∅ | 2 |
| del | use | ∅ | 2 |
| ins | ∅ | five | 1 |
| ins | ∅ | slash | 1 |

Number style examples: 3 → Three; 3 → three; 30 → Thirty; 30 → thirty

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (57.1% spelled out)

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| BM25 | en | 2 | 2 | 0.0% | ∅ ×1; speak M twenty five ×1 |
| Golang | en | 2 | 2 | 0.0% | Galling ×1; in Golang → ingalling ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| asyncio | en | 2 | 2 | 0.0% | ACICEO ×1; Acinsi O ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc. host ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| Jest | en | 1 | 1 | 0.0% | Gest ×1 |
| Modal | en | 1 | 1 | 0.0% | Model ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | slash | 1 |
| sub | backend | bekhand | 1 |
| del | build | ∅ | 1 |
| sub_near | cache | cash | 1 |
| sub_near | print the installed | prestalled | 1 |
| sub_near | sync | syn | 1 |
| del | target | ∅ | 1 |
| del | version | ∅ | 1 |

Number style examples: 3 → three; 300 → three hundred; 3:30 → three thirty; 4090 → forty ninety

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (15.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| BM25 | en | 2 | 2 | 0.0% | ∅ ×1; M25 ×1 |
| Golang | en | 2 | 2 | 0.0% | Gallang ×1; Galling ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| asyncio | en | 2 | 2 | 0.0% | Acinsio ×1; Asincio ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc. host ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| EC2 | en | 1 | 1 | 0.0% | EEC2 ×1 |
| Google Meet | en | 1 | 1 | 0.0% | Google Me ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | slash | 1 |
| sub_near | backend | backhand | 1 |
| sub_near | cache | cash | 1 |
| sub | fix | fixbeak | 1 |
| del | how | ∅ | 1 |
| sub_near | i'm | i am | 1 |
| sub_near | rebalance | balance | 1 |
| sub_near | sync | sink | 1 |

Number style examples: 3 → three; 3:30 → 330

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (85.7% spelled out)
- repair garbled terms (16.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| nginx.conf | en | 2 | 2 | 0.0% | Engine X Confor ×1; Engine X Conv ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc. host ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| Ktor | en | 1 | 1 | 0.0% | K tour ×1 |
| Phi-4 | en | 1 | 1 | 0.0% | four ×1 |
| PyTorch | en | 1 | 1 | 0.0% | Pi Torch ×1 |
| RNN-T | en | 1 | 1 | 0.0% | R N T ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | five | 1 |
| ins | ∅ | slash | 1 |
| sub_near | cache | cash | 1 |
| sub_near | card | car | 1 |
| sub_near | fix | fixbee | 1 |
| sub_near | i'm | i am | 1 |
| sub | set | said | 1 |
| sub | use | you | 1 |

Number style examples: 30 → thirty; 3 → Three; 3 → three; 3:30 → three thirty

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- little beyond light punctuation fixes

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Golang | en | 2 | 2 | 0.0% | Galang ×2 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| asyncio | en | 2 | 2 | 0.0% | Ascensio ×1; essencia ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc host ×1 |
| AndroidManifest.xml | en | 1 | 1 | 0.0% | android manifest dot xml ×1 |
| Cargo.toml | en | 1 | 1 | 0.0% | cargo dot tommel ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| NumPy | en | 1 | 1 | 0.0% | num pi ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | and | in | 1 |
| sub_near | backend | backhand | 1 |
| sub | fix | fixbeat | 1 |
| sub_near | sync | sink | 1 |

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
| sub | fix | fixbeak | 1 |
| sub_near | sync | sink | 1 |

Number style examples: 3:30 → 330

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (19.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Golang | en | 2 | 2 | 0.0% | Galing ×1; Gulleng ×1 |
| LoRA | en | 2 | 2 | 0.0% | Q low RA ×1; low RA ×1 |
| Silero | en | 2 | 2 | 0.0% | Cylero ×1; Silero VAD → CyleroVAD ×1 |
| asyncio | en | 2 | 2 | 0.0% | Asensio ×1; essenseo ×1 |
| /etc/hosts | en | 1 | 1 | 0.0% | etc/host ×1 |
| BF16 | en | 1 | 1 | 0.0% | beat F16 ×1 |
| Deno | en | 1 | 1 | 0.0% | Dino ×1 |
| Kafka Connect | en | 1 | 1 | 0.0% | Kafka Connector ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub | as | forrest | 1 |
| sub_near | backend | backhand | 1 |
| sub_near | cache | cash | 1 |
| sub | fix | fixbeat | 1 |
| sub_near | rebalance | balance | 1 |
| sub_near | root | route | 1 |
| sub | use | you | 1 |

Number style examples: 3:30 → 330
