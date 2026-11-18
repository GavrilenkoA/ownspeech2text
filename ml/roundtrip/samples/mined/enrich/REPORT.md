# Error mining — real speech, MishZor256/russian-asr-enrichment (cv21_ru + rulibrispeech, human ref)

Rows mined: 5000 (skipped with null hypothesis: 0). Sources: enrich_rows.jsonl.

WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.

## Per model

| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|
| `gigaam-v2@enrich-dataset` | 2500 | 2.6% | 2.2% | 0.9% | – | 2.2% | – | sentence | full | unknown | unknown | 0.1% |
| `nemotron-3.5-transcribe@enrich-dataset` | 2500 | 5.9% | 5.3% | 2.8% | – | 5.3% | – | sentence | full | unknown | unknown | 0.0% |

## WER by language

| ASR model | lang | rows | WER | style-aware WER | CER |
|---|---|---:|---:|---:|---:|
| `gigaam-v2@enrich-dataset` | ru | 2500 | 2.6% | 2.2% | 0.9% |
| `nemotron-3.5-transcribe@enrich-dataset` | ru | 2500 | 5.9% | 5.3% | 2.8% |

## Output style

| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v2@enrich-dataset` | 99.3% | 98.3% | 87.4% | 99.1% | 98.0% | 110.6% | – | – | 100.0% |
| `nemotron-3.5-transcribe@enrich-dataset` | 99.8% | 99.7% | 81.0% | 99.7% | 98.2% | 113.0% | – | – | 32.4% |

## By TTS system

The voice that read a sentence moves these numbers as much as the recogniser does: Piper's Russian voices read the Cyrillic respelling (`tts_text`), Supertonic reads the Latin terms as written. Every per-model figure above pools the TTS systems in the proportions of the input rows.

| ASR model | TTS | lang | rows | WER | style-aware WER | term exact | term error | Latin kept |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `gigaam-v2@enrich-dataset` | human | ru | 2500 | 2.6% | 2.2% | – | – | – |
| `nemotron-3.5-transcribe@enrich-dataset` | human | ru | 2500 | 5.9% | 5.3% | – | – | – |

## `gigaam-v2@enrich-dataset`

What the cleanup LLM must learn to undo:
- spell out again numbers the writer wanted in words (50.0% of spelled-out numbers come back as digits)

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| ins | ∅ | что | 13 |
| sub_near | ней | нее | 8 |
| ins | ∅ | и | 6 |
| ins | ∅ | как | 6 |
| sub_near | но | ну | 6 |
| sub_near | чтоб | чтобы | 5 |
| sub_near | бачка | батюшка | 4 |
| sub | гм | хмм | 4 |

Hallucinated tails: “к че” ×1; “я за” ×1

## `nemotron-3.5-transcribe@enrich-dataset`

What the cleanup LLM must learn to undo:
- little beyond light punctuation fixes

Top non-term recognition errors:

| kind | ref | hyp | count |
|---|---|---|---:|
| del | и | ∅ | 24 |
| sub_near | чтоб | чтобы | 12 |
| ins | ∅ | что | 10 |
| del | в | ∅ | 10 |
| del | что | ∅ | 10 |
| ins | ∅ | и | 9 |
| sub_near | но | ну | 7 |
| del | а | ∅ | 6 |
