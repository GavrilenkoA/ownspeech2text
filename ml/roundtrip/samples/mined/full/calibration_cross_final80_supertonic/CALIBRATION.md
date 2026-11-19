# Noise sampler calibration

Cross-set: tables mined on other rows (.cache/ml/roundtrip/mined/full/primary), simulated and compared on these 773 rows (different sentences), 4 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 0.068→0.079 | 0.050→0.058 | 0.017→0.024 | 0.667→0.578 ~ | 0.196→0.240 | 0.000→0.037 | 1.000→0.898 ⚠ | 1.000→0.991 | 1.000→0.963 | (1.000→1.000) | – | 0.997→1.000 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-ctc@onnx-int8` | 0.185→0.149 | 0.157→0.125 ~ | 0.083→0.071 | 0.293→0.343 | 0.576→0.525 | 0.000→0.005 | 1.000→0.991 | 0.887→0.811 | 0.189→0.217 | (0.833→0.913) | – | 0.643→0.674 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.184→0.139 | 0.155→0.116 ~ | 0.081→0.069 | 0.303→0.351 | 0.586→0.520 | 0.000→0.000 | 1.000→0.995 | 1.000→1.000 | 1.000→1.000 | (0.833→0.773) | – | 0.667→0.654 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.152→0.184 | 0.101→0.137 ~ | 0.085→0.103 | 0.427→0.377 | 0.340→0.417 | 0.000→0.009 | 1.000→0.984 | 0.650→0.559 ~ | 0.300→0.259 | (0.000→0.233) | – | 0.967→0.965 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 0.086→0.134 | 0.031→0.102 ⚠ | 0.041→0.072 | 0.667→0.529 ⚠ | 0.118→0.260 ⚠ | 0.000→0.000 | 1.000→1.000 | 0.333→0.250 ~ | 0.000→0.018 | (0.000→0.000) | – | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.123→0.127 | 0.089→0.095 | 0.055→0.052 | 0.513→0.465 | 0.320→0.343 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | (0.778→0.941) | – | 0.987→0.981 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-ultra-0.6b@onnx-int8` | 0.114→0.101 | 0.079→0.074 | 0.052→0.043 | 0.520→0.498 | 0.300→0.327 | 0.000→0.000 | 1.000→0.997 | 1.000→1.000 | 1.000→1.000 | (0.778→0.861) | – | 0.985→0.984 | 0.000→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.204→0.193 | 0.144→0.147 | 0.106→0.110 | 0.433→0.388 | 0.427→0.413 | 0.000→0.013 | 1.000→0.978 | 1.000→0.997 | 1.000→0.997 | (0.000→0.000) | – | 0.977→0.972 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.235→0.221 | 0.185→0.161 | 0.146→0.154 | 0.222→0.258 | 0.606→0.548 | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | (0.000→0.000) | – | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | 0.098→0.082 | 0.064→0.056 | 0.046→0.039 | 0.587→0.585 | 0.253→0.220 | 0.000→0.000 | 1.000→1.000 | 0.975→0.994 | 0.963→0.978 | (1.000→0.944) | – | 0.988→0.985 | 0.000→0.000 | 0.000→0.000 |
| `whisper-medium@onnx-int8` | 0.106→0.088 | 0.074→0.070 | 0.045→0.034 | 0.553→0.563 | 0.287→0.303 | 0.013→0.016 | 0.988→0.963 | 0.975→0.984 | 0.950→0.975 | (0.778→1.000) | – | 0.995→0.991 | 0.000→0.000 | 0.000→0.000 |
| `whisper-small@onnx-int8` | 0.131→0.130 | 0.093→0.098 | 0.054→0.059 | 0.460→0.457 | 0.313→0.347 | 0.000→0.013 | 1.000→0.988 | 0.988→0.991 | 0.988→0.991 | (0.889→1.000) | – | 0.982→0.981 | 0.000→0.000 | 0.000→0.000 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 0.050→0.058 | 0.667→0.578 ~ | 0.196→0.240 |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 0.236→0.183 ~ | 0.130→0.148 | 0.722→0.681 |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 0.080→0.067 | 0.489→0.578 ~ | 0.400→0.339 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 0.236→0.173 ~ | 0.148→0.157 | 0.759→0.708 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 0.076→0.059 | 0.489→0.583 ~ | 0.378→0.294 ~ |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 0.057→0.112 ~ | 0.451→0.387 | 0.216→0.338 ⚠ |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 0.199→0.201 | 0.167→0.171 | 0.593→0.583 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 0.060→0.106 ~ | 0.711→0.611 ~ | 0.178→0.306 ⚠ |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 0.031→0.102 ⚠ | 0.667→0.529 ⚠ | 0.118→0.260 ⚠ |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 0.039→0.078 ~ | 0.686→0.471 ⚠ | 0.137→0.275 ⚠ |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 0.182→0.144 ~ | 0.278→0.324 | 0.574→0.495 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 0.063→0.070 | 0.600→0.628 | 0.222→0.239 |
| `parakeet-ultra-0.6b@onnx-int8` | en | 0.042→0.053 | 0.667→0.539 ~ | 0.157→0.260 ⚠ |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 0.178→0.126 ~ | 0.241→0.301 | 0.574→0.491 ~ |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 0.030→0.049 | 0.689→0.689 | 0.133→0.206 |
| `qwen3-asr-0.6b@onnx-int8` | en | 0.050→0.077 | 0.667→0.524 ⚠ | 0.196→0.260 |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 0.256→0.231 | 0.185→0.222 | 0.648→0.579 |
| `qwen3-asr-0.6b@onnx-int8` | ru | 0.156→0.155 | 0.467→0.433 | 0.422→0.389 |
| `t-one@onnx` | mixed | 0.273→0.230 ~ | 0.018→0.023 | 0.778→0.694 ~ |
| `t-one@onnx` | ru | 0.099→0.093 | 0.467→0.539 | 0.400→0.372 |
| `whisper-large-v3-turbo@onnx-int8` | en | 0.029→0.053 | 0.706→0.520 ⚠ | 0.137→0.226 ~ |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 0.145→0.081 ~ | 0.370→0.514 ⚠ | 0.463→0.278 ⚠ |
| `whisper-large-v3-turbo@onnx-int8` | ru | 0.030→0.036 | 0.711→0.744 | 0.133→0.144 |
| `whisper-medium@onnx-int8` | en | 0.031→0.052 | 0.726→0.618 ~ | 0.137→0.235 ⚠ |
| `whisper-medium@onnx-int8` | mixed | 0.165→0.100 ~ | 0.333→0.449 ~ | 0.500→0.380 ~ |
| `whisper-medium@onnx-int8` | ru | 0.040→0.062 | 0.622→0.639 | 0.200→0.289 ~ |
| `whisper-small@onnx-int8` | en | 0.042→0.065 | 0.608→0.564 | 0.157→0.235 |
| `whisper-small@onnx-int8` | mixed | 0.202→0.152 ~ | 0.259→0.343 ~ | 0.518→0.458 |
| `whisper-small@onnx-int8` | ru | 0.050→0.088 ~ | 0.533→0.472 | 0.244→0.339 ~ |

Flagged gaps: 4 of 144 pooled metrics, 13 of 87 per-language metrics; 33 more exceed the tolerance within the real set's sampling noise.
- `distil-large-v3.5@onnx-int8` first_upper_rate: real 1.000, sim 0.898 (gap -0.102, tol 0.08, 2·SE 0.004, n=27; mined-on rows 0.958, sampler gap -0.060)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_error_rate@en: real 0.216, sim 0.338 (gap +0.122, tol 0.08, 2·SE 0.115, n=51; mined-on rows 0.324, sampler gap +0.014)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_error_rate@ru: real 0.178, sim 0.306 (gap +0.128, tol 0.08, 2·SE 0.114, n=45; mined-on rows 0.348, sampler gap -0.043)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` wer_style_aware: real 0.031, sim 0.102 (gap +0.070, tol 0.03, 2·SE 0.067, n=384; mined-on rows 0.106, sampler gap -0.004)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_exact_rate: real 0.667, sim 0.529 (gap -0.137, tol 0.08, 2·SE 0.132, n=51; mined-on rows 0.424, sampler gap +0.105)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_error_rate: real 0.118, sim 0.260 (gap +0.142, tol 0.08, 2·SE 0.090, n=51; mined-on rows 0.277, sampler gap -0.017)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` wer_style_aware@en: real 0.031, sim 0.102 (gap +0.070, tol 0.03, 2·SE 0.067, n=384; mined-on rows 0.106, sampler gap -0.004)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_exact_rate@en: real 0.667, sim 0.529 (gap -0.137, tol 0.08, 2·SE 0.132, n=51; mined-on rows 0.424, sampler gap +0.105)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_error_rate@en: real 0.118, sim 0.260 (gap +0.142, tol 0.08, 2·SE 0.090, n=51; mined-on rows 0.277, sampler gap -0.017)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_exact_rate@en: real 0.686, sim 0.471 (gap -0.216, tol 0.08, 2·SE 0.130, n=51; mined-on rows 0.481, sampler gap -0.011)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_error_rate@en: real 0.137, sim 0.275 (gap +0.137, tol 0.08, 2·SE 0.096, n=51; mined-on rows 0.259, sampler gap +0.015)
- `parakeet-ultra-0.6b@onnx-int8` term_error_rate@en: real 0.157, sim 0.260 (gap +0.103, tol 0.08, 2·SE 0.102, n=51; mined-on rows 0.256, sampler gap +0.004)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate@en: real 0.667, sim 0.524 (gap -0.142, tol 0.08, 2·SE 0.132, n=51; mined-on rows 0.442, sampler gap +0.083)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate@en: real 0.706, sim 0.520 (gap -0.186, tol 0.08, 2·SE 0.128, n=51; mined-on rows 0.538, sampler gap -0.018)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate@mixed: real 0.370, sim 0.514 (gap +0.143, tol 0.08, 2·SE 0.131, n=54; mined-on rows 0.431, sampler gap +0.083)
- `whisper-large-v3-turbo@onnx-int8` term_error_rate@mixed: real 0.463, sim 0.278 (gap -0.185, tol 0.08, 2·SE 0.136, n=54; mined-on rows 0.330, sampler gap -0.052)
- `whisper-medium@onnx-int8` term_error_rate@en: real 0.137, sim 0.235 (gap +0.098, tol 0.08, 2·SE 0.096, n=51; mined-on rows 0.221, sampler gap +0.015)

Simulated held-out vs the rows the tables were mined from (different texts, so this mixes sampler error with the difference in text mix; indicative only): 12 metric(s) outside tolerance.
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` punct_any_rate: mined 0.665, sim 0.559 (gap -0.105)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` wer_style_aware@mixed: mined 0.169, sim 0.201 (gap +0.032)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate@ru: mined 0.510, sim 0.611 (gap +0.101)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_exact_rate: mined 0.424, sim 0.529 (gap +0.105)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_exact_rate@en: mined 0.424, sim 0.529 (gap +0.105)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@ru: mined 0.595, sim 0.689 (gap +0.094)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate@en: mined 0.442, sim 0.524 (gap +0.083)
- `qwen3-asr-0.6b@onnx-int8` term_error_rate@ru: mined 0.488, sim 0.389 (gap -0.099)
- `t-one@onnx` term_exact_rate@ru: mined 0.428, sim 0.539 (gap +0.111)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate@mixed: mined 0.431, sim 0.514 (gap +0.083)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate@ru: mined 0.662, sim 0.744 (gap +0.082)
- `whisper-medium@onnx-int8` term_exact_rate@ru: mined 0.532, sim 0.639 (gap +0.107)
