# Error mining — full CPU run, all voices

Rows mined: 9882 (skipped with null hypothesis: 0). Sources: all_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 307 | 9.6% | 6.3% | 2.8% | 26.7% | 1.9% | 52.5% | sentence | full | digits | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 766 | 15.9% | 13.5% | 7.1% | 61.9% | 2.9% | 30.5% | sentence | partial | digits | mixed | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 766 | 15.7% | 13.2% | 7.7% | 60.7% | 2.8% | 30.4% | sentence | full | digits | mixed | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 1073 | 21.0% | 15.8% | 10.4% | 52.1% | 8.0% | 31.5% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 308 | 16.6% | 10.6% | 7.0% | 34.1% | 5.6% | 42.4% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 1074 | 15.2% | 11.2% | 6.4% | 42.6% | 4.5% | 40.7% | sentence | full | digits | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 1074 | 13.8% | 10.0% | 5.7% | 40.2% | 3.5% | 42.8% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 1074 | 25.0% | 19.7% | 12.3% | 57.7% | 11.5% | 30.6% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 766 | 23.5% | 18.5% | 13.9% | 74.5% | 6.3% | 18.6% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 1074 | 11.5% | 8.4% | 4.8% | 32.6% | 3.1% | 50.2% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 526 | 12.8% | 9.1% | 5.3% | 34.0% | 3.7% | 47.8% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 1074 | 16.4% | 12.9% | 6.9% | 45.7% | 5.8% | 38.7% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 307 | 9.6% | 6.3% | 2.8% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 419 | 20.7% | 17.6% | 10.5% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 347 | 9.8% | 8.3% | 3.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 419 | 20.4% | 17.2% | 11.3% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 347 | 9.8% | 8.0% | 3.5% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 308 | 18.9% | 12.8% | 8.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 418 | 26.7% | 20.4% | 16.7% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 347 | 16.0% | 13.2% | 4.9% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 308 | 16.6% | 10.6% | 7.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 308 | 12.1% | 7.4% | 3.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 419 | 20.0% | 15.6% | 10.2% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 347 | 12.4% | 9.7% | 4.0% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 308 | 10.2% | 6.4% | 2.9% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 419 | 18.6% | 14.4% | 9.7% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 347 | 11.2% | 8.3% | 3.4% |
| `qwen3-asr-0.6b@onnx-int8` | en | 308 | 15.0% | 8.2% | 5.3% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 419 | 34.2% | 28.4% | 20.9% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 347 | 23.4% | 20.5% | 8.1% |
| `t-one@onnx` | mixed | 419 | 29.6% | 23.3% | 21.6% |
| `t-one@onnx` | ru | 347 | 15.8% | 12.4% | 4.7% |
| `whisper-large-v3-turbo@onnx-int8` | en | 308 | 9.4% | 6.0% | 2.7% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 419 | 15.1% | 11.3% | 7.8% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 347 | 9.1% | 7.1% | 2.9% |
| `whisper-medium@onnx-int8` | en | 154 | 9.1% | 5.6% | 2.5% |
| `whisper-medium@onnx-int8` | mixed | 202 | 15.8% | 12.1% | 7.4% |
| `whisper-medium@onnx-int8` | ru | 170 | 12.8% | 8.8% | 5.2% |
| `whisper-small@onnx-int8` | en | 308 | 10.9% | 7.5% | 3.4% |
| `whisper-small@onnx-int8` | mixed | 419 | 21.8% | 17.6% | 11.1% |
| `whisper-small@onnx-int8` | ru | 347 | 15.3% | 12.4% | 5.0% |

## WER by channel

| ASR model | channel | rows | WER | style-aware WER |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | clean | 241 | 9.2% | 5.8% |
| `distil-large-v3.5@onnx-int8` | phone_noisy | 66 | 11.3% | 8.1% |
| `gigaam-v3-e2e-ctc@onnx-int8` | clean | 605 | 15.5% | 13.1% |
| `gigaam-v3-e2e-ctc@onnx-int8` | phone_noisy | 161 | 17.2% | 15.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | clean | 605 | 15.2% | 12.5% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | phone_noisy | 161 | 17.5% | 15.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | clean | 846 | 20.0% | 14.7% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | phone_noisy | 227 | 24.9% | 20.1% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | clean | 242 | 16.6% | 10.4% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | phone_noisy | 66 | 16.4% | 11.4% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | clean | 847 | 14.3% | 10.2% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | phone_noisy | 227 | 18.7% | 15.2% |
| `parakeet-ultra-0.6b@onnx-int8` | clean | 847 | 12.8% | 9.0% |
| `parakeet-ultra-0.6b@onnx-int8` | phone_noisy | 227 | 17.4% | 14.1% |
| `qwen3-asr-0.6b@onnx-int8` | clean | 847 | 23.9% | 18.6% |
| `qwen3-asr-0.6b@onnx-int8` | phone_noisy | 227 | 29.1% | 24.3% |
| `t-one@onnx` | clean | 605 | 22.5% | 17.3% |
| `t-one@onnx` | phone_noisy | 161 | 27.3% | 23.1% |
| `whisper-large-v3-turbo@onnx-int8` | clean | 847 | 10.9% | 7.6% |
| `whisper-large-v3-turbo@onnx-int8` | phone_noisy | 227 | 14.0% | 11.3% |
| `whisper-medium@onnx-int8` | clean | 418 | 12.4% | 8.6% |
| `whisper-medium@onnx-int8` | phone_noisy | 108 | 14.6% | 11.1% |
| `whisper-small@onnx-int8` | clean | 847 | 15.5% | 11.9% |
| `whisper-small@onnx-int8` | phone_noisy | 227 | 20.1% | 16.6% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 98.7% | 95.8% | 83.5% | 98.7% | 97.4% | 116.0% | 95.7% | 99.9% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 99.6% | 94.3% | 89.0% | 23.9% | 85.2% | 87.0% | 66.7% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 94.4% | 100.0% | 100.0% | 85.4% | 90.7% | 60.7% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 99.8% | 99.2% | 79.2% | 67.2% | 27.5% | 81.0% | 14.4% | 95.4% | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 81.3% | 28.2% | 0.3% | 51.0% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 99.9% | 99.8% | 89.1% | 99.9% | 99.9% | 92.7% | 81.4% | 97.8% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 99.9% | 99.7% | 88.1% | 100.0% | 100.0% | 93.4% | 83.8% | 97.6% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 99.4% | 99.3% | 82.4% | 99.4% | 99.3% | 81.5% | 7.4% | 96.7% | 7.8% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 99.8% | 99.5% | 89.6% | 99.1% | 95.0% | 86.4% | 98.0% | 97.9% | 15.4% |
| `whisper-medium@onnx-int8` | 99.4% | 98.1% | 89.2% | 98.5% | 95.8% | 84.2% | 98.1% | 98.4% | 14.3% |
| `whisper-small@onnx-int8` | 99.5% | 98.6% | 86.6% | 99.2% | 95.0% | 85.7% | 93.6% | 97.8% | 10.1% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | kokoro1 | en | 102 | 8.0% | 4.0% | 57.6% | 15.2% | 99.9% |
| `distil-large-v3.5@onnx-int8` | piper | en | 104 | 12.2% | 9.8% | 44.9% | 33.2% | 99.9% |
| `distil-large-v3.5@onnx-int8` | supertonic3 | en | 101 | 8.5% | 4.8% | 54.7% | 23.6% | 99.9% |
| `gigaam-v3-e2e-ctc@onnx-int8` | piper | mixed | 60 | 26.6% | 23.0% | 11.9% | 72.2% | 41.9% |
| `gigaam-v3-e2e-ctc@onnx-int8` | piper | ru | 46 | 18.2% | 15.8% | 32.3% | 57.3% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | mixed | 359 | 19.7% | 16.7% | 16.8% | 67.5% | 69.8% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | ru | 301 | 8.5% | 7.1% | 51.5% | 36.4% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | piper | mixed | 60 | 27.0% | 24.1% | 9.5% | 77.8% | 37.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | piper | ru | 46 | 20.4% | 18.4% | 28.1% | 62.5% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | mixed | 359 | 19.2% | 16.1% | 16.8% | 66.6% | 63.2% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | ru | 301 | 8.2% | 6.4% | 52.6% | 32.6% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | kokoro1 | en | 102 | 18.4% | 10.3% | 35.5% | 23.5% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | en | 104 | 23.5% | 18.6% | 26.3% | 45.9% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | mixed | 60 | 44.2% | 40.5% | 2.4% | 86.5% | 18.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | ru | 46 | 36.7% | 34.7% | 26.0% | 70.8% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | en | 102 | 14.8% | 9.1% | 40.2% | 28.5% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | mixed | 358 | 23.7% | 16.9% | 19.5% | 55.0% | 49.3% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | ru | 301 | 12.8% | 10.0% | 51.0% | 34.8% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | kokoro1 | en | 102 | 16.8% | 9.5% | 44.2% | 18.9% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | piper | en | 104 | 21.8% | 15.8% | 31.2% | 40.5% | 100.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | supertonic3 | en | 102 | 10.9% | 6.2% | 51.4% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | kokoro1 | en | 102 | 10.2% | 4.5% | 54.4% | 16.1% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | en | 104 | 16.1% | 11.5% | 36.1% | 38.0% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | mixed | 60 | 35.7% | 31.4% | 7.1% | 75.4% | 37.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | ru | 46 | 28.2% | 26.5% | 30.2% | 65.6% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | en | 102 | 9.7% | 5.9% | 53.3% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | mixed | 359 | 17.2% | 12.9% | 28.9% | 47.9% | 81.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | ru | 301 | 10.0% | 7.1% | 56.5% | 26.4% | – |
| `parakeet-ultra-0.6b@onnx-int8` | kokoro1 | en | 102 | 9.4% | 4.4% | 52.1% | 18.0% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | en | 104 | 11.9% | 8.7% | 42.9% | 33.2% | 99.9% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | mixed | 60 | 34.2% | 29.6% | 8.7% | 75.4% | 40.5% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | ru | 46 | 25.5% | 23.9% | 32.3% | 59.4% | – |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | en | 102 | 9.3% | 5.9% | 50.9% | 26.2% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | mixed | 359 | 15.9% | 11.8% | 31.6% | 44.8% | 80.6% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | ru | 301 | 9.1% | 6.0% | 59.5% | 23.4% | – |
| `qwen3-asr-0.6b@onnx-int8` | kokoro1 | en | 102 | 13.7% | 5.9% | 50.2% | 17.1% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | piper | en | 104 | 20.3% | 13.1% | 31.2% | 39.0% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | piper | mixed | 60 | 55.4% | 52.2% | 0.8% | 88.1% | 29.4% |
| `qwen3-asr-0.6b@onnx-int8` | piper | ru | 46 | 46.6% | 43.9% | 14.6% | 83.3% | – |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | en | 102 | 10.9% | 5.5% | 50.5% | 24.8% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | mixed | 359 | 30.6% | 24.4% | 20.1% | 57.7% | 60.1% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | ru | 301 | 19.9% | 17.0% | 38.3% | 48.8% | – |
| `t-one@onnx` | piper | mixed | 60 | 42.1% | 38.2% | 0.8% | 85.7% | 0.0% |
| `t-one@onnx` | piper | ru | 46 | 32.6% | 28.4% | 15.6% | 71.9% | – |
| `t-one@onnx` | supertonic3 | mixed | 359 | 27.4% | 20.8% | 2.9% | 73.4% | 0.0% |
| `t-one@onnx` | supertonic3 | ru | 301 | 13.3% | 10.0% | 42.8% | 39.8% | – |
| `whisper-large-v3-turbo@onnx-int8` | kokoro1 | en | 102 | 7.5% | 3.3% | 60.4% | 12.4% | 100.0% |
| `whisper-large-v3-turbo@onnx-int8` | piper | en | 104 | 12.6% | 10.0% | 44.4% | 33.7% | 99.9% |
| `whisper-large-v3-turbo@onnx-int8` | piper | mixed | 60 | 28.8% | 25.5% | 18.2% | 63.5% | 64.1% |
| `whisper-large-v3-turbo@onnx-int8` | piper | ru | 46 | 26.0% | 24.3% | 27.1% | 65.6% | – |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | en | 102 | 7.9% | 4.5% | 56.1% | 20.1% | 99.8% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | mixed | 359 | 12.8% | 8.9% | 43.1% | 33.0% | 85.5% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | ru | 301 | 6.5% | 4.5% | 66.2% | 19.1% | – |
| `whisper-medium@onnx-int8` | kokoro1 | en | 51 | 7.4% | 3.3% | 60.6% | 11.9% | 100.0% |
| `whisper-medium@onnx-int8` | piper | en | 52 | 11.7% | 9.2% | 46.5% | 33.7% | 99.9% |
| `whisper-medium@onnx-int8` | piper | mixed | 28 | 29.1% | 26.7% | 14.3% | 66.7% | 82.6% |
| `whisper-medium@onnx-int8` | piper | ru | 22 | 27.5% | 25.4% | 20.4% | 70.5% | – |
| `whisper-medium@onnx-int8` | supertonic3 | en | 51 | 8.1% | 4.2% | 61.7% | 21.5% | 99.7% |
| `whisper-medium@onnx-int8` | supertonic3 | mixed | 174 | 13.7% | 9.8% | 44.9% | 35.8% | 88.4% |
| `whisper-medium@onnx-int8` | supertonic3 | ru | 148 | 10.4% | 6.2% | 53.2% | 22.6% | – |
| `whisper-small@onnx-int8` | kokoro1 | en | 102 | 9.5% | 5.0% | 53.9% | 19.4% | 100.0% |
| `whisper-small@onnx-int8` | piper | en | 104 | 13.7% | 11.2% | 40.0% | 36.6% | 99.9% |
| `whisper-small@onnx-int8` | piper | mixed | 60 | 42.5% | 39.5% | 10.3% | 78.6% | 66.7% |
| `whisper-small@onnx-int8` | piper | ru | 46 | 37.8% | 35.9% | 16.7% | 76.0% | – |
| `whisper-small@onnx-int8` | supertonic3 | en | 102 | 9.3% | 6.2% | 51.9% | 25.7% | 99.9% |
| `whisper-small@onnx-int8` | supertonic3 | mixed | 359 | 18.3% | 13.9% | 31.4% | 46.5% | 79.1% |
| `whisper-small@onnx-int8` | supertonic3 | ru | 301 | 11.9% | 8.9% | 46.9% | 35.0% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| эстимейт | ru | 10 | 66 | 61 | 92.4% | стимейт ×9; и стимейт ×3; иметь ×3; стимеи ×3 |
| передеплоить | ru | 10 | 67 | 61 | 91.0% | передеплеить ×8; передеплейть ×7; передеплейт ×6; передиплеить ×6 |
| ребейз | ru | 10 | 66 | 59 | 89.4% | ребейс ×27; ребей ×3; ребис ×3; Рибейс ×2 |
| прод | ru | 10 | 67 | 51 | 76.1% | прот ×26; на прод → напрот ×4; на прод → напротив ×3; Прот ×2 |
| багфикс | ru | 10 | 57 | 49 | 86.0% | бакфикс ×32; Бакфикс ×12; бакфис ×3; баксикс ×1 |
| LiteLLM | mixed | 10 | 48 | 45 | 93.8% | Light LM ×5; Light LLM ×4; Alone ×3; L ×3 |
| дообучение | ru | 10 | 49 | 44 | 89.8% | до обучения ×33; дабычение ×2; обучение ×2; до обучеениея ×1 |
| фичефлаг | ru | 10 | 50 | 43 | 86.0% | фичи флаг ×8; фичи-флаг ×4; хичи флаг ×3; фичефлак ×2 |
| постмортем | ru | 9 | 45 | 42 | 93.3% | постмартем ×12; постмартём ×3; по смартем ×2; по смартём ×2 |
| лейтенси | ru | 10 | 65 | 42 | 64.6% | лейтинси ×7; лейтензии ×4; лейтенци ×3; лейтензия ×2 |
| kubectl logs | mixed | 10 | 38 | 37 | 97.4% | Quebec Logs ×2; кубек локс ×2; Back to Locs ×1; Back to Lox ×1 |
| отребейзить | ru | 10 | 38 | 36 | 94.7% | отребезить ×33; отревезить ×1; отреть изить ×1; отрибейзить ×1 |
| мерджреквест | ru | 10 | 49 | 36 | 73.5% | мержеквест ×4; межреквест ×3; мерджереквест ×3; GQS ×1 |
| тикет | ru | 10 | 78 | 36 | 46.2% | этикет ×22; кет ×5; Закрываю тикет → закрывайтикето ×1; кета ×1 |
| docker compose up | mixed | 10 | 38 | 35 | 92.1% | Docker Compose App ×4; Docker Composer App ×3; докер компулсап ×2; дотер компусап ×2 |
| Pydantic | mixed | 10 | 40 | 35 | 87.5% | педантик ×4; Paydantic ×3; Pedantic ×3; Пайдотик ×2 |
| HAProxy | mixed | 10 | 38 | 32 | 84.2% | H-Proxy ×4; А прокси ×3; А Прокси ×2; H Proxy ×1 |
| заапрувить | ru | 10 | 38 | 32 | 84.2% | заопровить ×5; заопрувить ×4; заправить ×3; запровить ×3 |
| Svelte | mixed | 10 | 38 | 31 | 81.6% | свет ×5; свалте ×4; Svelte, проверка → сверт-проверка ×1; Svelte, проверка → сверхпроверка ×1 |
| Slack | mixed | 10 | 39 | 31 | 79.5% | слаг ×11; в Slack → вслак ×3; Слаг ×2; к ×2 |
| tsconfig.json | mixed | 10 | 30 | 30 | 100.0% | конфиг ×3; конфит ×3; с конфиг, с ×2; сон ×2 |
| Protobuf | mixed | 10 | 29 | 29 | 100.0% | протобов ×5; протубов ×3; про тобов ×2; протобув ×2 |
| черрипик | ru | 10 | 29 | 29 | 100.0% | черепик ×13; чирипик ×2; чрипик ×2; чрыпик ×2 |
| pgBouncer | mixed | 10 | 30 | 29 | 96.7% | бы баунсер ×4; ∅ ×1; B-Bouncer ×1; B-баунсера ×1 |
| Hadoop | mixed | 10 | 38 | 29 | 76.3% | ходу ×7; Хадуб ×6; Hadub ×3; Hadob ×2 |

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
- restore missing punctuation (commas at 85.2% of reference)
- spell out again numbers the writer wanted in words (33.3% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (33.3% of Latin words)
- repair garbled terms (55.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 7 | 7 | 0.0% | передеплейть ×3; передеплеить ×2; передепловать ×1; передиплейть ×1 |
| эстимейт | ru | 7 | 7 | 0.0% | Estmat ×1; Stmat ×1; истемей ×1; истемейд ×1; эстимейт в → темете ×1 |
| тикет | ru | 8 | 7 | 12.5% | этикет ×6; кет ×1; тикет ×1 |
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×5; Бакфикс ×1 |
| лейтенси | ru | 7 | 6 | 14.3% | лейтинси ×3; Лэйтенси ×1; лейтензия ×1; лейтенси ×1 |
| Авито | ru | 5 | 5 | 0.0% | Авита ×2; Аvito ×1; с Авито → Совета ×2 |
| постмортем | ru | 5 | 5 | 0.0% | Смартом ×1; паспортём ×1; пастмортям ×1; пост-мортм ×1 |
| прод | ru | 7 | 5 | 28.6% | прот ×4; прод ×2; на прод → напрок ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 6 |
| sub_near | исправление | исправления | 5 |
| del | и | ∅ | 4 |
| del | с | ∅ | 3 |
| del | а | ∅ | 2 |
| sub_near | в файл | файла | 2 |
| sub_near | запасной | запасный | 2 |
| sub_near | исправление | исправления | 2 |

Number style examples: 100 → сто; 12:00 → 12; 3000 → трёх тысячах; 5000 → 5 000

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (50.0% of spelled-out numbers come back as digits)
- turn Cyrillic transliterations back into Latin terms (39.3% of Latin words)
- repair garbled terms (54.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 7 | 7 | 0.0% | передеплеить ×4; передеплейть ×2; перейплоть ×1 |
| прод | ru | 7 | 7 | 0.0% | прот ×5; пруд ×1; на прод → напрот ×1 |
| ребейз | ru | 7 | 7 | 0.0% | ребейс ×4; Рибейс ×1; ребис ×1; ремиз ×1 |
| эстимейт | ru | 7 | 7 | 0.0% | Истемет ×1; имейте ×1; исмейт ×1; истемей ×1; эстимейт в задаче → мейдзадаче ×1 |
| тикет | ru | 8 | 7 | 12.5% | этикет ×6; кет ×1; тикет ×1 |
| багфикс | ru | 6 | 6 | 0.0% | Бакфикс ×4; бакфикс ×2 |
| LiteLLM | mixed | 5 | 5 | 0.0% | Light LLM ×1; Littlem ×1; Лейталон ×1; лайк ×1 |
| дашборд | ru | 5 | 5 | 0.0% | дашборт ×2; борт ×1; дашбот ×1; дош борт ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 6 |
| sub_near | исправление | исправления | 4 |
| del | и | ∅ | 3 |
| del | с | ∅ | 3 |
| sub_near | уведомление | уведомления | 3 |
| sub_near | запасной | запасный | 2 |
| sub_near | тесты | тесто | 2 |
| sub_near | дублируется | дублируются | 2 |

Number style examples: 12:00 → 12; 100 → сто; 10:00 → 10; 15:00 → 15

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 79.2%)
- restore missing punctuation (commas at 81.0% of reference)
- convert spoken numbers, times and dates to digits (85.6% spelled out)
- spell out again numbers the writer wanted in words (75.0% of spelled-out numbers come back as digits)
- repair garbled terms (45.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 7 | 7 | 0.0% | передиплеить ×2; передиплеть ×2; переделать ×1; передеплыть ×1 |
| ребейз | ru | 7 | 7 | 0.0% | ребейс ×3; Левис ×1; Рибейс ×1; ребе из ×1; упростить ребейз → упростителебесь ×1 |
| эстимейт | ru | 7 | 7 | 0.0% | стиммейт ×2; и стеметь ×1; из стиммейт ×1; имеет ×1 |
| багфикс | ru | 6 | 6 | 0.0% | Бакфикс ×5; бакфикс ×1 |
| лейтенси | ru | 7 | 6 | 0.0% | Лейтинси ×1; лейтенсии ×1; лейтенци ×1; лейтинзи ×1; с лейтенси → Слейтенси ×1 |
| LiteLLM | mixed | 5 | 5 | 0.0% | Light LM ×1; LightL ×1; Лайте ЛЛМ ×1; Лайтелон ×1 |
| Авито | ru | 5 | 5 | 0.0% | Авита ×2; с Авито → Совета ×2; с Авито → Совита ×1 |
| дообучение | ru | 5 | 5 | 0.0% | до обучения ×5 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | the | ∅ | 15 |
| del | could | ∅ | 14 |
| del | can | ∅ | 10 |
| del | you | ∅ | 10 |
| del | и | ∅ | 9 |
| del | в | ∅ | 8 |
| del | в | ∅ | 7 |
| del | run | ∅ | 5 |

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
- spell out again numbers the writer wanted in words (80.0% of spelled-out numbers come back as digits)
- repair garbled terms (38.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| ребейз | ru | 7 | 7 | 0.0% | ребейс ×5; ребисс ×1; ребись ×1 |
| эстимейт | ru | 7 | 7 | 0.0% | стимейт ×3; и стимейт ×1; истимей ×1; стиметь ×1; эстимейт в → истиметь ×1 |
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×6 |
| передеплоить | ru | 7 | 6 | 14.3% | передиплейт ×2; передиплоить ×2; передеплей ×1; передеплоит ×1 |
| LiteLLM | mixed | 5 | 5 | 0.0% | Alone ×1; L ×1; Light LLM ×1; Light LM ×1 |
| постмортем | ru | 5 | 5 | 0.0% | постмартем ×2; по смарт<unk>м ×1; по смартем ×1; с Мартем ×1 |
| фичефлаг | ru | 5 | 5 | 0.0% | фичефлак ×1; фичи факт ×1; фичи флаг ×1; фичифлаг ×1 |
| Олей | mixed | 7 | 5 | 14.3% | Алей ×3; Олей ×1; Солей ×1; часове ×1; с Олей → солей ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 6 |
| sub_near | правки | прав | 4 |
| ins | ∅ | sharp | 3 |
| del | и | ∅ | 3 |
| sub | из | с | 3 |
| sub_near | уведомление | уведомления | 3 |
| ins | ∅ | d | 2 |
| ins | ∅ | the | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14.30; 15:30 → 15.30; 10:00 → 10

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (35.9% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| ребейз | ru | 7 | 7 | 0.0% | ребейс ×5; ребис ×1; ревиз ×1 |
| эстимейт | ru | 7 | 7 | 0.0% | стимейт ×3; есть ×1; и стимейт ×1; из тимейт ×1 |
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×5; Бакфикс ×1 |
| LiteLLM | mixed | 5 | 5 | 0.0% | L ×1; Light LLM ×1; Light LM ×1; Littlearm ×1 |
| постмортем | ru | 5 | 5 | 0.0% | постмартем ×3; по смартем ×1; с Мартем ×1 |
| фичефлаг | ru | 5 | 5 | 0.0% | фичефак ×1; фичефлак ×1; фичи флаг ×1; фичифлак ×1 |
| HAProxy | mixed | 4 | 4 | 0.0% | H Proxy ×1; H-Proxy ×1; H-прокси ×1; А прокси ×1 |
| Hadoop | mixed | 4 | 4 | 0.0% | Hadob ×1; Хаду ×1; Хадуб ×1; ходу ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 4 |
| del | в | ∅ | 4 |
| ins | ∅ | sharp | 3 |
| sub | из | с | 3 |
| del | с | ∅ | 3 |
| sub_near | уведомление | уведомления | 3 |
| ins | ∅ | slash | 2 |
| ins | ∅ | to | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14.30; 15:30 → 15.30; 11:30 → одиннадцать тридцать

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (92.6% spelled out)
- repair garbled terms (49.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| лейтенси | ru | 7 | 7 | 0.0% | Летинсия ×1; лейт-нс и ×1; лейтенсива в ×1; лейтинга ×1; лейтенси на → Лейтенсена ×1; с лейтенси → слайденте ×1 |
| передеплоить | ru | 7 | 7 | 0.0% | передеплейт ×3; переделать ×1; передеплать ×1; передеплейить ×1 |
| прод | ru | 7 | 7 | 0.0% | прот ×3; прототип ×1; пруд ×1; прод вечером → прокувещенном ×1; прод опять → протопять ×1 |
| ребейз | ru | 7 | 7 | 0.0% | ребейс ×2; рибейс ×2; лейбис ×1; ребеис ×1 |
| эстимейт | ru | 7 | 6 | 0.0% | Steam Edit ×1; Steamaid ×1; автомобиль ×1; и стимейт ×1 |
| LiteLLM | mixed | 5 | 5 | 0.0% | Alone ×1; Light L M ×1; Light LLM ×1; лайт-елф-элфа ×1 |
| дообучение | ru | 5 | 5 | 0.0% | до обучения ×3; дабычение ×2 |
| легаси | ru | 5 | 5 | 0.0% | легесси ×1; легкой ×1; легосе ×1; легости ×1; легаси код → легосекот ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 7 |
| del | и | ∅ | 5 |
| sub_near | объясни | объясните | 5 |
| sub_near | пятницу | пяточку | 5 |
| sub_near | во | в | 4 |
| sub_near | пятницу | пяточку | 4 |
| ins | ∅ | в | 4 |
| sub_near | объясни | объяснение | 4 |

Number style examples: 14:30 → четырнадцать тридцать; 11:30 → одиннадцать тридцать; 15:30 → пятнадцать тридцать; 30 → thirty

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)
- repair garbled terms (61.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| тикет | ru | 8 | 8 | 0.0% | этикет ×6; тикек ×1; Закрываю тикет → закрывайтикето ×1 |
| передеплоить | ru | 7 | 7 | 0.0% | передиплеить ×3; передеплеить ×2; передиплыть ×1; перейди вплеить ×1 |
| ребейз | ru | 7 | 7 | 0.0% | ребей ×2; ребейс ×2; ребей ис ×1; ребиз ×1 |
| эстимейт | ru | 7 | 7 | 0.0% | из темей ×1; имеет ×1; иметь ×1; истимеет ×1; Скинь эстимейт → скинестемеет ×1; эстимейт по → стемеетпо ×1 |
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×5; баксикс ×1 |
| LiteLLM | mixed | 5 | 5 | 0.0% | лайкл ×1; лайт ×1; лайтелон ×1; летелом ×1; подключаю LiteLLM → подключаем ×1 |
| дашборд | ru | 5 | 5 | 0.0% | дашбор ×2; дашборт ×2; шборт ×1 |
| дообучение | ru | 5 | 5 | 0.0% | до обучения ×4; до обычения ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 11 |
| del | и | ∅ | 10 |
| del | в | ∅ | 5 |
| del | с | ∅ | 4 |
| ins | ∅ | в | 3 |
| ins | ∅ | и | 3 |
| sub_near | тесты | тесто | 3 |
| ins | ∅ | четыре | 2 |

Number style examples: 11:30 → одиннадцать тридцать; 14:30 → четырнадцать тридцать; 15:30 → пятнадцать тридцать; 100 → сто

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (29.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 7 | 7 | 0.0% | передеплейт ×3; передеплать ×1; передеплейить ×1; передеплейть ×1 |
| прод | ru | 7 | 6 | 14.3% | прот ×5; прод ×1; на прод → напротив ×1 |
| дообучение | ru | 5 | 5 | 0.0% | до обучения ×5 |
| постмортем | ru | 5 | 5 | 0.0% | по смартём ×2; постмартем ×2; Мартем ×1 |
| ребейз | ru | 7 | 5 | 0.0% | Rebase ×2; ребейс ×2; лейбис ×1; ребей с ×1 |
| эстимейт | ru | 7 | 5 | 28.6% | эстимейт ×2; Тиммейт ×1; иметь ×1; истимей ×1 |
| Pydantic | mixed | 4 | 4 | 0.0% | пайдотик ×2; Pedantic ×1; педантик ×1 |
| kubectl logs | mixed | 4 | 4 | 0.0% | Quebec Logs ×1; кубек Локс ×1; кубектл локс ×1; кубканталл локс ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 6 |
| del | и | ∅ | 4 |
| sub_near | уведомление | уведомления | 3 |
| sub | a | the | 2 |
| sub | and | in | 2 |
| sub_near | finished | finish | 2 |
| sub_near | in | an | 2 |
| sub | in | and | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14.30; 15:30 → 15.30; 10:00 → 10

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (31.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| дообучение | ru | 4 | 4 | 0.0% | до обучения ×4 |
| передеплоить | ru | 4 | 4 | 0.0% | передиплать ×2; передиплеить ×1; перейти ×1 |
| фичефлаг | ru | 5 | 4 | 20.0% | фичефлаг ×1; фичехлаг ×1; фичи-флаг ×1; фичифак ×1 |
| LiteLLM | mixed | 3 | 3 | 0.0% | Light LM ×1; Light-LLM ×1; LitAloom ×1 |
| pgBouncer | mixed | 3 | 3 | 0.0% | B-Bouncer ×1; Bowser ×1; piggy bouncer ×1 |
| tsconfig.json | mixed | 3 | 3 | 0.0% | TSCanfit ×1; config. Солнц ×1; с конфиг, с он ×1 |
| бэкенда | ru | 3 | 3 | 0.0% | Бекенда ×1; бекенда ×1; бэк ×1 |
| деплой | ru | 4 | 3 | 25.0% | деплое ×1; деплой ×1; диплой ×1; теплой ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 2 |
| sub_near | правок | справок | 2 |
| sub_near | проверил | проверила | 2 |
| sub_near | дублируется | дублируются | 2 |
| sub_near | описание | описании | 2 |
| sub_near | повышение | повышения | 2 |
| sub_near | уведомление | уведомления | 2 |
| ins | ∅ | o'clock | 1 |

Number style examples: 14:30 → 14.30; 11:30 → 11.30; 12:00 → 12; 15:00 → 15.00

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (40.9% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 7 | 7 | 0.0% | передеплодить ×2; передеплывать ×2; передиплейть ×2; передеплэйд ×1 |
| прод | ru | 7 | 7 | 0.0% | прот ×3; Прот ×2; прода ×1; прод, я → Продья ×1 |
| ребейз | ru | 7 | 7 | 0.0% | ребейс ×4; лебезь ×1; ребей ×1; ребес ×1 |
| багфикс | ru | 6 | 6 | 0.0% | бакфикс ×6 |
| лейтенси | ru | 7 | 6 | 0.0% | лейтензии ×4; Latency ×1; лейтенсии ×1; лейтенция ×1 |
| эстимейт | ru | 7 | 6 | 0.0% | Steamate ×1; и стемеет ×1; и стимеет ×1; и стимейд ×1; эстимейт в → стеметь ×1 |
| дообучение | ru | 5 | 5 | 0.0% | до обучения ×4; добычение ×1 |
| мерджреквест | ru | 5 | 5 | 0.0% | кс ×1; мерджееквест ×1; мерджереквест ×1; мерджрёквес ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 7 |
| ins | ∅ | и | 3 |
| ins | ∅ | с | 3 |
| del | и | ∅ | 3 |
| sub_near | поставь | постав | 3 |
| sub_near | тесты | тесто | 3 |
| del | в | ∅ | 3 |
| sub_near | уведомление | уведомления | 3 |

Number style examples: 11:30 → 11.30; 14:30 → 14-30; 14:30 → 14.30; 10:00 → 10
