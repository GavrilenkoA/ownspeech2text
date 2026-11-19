# Error mining — full CPU run, piper, clean

Rows mined: 1380 (skipped with null hypothesis: 0). Sources: all_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 82 | 11.4% | 8.6% | 3.7% | 37.1% | 3.1% | 44.8% | sentence | full | digits | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 72 | 22.0% | 19.0% | 10.0% | 76.1% | 5.5% | 24.8% | sentence | partial | digits | mixed | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 72 | 22.8% | 19.9% | 11.1% | 76.6% | 6.5% | 22.2% | sentence | full | digits | mixed | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 154 | 29.1% | 24.5% | 14.9% | 70.2% | 14.9% | 20.6% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 82 | 21.7% | 15.1% | 10.5% | 46.5% | 9.1% | 30.7% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 154 | 20.8% | 16.5% | 9.5% | 60.6% | 7.2% | 29.1% | sentence | full | mixed | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 154 | 18.1% | 14.4% | 8.0% | 53.6% | 6.1% | 33.5% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 154 | 32.5% | 26.5% | 16.5% | 74.6% | 16.3% | 19.3% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 72 | 35.4% | 31.5% | 18.3% | 97.3% | 16.0% | 7.2% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 154 | 17.9% | 15.0% | 7.9% | 51.8% | 7.3% | 35.4% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 76 | 17.5% | 15.3% | 6.9% | 54.5% | 7.4% | 34.2% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 154 | 23.5% | 20.9% | 10.8% | 63.5% | 11.9% | 28.8% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 82 | 11.4% | 8.6% | 3.7% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 41 | 24.2% | 20.6% | 13.7% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 31 | 18.6% | 16.6% | 4.9% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 41 | 24.2% | 20.8% | 14.6% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 31 | 20.7% | 18.6% | 6.4% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 82 | 22.7% | 17.3% | 11.2% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 41 | 41.0% | 37.0% | 24.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 31 | 31.9% | 29.3% | 11.1% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 82 | 21.7% | 15.1% | 10.5% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 82 | 14.3% | 9.5% | 4.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 41 | 31.3% | 26.8% | 19.7% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 31 | 25.9% | 23.8% | 8.1% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 82 | 10.9% | 7.4% | 3.4% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 41 | 30.3% | 25.3% | 18.0% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 31 | 23.3% | 21.0% | 6.5% |
| `qwen3-asr-0.6b@onnx-int8` | en | 82 | 19.5% | 11.8% | 7.6% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 41 | 51.6% | 47.6% | 32.6% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 31 | 45.9% | 42.2% | 18.3% |
| `t-one@onnx` | mixed | 41 | 37.7% | 34.1% | 23.9% |
| `t-one@onnx` | ru | 31 | 31.9% | 27.7% | 10.5% |
| `whisper-large-v3-turbo@onnx-int8` | en | 82 | 12.0% | 9.1% | 4.0% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 41 | 25.9% | 22.5% | 15.4% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 31 | 25.1% | 23.1% | 8.1% |
| `whisper-medium@onnx-int8` | en | 41 | 11.0% | 8.5% | 3.8% |
| `whisper-medium@onnx-int8` | mixed | 20 | 23.7% | 21.2% | 11.3% |
| `whisper-medium@onnx-int8` | ru | 15 | 28.7% | 27.7% | 9.0% |
| `whisper-small@onnx-int8` | en | 82 | 12.6% | 9.9% | 4.7% |
| `whisper-small@onnx-int8` | mixed | 41 | 37.4% | 34.3% | 20.3% |
| `whisper-small@onnx-int8` | ru | 31 | 38.3% | 36.5% | 14.1% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 98.8% | 91.5% | 82.8% | 97.6% | 92.7% | 153.8% | 100.0% | 99.8% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 98.6% | 95.5% | 93.1% | 19.4% | 89.0% | 85.7% | 47.6% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 94.4% | 100.0% | 100.0% | 84.6% | 90.0% | 39.1% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 99.4% | 96.8% | 74.0% | 48.0% | 9.1% | 78.6% | 0.0% | 98.8% | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 80.2% | 25.6% | 0.0% | 50.0% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 99.4% | 99.4% | 85.6% | 100.0% | 100.0% | 100.0% | 43.8% | 98.6% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 99.4% | 98.0% | 84.6% | 100.0% | 100.0% | 96.6% | 87.5% | 98.4% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 98.7% | 98.7% | 78.2% | 97.4% | 97.4% | 75.2% | 12.5% | 99.2% | 7.7% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 98.7% | 97.4% | 81.4% | 98.0% | 83.1% | 90.6% | 100.0% | 98.8% | 10.0% |
| `whisper-medium@onnx-int8` | 98.7% | 98.7% | 83.6% | 94.7% | 90.8% | 86.3% | 100.0% | 99.5% | 55.6% |
| `whisper-small@onnx-int8` | 99.4% | 98.7% | 80.5% | 98.0% | 83.1% | 84.6% | 100.0% | 99.5% | 6.2% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | piper | en | 82 | 11.4% | 8.6% | 44.8% | 31.9% | 99.8% |
| `gigaam-v3-e2e-ctc@onnx-int8` | piper | mixed | 41 | 24.2% | 20.6% | 15.1% | 69.8% | 47.6% |
| `gigaam-v3-e2e-ctc@onnx-int8` | piper | ru | 31 | 18.6% | 16.6% | 37.3% | 55.2% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | piper | mixed | 41 | 24.2% | 20.8% | 14.0% | 70.9% | 39.1% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | piper | ru | 31 | 20.7% | 18.6% | 32.8% | 58.2% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | en | 82 | 22.7% | 17.3% | 25.8% | 46.0% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | mixed | 41 | 41.0% | 37.0% | 3.5% | 83.7% | 23.5% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | piper | ru | 31 | 31.9% | 29.3% | 29.8% | 65.7% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | piper | en | 82 | 21.7% | 15.1% | 30.7% | 39.9% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | en | 82 | 14.3% | 9.5% | 37.4% | 36.8% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | mixed | 41 | 31.3% | 26.8% | 10.5% | 70.9% | 39.3% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | piper | ru | 31 | 25.9% | 23.8% | 32.8% | 64.2% | – |
| `parakeet-ultra-0.6b@onnx-int8` | piper | en | 82 | 10.9% | 7.4% | 44.8% | 31.9% | 99.9% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | mixed | 41 | 30.3% | 25.3% | 11.6% | 72.1% | 40.0% |
| `parakeet-ultra-0.6b@onnx-int8` | piper | ru | 31 | 23.3% | 21.0% | 34.3% | 55.2% | – |
| `qwen3-asr-0.6b@onnx-int8` | piper | en | 82 | 19.5% | 11.8% | 31.3% | 37.4% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | piper | mixed | 41 | 51.6% | 47.6% | 0.0% | 86.1% | 30.8% |
| `qwen3-asr-0.6b@onnx-int8` | piper | ru | 31 | 45.9% | 42.2% | 14.9% | 82.1% | – |
| `t-one@onnx` | piper | mixed | 41 | 37.7% | 34.1% | 1.2% | 84.9% | 0.0% |
| `t-one@onnx` | piper | ru | 31 | 31.9% | 27.7% | 14.9% | 71.6% | – |
| `whisper-large-v3-turbo@onnx-int8` | piper | en | 82 | 12.0% | 9.1% | 45.4% | 32.5% | 99.8% |
| `whisper-large-v3-turbo@onnx-int8` | piper | mixed | 41 | 25.9% | 22.5% | 22.1% | 60.5% | 70.0% |
| `whisper-large-v3-turbo@onnx-int8` | piper | ru | 31 | 25.1% | 23.1% | 28.4% | 65.7% | – |
| `whisper-medium@onnx-int8` | piper | en | 41 | 11.0% | 8.5% | 44.2% | 35.1% | 99.8% |
| `whisper-medium@onnx-int8` | piper | mixed | 20 | 23.7% | 21.2% | 20.4% | 63.6% | 88.9% |
| `whisper-medium@onnx-int8` | piper | ru | 15 | 28.7% | 27.7% | 29.0% | 71.0% | – |
| `whisper-small@onnx-int8` | piper | en | 82 | 12.6% | 9.9% | 41.1% | 35.0% | 99.9% |
| `whisper-small@onnx-int8` | piper | mixed | 41 | 37.4% | 34.3% | 12.8% | 75.6% | 77.3% |
| `whisper-small@onnx-int8` | piper | ru | 31 | 38.3% | 36.5% | 19.4% | 74.6% | – |

## Hardest terms across models

Ranked by recognition errors summed over every model (then by error rate).

| term | context | models | n | errors | error rate | most frequent wrong renderings |
|---|---|---:|---:|---:|---:|---|
| C# | en | 8 | 24 | 21 | 87.5% | C ×10; C-chart ×2; Sharp ×2; C-Tarped ×1 |
| Pydantic | mixed | 10 | 20 | 20 | 100.0% | Пайдотик ×2; падотик ×2; пайдотик ×2; пойдатик ×2 |
| мерджреквест | ru | 10 | 19 | 19 | 100.0% | мержеквест ×4; межреквест ×3; GQS ×1; есть ×1 |
| эстимейт | ru | 10 | 19 | 19 | 100.0% | иметь ×3; стимеи ×3; Истемет ×1; автомобиль ×1 |
| лора-адаптер | ru | 9 | 18 | 18 | 100.0% | лор-адаптер ×3; лодаптер ×2; лор адаптер ×2; лорадаптер ×2 |
| API | en | 9 | 18 | 17 | 94.4% | AP ×7; APLI ×5; APE ×2; A P L I ×1 |
| docker compose up | mixed | 9 | 18 | 17 | 94.4% | докер компулсап ×2; дотер компусап ×2; Dker composUp ×1; Doter compos p ×1 |
| Exynos | en | 9 | 17 | 15 | 88.2% | Xeno's ×2; Xinos ×2; Ixanos ×1; X and Os ×1 |
| AMD | en | 8 | 16 | 14 | 87.5% | MD ×10; Andy ×3; M D ×1 |
| IAM | en | 8 | 16 | 14 | 87.5% | AM ×8; IM ×6 |
| тикет | ru | 10 | 19 | 13 | 68.4% | кет ×5; Закрываю тикет → закрывайтикето ×1; кета ×1; китайские ×1 |
| Envoy | en | 9 | 17 | 11 | 64.7% | Envy ×8; envoid ×1; on-boarding ×1; void ×1 |
| Crashlytics | mixed | 10 | 10 | 10 | 100.0% | кашлите к ×2; Crashlytics отчёты → кашлитексачеты ×1; Кашлитиксе ×1; вCшlt ×1 |
| NVMe | mixed | 10 | 10 | 10 | 100.0% | NVM ×6; N V M ×1; NVMB ×1; нвм ×1 |
| OOM | mixed | 10 | 10 | 10 | 100.0% | ∅ ×2; ошибку OOM в → ошибком ×2; ошибку OOM → ошибком ×2; влюбиться ×1 |
| PoC | mixed | 10 | 10 | 10 | 100.0% | пилу ×2; Пилси ×1; Пиоси ×1; пиво ×1 |
| README | mixed | 10 | 10 | 10 | 100.0% | ритми ×4; ритм ×3; ритме ×2; ритмы ×1 |
| Rancher | mixed | 10 | 10 | 10 | 100.0% | Rancher вручную → ранчеровочную ×2; раньше ×2; Rancher вручную и → ранчеровочную ×1; Rancher вручную → ранчевоочную ×1 |
| Rust | mixed | 10 | 10 | 10 | 100.0% | RAS ×2; раз ×2; R ×1; RES ×1 |
| Sentry | mixed | 10 | 10 | 10 | 100.0% | В центре ×3; центре ×2; Centry ×1; Tentri ×1 |
| Silero | mixed | 10 | 10 | 10 | 100.0% | Лерой ×2; с ×2; Silero и → лерой ×1; Silero и → селе ×1 |
| Svelte | mixed | 10 | 10 | 10 | 100.0% | свет ×5; Svelte, проверка → сверт-проверка ×1; Svelte, проверка → сверхпроверка ×1; на Svelte → насведт ×1 |
| git cherry-pick | mixed | 10 | 10 | 10 | 100.0% | гид черепит ×3; гитчерепит ×2; Git-черепит ×1; Через git cherry-pick → Челюсть GIT-Cherry-Pit ×1 |
| nginx.conf | mixed | 10 | 10 | 10 | 100.0% | Angen's Comp ×1; EngineSConf, идти ×1; EnginesConf, идти ×1; Ranging's ×1 |
| tsconfig.json | mixed | 10 | 10 | 10 | 100.0% | конфит ×3; TSCanFit ×1; TSCanfit ×1; Tasconfit ×1 |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (31.9% of terms are recognition errors) — from context only

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
| ins | ∅ | dot | 1 |
| ins | ∅ | slash | 1 |
| ins | ∅ | the | 1 |
| ins | ∅ | to | 1 |
| ins | ∅ | z | 1 |
| sub_near | 99.9% | 90 9.9% | 1 |
| sub | a | it | 1 |
| sub | a | this | 1 |

Number style examples: 3:30 → 330

## `gigaam-v3-e2e-ctc@onnx-int8`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 89.0% of reference)
- turn Cyrillic transliterations back into Latin terms (52.4% of Latin words)
- repair garbled terms (63.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Pydantic | mixed | 2 | 2 | 0.0% | Pydotik ×1; падотик ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | Dker composUp ×1; Doter compos p ×1 |
| лора-адаптер | ru | 2 | 2 | 0.0% | лор-адаптер ×1; лорадаптер ×1 |
| мерджреквест | ru | 2 | 2 | 0.0% | межреквест ×1; мержеквест ×1 |
| эстимейт | ru | 2 | 2 | 0.0% | истемейд ×1; эстимейт в → темете ×1 |
| 1С | ru | 1 | 1 | 0.0% | DS ×1 |
| ARM64 | mixed | 1 | 1 | 0.0% | RM64 ×1 |
| Adreno | mixed | 1 | 1 | 0.0% | с Adreno → сатрена ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 2 |
| ins | ∅ | с | 1 |
| sub_near | 15:00 | 15:0чал | 1 |
| sub_near | встречу | встречи | 1 |
| sub_near | до | да | 1 |
| sub_near | записях | записи | 1 |
| sub | и | conf | 1 |
| sub_near | исправление | исправления | 1 |

Number style examples: 10:00 → 10:0; 12:00 → 12; 3 → три; 5000 → 5 000

## `gigaam-v3-e2e-rnnt@onnx-int8`

What the cleanup LLM must learn to undo:
- turn Cyrillic transliterations back into Latin terms (60.9% of Latin words)
- repair garbled terms (65.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Pydantic | mixed | 2 | 2 | 0.0% | Pitotic ×1; Potik ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | Doterpos app ×1; докер Compolsap ×1 |
| лора-адаптер | ru | 2 | 2 | 0.0% | лор-адаптер ×1; лороадаптер ×1 |
| мерджреквест | ru | 2 | 2 | 0.0% | межреквест ×1; мержеквест ×1 |
| эстимейт | ru | 2 | 2 | 0.0% | Истемет ×1; имейте ×1 |
| Adreno | mixed | 1 | 1 | 0.0% | Aderena ×1 |
| CHANGELOG | mixed | 1 | 1 | 0.0% | Чэнджлок ×1 |
| Celery | mixed | 1 | 1 | 0.0% | сервере ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | pij | 1 |
| ins | ∅ | начал | 1 |
| ins | ∅ | с | 1 |
| del | в | ∅ | 1 |
| sub | в | queenb | 1 |
| sub | в | victoria | 1 |
| sub | в | с | 1 |
| sub_near | встречу | встречи | 1 |

Number style examples: 12:00 → 12; 15:00 → 15; 3 → три; 5000 → 5 000

## `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 74.0%)
- restore missing punctuation (commas at 78.6% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (60.4% of terms are recognition errors) — from context only
- tolerate dropped words (3.5% of non-term words) without inventing content

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | Sharp ×1; Zar ×1; because ×1 |
| AMD | en | 2 | 2 | 0.0% | MD ×2 |
| API | en | 2 | 2 | 0.0% | AP ×1; APL ×1 |
| Envoy | en | 2 | 2 | 0.0% | Envy ×1; envoid ×1 |
| Exynos | en | 2 | 2 | 0.0% | X and nos ×1; XNO's ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | пойдатик ×2 |
| docker compose up | mixed | 2 | 2 | 0.0% | докирка по усап ×1; дотер компусап ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | the | ∅ | 9 |
| del | could | ∅ | 6 |
| del | you | ∅ | 5 |
| del | can | ∅ | 4 |
| del | run | ∅ | 3 |
| del | в | ∅ | 3 |
| ins | ∅ | plus | 2 |
| del | add | ∅ | 2 |

Number style examples: 10:00 → десяти; 11:30 → одиннадцать-тридцать; 12:00 → двенадцати; 15:00 → пятнадцать

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 50.0% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (39.9% of terms are recognition errors) — from context only
- tolerate dropped words (5.1% of non-term words) without inventing content

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×2; Sharp ×1 |
| AMD | en | 2 | 2 | 0.0% | MD ×2 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLI ×1 |
| Exynos | en | 2 | 2 | 0.0% | X and Os ×1; XNOS ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| ./gradlew assembleRelease | en | 1 | 1 | 0.0% | gradually assemble release ×1 |
| AAB | en | 1 | 1 | 0.0% | Abbey ×1 |
| ASR | en | 1 | 1 | 0.0% | S our ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | the | ∅ | 7 |
| del | could | ∅ | 6 |
| del | you | ∅ | 6 |
| del | can | ∅ | 4 |
| del | run | ∅ | 3 |
| ins | ∅ | plus | 2 |
| ins | ∅ | sharp | 2 |
| del | draft | ∅ | 2 |

Number style examples: 30 → thirty; 3:30 → three thirty; 4 → Four; 5.0 → five

## `parakeet-tdt-0.6b-v3@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (56.2% spelled out)
- spell out again numbers the writer wanted in words (50.0% of spelled-out numbers come back as digits)
- repair garbled terms (51.9% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×3 |
| AMD | en | 2 | 2 | 0.0% | Andy ×1; MD ×1 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLI ×1 |
| Exynos | en | 2 | 2 | 0.0% | Xinos ×1; the Exynos → Ixanos ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | Пайдотик ×1; податик ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | дойтер компусап ×1; докер компулсап ×1 |
| лора-адаптер | ru | 2 | 2 | 0.0% | лодаптер ×1; лор-адаптер ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | sharp | 3 |
| ins | ∅ | d | 2 |
| ins | ∅ | to | 2 |
| sub | through | to | 2 |
| ins | ∅ | dot | 1 |
| sub_near | 99.9% | 90 9.9% | 1 |
| sub | a | the | 1 |
| sub_near | before | for | 1 |

Number style examples: 10:00 → десяти; 11:30 → одиннадцать тридцать; 12:00 → двенадцати; 14:30 → четырнадцать тридцать

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (47.8% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×3 |
| AMD | en | 2 | 2 | 0.0% | MD ×2 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLai ×1 |
| Exynos | en | 2 | 2 | 0.0% | Ixanos ×1; Xinos ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | Pedotik ×1; Пайдотик ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | докер компулсап ×1; докер компусап ×1 |
| лора-адаптер | ru | 2 | 2 | 0.0% | лор адаптер ×2 |
| мерджреквест | ru | 2 | 2 | 0.0% | межрекст ×1; мержеквест ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | sharp | 3 |
| ins | ∅ | to | 2 |
| sub | through | to | 2 |
| ins | ∅ | d | 1 |
| ins | ∅ | dot | 1 |
| ins | ∅ | opening | 1 |
| ins | ∅ | slash | 1 |
| sub_near | 99.9% | 90 9.9% | 1 |

Number style examples: 10:00 → десяти; 11:30 → 11.30; 12:00 → двенадцати; 15:00 → 15

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- restore capitalisation of names/terms (kept only 78.2%)
- convert spoken numbers, times and dates to digits (87.5% spelled out)
- repair garbled terms (60.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| C# | en | 3 | 3 | 0.0% | C ×2; chart ×1 |
| API | en | 2 | 2 | 0.0% | A P L I ×1; AP ×1 |
| Envoy | en | 2 | 2 | 0.0% | Envy ×1; on-boarding ×1 |
| Exynos | en | 2 | 2 | 0.0% | Xeno ×1; Xnol's ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | падотик ×1; пататик ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | Докер-Компьютер ×1; через docker compose up → чрездойтер к Кампусу ×1 |
| зарелизить | ru | 2 | 2 | 0.0% | заверлить ×1; зарезервировать ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | plus | 2 |
| ins | ∅ | sharp | 2 |
| ins | ∅ | a | 1 |
| ins | ∅ | dot | 1 |
| ins | ∅ | eight | 1 |
| ins | ∅ | nvidia | 1 |
| ins | ∅ | opening | 1 |
| ins | ∅ | sea | 1 |

Number style examples: 10:00 → десяти; 11:30 → одиннадцать тридцать; 12:00 → двенадцати; 14:30 → четырнадцать тридцать

## `t-one@onnx`

What the cleanup LLM must learn to undo:
- restore all casing (sentence starts, names, product names)
- restore all punctuation
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- turn Cyrillic transliterations back into Latin terms (100.0% of Latin words)
- repair garbled terms (79.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Pydantic | mixed | 2 | 2 | 0.0% | патотип ×1; на Pydantic → нападатик ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | докер капуусаб ×1; дотер компусап ×1 |
| зарелизить | ru | 2 | 2 | 0.0% | даревидеть ×1; заревидеть ×1 |
| лора-адаптер | ru | 2 | 2 | 0.0% | вородактор ×1; лородации ×1 |
| мерджреквест | ru | 2 | 2 | 0.0% | есть ×1; немежрекве ×1 |
| тикет | ru | 2 | 2 | 0.0% | тикек ×1; Закрываю тикет → закрывайтикето ×1 |
| эстимейт | ru | 2 | 2 | 0.0% | иметь ×1; эстимейт по → стемеетпо ×1 |
| ARM64 | mixed | 1 | 1 | 0.0% | шестьдесят ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | в | 2 |
| sub_near | вчера | тера | 2 |
| del | с | ∅ | 2 |
| ins | ∅ | езь | 1 |
| ins | ∅ | пиджа | 1 |
| ins | ∅ | пяти | 1 |
| ins | ∅ | четыре | 1 |
| sub | в | б | 1 |

Number style examples: 10:00 → десяти; 11:30 → одиннадцать тридцать; 12:00 → двенадцати; 14:30 → четырнадцать тридцать

## `whisper-large-v3-turbo@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (47.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| AMD | en | 2 | 2 | 0.0% | Andy ×1; MD ×1 |
| API | en | 2 | 2 | 0.0% | AP ×1; APLI ×1 |
| Envoy | en | 2 | 2 | 0.0% | Envy ×1; void ×1 |
| Exynos | en | 2 | 2 | 0.0% | Xeno's ×1; Xenos ×1 |
| IAM | en | 2 | 2 | 0.0% | AM ×1; IM ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | пайдотик ×2 |
| лора-адаптер | ru | 2 | 2 | 0.0% | лорадаптер ×1; лороадаптер ×1 |
| мерджреквест | ru | 2 | 2 | 0.0% | GQS ×1; межреквест ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 2 |
| ins | ∅ | on | 1 |
| ins | ∅ | opening | 1 |
| ins | ∅ | to | 1 |
| sub_near | 99.9% | 90 9.9% | 1 |
| sub | a | it | 1 |
| sub | a | the | 1 |
| sub | a | to | 1 |

Number style examples: 10:00 → 10; 11:30 → 11.30; 12:00 → 12; 14:30 → 14.30

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (50.7% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| API | en | 2 | 2 | 0.0% | APE ×1; APLI ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | PadaTik ×1; Python.tick ×1 |
| ./gradlew assembleRelease | en | 1 | 1 | 0.0% | gradlu assemble release ×1 |
| AAB | en | 1 | 1 | 0.0% | abbey ×1 |
| AWS | en | 1 | 1 | 0.0% | iOS ×1 |
| Adreno | mixed | 1 | 1 | 0.0% | с Adreno → сатарена ×1 |
| Axolotl | en | 1 | 1 | 0.0% | AxelOwl ×1 |
| CPU | en | 1 | 1 | 0.0% | Sagi ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | shen | 1 |
| sub_near | 99.9% | 90 9.9% | 1 |
| sub | a | led | 1 |
| sub_near | finished | finish | 1 |
| sub | in | and | 1 |
| sub | our | r-cell | 1 |
| sub | recall | ricoh | 1 |
| sub_near | rerun | run | 1 |

Number style examples: 3:30 → 330

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (54.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| AMD | en | 2 | 2 | 0.0% | Andy ×1; MD ×1 |
| Neo4j | en | 2 | 2 | 0.0% | Neo for ×1; the O4J ×1 |
| Pydantic | mixed | 2 | 2 | 0.0% | PIDOTIC ×1; пайдо тик ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | докер КПУ САП ×1; дотер компус-ап ×1 |
| лора-адаптер | ru | 2 | 2 | 0.0% | ∅ ×1; лородатка ×1 |
| мерджреквест | ru | 2 | 2 | 0.0% | кс ×1; реквесс ×1 |
| тикет | ru | 2 | 2 | 0.0% | текер ×1; этому ×1 |
| эстимейт | ru | 2 | 2 | 0.0% | и стимеет ×1; эстимейт в → стеметь ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | поставь | постав | 3 |
| sub_near | rerun | run | 2 |
| del | в | ∅ | 2 |
| sub_near | записях | записи | 2 |
| ins | ∅ | 3-cent | 1 |
| ins | ∅ | media | 1 |
| ins | ∅ | to | 1 |
| sub_near | 99.9% | 90 9.9% | 1 |

Number style examples: 10:00 → 10; 11:30 → 11.30; 12:00 → 12; 15:00 → 15-е
