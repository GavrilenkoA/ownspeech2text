# Error mining — full CPU run, piper

Rows mined: 1888 (skipped with null hypothesis: 0). Sources: all_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 104 | 12.2% | 9.8% | 4.2% | 41.0% | 3.9% | 44.9% | sentence | full | digits | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 106 | 23.1% | 20.0% | 11.5% | 75.9% | 7.0% | 20.7% | sentence | partial | digits | mixed | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 106 | 24.3% | 21.7% | 12.6% | 80.2% | 8.2% | 17.6% | sentence | full | digits | mixed | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 210 | 31.8% | 27.8% | 16.5% | 74.4% | 18.0% | 19.2% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 104 | 21.8% | 15.8% | 10.3% | 48.6% | 9.6% | 31.2% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 210 | 23.8% | 19.9% | 11.4% | 66.0% | 10.3% | 26.2% | sentence | full | mixed | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 210 | 20.7% | 17.4% | 9.8% | 58.3% | 8.8% | 30.4% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 210 | 35.1% | 29.9% | 18.3% | 77.9% | 19.8% | 18.5% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 106 | 38.2% | 34.1% | 20.7% | 99.6% | 18.9% | 7.2% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 210 | 19.7% | 17.1% | 9.3% | 56.0% | 8.9% | 32.8% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 102 | 19.6% | 17.2% | 8.8% | 56.9% | 9.1% | 31.2% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 210 | 26.3% | 23.8% | 12.5% | 67.7% | 14.6% | 26.0% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 104 | 12.2% | 9.8% | 4.2% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 60 | 26.6% | 23.0% | 15.9% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 46 | 18.2% | 15.8% | 5.6% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 60 | 27.0% | 24.1% | 16.8% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 46 | 20.4% | 18.4% | 7.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 104 | 23.5% | 18.6% | 11.7% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 60 | 44.2% | 40.5% | 26.7% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 46 | 36.7% | 34.7% | 13.9% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 104 | 21.8% | 15.8% | 10.3% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 104 | 16.1% | 11.5% | 5.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 60 | 35.7% | 31.4% | 22.4% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 46 | 28.2% | 26.5% | 9.7% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 104 | 11.9% | 8.7% | 4.2% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 60 | 34.2% | 29.6% | 20.8% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 46 | 25.5% | 23.9% | 8.0% |
| `qwen3-asr-0.6b@onnx-int8` | en | 104 | 20.3% | 13.1% | 7.8% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 60 | 55.4% | 52.2% | 35.5% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 46 | 46.6% | 43.9% | 19.6% |
| `t-one@onnx` | mixed | 60 | 42.1% | 38.2% | 27.5% |
| `t-one@onnx` | ru | 46 | 32.6% | 28.4% | 11.9% |
| `whisper-large-v3-turbo@onnx-int8` | en | 104 | 12.6% | 10.0% | 4.4% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 60 | 28.8% | 25.5% | 18.0% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 46 | 26.0% | 24.3% | 9.0% |
| `whisper-medium@onnx-int8` | en | 52 | 11.7% | 9.2% | 4.0% |
| `whisper-medium@onnx-int8` | mixed | 28 | 29.1% | 26.7% | 15.9% |
| `whisper-medium@onnx-int8` | ru | 22 | 27.5% | 25.4% | 10.4% |
| `whisper-small@onnx-int8` | en | 104 | 13.7% | 11.2% | 5.3% |
| `whisper-small@onnx-int8` | mixed | 60 | 42.5% | 39.5% | 23.4% |
| `whisper-small@onnx-int8` | ru | 46 | 37.8% | 35.9% | 14.4% |

## WER by channel

| ASR model | channel | rows | WER | style-aware WER |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | clean | 82 | 11.4% | 8.6% |
| `distil-large-v3.5@onnx-int8` | phone_noisy | 22 | 15.5% | 14.6% |
| `gigaam-v3-e2e-ctc@onnx-int8` | clean | 72 | 22.0% | 19.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | phone_noisy | 34 | 25.7% | 22.4% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | clean | 72 | 22.8% | 19.9% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | phone_noisy | 34 | 27.5% | 25.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | clean | 154 | 29.1% | 24.5% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | phone_noisy | 56 | 39.9% | 37.5% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | clean | 82 | 21.7% | 15.1% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | phone_noisy | 22 | 22.0% | 18.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | clean | 154 | 20.8% | 16.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | phone_noisy | 56 | 33.1% | 30.1% |
| `parakeet-ultra-0.6b@onnx-int8` | clean | 154 | 18.1% | 14.4% |
| `parakeet-ultra-0.6b@onnx-int8` | phone_noisy | 56 | 28.6% | 26.4% |
| `qwen3-asr-0.6b@onnx-int8` | clean | 154 | 32.5% | 26.5% |
| `qwen3-asr-0.6b@onnx-int8` | phone_noisy | 56 | 43.0% | 40.2% |
| `t-one@onnx` | clean | 72 | 35.4% | 31.5% |
| `t-one@onnx` | phone_noisy | 34 | 44.5% | 40.0% |
| `whisper-large-v3-turbo@onnx-int8` | clean | 154 | 17.9% | 15.0% |
| `whisper-large-v3-turbo@onnx-int8` | phone_noisy | 56 | 25.1% | 23.2% |
| `whisper-medium@onnx-int8` | clean | 76 | 17.5% | 15.3% |
| `whisper-medium@onnx-int8` | phone_noisy | 26 | 26.2% | 23.3% |
| `whisper-small@onnx-int8` | clean | 154 | 23.5% | 20.9% |
| `whisper-small@onnx-int8` | phone_noisy | 56 | 34.7% | 32.6% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 99.0% | 91.3% | 83.3% | 97.1% | 93.3% | 131.2% | 100.0% | 99.9% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 99.1% | 92.9% | 91.5% | 23.6% | 87.4% | 83.3% | 41.9% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 90.0% | 100.0% | 100.0% | 83.7% | 93.3% | 37.0% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 99.5% | 97.6% | 76.9% | 50.9% | 10.0% | 78.4% | 17.6% | 98.6% | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 81.0% | 26.9% | 0.0% | 43.8% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 99.5% | 99.5% | 85.0% | 100.0% | 100.0% | 97.0% | 50.0% | 98.5% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 99.5% | 98.6% | 84.0% | 100.0% | 100.0% | 90.4% | 81.0% | 98.4% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 99.1% | 99.1% | 79.3% | 98.1% | 97.6% | 77.8% | 19.1% | 99.2% | 11.8% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 99.1% | 97.6% | 82.3% | 97.1% | 80.5% | 86.8% | 100.0% | 98.6% | 16.0% |
| `whisper-medium@onnx-int8` | 98.0% | 97.1% | 86.7% | 95.1% | 85.3% | 79.2% | 100.0% | 99.3% | 50.0% |
| `whisper-small@onnx-int8` | 99.1% | 96.7% | 81.9% | 96.7% | 80.5% | 85.0% | 100.0% | 99.3% | 10.0% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | piper | en | 104 | 12.2% | 9.8% | 44.9% | 33.2% | 99.9% |
| `gigaam-v3-e2e-ctc@onnx-int8` | piper | mixed | 60 | 26.6% | 23.0% | 11.9% | 72.2% | 41.9% |
| `gigaam-v3-e2e-ctc@onnx-int8` | piper | ru | 46 | 18.2% | 15.8% | 32.3% | 57.3% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | piper | mixed | 60 | 27.0% | 24.1% | 9.5% | 77.8% | 37.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | piper | ru | 46 | 20.4% | 18.4% | 28.1% | 62.5% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | en | 104 | 23.5% | 18.6% | 26.3% | 45.9% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | mixed | 60 | 44.2% | 40.5% | 2.4% | 86.5% | 18.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | ru | 46 | 36.7% | 34.7% | 26.0% | 70.8% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | piper | en | 104 | 21.8% | 15.8% | 31.2% | 40.5% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | en | 104 | 16.1% | 11.5% | 36.1% | 38.0% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | mixed | 60 | 35.7% | 31.4% | 7.1% | 75.4% | 37.1% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | ru | 46 | 28.2% | 26.5% | 30.2% | 65.6% | – |
| `parakeet-ultra-0.6b@onnx-int8` | piper | en | 104 | 11.9% | 8.7% | 42.9% | 33.2% | 99.9% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | mixed | 60 | 34.2% | 29.6% | 8.7% | 75.4% | 40.5% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | ru | 46 | 25.5% | 23.9% | 32.3% | 59.4% | – |
| `qwen3-asr-0.6b@onnx-int8` | piper | en | 104 | 20.3% | 13.1% | 31.2% | 39.0% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | piper | mixed | 60 | 55.4% | 52.2% | 0.8% | 88.1% | 29.4% |
| `qwen3-asr-0.6b@onnx-int8` | piper | ru | 46 | 46.6% | 43.9% | 14.6% | 83.3% | – |
| `t-one@onnx` | piper | mixed | 60 | 42.1% | 38.2% | 0.8% | 85.7% | 0.0% |
| `t-one@onnx` | piper | ru | 46 | 32.6% | 28.4% | 15.6% | 71.9% | – |
| `whisper-large-v3-turbo@onnx-int8` | piper | en | 104 | 12.6% | 10.0% | 44.4% | 33.7% | 99.9% |
| `whisper-large-v3-turbo@onnx-int8` | piper | mixed | 60 | 28.8% | 25.5% | 18.2% | 63.5% | 64.1% |
| `whisper-large-v3-turbo@onnx-int8` | piper | ru | 46 | 26.0% | 24.3% | 27.1% | 65.6% | – |
| `whisper-medium@onnx-int8` | piper | en | 52 | 11.7% | 9.2% | 46.5% | 33.7% | 99.9% |
| `whisper-medium@onnx-int8` | piper | mixed | 28 | 29.1% | 26.7% | 14.3% | 66.7% | 82.6% |
| `whisper-medium@onnx-int8` | piper | ru | 22 | 27.5% | 25.4% | 20.4% | 70.5% | – |
| `whisper-small@onnx-int8` | piper | en | 104 | 13.7% | 11.2% | 40.0% | 36.6% | 99.9% |
| `whisper-small@onnx-int8` | piper | mixed | 60 | 42.5% | 39.5% | 10.3% | 78.6% | 66.7% |
| `whisper-small@onnx-int8` | piper | ru | 46 | 37.8% | 35.9% | 16.7% | 76.0% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| C# | en | 8 | 24 | 21 | 87.5% | C ×10; C-chart ×2; Sharp ×2; C-Tarped ×1 |
| Pydantic | mixed | 10 | 20 | 20 | 100.0% | Пайдотик ×2; падотик ×2; пайдотик ×2; пойдатик ×2 |
| фичефлаг | ru | 10 | 20 | 20 | 100.0% | хичи флаг ×3; фичифак ×2; Kitch Flack ×1; Kiчифlг ×1 |
| Slack | mixed | 10 | 19 | 19 | 100.0% | слаг ×3; в Slack → вслак ×2; к ×2; ∅ ×1 |
| мерджреквест | ru | 10 | 19 | 19 | 100.0% | мержеквест ×4; межреквест ×3; GQS ×1; есть ×1 |
| ребейз | ru | 10 | 19 | 19 | 100.0% | ребис ×3; лейбис ×2; ребиз ×2; ремиз ×2 |
| эстимейт | ru | 10 | 19 | 19 | 100.0% | иметь ×3; стимеи ×3; Истемет ×1; автомобиль ×1 |
| лора-адаптер | ru | 9 | 18 | 18 | 100.0% | лор-адаптер ×3; лодаптер ×2; лор адаптер ×2; лорадаптер ×2 |
| API | en | 9 | 18 | 17 | 94.4% | AP ×7; APLI ×5; APE ×2; A P L I ×1 |
| docker compose up | mixed | 9 | 18 | 17 | 94.4% | докер компулсап ×2; дотер компусап ×2; Dker composUp ×1; Doter compos p ×1 |
| Terraform | mixed | 10 | 19 | 17 | 89.5% | терратором ×3; терафа ×2; терафор ×2; Терафор ×1 |
| LiteLLM | mixed | 10 | 19 | 16 | 84.2% | Light LLM ×4; L ×3; LL ×1; Light-LLM ×1 |
| Exynos | en | 9 | 17 | 15 | 88.2% | Xeno's ×2; Xinos ×2; Ixanos ×1; X and Os ×1 |
| AMD | en | 8 | 16 | 14 | 87.5% | MD ×10; Andy ×3; M D ×1 |
| IAM | en | 8 | 16 | 14 | 87.5% | AM ×8; IM ×6 |
| обучающая выборка | ru | 10 | 19 | 14 | 73.7% | обучающая выбека ×2; веба ×1; ли обучающая выборка → вебучающая ×1; ли обучающая выборка → леобучающая выборка ×1 |
| линтер | ru | 9 | 18 | 13 | 72.2% | лентер ×2; лидер ×2; линтер, поведение → терповедения ×2; Лентя ×1 |
| тикет | ru | 10 | 19 | 13 | 68.4% | кет ×5; Закрываю тикет → закрывайтикето ×1; кета ×1; китайские ×1 |
| ARM64 | mixed | 10 | 20 | 13 | 65.0% | шестьдесят ×2; AM64 ×1; AMU-64 ×1; M-64 ×1 |
| Envoy | en | 9 | 17 | 11 | 64.7% | Envy ×8; envoid ×1; on-boarding ×1; void ×1 |
| задеплоить | ru | 9 | 18 | 11 | 61.1% | задеплойить ×3; за депло еть ×1; за диплоид ×1; за диплоить ×1 |
| Озон | ru | 10 | 29 | 11 | 37.9% | ∅ ×1; OZ ×1; АЗОЛ ×1; О ×1 |
| CoreDNS | mixed | 10 | 10 | 10 | 100.0% | код DNS ×3; ДНС ×1; ко ДНС ×1; коd DNS ×1 |
| Crashlytics | mixed | 10 | 10 | 10 | 100.0% | кашлите к ×2; Crashlytics отчёты → кашлитексачеты ×1; Кашлитиксе ×1; вCшlt ×1 |
| GGUF | mixed | 10 | 10 | 10 | 100.0% | GU ×2; ∅ ×1; DFF/USB ×1; GFGF ×1 |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (33.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | en | 2 | 2 | 0.0% | APE ×1; APLI ×1 |
| Exynos | en | 2 | 2 | 0.0% | Xenose ×1; xeno's ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| git rebase | en | 2 | 2 | 0.0% | get rebase ×1; give your base ×1 |
| C# | en | 3 | 2 | 0.0% | C-chart ×1; C-sharp ×1; sharp ×1 |
| ./gradlew assembleRelease | en | 1 | 1 | 0.0% | gradlu assemble release ×1 |
| AAB | en | 1 | 1 | 0.0% | abbey ×1 |
| ASR | en | 1 | 1 | 0.0% | ASAR ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | slash | 3 |
| sub | in | and | 2 |
| ins | ∅ | dot | 1 |
| ins | ∅ | hot | 1 |
| ins | ∅ | ssh | 1 |
| ins | ∅ | the | 1 |
| ins | ∅ | tilde | 1 |
| ins | ∅ | to | 1 |

Number style examples: 3:30 → 330

## `gigaam-v3-e2e-ctc@onnx-int8`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 87.4% of reference)
- turn Cyrillic transliterations back into Latin terms (58.1% of Latin words)
- repair garbled terms (65.8% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| ARM64 | mixed | 2 | 2 | 0.0% | M-64 ×1; RM64 ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | Pydotik ×1; падотик ×1 |
| Slack | mixed | 2 | 2 | 0.0% | стаб ×1; в Slack → вслак ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | Dker composUp ×1; Doter compos p ×1 |
| лора-адаптер | ru | 2 | 2 | 0.0% | лор-адаптер ×1; лорадаптер ×1 |
| мерджреквест | ru | 2 | 2 | 0.0% | межреквест ×1; мержеквест ×1 |
| ребейз | ru | 2 | 2 | 0.0% | ребиз ×1; ремиз ×1 |
| фичефлаг | ru | 2 | 2 | 0.0% | Kiчифlг ×1; фичифак ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 3 |
| sub_near | новые | новый | 2 |
| ins | ∅ | всей | 1 |
| ins | ∅ | лист | 1 |
| ins | ∅ | п | 1 |
| ins | ∅ | поер | 1 |
| ins | ∅ | с | 1 |
| sub_near | 15:00 | 15:0чал | 1 |

Number style examples: 10:00 → 10:0; 12:00 → 12; 3 → три; 3000 → трёх тысячах

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- turn Cyrillic transliterations back into Latin terms (63.0% of Latin words)
- repair garbled terms (71.2% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| LiteLLM | mixed | 2 | 2 | 0.0% | Light LLM ×1; лайтл ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | Pitotic ×1; Potik ×1 |
| Slack | mixed | 2 | 2 | 0.0% | штаб ×1; в Slack → флак ×1 |
| Terraform | mixed | 2 | 2 | 0.0% | Террафра ×1; тератором ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | Doterpos app ×1; докер Compolsap ×1 |
| лора-адаптер | ru | 2 | 2 | 0.0% | лор-адаптер ×1; лороадаптер ×1 |
| мерджреквест | ru | 2 | 2 | 0.0% | межреквест ×1; мержеквест ×1 |
| ребейз | ru | 2 | 2 | 0.0% | ребис ×1; ремиз ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 2 |
| ins | ∅ | pij | 1 |
| ins | ∅ | весь | 1 |
| ins | ∅ | всей | 1 |
| ins | ∅ | двух | 1 |
| ins | ∅ | и | 1 |
| ins | ∅ | начал | 1 |
| ins | ∅ | по | 1 |

Number style examples: 12:00 → 12; 15:00 → 15; 3 → три; 3000 → 3 000

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 76.9%)
- restore missing punctuation (commas at 78.4% of reference)
- convert spoken numbers, times and dates to digits (82.3% spelled out)
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (63.5% of terms are recognition errors) — from context only
- tolerate dropped words (3.7% of non-term words) without inventing content

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | Sharp ×1; Zar ×1; because ×1 |
| AMD | en | 2 | 2 | 0.0% | MD ×2 |
| API | en | 2 | 2 | 0.0% | AP ×1; APL ×1 |
| ARM64 | mixed | 2 | 2 | 0.0% | РМ шестьдесят четыре ×1; маме 64 ×1 |
| Envoy | en | 2 | 2 | 0.0% | Envy ×1; envoid ×1 |
| Exynos | en | 2 | 2 | 0.0% | X and nos ×1; XNO's ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| LiteLLM | mixed | 2 | 2 | 0.0% | LightL ×1; Лайте ЛЛМ ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | could | ∅ | 10 |
| del | the | ∅ | 10 |
| del | you | ∅ | 8 |
| del | can | ∅ | 5 |
| del | add | ∅ | 3 |
| sub | in | and | 3 |
| del | run | ∅ | 3 |
| sub | the | a | 3 |

Number style examples: 10:00 → десяти; 10:30 → 10-30; 11:30 → 1130; 11:30 → одиннадцать-тридцать

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 43.8% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (40.5% of terms are recognition errors) — from context only
- tolerate dropped words (4.9% of non-term words) without inventing content

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×2; Sharp ×1 |
| AMD | en | 2 | 2 | 0.0% | MD ×2 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLI ×1 |
| Exynos | en | 2 | 2 | 0.0% | X and Os ×1; XNOS ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| MLX | en | 2 | 2 | 0.0% | a Mlex ×1; him elects ×1 |
| ./gradlew assembleRelease | en | 1 | 1 | 0.0% | gradually assemble release ×1 |
| AAB | en | 1 | 1 | 0.0% | Abbey ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | could | ∅ | 9 |
| del | the | ∅ | 8 |
| del | you | ∅ | 8 |
| del | can | ∅ | 5 |
| sub | in | and | 3 |
| del | run | ∅ | 3 |
| sub | the | a | 3 |
| ins | ∅ | plus | 2 |

Number style examples: 30 → thirty; 3:30 → three thirty; 4 → Four; 5.0 → five

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (50.0% spelled out)
- spell out again numbers the writer wanted in words (50.0% of spelled-out numbers come back as digits)
- repair garbled terms (55.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×3 |
| AMD | en | 2 | 2 | 0.0% | Andy ×1; MD ×1 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLI ×1 |
| Exynos | en | 2 | 2 | 0.0% | Xinos ×1; the Exynos → Ixanos ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| LiteLLM | mixed | 2 | 2 | 0.0% | L ×1; Light LLM ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | Пайдотик ×1; податик ×1 |
| Slack | mixed | 2 | 2 | 0.0% | в Slack → вставку ×1; в Slack → слаг ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | sharp | 3 |
| ins | ∅ | d | 2 |
| ins | ∅ | the | 2 |
| ins | ∅ | to | 2 |
| sub | a | the | 2 |
| sub | in | and | 2 |
| sub | through | to | 2 |
| sub | write | right | 2 |

Number style examples: 11:30 → одиннадцать тридцать; 10:00 → десяти; 10:30 → 10 тридцать; 12:00 → двенадцати

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (51.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×3 |
| AMD | en | 2 | 2 | 0.0% | MD ×2 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLai ×1 |
| ARM64 | mixed | 2 | 2 | 0.0% | R64 ×1; чем ARM64 → мам64 ×1 |
| Exynos | en | 2 | 2 | 0.0% | Ixanos ×1; Xinos ×1 |
| LiteLLM | mixed | 2 | 2 | 0.0% | L ×1; Light LLM ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | Pedotik ×1; Пайдотик ×1 |
| Slack | mixed | 2 | 2 | 0.0% | слаб ×1; в Slack → вслак ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | sharp | 3 |
| ins | ∅ | to | 2 |
| sub | in | and | 2 |
| sub | through | to | 2 |
| del | в | ∅ | 2 |
| del | с | ∅ | 2 |
| del | в | ∅ | 2 |
| ins | ∅ | d | 1 |

Number style examples: 10:00 → десяти; 10:30 → 10 тридцать; 11:30 → 11.30; 11:30 → одиннадцать тридцать

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 79.3%)
- convert spoken numbers, times and dates to digits (81.0% spelled out)
- repair garbled terms (63.5% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×2; chart ×1 |
| API | en | 2 | 2 | 0.0% | A P L I ×1; AP ×1 |
| Envoy | en | 2 | 2 | 0.0% | Envy ×1; on-boarding ×1 |
| Exynos | en | 2 | 2 | 0.0% | Xeno ×1; Xnol's ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| LiteLLM | mixed | 2 | 2 | 0.0% | Light LLM ×1; лайт-елф-элфа ×1 |
| MLX | en | 2 | 2 | 0.0% | Hellex ×1; Mac ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | падотик ×1; пататик ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | a | 2 |
| ins | ∅ | plus | 2 |
| ins | ∅ | s | 2 |
| ins | ∅ | sharp | 2 |
| ins | ∅ | slash | 2 |
| sub | a | the | 2 |
| ins | ∅ | в | 2 |
| sub_near | объясни | объяснить | 2 |

Number style examples: 11:30 → одиннадцать тридцать; 100 → сто; 10:00 → десяти; 10:30 → 10-30

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)
- repair garbled terms (79.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| ARM64 | mixed | 2 | 2 | 0.0% | вам шестьдесят четыре ×1; шестьдесят ×1 |
| LiteLLM | mixed | 2 | 2 | 0.0% | лайт ×1; подключаю LiteLLM → подключаем ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | патотип ×1; на Pydantic → нападатик ×1 |
| Slack | mixed | 2 | 2 | 0.0% | свак ×1; спас ×1 |
| Terraform | mixed | 2 | 2 | 0.0% | терафор ×1; терратором ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | докер капуусаб ×1; дотер компусап ×1 |
| задеплоить | ru | 2 | 2 | 0.0% | за депло еть ×1; за диплоить ×1 |
| зарелизить | ru | 2 | 2 | 0.0% | даревидеть ×1; заревидеть ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 3 |
| ins | ∅ | в | 2 |
| ins | ∅ | четыре | 2 |
| sub_near | вчера | тера | 2 |
| sub_near | новые | новый | 2 |
| sub_near | выгрузку | выпуску | 2 |
| del | с | ∅ | 2 |
| ins | ∅ | езь | 1 |

Number style examples: 11:30 → одиннадцать тридцать; 10:00 → десяти; 10:30 → десять тридцать; 12:00 → двенадцати

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (49.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| AMD | en | 2 | 2 | 0.0% | Andy ×1; MD ×1 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLI ×1 |
| Envoy | en | 2 | 2 | 0.0% | Envy ×1; void ×1 |
| Exynos | en | 2 | 2 | 0.0% | Xeno's ×1; Xenos ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | пайдотик ×2 |
| Slack | mixed | 2 | 2 | 0.0% | ∅ ×1; слаг ×1 |
| Terraform | mixed | 2 | 2 | 0.0% | Терафоров ×1; тиррафером ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 3 |
| sub | a | the | 2 |
| sub | in | and | 2 |
| sub | к | в | 2 |
| ins | ∅ | on | 1 |
| ins | ∅ | opening | 1 |
| ins | ∅ | tilde | 1 |
| ins | ∅ | to | 1 |

Number style examples: 11:30 → 11.30; 10:00 → 10; 10:30 → 10-30; 12:00 → 12

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (51.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | en | 2 | 2 | 0.0% | APE ×1; APLI ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | PadaTik ×1; Python.tick ×1 |
| фичефлаг | ru | 2 | 2 | 0.0% | фичифак ×1; хитчет флаг ×1 |
| ./gradlew assembleRelease | en | 1 | 1 | 0.0% | gradlu assemble release ×1 |
| AAB | en | 1 | 1 | 0.0% | abbey ×1 |
| AWS | en | 1 | 1 | 0.0% | iOS ×1 |
| Adreno | mixed | 1 | 1 | 0.0% | с Adreno → сатарена ×1 |
| Axolotl | en | 1 | 1 | 0.0% | AxelOwl ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | shen | 1 |
| sub_near | 99.9% | 90 9.9% | 1 |
| sub | a | led | 1 |
| del | add | ∅ | 1 |
| sub_near | finished | finish | 1 |
| sub_near | hi | i | 1 |
| sub | in | and | 1 |
| sub | our | r-cell | 1 |

Number style examples: 10:30 → 10-30; 3:30 → 330

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (57.9% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| AMD | en | 2 | 2 | 0.0% | Andy ×1; MD ×1 |
| Neo4j | en | 2 | 2 | 0.0% | Neo for ×1; the O4J ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | PIDOTIC ×1; пайдо тик ×1 |
| Slack | mixed | 2 | 2 | 0.0% | к ×1; слаг ×1 |
| Terraform | mixed | 2 | 2 | 0.0% | Терафор ×1; с Terraform → стиравром ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | докер КПУ САП ×1; дотер компус-ап ×1 |
| задеплоить | ru | 2 | 2 | 0.0% | задеплойить ×1; задеплываете ×1 |
| лора-адаптер | ru | 2 | 2 | 0.0% | ∅ ×1; лородатка ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 4 |
| sub_near | поставь | постав | 3 |
| sub_near | rerun | run | 2 |
| ins | ∅ | а | 2 |
| ins | ∅ | с | 2 |
| sub_near | записях | записи | 2 |
| sub_near | новые | новой | 2 |
| del | в | ∅ | 2 |

Number style examples: 11:30 → 11.30; 10:00 → 10; 10:30 → 10 30; 12:00 → 12
