# Noise sampler calibration

Out-of-sample: tables mined on 6190 rows, simulated and compared on 2688 held-out rows (by probe_id), 4 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 0.089→0.064 | 0.068→0.048 | 0.028→0.018 | 0.538→0.531 | 0.254→0.231 | 0.000→0.026 | 0.954→0.954 | 0.988→0.994 | 0.965→0.977 | (1.000→0.941) | – | 1.000→0.999 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-ctc@onnx-int8` | 0.134→0.128 | 0.113→0.111 | 0.065→0.056 | 0.327→0.357 | 0.510→0.549 | 0.000→0.001 | 0.995→0.999 | 0.863→0.807 | 0.190→0.288 | 0.882→0.853 | (0.000→0.750) | 0.659→0.739 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.138→0.129 | 0.116→0.108 | 0.067→0.062 | 0.322→0.352 | 0.524→0.531 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→0.999 | 0.882→0.912 | (0.000→1.000) | 0.675→0.685 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.185→0.162 | 0.130→0.129 | 0.097→0.085 | 0.339→0.378 | 0.439→0.425 | 0.003→0.005 | 0.993→0.990 | 0.664→0.598 | 0.291→0.300 | 0.040→0.204 ⚠ | (0.000→1.000) | 0.946→0.960 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 0.164→0.139 | 0.109→0.094 | 0.079→0.074 | 0.450→0.431 | 0.304→0.269 | 0.000→0.000 | 1.000→1.000 | 0.253→0.238 | 0.011→0.032 | 0.000→0.000 | – | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.130→0.111 | 0.092→0.088 | 0.053→0.042 | 0.444→0.458 | 0.353→0.355 | 0.000→0.001 | 1.000→0.998 | 1.000→0.999 | 1.000→0.998 | 0.840→0.876 | (1.000→0.750) | 0.982→0.982 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-ultra-0.6b@onnx-int8` | 0.114→0.098 | 0.080→0.074 | 0.050→0.037 | 0.453→0.488 | 0.331→0.318 | 0.000→0.002 | 0.997→0.997 | 1.000→1.000 | 1.000→0.998 | 0.808→0.943 ~ | (1.000→1.000) | 0.980→0.976 | 0.000→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.219→0.193 | 0.167→0.159 | 0.106→0.109 | 0.331→0.355 | 0.465→0.459 | 0.000→0.012 | 1.000→0.984 | 1.000→1.000 | 1.000→0.997 | 0.038→0.097 | (0.000→0.000) | 0.968→0.963 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.205→0.193 | 0.161→0.143 | 0.129→0.138 | 0.193→0.224 | 0.602→0.581 | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | (0.000→0.000) | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | 0.094→0.085 | 0.068→0.064 | 0.036→0.032 | 0.551→0.557 | 0.255→0.263 | 0.000→0.003 | 0.993→0.996 | 0.997→0.989 | 0.983→0.980 | 1.000→0.963 | (1.000→1.000) | 0.981→0.985 | 0.000→0.000 | 0.000→0.000 |
| `whisper-medium@onnx-int8` | 0.105→0.094 | 0.074→0.073 | 0.037→0.039 | 0.547→0.532 | 0.282→0.289 | 0.007→0.008 | 0.960→0.983 | 0.993→0.978 | 0.980→0.973 | 1.000→0.967 | (1.000→1.000) | 0.988→0.986 | 0.000→0.000 | 0.000→0.000 |
| `whisper-small@onnx-int8` | 0.130→0.119 | 0.098→0.096 | 0.049→0.054 | 0.416→0.438 | 0.379→0.391 | 0.000→0.008 | 0.990→0.987 | 0.993→0.994 | 0.983→0.978 | 1.000→0.868 ⚠ | (1.000→1.000) | 0.984→0.975 | 0.000→0.000 | 0.000→0.000 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 0.068→0.048 | 0.538→0.531 | 0.254→0.231 |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 0.152→0.138 | 0.158→0.192 | 0.656→0.683 |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 0.056→0.073 | 0.568→0.593 | 0.302→0.359 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 0.158→0.130 | 0.158→0.208 | 0.689→0.634 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 0.056→0.077 | 0.556→0.556 | 0.290→0.385 ⚠ |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 0.121→0.113 | 0.380→0.385 | 0.363→0.320 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 0.155→0.158 | 0.178→0.236 | 0.556→0.555 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 0.104→0.105 | 0.527→0.574 | 0.349→0.345 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 0.109→0.094 | 0.450→0.431 | 0.304→0.269 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 0.075→0.070 | 0.520→0.490 | 0.275→0.282 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 0.123→0.115 | 0.286→0.323 | 0.498→0.472 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 0.066→0.070 | 0.592→0.620 | 0.225→0.260 |
| `parakeet-ultra-0.6b@onnx-int8` | en | 0.064→0.057 | 0.509→0.491 | 0.269→0.266 |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 0.105→0.103 | 0.328→0.345 | 0.431→0.443 |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 0.061→0.052 | 0.574→0.689 ⚠ | 0.248→0.194 |
| `qwen3-asr-0.6b@onnx-int8` | en | 0.085→0.077 | 0.468→0.475 | 0.304→0.273 |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 0.235→0.218 | 0.187→0.214 | 0.569→0.569 |
| `qwen3-asr-0.6b@onnx-int8` | ru | 0.165→0.169 | 0.396→0.435 | 0.479→0.490 |
| `t-one@onnx` | mixed | 0.203→0.176 | 0.042→0.023 | 0.734→0.713 |
| `t-one@onnx` | ru | 0.100→0.095 | 0.408→0.512 ⚠ | 0.414→0.394 |
| `whisper-large-v3-turbo@onnx-int8` | en | 0.066→0.050 | 0.544→0.586 | 0.257→0.178 |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 0.086→0.082 | 0.440→0.456 | 0.315→0.351 |
| `whisper-large-v3-turbo@onnx-int8` | ru | 0.043→0.053 | 0.716→0.672 | 0.166→0.225 |
| `whisper-medium@onnx-int8` | en | 0.053→0.055 | 0.568→0.559 | 0.247→0.228 |
| `whisper-medium@onnx-int8` | mixed | 0.101→0.090 | 0.485→0.466 | 0.333→0.373 |
| `whisper-medium@onnx-int8` | ru | 0.053→0.066 | 0.622→0.608 | 0.233→0.214 |
| `whisper-small@onnx-int8` | en | 0.077→0.063 | 0.444→0.531 ⚠ | 0.333→0.243 ⚠ |
| `whisper-small@onnx-int8` | mixed | 0.122→0.127 | 0.340→0.299 | 0.440→0.516 |
| `whisper-small@onnx-int8` | ru | 0.086→0.091 | 0.497→0.543 | 0.337→0.364 |

Flagged gaps: 2 of 155 pooled metrics, 5 of 87 per-language metrics; 1 more exceed the tolerance within the real set's sampling noise.
- `gigaam-v3-e2e-rnnt@onnx-int8` term_error_rate@ru: real 0.290, sim 0.385 (gap +0.095, tol 0.08, 2·SE 0.070, n=169; mined-on rows 0.341, sampler gap +0.044)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` digits_kept_rate: real 0.040, sim 0.204 (gap +0.164, tol 0.1, 2·SE 0.075, n=27; mined-on rows 0.167, sampler gap +0.037)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@ru: real 0.574, sim 0.689 (gap +0.115, tol 0.08, 2·SE 0.076, n=169; mined-on rows 0.603, sampler gap +0.086)
- `t-one@onnx` term_exact_rate@ru: real 0.408, sim 0.512 (gap +0.103, tol 0.08, 2·SE 0.076, n=169; mined-on rows 0.435, sampler gap +0.076)
- `whisper-small@onnx-int8` digits_kept_rate: real 1.000, sim 0.868 (gap -0.132, tol 0.1, 2·SE 0.004, n=27; mined-on rows 0.897, sampler gap -0.029)
- `whisper-small@onnx-int8` term_exact_rate@en: real 0.444, sim 0.531 (gap +0.086, tol 0.08, 2·SE 0.076, n=171; mined-on rows 0.503, sampler gap +0.028)
- `whisper-small@onnx-int8` term_error_rate@en: real 0.333, sim 0.243 (gap -0.091, tol 0.08, 2·SE 0.072, n=171; mined-on rows 0.247, sampler gap -0.005)

Simulated held-out vs the rows the tables were mined from (different texts, so this mixes sampler error with the difference in text mix; indicative only): 8 metric(s) outside tolerance.
- `gigaam-v3-e2e-ctc@onnx-int8` punct_any_rate: mined 0.897, sim 0.807 (gap -0.089)
- `gigaam-v3-e2e-ctc@onnx-int8` wer_style_aware@mixed: mined 0.174, sim 0.138 (gap -0.035)
- `gigaam-v3-e2e-ctc@onnx-int8` term_exact_rate@ru: mined 0.494, sim 0.593 (gap +0.099)
- `gigaam-v3-e2e-rnnt@onnx-int8` wer_style_aware@mixed: mined 0.162, sim 0.130 (gap -0.032)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@ru: mined 0.603, sim 0.689 (gap +0.086)
- `t-one@onnx` wer_style_aware@mixed: mined 0.210, sim 0.176 (gap -0.034)
- `whisper-medium@onnx-int8` term_exact_rate@ru: mined 0.493, sim 0.608 (gap +0.116)
- `whisper-small@onnx-int8` term_exact_rate@ru: mined 0.457, sim 0.543 (gap +0.086)
