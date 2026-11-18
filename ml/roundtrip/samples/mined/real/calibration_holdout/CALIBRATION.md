# Noise sampler calibration

Out-of-sample: tables mined on 1000 rows, simulated and compared on 546 held-out rows (by probe_id), 4 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 0.098→0.097 | 0.071→0.082 | 0.028→0.036 | 0.692→0.481 ⚠ | 0.269→0.298 | 0.000→0.054 | 1.000→0.929 | 1.000→0.964 | 1.000→0.911 | (1.000→1.000) | – | 1.000→0.999 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-ctc@onnx-int8` | 0.242→0.184 ~ | 0.216→0.167 ~ | 0.112→0.073 | 0.188→0.281 ⚠ | 0.688→0.631 | 0.000→0.000 | 1.000→1.000 | 0.786→0.708 | 0.143→0.274 ⚠ | (1.000→0.812) | – | 0.333→0.776 ⚠ | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.233→0.170 ~ | 0.214→0.150 ~ | 0.104→0.066 | 0.237→0.325 ~ | 0.650→0.619 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | (1.000→0.750) | – | 0.200→0.716 ⚠ | 0.000→0.000 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.301→0.243 ~ | 0.253→0.209 ~ | 0.163→0.123 | 0.283→0.321 | 0.585→0.535 | 0.000→0.013 | 1.000→0.987 | 0.554→0.509 | 0.179→0.183 | (0.000→0.000) | – | 0.953→0.957 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 0.179→0.133 | 0.065→0.110 ~ | 0.118→0.087 | 0.692→0.654 | 0.154→0.164 | 0.000→0.000 | 1.000→1.000 | 0.357→0.286 | 0.000→0.000 | (0.000→0.000) | – | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.233→0.172 ~ | 0.186→0.155 ~ | 0.113→0.087 | 0.406→0.403 | 0.415→0.495 ~ | 0.018→0.022 | 0.982→0.969 | 1.000→1.000 | 1.000→0.996 | (0.625→0.481) | – | 0.961→0.962 | 0.018→0.000 | 0.000→0.000 |
| `parakeet-ultra-0.6b@onnx-int8` | 0.209→0.168 | 0.176→0.142 ~ | 0.088→0.093 | 0.377→0.465 ~ | 0.424→0.422 | 0.000→0.022 | 1.000→0.964 | 1.000→1.000 | 1.000→0.996 | (0.875→0.692) | – | 0.973→0.957 | 0.018→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.362→0.271 ~ | 0.304→0.243 ~ | 0.190→0.167 | 0.283→0.328 | 0.604→0.583 | 0.000→0.004 | 1.000→0.982 | 0.982→0.960 | 0.982→0.960 | (0.200→0.000) | – | 0.967→0.962 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.332→0.317 | 0.290→0.264 | 0.185→0.208 | 0.163→0.131 | 0.713→0.709 | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | (0.000→0.000) | – | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | 0.176→0.128 | 0.142→0.113 | 0.077→0.058 | 0.443→0.542 ⚠ | 0.396→0.377 | 0.000→0.036 | 1.000→0.951 | 0.893→0.946 | 0.750→0.888 ⚠ | (1.000→1.000) | – | 0.989→0.978 | 0.000→0.000 | 0.000→0.000 |
| `whisper-medium@onnx-int8` | 0.215→0.137 ~ | 0.180→0.129 ~ | 0.091→0.053 | 0.406→0.422 | 0.396→0.491 ~ | 0.000→0.049 | 1.000→0.924 | 0.946→0.942 | 0.804→0.897 | (0.875→0.938) | – | 0.990→0.996 | 0.000→0.000 | 0.000→0.000 |
| `whisper-small@onnx-int8` | 0.256→0.204 ~ | 0.221→0.182 ~ | 0.121→0.107 | 0.330→0.392 | 0.491→0.460 | 0.018→0.036 | 0.982→0.933 | 0.929→0.951 | 0.786→0.911 ⚠ | (1.000→0.968) | – | 0.967→0.952 | 0.000→0.000 | 0.000→0.000 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 0.071→0.082 | 0.692→0.481 ⚠ | 0.269→0.298 |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 0.302→0.219 ~ | 0.042→0.146 ⚠ | 0.833→0.714 ⚠ |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 0.120→0.108 | 0.406→0.484 | 0.469→0.508 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 0.325→0.200 ~ | 0.062→0.151 ⚠ | 0.833→0.755 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 0.089→0.093 | 0.500→0.586 ~ | 0.375→0.414 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 0.136→0.174 ~ | 0.500→0.471 | 0.385→0.288 ~ |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 0.361→0.258 ~ | 0.042→0.162 ⚠ | 0.792→0.693 ~ |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 0.228→0.183 ~ | 0.469→0.438 | 0.438→0.500 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 0.065→0.110 ~ | 0.692→0.654 | 0.154→0.164 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 0.103→0.087 | 0.654→0.673 | 0.231→0.202 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 0.266→0.232 ~ | 0.167→0.146 | 0.604→0.698 ~ |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 0.165→0.124 ~ | 0.562→0.570 | 0.281→0.430 ~ |
| `parakeet-ultra-0.6b@onnx-int8` | en | 0.071→0.084 | 0.538→0.644 ~ | 0.269→0.221 |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 0.290→0.228 ~ | 0.167→0.203 | 0.625→0.620 |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 0.134→0.094 ~ | 0.562→0.711 ~ | 0.250→0.289 |
| `qwen3-asr-0.6b@onnx-int8` | en | 0.087→0.109 | 0.692→0.673 | 0.231→0.211 |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 0.460→0.303 ~ | 0.042→0.151 ⚠ | 0.812→0.714 ~ |
| `qwen3-asr-0.6b@onnx-int8` | ru | 0.308→0.286 | 0.312→0.312 | 0.594→0.688 ~ |
| `t-one@onnx` | mixed | 0.381→0.324 ~ | 0.021→0.021 | 0.854→0.781 |
| `t-one@onnx` | ru | 0.188→0.196 | 0.375→0.297 | 0.500→0.602 ~ |
| `whisper-large-v3-turbo@onnx-int8` | en | 0.049→0.072 | 0.577→0.606 | 0.192→0.279 ~ |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 0.222→0.172 ~ | 0.250→0.422 ⚠ | 0.604→0.469 ~ |
| `whisper-large-v3-turbo@onnx-int8` | ru | 0.130→0.082 ~ | 0.625→0.672 | 0.250→0.320 |
| `whisper-medium@onnx-int8` | en | 0.065→0.068 | 0.692→0.577 ~ | 0.231→0.260 |
| `whisper-medium@onnx-int8` | mixed | 0.266→0.175 ~ | 0.188→0.292 ~ | 0.562→0.604 |
| `whisper-medium@onnx-int8` | ru | 0.179→0.127 ~ | 0.500→0.492 | 0.281→0.508 ⚠ |
| `whisper-small@onnx-int8` | en | 0.092→0.090 | 0.615→0.567 | 0.308→0.231 |
| `whisper-small@onnx-int8` | mixed | 0.345→0.254 ~ | 0.125→0.250 ⚠ | 0.688→0.531 ⚠ |
| `whisper-small@onnx-int8` | ru | 0.188→0.176 | 0.406→0.461 | 0.344→0.539 ⚠ |

Flagged gaps: 8 of 144 pooled metrics, 11 of 87 per-language metrics; 51 more exceed the tolerance within the real set's sampling noise.
- `distil-large-v3.5@onnx-int8` term_exact_rate: real 0.692, sim 0.481 (gap -0.211, tol 0.08, 2·SE 0.181, n=26; mined-on rows 0.539, sampler gap -0.059)
- `distil-large-v3.5@onnx-int8` term_exact_rate@en: real 0.692, sim 0.481 (gap -0.211, tol 0.08, 2·SE 0.181, n=26; mined-on rows 0.539, sampler gap -0.059)
- `gigaam-v3-e2e-ctc@onnx-int8` term_exact_rate: real 0.188, sim 0.281 (gap +0.094, tol 0.08, 2·SE 0.087, n=80; mined-on rows 0.305, sampler gap -0.024)
- `gigaam-v3-e2e-ctc@onnx-int8` terminal_kept_rate: real 0.143, sim 0.274 (gap +0.131, tol 0.1, 2·SE 0.108, n=42; mined-on rows 0.250, sampler gap +0.024)
- `gigaam-v3-e2e-ctc@onnx-int8` latin_kept_rate: real 0.333, sim 0.776 (gap +0.443, tol 0.1, 2·SE 0.133, n=50; mined-on rows 0.667, sampler gap +0.109)
- `gigaam-v3-e2e-ctc@onnx-int8` term_exact_rate@mixed: real 0.042, sim 0.146 (gap +0.104, tol 0.08, 2·SE 0.058, n=48; mined-on rows 0.167, sampler gap -0.021)
- `gigaam-v3-e2e-ctc@onnx-int8` term_error_rate@mixed: real 0.833, sim 0.714 (gap -0.120, tol 0.08, 2·SE 0.108, n=48; mined-on rows 0.683, sampler gap +0.030)
- `gigaam-v3-e2e-rnnt@onnx-int8` latin_kept_rate: real 0.200, sim 0.716 (gap +0.516, tol 0.1, 2·SE 0.113, n=50; mined-on rows 0.632, sampler gap +0.085)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_exact_rate@mixed: real 0.062, sim 0.151 (gap +0.088, tol 0.08, 2·SE 0.070, n=48; mined-on rows 0.167, sampler gap -0.016)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate@mixed: real 0.042, sim 0.162 (gap +0.120, tol 0.08, 2·SE 0.058, n=48; mined-on rows 0.167, sampler gap -0.005)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate@mixed: real 0.042, sim 0.151 (gap +0.109, tol 0.08, 2·SE 0.058, n=48; mined-on rows 0.217, sampler gap -0.066)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate: real 0.443, sim 0.542 (gap +0.099, tol 0.08, 2·SE 0.097, n=106; mined-on rows 0.515, sampler gap +0.027)
- `whisper-large-v3-turbo@onnx-int8` terminal_kept_rate: real 0.750, sim 0.888 (gap +0.138, tol 0.1, 2·SE 0.116, n=56; mined-on rows 0.885, sampler gap +0.004)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate@mixed: real 0.250, sim 0.422 (gap +0.172, tol 0.08, 2·SE 0.125, n=48; mined-on rows 0.350, sampler gap +0.072)
- `whisper-medium@onnx-int8` term_error_rate@ru: real 0.281, sim 0.508 (gap +0.227, tol 0.08, 2·SE 0.159, n=32; mined-on rows 0.483, sampler gap +0.025)
- `whisper-small@onnx-int8` terminal_kept_rate: real 0.786, sim 0.911 (gap +0.125, tol 0.1, 2·SE 0.110, n=56; mined-on rows 0.894, sampler gap +0.017)
- `whisper-small@onnx-int8` term_exact_rate@mixed: real 0.125, sim 0.250 (gap +0.125, tol 0.08, 2·SE 0.095, n=48; mined-on rows 0.233, sampler gap +0.017)
- `whisper-small@onnx-int8` term_error_rate@mixed: real 0.688, sim 0.531 (gap -0.156, tol 0.08, 2·SE 0.134, n=48; mined-on rows 0.583, sampler gap -0.052)
- `whisper-small@onnx-int8` term_error_rate@ru: real 0.344, sim 0.539 (gap +0.195, tol 0.08, 2·SE 0.168, n=32; mined-on rows 0.500, sampler gap +0.039)

Simulated held-out vs the rows the tables were mined from (different texts, so this mixes sampler error with the difference in text mix; indicative only): 23 metric(s) outside tolerance.
- `gigaam-v3-e2e-ctc@onnx-int8` punct_any_rate: mined 0.906, sim 0.708 (gap -0.198)
- `gigaam-v3-e2e-ctc@onnx-int8` latin_kept_rate: mined 0.667, sim 0.776 (gap +0.109)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_exact_rate@ru: mined 0.397, sim 0.586 (gap +0.189)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_error_rate@ru: mined 0.500, sim 0.414 (gap -0.086)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` wer_style_aware@en: mined 0.116, sim 0.174 (gap +0.058)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate@en: mined 0.342, sim 0.471 (gap +0.129)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_exact_rate: mined 0.553, sim 0.654 (gap +0.101)
- `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` term_exact_rate@en: mined 0.553, sim 0.654 (gap +0.101)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_error_rate: mined 0.412, sim 0.495 (gap +0.083)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_exact_rate@en: mined 0.526, sim 0.673 (gap +0.147)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_exact_rate@ru: mined 0.448, sim 0.570 (gap +0.122)
- `parakeet-ultra-0.6b@onnx-int8` wer_style_aware: mined 0.110, sim 0.142 (gap +0.033)
- `parakeet-ultra-0.6b@onnx-int8` wer_style_aware@en: mined 0.044, sim 0.084 (gap +0.040)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate@ru: mined 0.552, sim 0.711 (gap +0.159)
- `qwen3-asr-0.6b@onnx-int8` wer_style_aware: mined 0.204, sim 0.243 (gap +0.039)
- `qwen3-asr-0.6b@onnx-int8` term_error_rate: mined 0.500, sim 0.583 (gap +0.083)
- `qwen3-asr-0.6b@onnx-int8` wer_style_aware@en: mined 0.070, sim 0.109 (gap +0.038)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate@en: mined 0.566, sim 0.673 (gap +0.107)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate@ru: mined 0.552, sim 0.672 (gap +0.120)
- `whisper-medium@onnx-int8` term_error_rate: mined 0.407, sim 0.491 (gap +0.083)
- `whisper-medium@onnx-int8` wer_style_aware@ru: mined 0.158, sim 0.127 (gap -0.031)
- `whisper-medium@onnx-int8` term_exact_rate@ru: mined 0.397, sim 0.492 (gap +0.096)
- `whisper-small@onnx-int8` term_exact_rate@en: mined 0.474, sim 0.567 (gap +0.094)
