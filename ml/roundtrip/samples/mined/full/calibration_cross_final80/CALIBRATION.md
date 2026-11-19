# Noise sampler calibration

Cross-set: tables mined on other rows (.cache/ml/roundtrip/mined/full/primary), simulated and compared on these 1546 rows (different sentences), 4 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 0.111→0.079 | 0.082→0.060 | 0.036→0.024 | 0.578→0.542 | 0.275→0.272 | 0.037→0.023 | 0.944→0.926 | 0.982→0.991 | 0.963→0.977 | (1.000→0.958) | – | 0.999→1.000 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-ctc@onnx-int8` | 0.212→0.146 ~ | 0.186→0.126 ~ | 0.096→0.067 | 0.258→0.327 | 0.631→0.547 ⚠ | 0.000→0.002 | 1.000→0.995 | 0.859→0.804 | 0.207→0.229 | 0.833→0.915 | – | 0.593→0.722 ⚠ | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.211→0.138 ~ | 0.184→0.113 ~ | 0.096→0.071 | 0.263→0.351 ⚠ | 0.631→0.509 ⚠ | 0.000→0.000 | 1.000→0.995 | 1.000→1.000 | 1.000→1.000 | 0.917→0.867 | – | 0.542→0.640 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.255→0.190 ~ | 0.207→0.145 ~ | 0.135→0.107 | 0.303→0.372 | 0.503→0.426 | 0.006→0.006 | 0.994→0.989 | 0.575→0.605 | 0.181→0.286 ⚠ | 0.000→0.190 ⚠ | – | 0.970→0.965 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 0.133→0.136 | 0.077→0.101 | 0.072→0.073 | 0.588→0.507 ~ | 0.186→0.253 | 0.000→0.000 | 1.000→1.000 | 0.333→0.250 ~ | 0.000→0.009 | (0.000→0.000) | – | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.195→0.124 ⚠ | 0.146→0.092 ~ | 0.098→0.052 | 0.407→0.474 | 0.413→0.330 ⚠ | 0.013→0.000 | 0.988→1.000 | 1.000→1.000 | 1.000→1.000 | 0.500→0.886 ⚠ | – | 0.978→0.981 | 0.006→0.000 | 0.000→0.000 |
| `parakeet-ultra-0.6b@onnx-int8` | 0.168→0.104 ⚠ | 0.132→0.077 ⚠ | 0.079→0.043 | 0.433→0.500 | 0.390→0.327 | 0.006→0.000 | 0.988→0.997 | 1.000→1.000 | 1.000→1.000 | 0.722→0.845 ~ | – | 0.977→0.984 | 0.006→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.285→0.205 ⚠ | 0.237→0.160 ⚠ | 0.147→0.118 | 0.347→0.383 | 0.537→0.426 ⚠ | 0.006→0.009 | 0.994→0.981 | 0.981→0.997 | 0.981→0.995 | 0.071→0.000 | – | 0.980→0.974 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.315→0.223 ⚠ | 0.267→0.167 ⚠ | 0.177→0.154 | 0.162→0.234 | 0.702→0.583 ⚠ | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | – | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | 0.148→0.084 ⚠ | 0.112→0.058 ⚠ | 0.072→0.036 | 0.490→0.574 ⚠ | 0.357→0.232 ⚠ | 0.013→0.006 | 0.988→0.994 | 0.944→0.991 | 0.838→0.980 ⚠ | 1.000→0.972 | – | 0.986→0.985 | 0.000→0.000 | 0.000→0.000 |
| `whisper-medium@onnx-int8` | 0.171→0.088 ⚠ | 0.143→0.070 ⚠ | 0.076→0.035 | 0.433→0.563 ⚠ | 0.403→0.300 ⚠ | 0.025→0.014 | 0.969→0.964 | 0.950→0.988 | 0.863→0.981 ⚠ | 0.889→1.000 ~ | – | 0.994→0.989 | 0.000→0.000 | 0.000→0.000 |
| `whisper-small@onnx-int8` | 0.212→0.126 ⚠ | 0.180→0.099 ⚠ | 0.094→0.056 | 0.360→0.455 ⚠ | 0.447→0.361 ⚠ | 0.025→0.006 | 0.969→0.991 | 0.963→0.992 | 0.856→0.984 ⚠ | 0.944→0.986 | – | 0.980→0.983 | 0.000→0.000 | 0.000→0.000 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 0.082→0.060 | 0.578→0.542 | 0.275→0.272 |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 0.251→0.182 ~ | 0.111→0.137 | 0.750→0.697 |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 0.122→0.071 ~ | 0.433→0.556 ⚠ | 0.489→0.367 ⚠ |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 0.261→0.170 ~ | 0.120→0.150 | 0.778→0.699 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 0.108→0.058 ~ | 0.433→0.592 ⚠ | 0.456→0.281 ⚠ |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 0.121→0.123 | 0.382→0.380 | 0.314→0.343 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 0.311→0.207 ~ | 0.111→0.178 | 0.704→0.586 ⚠ |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 0.215→0.113 ~ | 0.444→0.594 ⚠ | 0.478→0.328 ⚠ |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 0.077→0.101 | 0.588→0.507 ~ | 0.186→0.253 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 0.073→0.071 | 0.559→0.493 | 0.235→0.253 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 0.244→0.147 ~ | 0.194→0.319 ⚠ | 0.620→0.491 ⚠ |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 0.144→0.064 ~ | 0.489→0.639 ⚠ | 0.367→0.225 ⚠ |
| `parakeet-ultra-0.6b@onnx-int8` | en | 0.051→0.057 | 0.618→0.537 ~ | 0.196→0.265 |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 0.251→0.133 ~ | 0.157→0.287 ⚠ | 0.639→0.514 ⚠ |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 0.117→0.046 ~ | 0.556→0.714 ⚠ | 0.311→0.172 ⚠ |
| `qwen3-asr-0.6b@onnx-int8` | en | 0.074→0.086 | 0.598→0.537 | 0.245→0.255 |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 0.386→0.255 ~ | 0.139→0.234 ⚠ | 0.732→0.583 ⚠ |
| `qwen3-asr-0.6b@onnx-int8` | ru | 0.300→0.161 ⚠ | 0.311→0.386 | 0.633→0.431 ⚠ |
| `t-one@onnx` | mixed | 0.333→0.234 ~ | 0.009→0.018 | 0.815→0.727 ⚠ |
| `t-one@onnx` | ru | 0.202→0.102 ~ | 0.344→0.492 ⚠ | 0.567→0.411 ⚠ |
| `whisper-large-v3-turbo@onnx-int8` | en | 0.053→0.055 | 0.608→0.520 ~ | 0.206→0.223 |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 0.192→0.081 ⚠ | 0.306→0.486 ⚠ | 0.546→0.292 ⚠ |
| `whisper-large-v3-turbo@onnx-int8` | ru | 0.109→0.040 ~ | 0.578→0.742 ⚠ | 0.300→0.169 ⚠ |
| `whisper-medium@onnx-int8` | en | 0.061→0.054 | 0.627→0.625 | 0.226→0.245 |
| `whisper-medium@onnx-int8` | mixed | 0.226→0.101 ⚠ | 0.250→0.405 ⚠ | 0.565→0.403 ⚠ |
| `whisper-medium@onnx-int8` | ru | 0.166→0.060 ⚠ | 0.433→0.683 ⚠ | 0.411→0.239 ⚠ |
| `whisper-small@onnx-int8` | en | 0.077→0.069 | 0.510→0.544 | 0.255→0.262 |
| `whisper-small@onnx-int8` | mixed | 0.306→0.150 ⚠ | 0.185→0.343 ⚠ | 0.630→0.474 ⚠ |
| `whisper-small@onnx-int8` | ru | 0.187→0.086 ~ | 0.400→0.489 ~ | 0.444→0.336 ⚠ |

Flagged gaps: 32 of 154 pooled metrics, 37 of 87 per-language metrics; 30 more exceed the tolerance within the real set's sampling noise.
- `gigaam-v3-e2e-ctc@onnx-int8` term_error_rate: real 0.631, sim 0.547 (gap -0.085, tol 0.08, 2·SE 0.069, n=198; mined-on rows 0.538, sampler gap +0.009)
- `gigaam-v3-e2e-ctc@onnx-int8` latin_kept_rate: real 0.593, sim 0.722 (gap +0.129, tol 0.1, 2·SE 0.091, n=116; mined-on rows 0.698, sampler gap +0.024)
- `gigaam-v3-e2e-ctc@onnx-int8` term_exact_rate@ru: real 0.433, sim 0.556 (gap +0.122, tol 0.08, 2·SE 0.104, n=90; mined-on rows 0.515, sampler gap +0.040)
- `gigaam-v3-e2e-ctc@onnx-int8` term_error_rate@ru: real 0.489, sim 0.367 (gap -0.122, tol 0.08, 2·SE 0.105, n=90; mined-on rows 0.364, sampler gap +0.003)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_exact_rate: real 0.263, sim 0.351 (gap +0.088, tol 0.08, 2·SE 0.063, n=198; mined-on rows 0.326, sampler gap +0.025)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_error_rate: real 0.631, sim 0.509 (gap -0.122, tol 0.08, 2·SE 0.069, n=198; mined-on rows 0.516, sampler gap -0.007)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_exact_rate@ru: real 0.433, sim 0.592 (gap +0.158, tol 0.08, 2·SE 0.104, n=90; mined-on rows 0.526, sampler gap +0.066)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_error_rate@ru: real 0.456, sim 0.281 (gap -0.175, tol 0.08, 2·SE 0.105, n=90; mined-on rows 0.326, sampler gap -0.045)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` terminal_kept_rate: real 0.181, sim 0.286 (gap +0.105, tol 0.1, 2·SE 0.061, n=160; mined-on rows 0.283, sampler gap +0.003)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` digits_kept_rate: real 0.000, sim 0.190 (gap +0.190, tol 0.1, 2·SE 0.005, n=18; mined-on rows 0.127, sampler gap +0.063)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_error_rate@mixed: real 0.704, sim 0.586 (gap -0.118, tol 0.08, 2·SE 0.088, n=108; mined-on rows 0.550, sampler gap +0.035)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate@ru: real 0.444, sim 0.594 (gap +0.150, tol 0.08, 2·SE 0.105, n=90; mined-on rows 0.510, sampler gap +0.084)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_error_rate@ru: real 0.478, sim 0.328 (gap -0.150, tol 0.08, 2·SE 0.105, n=90; mined-on rows 0.348, sampler gap -0.021)
- `parakeet-tdt-0.6b-v3@onnx-int8` wer: real 0.195, sim 0.124 (gap -0.071, tol 0.05, 2·SE 0.063, n=1966; mined-on rows 0.133, sampler gap -0.009)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_error_rate: real 0.413, sim 0.330 (gap -0.083, tol 0.08, 2·SE 0.057, n=300; mined-on rows 0.343, sampler gap -0.013)
- `parakeet-tdt-0.6b-v3@onnx-int8` digits_kept_rate: real 0.500, sim 0.886 (gap +0.386, tol 0.1, 2·SE 0.236, n=18; mined-on rows 0.868, sampler gap +0.018)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_exact_rate@mixed: real 0.194, sim 0.319 (gap +0.125, tol 0.08, 2·SE 0.076, n=108; mined-on rows 0.289, sampler gap +0.030)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_error_rate@mixed: real 0.620, sim 0.491 (gap -0.130, tol 0.08, 2·SE 0.093, n=108; mined-on rows 0.479, sampler gap +0.012)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_exact_rate@ru: real 0.489, sim 0.639 (gap +0.150, tol 0.08, 2·SE 0.105, n=90; mined-on rows 0.566, sampler gap +0.073)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_error_rate@ru: real 0.367, sim 0.225 (gap -0.142, tol 0.08, 2·SE 0.102, n=90; mined-on rows 0.264, sampler gap -0.039)
- `parakeet-ultra-0.6b@onnx-int8` wer: real 0.168, sim 0.104 (gap -0.064, tol 0.05, 2·SE 0.059, n=1966; mined-on rows 0.120, sampler gap -0.016)
- `parakeet-ultra-0.6b@onnx-int8` wer_style_aware: real 0.132, sim 0.077 (gap -0.055, tol 0.03, 2·SE 0.053, n=1966; mined-on rows 0.082, sampler gap -0.006)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@mixed: real 0.157, sim 0.287 (gap +0.130, tol 0.08, 2·SE 0.070, n=108; mined-on rows 0.317, sampler gap -0.029)
- `parakeet-ultra-0.6b@onnx-int8` term_error_rate@mixed: real 0.639, sim 0.514 (gap -0.125, tol 0.08, 2·SE 0.092, n=108; mined-on rows 0.448, sampler gap +0.066)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@ru: real 0.556, sim 0.714 (gap +0.158, tol 0.08, 2·SE 0.105, n=90; mined-on rows 0.595, sampler gap +0.119)
- `parakeet-ultra-0.6b@onnx-int8` term_error_rate@ru: real 0.311, sim 0.172 (gap -0.139, tol 0.08, 2·SE 0.098, n=90; mined-on rows 0.234, sampler gap -0.062)
- `qwen3-asr-0.6b@onnx-int8` wer: real 0.285, sim 0.205 (gap -0.081, tol 0.05, 2·SE 0.071, n=1966; mined-on rows 0.221, sampler gap -0.017)
- `qwen3-asr-0.6b@onnx-int8` wer_style_aware: real 0.237, sim 0.160 (gap -0.077, tol 0.03, 2·SE 0.067, n=1966; mined-on rows 0.166, sampler gap -0.006)
- `qwen3-asr-0.6b@onnx-int8` term_error_rate: real 0.537, sim 0.426 (gap -0.111, tol 0.08, 2·SE 0.058, n=300; mined-on rows 0.450, sampler gap -0.024)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate@mixed: real 0.139, sim 0.234 (gap +0.095, tol 0.08, 2·SE 0.067, n=108; mined-on rows 0.201, sampler gap +0.033)
- `qwen3-asr-0.6b@onnx-int8` term_error_rate@mixed: real 0.732, sim 0.583 (gap -0.148, tol 0.08, 2·SE 0.085, n=108; mined-on rows 0.577, sampler gap +0.006)
- `qwen3-asr-0.6b@onnx-int8` wer_style_aware@ru: real 0.300, sim 0.161 (gap -0.139, tol 0.03, 2·SE 0.125, n=604; mined-on rows 0.170, sampler gap -0.009)
- `qwen3-asr-0.6b@onnx-int8` term_error_rate@ru: real 0.633, sim 0.431 (gap -0.203, tol 0.08, 2·SE 0.102, n=90; mined-on rows 0.488, sampler gap -0.057)
- `t-one@onnx` wer: real 0.315, sim 0.223 (gap -0.092, tol 0.05, 2·SE 0.090, n=1198; mined-on rows 0.211, sampler gap +0.012)
- `t-one@onnx` wer_style_aware: real 0.267, sim 0.167 (gap -0.100, tol 0.03, 2·SE 0.086, n=1198; mined-on rows 0.160, sampler gap +0.008)
- `t-one@onnx` term_error_rate: real 0.702, sim 0.583 (gap -0.119, tol 0.08, 2·SE 0.065, n=198; mined-on rows 0.586, sampler gap -0.002)
- `t-one@onnx` term_error_rate@mixed: real 0.815, sim 0.727 (gap -0.088, tol 0.08, 2·SE 0.075, n=108; mined-on rows 0.734, sampler gap -0.007)
- `t-one@onnx` term_exact_rate@ru: real 0.344, sim 0.492 (gap +0.147, tol 0.08, 2·SE 0.100, n=90; mined-on rows 0.428, sampler gap +0.064)
- `t-one@onnx` term_error_rate@ru: real 0.567, sim 0.411 (gap -0.156, tol 0.08, 2·SE 0.104, n=90; mined-on rows 0.398, sampler gap +0.013)
- `whisper-large-v3-turbo@onnx-int8` wer: real 0.148, sim 0.084 (gap -0.064, tol 0.05, 2·SE 0.056, n=1966; mined-on rows 0.098, sampler gap -0.014)
- `whisper-large-v3-turbo@onnx-int8` wer_style_aware: real 0.112, sim 0.058 (gap -0.054, tol 0.03, 2·SE 0.050, n=1966; mined-on rows 0.066, sampler gap -0.008)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate: real 0.490, sim 0.574 (gap +0.084, tol 0.08, 2·SE 0.058, n=300; mined-on rows 0.535, sampler gap +0.040)
- `whisper-large-v3-turbo@onnx-int8` term_error_rate: real 0.357, sim 0.232 (gap -0.125, tol 0.08, 2·SE 0.055, n=300; mined-on rows 0.252, sampler gap -0.021)
- `whisper-large-v3-turbo@onnx-int8` terminal_kept_rate: real 0.838, sim 0.980 (gap +0.142, tol 0.1, 2·SE 0.058, n=160; mined-on rows 0.980, sampler gap -0.001)
- `whisper-large-v3-turbo@onnx-int8` wer_style_aware@mixed: real 0.192, sim 0.081 (gap -0.111, tol 0.03, 2·SE 0.109, n=594; mined-on rows 0.089, sampler gap -0.008)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate@mixed: real 0.306, sim 0.486 (gap +0.180, tol 0.08, 2·SE 0.089, n=108; mined-on rows 0.431, sampler gap +0.055)
- `whisper-large-v3-turbo@onnx-int8` term_error_rate@mixed: real 0.546, sim 0.292 (gap -0.255, tol 0.08, 2·SE 0.096, n=108; mined-on rows 0.330, sampler gap -0.038)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate@ru: real 0.578, sim 0.742 (gap +0.164, tol 0.08, 2·SE 0.104, n=90; mined-on rows 0.662, sampler gap +0.080)
- `whisper-large-v3-turbo@onnx-int8` term_error_rate@ru: real 0.300, sim 0.169 (gap -0.131, tol 0.08, 2·SE 0.097, n=90; mined-on rows 0.191, sampler gap -0.022)
- `whisper-medium@onnx-int8` wer: real 0.171, sim 0.088 (gap -0.083, tol 0.05, 2·SE 0.060, n=1966; mined-on rows 0.112, sampler gap -0.024)
- `whisper-medium@onnx-int8` wer_style_aware: real 0.143, sim 0.070 (gap -0.073, tol 0.03, 2·SE 0.055, n=1966; mined-on rows 0.073, sampler gap -0.003)
- `whisper-medium@onnx-int8` term_exact_rate: real 0.433, sim 0.563 (gap +0.130, tol 0.08, 2·SE 0.057, n=300; mined-on rows 0.512, sampler gap +0.051)
- `whisper-medium@onnx-int8` term_error_rate: real 0.403, sim 0.300 (gap -0.103, tol 0.08, 2·SE 0.057, n=300; mined-on rows 0.273, sampler gap +0.027)
- `whisper-medium@onnx-int8` terminal_kept_rate: real 0.863, sim 0.981 (gap +0.119, tol 0.1, 2·SE 0.054, n=160; mined-on rows 0.983, sampler gap -0.002)
- `whisper-medium@onnx-int8` wer_style_aware@mixed: real 0.226, sim 0.101 (gap -0.124, tol 0.03, 2·SE 0.116, n=594; mined-on rows 0.098, sampler gap +0.004)
- `whisper-medium@onnx-int8` term_exact_rate@mixed: real 0.250, sim 0.405 (gap +0.155, tol 0.08, 2·SE 0.083, n=108; mined-on rows 0.449, sampler gap -0.044)
- `whisper-medium@onnx-int8` term_error_rate@mixed: real 0.565, sim 0.403 (gap -0.162, tol 0.08, 2·SE 0.095, n=108; mined-on rows 0.358, sampler gap +0.045)
- `whisper-medium@onnx-int8` wer_style_aware@ru: real 0.166, sim 0.060 (gap -0.105, tol 0.03, 2·SE 0.101, n=604; mined-on rows 0.062, sampler gap -0.002)
- `whisper-medium@onnx-int8` term_exact_rate@ru: real 0.433, sim 0.683 (gap +0.250, tol 0.08, 2·SE 0.104, n=90; mined-on rows 0.532, sampler gap +0.151)
- `whisper-medium@onnx-int8` term_error_rate@ru: real 0.411, sim 0.239 (gap -0.172, tol 0.08, 2·SE 0.104, n=90; mined-on rows 0.226, sampler gap +0.013)
- `whisper-small@onnx-int8` wer: real 0.212, sim 0.126 (gap -0.086, tol 0.05, 2·SE 0.065, n=1966; mined-on rows 0.139, sampler gap -0.013)
- `whisper-small@onnx-int8` wer_style_aware: real 0.180, sim 0.099 (gap -0.082, tol 0.03, 2·SE 0.061, n=1966; mined-on rows 0.102, sampler gap -0.004)
- `whisper-small@onnx-int8` term_exact_rate: real 0.360, sim 0.455 (gap +0.095, tol 0.08, 2·SE 0.055, n=300; mined-on rows 0.417, sampler gap +0.038)
- `whisper-small@onnx-int8` term_error_rate: real 0.447, sim 0.361 (gap -0.086, tol 0.08, 2·SE 0.057, n=300; mined-on rows 0.367, sampler gap -0.007)
- `whisper-small@onnx-int8` terminal_kept_rate: real 0.856, sim 0.984 (gap +0.128, tol 0.1, 2·SE 0.055, n=160; mined-on rows 0.982, sampler gap +0.002)
- `whisper-small@onnx-int8` wer_style_aware@mixed: real 0.306, sim 0.150 (gap -0.156, tol 0.03, 2·SE 0.128, n=594; mined-on rows 0.139, sampler gap +0.012)
- `whisper-small@onnx-int8` term_exact_rate@mixed: real 0.185, sim 0.343 (gap +0.157, tol 0.08, 2·SE 0.075, n=108; mined-on rows 0.314, sampler gap +0.029)
- `whisper-small@onnx-int8` term_error_rate@mixed: real 0.630, sim 0.474 (gap -0.155, tol 0.08, 2·SE 0.093, n=108; mined-on rows 0.465, sampler gap +0.009)
- `whisper-small@onnx-int8` term_error_rate@ru: real 0.444, sim 0.336 (gap -0.108, tol 0.08, 2·SE 0.105, n=90; mined-on rows 0.350, sampler gap -0.014)

Simulated held-out vs the rows the tables were mined from (different texts, so this mixes sampler error with the difference in text mix; indicative only): 8 metric(s) outside tolerance.
- `gigaam-v3-e2e-ctc@onnx-int8` punct_any_rate: mined 0.886, sim 0.804 (gap -0.082)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` wer_style_aware@mixed: mined 0.169, sim 0.207 (gap +0.037)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate@ru: mined 0.510, sim 0.594 (gap +0.084)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_exact_rate: mined 0.424, sim 0.507 (gap +0.083)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_exact_rate@en: mined 0.424, sim 0.507 (gap +0.083)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@ru: mined 0.595, sim 0.714 (gap +0.119)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate@en: mined 0.442, sim 0.537 (gap +0.095)
- `whisper-medium@onnx-int8` term_exact_rate@ru: mined 0.532, sim 0.683 (gap +0.151)
