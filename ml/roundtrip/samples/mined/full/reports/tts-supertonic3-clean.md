# Error mining — full CPU run, supertonic3, clean

Rows mined: 5737 (skipped with null hypothesis: 0). Sources: all_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `distil-large-v3.5@onnx-int8` | 79 | 8.7% | 5.0% | 2.3% | 23.0% | 0.8% | 56.3% | sentence | full | digits | latin | 0.0% |
| `gigaam-v3-e2e-ctc@onnx-int8` | 533 | 14.6% | 12.3% | 6.3% | 58.1% | 2.3% | 32.7% | sentence | partial | digits | mixed | 0.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 533 | 14.2% | 11.4% | 6.9% | 55.2% | 1.9% | 33.4% | sentence | full | digits | mixed | 0.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 612 | 17.8% | 12.7% | 9.1% | 48.4% | 5.0% | 35.2% | sentence | partial | words | latin | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 80 | 11.0% | 6.2% | 3.8% | 27.4% | 1.4% | 52.1% | sentence | partial | words | latin | 0.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 613 | 13.2% | 9.3% | 5.2% | 37.4% | 3.2% | 44.3% | sentence | full | digits | latin | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 613 | 12.0% | 8.3% | 4.7% | 35.9% | 2.2% | 46.8% | sentence | full | digits | latin | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 613 | 23.0% | 18.2% | 11.3% | 55.0% | 10.1% | 32.4% | sentence | full | words | latin | 0.0% |
| `t-one@onnx` | 533 | 20.7% | 15.3% | 12.7% | 68.4% | 3.8% | 21.1% | lowercase | none | words | cyrillic | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 613 | 9.4% | 6.2% | 3.9% | 26.6% | 1.7% | 54.9% | sentence | full | digits | latin | 0.0% |
| `whisper-medium@onnx-int8` | 302 | 11.7% | 7.5% | 4.8% | 29.2% | 2.6% | 52.3% | sentence | full | digits | latin | 0.0% |
| `whisper-small@onnx-int8` | 613 | 14.1% | 10.5% | 5.9% | 41.5% | 3.7% | 41.7% | sentence | full | digits | latin | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 79 | 8.7% | 5.0% | 2.3% |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 290 | 19.4% | 16.3% | 9.3% |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 243 | 8.6% | 7.2% | 2.7% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 290 | 18.9% | 15.5% | 10.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 243 | 8.2% | 6.3% | 3.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 80 | 14.3% | 8.7% | 4.8% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 289 | 23.2% | 16.6% | 14.9% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 243 | 12.6% | 9.5% | 3.5% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 80 | 11.0% | 6.2% | 3.8% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 80 | 9.8% | 5.8% | 2.4% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 290 | 16.7% | 12.4% | 7.6% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 243 | 10.1% | 6.9% | 3.3% |
| `parakeet-ultra-0.6b@onnx-int8` | en | 80 | 9.2% | 5.5% | 2.1% |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 290 | 15.2% | 11.3% | 7.1% |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 243 | 8.9% | 5.5% | 2.7% |
| `qwen3-asr-0.6b@onnx-int8` | en | 80 | 10.5% | 5.0% | 3.5% |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 290 | 29.4% | 23.3% | 17.7% |
| `qwen3-asr-0.6b@onnx-int8` | ru | 243 | 19.9% | 16.9% | 6.2% |
| `t-one@onnx` | mixed | 290 | 26.8% | 19.9% | 20.3% |
| `t-one@onnx` | ru | 243 | 13.1% | 9.6% | 3.4% |
| `whisper-large-v3-turbo@onnx-int8` | en | 80 | 7.6% | 4.5% | 1.8% |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 290 | 12.2% | 8.2% | 5.9% |
| `whisper-large-v3-turbo@onnx-int8` | ru | 243 | 6.7% | 4.3% | 2.1% |
| `whisper-medium@onnx-int8` | en | 40 | 7.5% | 4.3% | 1.9% |
| `whisper-medium@onnx-int8` | mixed | 142 | 13.6% | 9.5% | 5.8% |
| `whisper-medium@onnx-int8` | ru | 120 | 10.8% | 6.2% | 4.5% |
| `whisper-small@onnx-int8` | en | 80 | 8.7% | 6.0% | 2.4% |
| `whisper-small@onnx-int8` | mixed | 290 | 17.6% | 13.3% | 8.6% |
| `whisper-small@onnx-int8` | ru | 243 | 11.9% | 8.6% | 3.8% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 100.0% | 100.0% | 84.4% | 100.0% | 100.0% | 118.5% | 100.0% | 99.9% | – |
| `gigaam-v3-e2e-ctc@onnx-int8` | 100.0% | 99.8% | 94.6% | 88.6% | 25.9% | 86.2% | 86.7% | 69.7% | 100.0% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 100.0% | 100.0% | 94.5% | 100.0% | 100.0% | 87.6% | 89.6% | 63.4% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 99.8% | 99.7% | 80.7% | 75.6% | 36.9% | 81.8% | 11.8% | 89.6% | 0.0% |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 100.0% | 100.0% | 82.1% | 21.2% | 0.0% | 55.6% | 0.0% | 100.0% | – |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 100.0% | 100.0% | 93.2% | 99.8% | 99.8% | 93.9% | 96.4% | 95.8% | 0.0% |
| `parakeet-ultra-0.6b@onnx-int8` | 100.0% | 100.0% | 91.6% | 100.0% | 100.0% | 94.7% | 85.7% | 95.6% | 0.0% |
| `qwen3-asr-0.6b@onnx-int8` | 99.4% | 99.0% | 81.8% | 99.7% | 99.5% | 78.8% | 3.8% | 92.3% | 8.9% |
| `t-one@onnx` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `whisper-large-v3-turbo@onnx-int8` | 100.0% | 100.0% | 92.9% | 99.5% | 98.2% | 87.6% | 98.2% | 95.6% | 17.7% |
| `whisper-medium@onnx-int8` | 100.0% | 99.3% | 93.0% | 99.3% | 98.0% | 88.0% | 96.8% | 96.7% | 7.3% |
| `whisper-small@onnx-int8` | 99.8% | 99.5% | 89.2% | 99.8% | 98.2% | 86.3% | 92.7% | 95.1% | 8.2% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | supertonic3 | en | 79 | 8.7% | 5.0% | 56.3% | 24.6% | 99.9% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | mixed | 290 | 19.4% | 16.3% | 18.1% | 65.6% | 69.7% |
| `gigaam-v3-e2e-ctc@onnx-int8` | supertonic3 | ru | 243 | 8.6% | 7.2% | 51.2% | 36.2% | – |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | mixed | 290 | 18.9% | 15.5% | 17.8% | 64.0% | 63.4% |
| `gigaam-v3-e2e-rnnt@onnx-int8` | supertonic3 | ru | 243 | 8.2% | 6.3% | 53.1% | 31.3% | – |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | en | 80 | 14.3% | 8.7% | 40.8% | 28.4% | 100.0% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | mixed | 289 | 23.2% | 16.6% | 20.4% | 53.5% | 50.5% |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | supertonic3 | ru | 243 | 12.6% | 9.5% | 51.6% | 34.1% | – |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | supertonic3 | en | 80 | 11.0% | 6.2% | 52.1% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | en | 80 | 9.8% | 5.8% | 54.4% | 24.3% | 100.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | mixed | 290 | 16.7% | 12.4% | 31.3% | 46.0% | 83.0% |
| `parakeet-tdt-0.6b-v3@onnx-int8` | supertonic3 | ru | 243 | 10.1% | 6.9% | 56.9% | 25.2% | – |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | en | 80 | 9.2% | 5.5% | 52.7% | 24.9% | 100.0% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | mixed | 290 | 15.2% | 11.3% | 34.5% | 43.0% | 82.6% |
| `parakeet-ultra-0.6b@onnx-int8` | supertonic3 | ru | 243 | 8.9% | 5.5% | 60.1% | 21.8% | – |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | en | 80 | 10.5% | 5.0% | 53.2% | 23.7% | 100.0% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | mixed | 290 | 29.4% | 23.3% | 22.2% | 55.5% | 63.1% |
| `qwen3-asr-0.6b@onnx-int8` | supertonic3 | ru | 243 | 19.9% | 16.9% | 37.7% | 48.8% | – |
| `t-one@onnx` | supertonic3 | mixed | 290 | 26.8% | 19.9% | 3.0% | 71.1% | 0.0% |
| `t-one@onnx` | supertonic3 | ru | 243 | 13.1% | 9.6% | 43.9% | 38.6% | – |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | en | 80 | 7.6% | 4.5% | 59.2% | 20.7% | 99.8% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | mixed | 290 | 12.2% | 8.2% | 44.5% | 30.8% | 85.3% |
| `whisper-large-v3-turbo@onnx-int8` | supertonic3 | ru | 243 | 6.7% | 4.3% | 66.5% | 18.1% | – |
| `whisper-medium@onnx-int8` | supertonic3 | en | 40 | 7.5% | 4.3% | 67.4% | 22.1% | 99.8% |
| `whisper-medium@onnx-int8` | supertonic3 | mixed | 142 | 13.6% | 9.5% | 45.6% | 34.7% | 89.0% |
| `whisper-medium@onnx-int8` | supertonic3 | ru | 120 | 10.8% | 6.2% | 55.0% | 20.8% | – |
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
| Svelte | mixed | 10 | 28 | 21 | 75.0% | свалте ×4; Swelta ×1; Vue на Svelte → Woensvelt ×1; Vue на Svelte → Woinasvelt ×1 |
| Hadoop | mixed | 10 | 29 | 21 | 72.4% | Хадуб ×6; Hadub ×3; Hadob ×2; хадуб ×2 |
| pgBouncer | mixed | 10 | 20 | 20 | 100.0% | бы баунсер ×4; B-Bouncer ×1; B-баунсера ×1; Bigi-bounter ×1 |
| tsconfig.json | mixed | 10 | 20 | 20 | 100.0% | конфиг ×2; с конфиг, с ×2; сон ×2; Config, с он ×1 |
| Zabbix | mixed | 9 | 27 | 20 | 74.1% | Zabix ×4; Забег ×3; Забик ×2; The Bex ×1 |

## `distil-large-v3.5@onnx-int8`

What the cleanup LLM must learn to undo:
- repair garbled terms (24.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| Milvus | en | 2 | 2 | 0.0% | Milvas ×1; Milvos ×1 |
| Axolotl | en | 1 | 1 | 0.0% | Axel-Lawal ×1 |
| Cilium | en | 1 | 1 | 0.0% | psyllium ×1 |
| Consul | en | 1 | 1 | 0.0% | console ×1 |
| GLM-4.5 | en | 1 | 1 | 0.0% | GM 4.5 ×1 |
| HashiCorp Vault | en | 1 | 1 | 0.0% | Hashikorp Vault ×1 |
| Hexagon NPU | en | 1 | 1 | 0.0% | hexagon in PU ×1 |
| KV cache | en | 1 | 1 | 0.0% | KV catchy ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | at | 1 |
| ins | ∅ | o'clock | 1 |
| ins | ∅ | out | 1 |
| sub_near | caching | catching | 1 |
| sub_near | ffmpeg | fefenpeg | 1 |
| del | idea | ∅ | 1 |
| sub_near | retry | rittri | 1 |
| sub_near | verbose | vervo's | 1 |

Number style examples: 18:00 → 18

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
- restore missing punctuation (commas at 81.8% of reference)
- convert spoken numbers, times and dates to digits (88.2% spelled out)
- spell out again numbers the writer wanted in words (50.0% of spelled-out numbers come back as digits)
- repair garbled terms (42.6% of terms are recognition errors) — from context only

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
| del | и | ∅ | 6 |
| del | в | ∅ | 3 |
| del | с | ∅ | 3 |
| sub_near | запасной | запасный | 2 |
| sub_near | записей | записи | 2 |
| sub_near | используется | используются | 2 |
| sub_near | коллеги | коллегии | 2 |
| sub_near | покажи | покаже | 2 |

Number style examples: 14:30 → четырнадцать тридцать; 11:30 → одиннадцать-тридцать; 30 → thirty; 100 → сто

## `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms`

What the cleanup LLM must learn to undo:
- restore missing punctuation (commas at 55.6% of reference)
- convert spoken numbers, times and dates to digits (100.0% spelled out)
- repair garbled terms (24.3% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| CLIP | en | 2 | 2 | 0.0% | CLAP ×1; Kalapi ×1 |
| uv sync | en | 2 | 2 | 0.0% | of sync ×1; youve sync ×1 |
| Cilium | en | 1 | 1 | 0.0% | pilium ×1 |
| Consul | en | 1 | 1 | 0.0% | console ×1 |
| DeepSeek-V3 | en | 1 | 1 | 0.0% | deepseq V three ×1 |
| GLM-4.5 | en | 1 | 1 | 0.0% | five ×1 |
| Gemini | en | 1 | 1 | 0.0% | Germini ×1 |
| Gemini 2.5 Pro | en | 1 | 1 | 0.0% | Jimini two point five pro ×1 |

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
- repair garbled terms (35.1% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| эстимейт | ru | 5 | 5 | 0.0% | стимейт ×3; и стимейт ×1; истимей ×1 |
| передеплоить | ru | 6 | 5 | 16.7% | передиплейт ×2; передиплоить ×2; передеплоит ×1; передеплоить ×1 |
| постмортем | ru | 4 | 4 | 0.0% | постмартем ×2; по смартем ×1; с Мартем ×1 |
| Hadoop | mixed | 3 | 3 | 0.0% | Хадуб ×2; Hadob ×1 |
| SQLite | mixed | 3 | 3 | 0.0% | Esqula ×1; в SQLite → VSQLite ×1; в SQLite → Vescualait ×1 |
| багфикс | ru | 3 | 3 | 0.0% | бакфикс ×3 |
| дейлик | ru | 3 | 3 | 0.0% | деелик ×1; делик ×1; деяк ×1 |
| закоммитил | ru | 3 | 3 | 0.0% | закамитил ×1; закометил ×1; закомитил ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | правки | прав | 4 |
| del | в | ∅ | 3 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| sub | и | sony | 2 |
| sub_near | ночная | ночное | 2 |
| sub_near | уведомление | уведомления | 2 |
| ins | ∅ | o'clock | 1 |

Number style examples: 14:30 → 14.30; 15:30 → 15.30; 11:30 → 11.30; 10:00 → 10

## `parakeet-ultra-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (32.4% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| эстимейт | ru | 5 | 5 | 0.0% | стимейт ×3; и стимейт ×1; из тимейт ×1 |
| постмортем | ru | 4 | 4 | 0.0% | постмартем ×2; по смартем ×1; с Мартем ×1 |
| Hadoop | mixed | 3 | 3 | 0.0% | Hadob ×1; Хаду ×1; Хадуб ×1 |
| Zabbix | mixed | 3 | 3 | 0.0% | The Bex ×1; Забег ×1; забекс ×1 |
| багфикс | ru | 3 | 3 | 0.0% | бакфикс ×3 |
| дейлик | ru | 3 | 3 | 0.0% | делик ×2; делек ×1 |
| закоммитил | ru | 3 | 3 | 0.0% | закаметил ×1; закометил ×1; закомитил ×1 |
| коммит | ru | 3 | 3 | 0.0% | комет ×1; комит ×1; коммент ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | в | ∅ | 2 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| sub | и | sony | 2 |
| sub_near | правки | прав | 2 |
| sub_near | уведомление | уведомления | 2 |
| ins | ∅ | o'clock | 1 |
| ins | ∅ | wii | 1 |

Number style examples: 14:30 → 14.30; 11:30 → 11.30; 15:30 → 15.30; 14:30 → четырнадцать тридцать

## `qwen3-asr-0.6b@onnx-int8`

What the cleanup LLM must learn to undo:
- convert spoken numbers, times and dates to digits (96.2% spelled out)
- repair garbled terms (48.6% of terms are recognition errors) — from context only

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

Number style examples: 14:30 → четырнадцать тридцать; 15:30 → пятнадцать тридцать; 11:30 → одиннадцать тридцать; 15:00 → пятнадцати

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
- repair garbled terms (24.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| передеплоить | ru | 6 | 6 | 0.0% | передеплейт ×3; передеплейить ×1; передеплейть ×1; передеплэйть ×1 |
| дообучение | ru | 4 | 4 | 0.0% | до обучения ×4 |
| постмортем | ru | 4 | 4 | 0.0% | постмартем ×2; Мартем ×1; по смартём ×1 |
| прод | ru | 5 | 4 | 20.0% | прот ×4; прод ×1 |
| BLEU | mixed | 3 | 3 | 0.0% | BellyU ×1; Беллиу ×1; бэллиу ×1 |
| отребейзить | ru | 3 | 3 | 0.0% | отребезить ×3 |
| эстимейт | ru | 5 | 3 | 40.0% | эстимейт ×2; Тиммейт ×1; истимей ×1; стимейт ×1 |
| ASR | mixed | 2 | 2 | 0.0% | ISR ×1; ОАСР ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| sub_near | правок | справок | 2 |
| sub_near | проверил | проверила | 2 |
| del | у | ∅ | 2 |
| sub_near | уведомление | уведомления | 2 |
| ins | ∅ | out | 1 |
| ins | ∅ | wee | 1 |

Number style examples: 14:30 → 14.30; 15:30 → 15.30; 11:30 → 11.30; 10:00 → 10

## `whisper-medium@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (27.6% of terms are recognition errors) — from context only

Top term manglings (by error count):

| term | context | n | errors | exact | variants |
|---|---|---:|---:|---:|---|
| дообучение | ru | 3 | 3 | 0.0% | до обучения ×3 |
| передеплоить | ru | 3 | 3 | 0.0% | передиплать ×2; передиплеить ×1 |
| BLEU | mixed | 2 | 2 | 0.0% | BAL-EU ×1; BLU ×1 |
| docker compose up | mixed | 2 | 2 | 0.0% | Docker Compose App ×1; Docker Composer App ×1 |
| kubectl logs | mixed | 2 | 2 | 0.0% | кубек локс ×1; кубектл локс ×1 |
| pgBouncer | mixed | 2 | 2 | 0.0% | B-Bouncer ×1; piggy bouncer ×1 |
| tsconfig.json | mixed | 2 | 2 | 0.0% | config. Солнц ×1; с конфиг, с он ×1 |
| values.yaml | mixed | 2 | 2 | 0.0% | Valius ×1; values. Я мул ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| sub_near | правок | справок | 2 |
| sub_near | проверил | проверила | 2 |
| sub_near | описание | описании | 2 |
| ins | ∅ | o'clock | 1 |
| sub | and | in | 1 |
| ins | ∅ | млэй | 1 |
| sub_near | 900 | 90 | 1 |
| del | в | ∅ | 1 |

Number style examples: 14:30 → 14.30; 11:30 → 11.30; 12:00 → 12; 100 → сто

## `whisper-small@onnx-int8`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (100.0% of spelled-out numbers come back as digits)
- repair garbled terms (37.6% of terms are recognition errors) — from context only

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
| Svelte | mixed | 3 | 3 | 0.0% | сфелты ×1; Vue на Svelte → вуеносвелта ×1; на Svelte → насвалта ×1 |

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | и | 2 |
| del | в | ∅ | 2 |
| sub_near | запасной | запасный | 2 |
| del | и | ∅ | 2 |
| sub_near | мне | не | 2 |
| sub_near | проверил | проверила | 2 |
| sub_near | пятничного | петничного | 2 |
| del | у | ∅ | 2 |

Number style examples: 11:30 → 11.30; 14:30 → 14-30; 14:30 → 14.30; 10:00 → 10
