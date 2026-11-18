# Noise sampler calibration

Out-of-sample: tables mined on 3474 rows, simulated and compared on 1526 held-out rows (by probe_id), 2 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v2@enrich-dataset` | 0.028→0.021 | 0.024→0.020 | 0.009→0.007 | – | – | 0.003→0.019 | 0.990→0.973 | 0.996→0.937 | 0.989→0.974 | – | (0.750→0.500) | – | 0.001→0.000 | 0.000→0.000 |
| `nemotron-3.5-transcribe@enrich-dataset` | 0.067→0.045 | 0.060→0.045 | 0.033→0.022 | – | – | 0.002→0.006 | 0.999→0.992 | 0.999→0.936 | 0.982→0.975 | – | (0.111→0.000) | – | 0.000→0.000 | 0.017→0.012 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `gigaam-v2@enrich-dataset` | ru | 0.024→0.020 | – | – |
| `nemotron-3.5-transcribe@enrich-dataset` | ru | 0.060→0.045 | – | – |

Flagged gaps: 0 of 18 pooled metrics, 0 of 2 per-language metrics; 0 more exceed the tolerance within the real set's sampling noise.

Simulated held-out vs the rows the tables were mined from (different texts, so this mixes sampler error with the difference in text mix; indicative only): 0 metric(s) outside tolerance.
