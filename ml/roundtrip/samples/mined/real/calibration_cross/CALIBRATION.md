# Noise sampler calibration

Cross-set: tables mined on other rows (.cache/ml/roundtrip/mined/cross), simulated and compared on these 1546 rows (different sentences), 4 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 0.111→0.070 | 0.082→0.053 | 0.036→0.027 | 0.578→0.615 | 0.275→0.201 | 0.037→0.018 | 0.944→0.954 | 0.982→0.968 | 0.963→0.963 | (1.000→1.000) | – | 0.999→0.999 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-ctc@onnx-int8` | 0.212→0.195 | 0.186→0.171 | 0.096→0.111 | 0.258→0.231 | 0.631→0.658 | 0.000→0.000 | 1.000→1.000 | 0.859→0.816 | 0.207→0.356 ⚠ | 0.833→1.000 ~ | – | 0.593→0.311 ⚠ | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.211→0.180 | 0.184→0.156 | 0.096→0.105 | 0.263→0.313 | 0.631→0.595 | 0.000→0.000 | 1.000→1.000 | 1.000→0.998 | 1.000→0.981 | 0.917→1.000 | – | 0.542→0.269 ⚠ | 0.000→0.000 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.255→0.211 | 0.207→0.187 | 0.135→0.116 | 0.303→0.316 | 0.503→0.537 | 0.006→0.044 | 0.994→0.944 | 0.575→0.631 | 0.181→0.150 | 0.000→0.641 ⚠ | – | 0.970→0.963 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.195→0.141 ~ | 0.146→0.127 | 0.098→0.065 | 0.407→0.466 | 0.413→0.435 | 0.013→0.000 | 0.988→1.000 | 1.000→1.000 | 1.000→0.998 | 0.500→1.000 ⚠ | – | 0.978→0.976 | 0.006→0.000 | 0.000→0.000 |
| `parakeet-ultra-0.6b@onnx-int8` | 0.168→0.137 | 0.132→0.116 | 0.079→0.062 | 0.433→0.514 ⚠ | 0.390→0.371 | 0.006→0.000 | 0.988→1.000 | 1.000→1.000 | 1.000→0.997 | 0.722→1.000 ⚠ | – | 0.977→0.972 | 0.006→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.285→0.218 ~ | 0.237→0.189 ~ | 0.147→0.143 | 0.347→0.368 | 0.537→0.517 | 0.006→0.000 | 0.994→1.000 | 0.981→1.000 | 0.981→0.994 | 0.071→0.000 | – | 0.980→0.967 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.315→0.292 | 0.267→0.242 | 0.177→0.179 | 0.162→0.207 | 0.702→0.650 | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | – | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | 0.148→0.116 | 0.112→0.095 | 0.072→0.057 | 0.490→0.533 | 0.357→0.312 | 0.013→0.020 | 0.988→0.977 | 0.944→0.938 | 0.838→0.847 | 1.000→1.000 | – | 0.986→0.981 | 0.000→0.000 | 0.000→0.000 |
| `whisper-medium@onnx-int8` | 0.171→0.131 | 0.143→0.121 | 0.076→0.065 | 0.433→0.522 ⚠ | 0.403→0.378 | 0.025→0.003 | 0.969→0.986 | 0.950→0.953 | 0.863→0.880 | 0.889→1.000 ~ | – | 0.994→0.992 | 0.000→0.000 | 0.000→0.000 |
| `whisper-small@onnx-int8` | 0.212→0.174 | 0.180→0.160 | 0.094→0.097 | 0.360→0.399 | 0.447→0.466 | 0.025→0.033 | 0.969→0.948 | 0.963→0.945 | 0.856→0.841 | 0.944→1.000 | – | 0.980→0.983 | 0.000→0.000 | 0.000→0.000 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 0.082→0.053 | 0.578→0.615 | 0.275→0.201 |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 0.251→0.230 | 0.111→0.060 | 0.750→0.736 |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 0.122→0.113 | 0.433→0.436 | 0.489→0.564 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 0.261→0.226 ~ | 0.120→0.079 | 0.778→0.752 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 0.108→0.086 | 0.433→0.594 ⚠ | 0.456→0.406 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 0.121→0.134 | 0.382→0.422 | 0.314→0.350 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 0.311→0.285 | 0.111→0.056 | 0.704→0.766 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 0.215→0.157 ~ | 0.444→0.508 | 0.478→0.475 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 0.073→0.045 | 0.559→0.743 ⚠ | 0.235→0.145 ⚠ |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 0.244→0.228 | 0.194→0.194 | 0.620→0.637 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 0.144→0.131 | 0.489→0.478 | 0.367→0.522 ⚠ |
| `parakeet-ultra-0.6b@onnx-int8` | en | 0.051→0.047 | 0.618→0.733 ⚠ | 0.196→0.135 |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 0.251→0.208 ~ | 0.157→0.250 ⚠ | 0.639→0.556 ~ |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 0.117→0.113 | 0.556→0.583 | 0.311→0.417 ⚠ |
| `qwen3-asr-0.6b@onnx-int8` | en | 0.074→0.072 | 0.598→0.676 | 0.245→0.162 ~ |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 0.386→0.338 ~ | 0.139→0.095 | 0.732→0.738 |
| `qwen3-asr-0.6b@onnx-int8` | ru | 0.300→0.193 ~ | 0.311→0.347 | 0.633→0.653 |
| `t-one@onnx` | mixed | 0.333→0.332 | 0.009→0.009 | 0.815→0.799 |
| `t-one@onnx` | ru | 0.202→0.154 ~ | 0.344→0.444 ~ | 0.567→0.472 ~ |
| `whisper-large-v3-turbo@onnx-int8` | en | 0.053→0.040 | 0.608→0.676 | 0.206→0.110 ⚠ |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 0.192→0.141 ~ | 0.306→0.380 | 0.546→0.396 ⚠ |
| `whisper-large-v3-turbo@onnx-int8` | ru | 0.109→0.119 | 0.578→0.556 | 0.300→0.439 ⚠ |
| `whisper-medium@onnx-int8` | en | 0.061→0.054 | 0.627→0.681 | 0.226→0.181 |
| `whisper-medium@onnx-int8` | mixed | 0.226→0.215 | 0.250→0.312 | 0.565→0.537 |
| `whisper-medium@onnx-int8` | ru | 0.166→0.114 ~ | 0.433→0.592 ⚠ | 0.411→0.408 |
| `whisper-small@onnx-int8` | en | 0.077→0.067 | 0.510→0.598 ~ | 0.255→0.199 |
| `whisper-small@onnx-int8` | mixed | 0.306→0.285 | 0.185→0.188 | 0.630→0.632 |
| `whisper-small@onnx-int8` | ru | 0.187→0.156 ~ | 0.400→0.428 | 0.444→0.569 ⚠ |

Flagged gaps: 8 of 142 pooled metrics, 12 of 84 per-language metrics; 19 more exceed the tolerance within the real set's sampling noise.
- `gigaam-v3-e2e-ctc@onnx-int8` terminal_kept_rate: real 0.207, sim 0.356 (gap +0.149, tol 0.1, 2·SE 0.079, n=106; mined-on rows 0.333, sampler gap +0.023)
- `gigaam-v3-e2e-ctc@onnx-int8` latin_kept_rate: real 0.593, sim 0.311 (gap -0.281, tol 0.1, 2·SE 0.091, n=116; mined-on rows 0.263, sampler gap +0.048)
- `gigaam-v3-e2e-rnnt@onnx-int8` latin_kept_rate: real 0.542, sim 0.269 (gap -0.273, tol 0.1, 2·SE 0.093, n=116; mined-on rows 0.294, sampler gap -0.025)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_exact_rate@ru: real 0.433, sim 0.594 (gap +0.161, tol 0.08, 2·SE 0.104, n=90; mined-on rows 0.471, sampler gap +0.124)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` digits_kept_rate: real 0.000, sim 0.641 (gap +0.641, tol 0.1, 2·SE 0.005, n=18; mined-on rows 0.500, sampler gap +0.141)
- `parakeet-tdt-0.6b-v3@onnx-int8` digits_kept_rate: real 0.500, sim 1.000 (gap +0.500, tol 0.1, 2·SE 0.236, n=18; mined-on rows 1.000, sampler gap +0.000)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_exact_rate@en: real 0.559, sim 0.743 (gap +0.184, tol 0.08, 2·SE 0.098, n=102; mined-on rows 0.713, sampler gap +0.030)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_error_rate@en: real 0.235, sim 0.145 (gap -0.091, tol 0.08, 2·SE 0.084, n=102; mined-on rows 0.149, sampler gap -0.005)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_error_rate@ru: real 0.367, sim 0.522 (gap +0.155, tol 0.08, 2·SE 0.102, n=90; mined-on rows 0.500, sampler gap +0.022)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate: real 0.433, sim 0.514 (gap +0.081, tol 0.08, 2·SE 0.057, n=300; mined-on rows 0.489, sampler gap +0.025)
- `parakeet-ultra-0.6b@onnx-int8` digits_kept_rate: real 0.722, sim 1.000 (gap +0.278, tol 0.1, 2·SE 0.211, n=18; mined-on rows 1.000, sampler gap +0.000)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@en: real 0.618, sim 0.733 (gap +0.115, tol 0.08, 2·SE 0.096, n=102; mined-on rows 0.690, sampler gap +0.043)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@mixed: real 0.157, sim 0.250 (gap +0.093, tol 0.08, 2·SE 0.070, n=108; mined-on rows 0.221, sampler gap +0.029)
- `parakeet-ultra-0.6b@onnx-int8` term_error_rate@ru: real 0.311, sim 0.417 (gap +0.106, tol 0.08, 2·SE 0.098, n=90; mined-on rows 0.456, sampler gap -0.039)
- `whisper-large-v3-turbo@onnx-int8` term_error_rate@en: real 0.206, sim 0.110 (gap -0.096, tol 0.08, 2·SE 0.080, n=102; mined-on rows 0.126, sampler gap -0.016)
- `whisper-large-v3-turbo@onnx-int8` term_error_rate@mixed: real 0.546, sim 0.396 (gap -0.150, tol 0.08, 2·SE 0.096, n=108; mined-on rows 0.426, sampler gap -0.031)
- `whisper-large-v3-turbo@onnx-int8` term_error_rate@ru: real 0.300, sim 0.439 (gap +0.139, tol 0.08, 2·SE 0.097, n=90; mined-on rows 0.397, sampler gap +0.042)
- `whisper-medium@onnx-int8` term_exact_rate: real 0.433, sim 0.522 (gap +0.088, tol 0.08, 2·SE 0.057, n=300; mined-on rows 0.502, sampler gap +0.019)
- `whisper-medium@onnx-int8` term_exact_rate@ru: real 0.433, sim 0.592 (gap +0.158, tol 0.08, 2·SE 0.104, n=90; mined-on rows 0.485, sampler gap +0.106)
- `whisper-small@onnx-int8` term_error_rate@ru: real 0.444, sim 0.569 (gap +0.125, tol 0.08, 2·SE 0.105, n=90; mined-on rows 0.529, sampler gap +0.040)

Simulated held-out vs the rows the tables were mined from (different texts, so this mixes sampler error with the difference in text mix; indicative only): 21 metric(s) outside tolerance.
- `gigaam-v3-e2e-ctc@onnx-int8` punct_any_rate: mined 0.967, sim 0.816 (gap -0.151)
- `gigaam-v3-e2e-rnnt@onnx-int8` wer_style_aware@mixed: mined 0.266, sim 0.226 (gap -0.040)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_exact_rate@ru: mined 0.471, sim 0.594 (gap +0.124)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` punct_any_rate: mined 0.790, sim 0.631 (gap -0.159)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` digits_kept_rate: mined 0.500, sim 0.641 (gap +0.141)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate@en: mined 0.333, sim 0.422 (gap +0.088)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` wer_style_aware@ru: mined 0.198, sim 0.157 (gap -0.041)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate@ru: mined 0.412, sim 0.508 (gap +0.097)
- `parakeet-tdt-0.6b-v3@onnx-int8` wer_style_aware@mixed: mined 0.263, sim 0.228 (gap -0.035)
- `parakeet-tdt-0.6b-v3@onnx-int8` wer_style_aware@ru: mined 0.166, sim 0.131 (gap -0.035)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@ru: mined 0.500, sim 0.583 (gap +0.083)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate@en: mined 0.563, sim 0.676 (gap +0.113)
- `qwen3-asr-0.6b@onnx-int8` wer_style_aware@mixed: mined 0.386, sim 0.338 (gap -0.048)
- `qwen3-asr-0.6b@onnx-int8` wer_style_aware@ru: mined 0.255, sim 0.193 (gap -0.063)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate@ru: mined 0.235, sim 0.347 (gap +0.112)
- `whisper-large-v3-turbo@onnx-int8` wer_style_aware@mixed: mined 0.181, sim 0.141 (gap -0.041)
- `whisper-medium@onnx-int8` wer_style_aware@mixed: mined 0.254, sim 0.215 (gap -0.039)
- `whisper-medium@onnx-int8` wer_style_aware@ru: mined 0.155, sim 0.114 (gap -0.041)
- `whisper-medium@onnx-int8` term_exact_rate@ru: mined 0.485, sim 0.592 (gap +0.106)
- `whisper-small@onnx-int8` wer_style_aware@mixed: mined 0.322, sim 0.285 (gap -0.037)
- `whisper-small@onnx-int8` wer_style_aware@ru: mined 0.198, sim 0.156 (gap -0.043)
