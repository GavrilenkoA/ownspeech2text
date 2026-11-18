# Noise sampler calibration

In-sample: simulated over the same 5000 rows the tables were mined from, 2 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v2@enrich-dataset` | 0.026→0.025 | 0.022→0.024 | 0.009→0.008 | – | – | 0.007→0.018 | 0.983→0.978 | 0.991→0.946 | 0.980→0.977 | – | 0.500→0.500 | – | 0.001→0.000 | 0.000→0.000 |
| `nemotron-3.5-transcribe@enrich-dataset` | 0.059→0.053 | 0.053→0.053 | 0.028→0.027 | – | – | 0.002→0.004 | 0.997→0.995 | 0.997→0.944 | 0.982→0.976 | – | 0.048→0.026 | – | 0.000→0.000 | 0.012→0.013 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `gigaam-v2@enrich-dataset` | ru | 0.022→0.024 | – | – |
| `nemotron-3.5-transcribe@enrich-dataset` | ru | 0.053→0.053 | – | – |

Flagged gaps: 0 of 20 pooled metrics, 0 of 2 per-language metrics; 0 more exceed the tolerance within the real set's sampling noise.
