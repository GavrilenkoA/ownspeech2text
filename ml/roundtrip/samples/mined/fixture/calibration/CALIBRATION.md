# Noise sampler calibration

In-sample: simulated over the same 140 rows the tables were mined from, 5 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.214→0.208 | 0.000→0.000 | 0.191→0.184 | 0.460→0.460 | 0.000→0.000 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | (1.000→1.000) | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.284→0.289 | 0.055→0.071 | 0.231→0.226 | 0.409→0.388 | 0.212→0.248 | 0.000→0.000 | 1.000→1.000 | 0.962→0.985 | 0.962→0.977 | 0.059→0.059 | (0.000→0.000) | 0.866→0.863 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.343→0.316 | 0.037→0.033 | 0.298→0.277 | 0.409→0.430 | 0.121→0.121 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | 0.000→0.000 | (0.000→0.000) | 0.754→0.762 | 0.000→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.081→0.068 | 0.044→0.045 | 0.016→0.016 | 0.742→0.773 | 0.167→0.167 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | 0.941→0.977 | (0.000→0.000) | 0.992→0.998 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.387→0.380 | 0.023→0.010 | 0.336→0.329 | 0.260→0.264 | 0.060→0.028 | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | (0.000→0.000) | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | 0.096→0.078 | 0.081→0.050 ~ | 0.091→0.074 | 0.790→0.803 | 0.065→0.043 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 0.923→0.962 | 0.938→0.904 | (0.000→0.000) | 0.992→0.990 | 0.115→0.092 | 0.038→0.015 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 0.000→0.000 | 0.071→0.071 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 0.000→0.000 | 0.955→0.955 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 0.051→0.063 | 0.312→0.338 | 0.250→0.312 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 0.068→0.064 | 0.250→0.286 | 0.214→0.164 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 0.047→0.087 ~ | 0.682→0.554 ~ | 0.182→0.309 ~ |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 0.041→0.037 | 0.562→0.575 | 0.188→0.175 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 0.023→0.027 | 0.036→0.064 | 0.071→0.086 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 0.047→0.035 | 0.773→0.791 | 0.136→0.127 |
| `qwen3-asr-0.6b@onnx-int8` | en | 0.010→0.004 | 0.875→0.912 | 0.062→0.025 |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 0.011→0.014 | 0.929→0.957 | 0.036→0.036 |
| `qwen3-asr-0.6b@onnx-int8` | ru | 0.118→0.125 | 0.409→0.436 | 0.409→0.436 |
| `t-one@onnx` | mixed | 0.000→0.000 | 0.036→0.036 | 0.000→0.000 |
| `t-one@onnx` | ru | 0.047→0.021 | 0.545→0.554 | 0.136→0.064 |
| `whisper-large-v3-turbo@onnx-int8` | en | 0.051→0.041 | 0.750→0.750 | 0.062→0.062 |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 0.125→0.027 ~ | 0.833→0.807 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | ru | 0.071→0.085 | 0.773→0.838 | 0.136→0.086 |

Flagged gaps: 0 of 78 pooled metrics, 0 of 48 per-language metrics; 5 more exceed the tolerance within the real set's sampling noise.
