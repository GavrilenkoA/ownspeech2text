# Noise sampler calibration

Out-of-sample: tables mined on 84 rows, simulated and compared on 56 held-out rows (by probe_id), 5 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.162→0.149 | 0.000→0.000 | 0.178→0.154 | 0.526→0.526 | 0.000→0.000 | (0.000→0.000) | (1.000→1.000) | (1.000→1.000) | (1.000→1.000) | (1.000→1.000) | – | 0.000→0.000 | (0.000→0.000) | (0.000→0.000) |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.260→0.176 ~ | 0.100→0.020 ~ | 0.197→0.166 | 0.292→0.558 ⚠ | 0.417→0.058 ⚠ | 0.000→0.000 | 1.000→1.000 | 1.000→0.940 | 1.000→0.940 | (0.000→0.050) | – | 0.812→0.794 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.240→0.206 | 0.020→0.032 | 0.213→0.205 | 0.500→0.475 | 0.083→0.133 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | (0.000→0.000) | – | 0.694→0.710 | 0.000→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.070→0.066 | 0.060→0.038 | 0.020→0.014 | 0.708→0.792 ~ | 0.250→0.125 ~ | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | (1.000→1.000) | – | 0.972→1.000 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.284→0.260 | 0.013→0.003 | 0.268→0.260 | 0.316→0.358 | 0.053→0.011 | (1.000→1.000) | (0.000→0.000) | (0.000→0.000) | (0.000→0.000) | (0.000→0.000) | – | 0.000→0.000 | (0.000→0.000) | (0.000→0.000) |
| `whisper-large-v3-turbo@onnx-int8` | 0.060→0.110 | 0.040→0.102 ~ | 0.068→0.109 | 0.833→0.836 | 0.042→0.052 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 0.900→0.960 | (0.750→1.000) | – | 0.973→1.000 | 0.100→0.160 ~ | 0.000→0.040 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 0.000→0.000 | 0.182→0.182 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 0.000→0.000 | (1.000→1.000) | (0.000→0.000) |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 0.115→0.015 ~ | (0.200→0.400) | (0.600→0.080) |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 0.088→0.029 ~ | 0.182→0.309 ~ | 0.273→0.091 ~ |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 0.100→0.015 ~ | (0.500→1.000) | (0.500→0.000) |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 0.038→0.023 | (0.600→0.680) | (0.200→0.120) |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 0.029→0.018 | 0.091→0.182 ~ | 0.091→0.054 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 0.000→0.050 ⚠ | (1.000→0.750) | (0.000→0.250) |
| `qwen3-asr-0.6b@onnx-int8` | en | 0.038→0.008 ~ | (0.800→0.880) | (0.200→0.000) |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 0.029→0.000 | 0.818→0.873 | 0.091→0.000 ~ |
| `qwen3-asr-0.6b@onnx-int8` | ru | 0.100→0.090 | (0.500→0.625) | (0.500→0.375) |
| `t-one@onnx` | mixed | 0.000→0.000 | 0.091→0.091 | 0.000→0.000 |
| `t-one@onnx` | ru | 0.025→0.005 | (0.625→0.725) | (0.125→0.025) |
| `whisper-large-v3-turbo@onnx-int8` | en | 0.000→0.015 | (0.800→0.640) | (0.000→0.080) |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 0.000→0.118 ⚠ | 0.818→0.891 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | ru | 0.100→0.145 ~ | (0.875→0.889) | (0.125→0.111) |

Flagged gaps: 2 of 60 pooled metrics, 2 of 28 per-language metrics; 15 more exceed the tolerance within the real set's sampling noise.
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate: real 0.292, sim 0.558 (gap +0.267, tol 0.08, 2·SE 0.186, n=24; mined-on rows 0.476, sampler gap +0.082)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_error_rate: real 0.417, sim 0.058 (gap -0.358, tol 0.08, 2·SE 0.201, n=24; mined-on rows 0.095, sampler gap -0.037)
- `parakeet-tdt-0.6b-v3@onnx-int8` wer_style_aware@ru: real 0.000, sim 0.050 (gap +0.050, tol 0.03, 2·SE 0.010, n=40; mined-on rows 0.089, sampler gap -0.039)
- `whisper-large-v3-turbo@onnx-int8` wer_style_aware@mixed: real 0.000, sim 0.118 (gap +0.118, tol 0.03, 2·SE 0.010, n=34; mined-on rows 0.204, sampler gap -0.086)

Simulated held-out vs the rows the tables were mined from (different texts, so this mixes sampler error with the difference in text mix; indicative only): 22 metric(s) outside tolerance.
- `gigaam-v3-e2e-rnnt@onnx-int8` wer: mined 0.253, sim 0.149 (gap -0.104)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_exact_rate: mined 0.419, sim 0.526 (gap +0.107)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_exact_rate@mixed: mined 0.000, sim 0.182 (gap +0.182)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` wer: mined 0.298, sim 0.176 (gap -0.122)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` cer: mined 0.251, sim 0.166 (gap -0.084)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate: mined 0.476, sim 0.558 (gap +0.082)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_error_rate@mixed: mined 0.176, sim 0.091 (gap -0.086)
- `parakeet-tdt-0.6b-v3@onnx-int8` wer: mined 0.404, sim 0.206 (gap -0.198)
- `parakeet-tdt-0.6b-v3@onnx-int8` cer: mined 0.345, sim 0.205 (gap -0.140)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_exact_rate: mined 0.357, sim 0.475 (gap +0.118)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_exact_rate@mixed: mined 0.000, sim 0.182 (gap +0.182)
- `parakeet-tdt-0.6b-v3@onnx-int8` wer_style_aware@ru: mined 0.089, sim 0.050 (gap -0.039)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate@mixed: mined 1.000, sim 0.873 (gap -0.127)
- `qwen3-asr-0.6b@onnx-int8` wer_style_aware@ru: mined 0.133, sim 0.090 (gap -0.043)
- `t-one@onnx` wer: mined 0.465, sim 0.260 (gap -0.205)
- `t-one@onnx` cer: mined 0.384, sim 0.260 (gap -0.124)
- `t-one@onnx` term_exact_rate: mined 0.226, sim 0.358 (gap +0.132)
- `t-one@onnx` term_exact_rate@mixed: mined 0.000, sim 0.091 (gap +0.091)
- `t-one@onnx` wer_style_aware@ru: mined 0.067, sim 0.005 (gap -0.062)
- `whisper-large-v3-turbo@onnx-int8` wer_style_aware@en: mined 0.069, sim 0.015 (gap -0.054)
- `whisper-large-v3-turbo@onnx-int8` wer_style_aware@mixed: mined 0.204, sim 0.118 (gap -0.086)
- `whisper-large-v3-turbo@onnx-int8` wer_style_aware@ru: mined 0.044, sim 0.145 (gap +0.101)
