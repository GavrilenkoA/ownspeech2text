# Noise sampler calibration

In-sample: simulated over the same 1546 rows the tables were mined from, 4 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 0.111→0.107 | 0.082→0.085 | 0.036→0.039 | 0.578→0.542 | 0.275→0.299 | 0.037→0.051 | 0.944→0.893 | 0.982→0.972 | 0.963→0.954 | (1.000→1.000) | – | 0.999→1.000 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-ctc@onnx-int8` | 0.212→0.209 | 0.186→0.189 | 0.096→0.096 | 0.258→0.247 | 0.631→0.638 | 0.000→0.000 | 1.000→1.000 | 0.859→0.766 ⚠ | 0.207→0.196 | 0.833→0.756 | – | 0.593→0.625 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.211→0.196 | 0.184→0.175 | 0.096→0.093 | 0.263→0.269 | 0.631→0.619 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | 0.917→0.875 | – | 0.542→0.491 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.255→0.255 | 0.207→0.215 | 0.135→0.137 | 0.303→0.302 | 0.503→0.508 | 0.006→0.011 | 0.994→0.988 | 0.575→0.486 ⚠ | 0.181→0.184 | 0.000→0.000 | – | 0.970→0.971 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 0.133→0.132 | 0.077→0.090 | 0.072→0.072 | 0.588→0.618 | 0.186→0.157 | 0.000→0.000 | 1.000→1.000 | 0.333→0.241 ~ | 0.000→0.009 | (0.000→0.000) | – | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.195→0.189 | 0.146→0.153 | 0.098→0.089 | 0.407→0.383 | 0.413→0.438 | 0.013→0.009 | 0.988→0.981 | 1.000→1.000 | 1.000→0.998 | 0.500→0.548 | – | 0.978→0.975 | 0.006→0.003 | 0.000→0.000 |
| `parakeet-ultra-0.6b@onnx-int8` | 0.168→0.164 | 0.132→0.134 | 0.079→0.081 | 0.433→0.440 | 0.390→0.382 | 0.006→0.009 | 0.988→0.989 | 1.000→1.000 | 1.000→0.998 | 0.722→0.791 | – | 0.977→0.972 | 0.006→0.009 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.285→0.277 | 0.237→0.244 | 0.147→0.165 | 0.347→0.356 | 0.537→0.528 | 0.006→0.008 | 0.994→0.989 | 0.981→0.988 | 0.981→0.983 | 0.071→0.036 | – | 0.980→0.980 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.315→0.311 | 0.267→0.267 | 0.177→0.188 | 0.162→0.164 | 0.702→0.715 | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | – | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | 0.148→0.133 | 0.112→0.108 | 0.072→0.063 | 0.490→0.516 | 0.357→0.335 | 0.013→0.023 | 0.988→0.969 | 0.944→0.908 | 0.838→0.823 | 1.000→1.000 | – | 0.986→0.989 | 0.000→0.000 | 0.000→0.000 |
| `whisper-medium@onnx-int8` | 0.171→0.171 | 0.143→0.148 | 0.076→0.079 | 0.433→0.429 | 0.403→0.416 | 0.025→0.047 | 0.969→0.936 | 0.950→0.944 | 0.863→0.864 | 0.889→0.871 | – | 0.994→0.995 | 0.000→0.000 | 0.000→0.000 |
| `whisper-small@onnx-int8` | 0.212→0.197 | 0.180→0.174 | 0.094→0.097 | 0.360→0.378 | 0.447→0.439 | 0.025→0.031 | 0.969→0.950 | 0.963→0.914 | 0.856→0.833 | 0.944→0.958 | – | 0.980→0.976 | 0.000→0.000 | 0.000→0.000 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 0.082→0.085 | 0.578→0.542 | 0.275→0.299 |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 0.251→0.242 | 0.111→0.090 | 0.750→0.768 |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 0.122→0.136 | 0.433→0.436 | 0.489→0.481 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 0.261→0.246 | 0.120→0.090 | 0.778→0.782 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 0.108→0.106 | 0.433→0.483 | 0.456→0.422 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 0.121→0.130 | 0.382→0.375 | 0.314→0.326 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 0.311→0.320 | 0.111→0.095 | 0.704→0.741 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 0.215→0.220 | 0.444→0.469 | 0.478→0.436 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 0.077→0.090 | 0.588→0.618 | 0.186→0.157 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 0.073→0.089 | 0.559→0.547 | 0.235→0.247 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 0.244→0.248 | 0.194→0.150 | 0.620→0.653 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 0.144→0.141 | 0.489→0.478 | 0.367→0.397 |
| `parakeet-ultra-0.6b@onnx-int8` | en | 0.051→0.057 | 0.618→0.615 | 0.196→0.196 |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 0.251→0.242 | 0.157→0.164 | 0.639→0.620 |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 0.117→0.125 | 0.556→0.572 | 0.311→0.306 |
| `qwen3-asr-0.6b@onnx-int8` | en | 0.074→0.095 | 0.598→0.610 | 0.245→0.228 |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 0.386→0.375 | 0.139→0.153 | 0.732→0.734 |
| `qwen3-asr-0.6b@onnx-int8` | ru | 0.300→0.304 | 0.311→0.311 | 0.633→0.622 |
| `t-one@onnx` | mixed | 0.333→0.319 | 0.009→0.012 | 0.815→0.838 |
| `t-one@onnx` | ru | 0.202→0.216 | 0.344→0.347 | 0.567→0.567 |
| `whisper-large-v3-turbo@onnx-int8` | en | 0.053→0.058 | 0.608→0.625 | 0.206→0.206 |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 0.192→0.169 | 0.306→0.354 | 0.546→0.488 |
| `whisper-large-v3-turbo@onnx-int8` | ru | 0.109→0.113 | 0.578→0.586 | 0.300→0.297 |
| `whisper-medium@onnx-int8` | en | 0.061→0.073 | 0.627→0.618 | 0.226→0.235 |
| `whisper-medium@onnx-int8` | mixed | 0.226→0.224 | 0.250→0.222 | 0.565→0.583 |
| `whisper-medium@onnx-int8` | ru | 0.166→0.171 | 0.433→0.464 | 0.411→0.419 |
| `whisper-small@onnx-int8` | en | 0.077→0.084 | 0.510→0.534 | 0.255→0.253 |
| `whisper-small@onnx-int8` | mixed | 0.306→0.275 ~ | 0.185→0.183 | 0.630→0.611 |
| `whisper-small@onnx-int8` | ru | 0.187→0.190 | 0.400→0.433 | 0.444→0.444 |

Flagged gaps: 2 of 154 pooled metrics, 0 of 87 per-language metrics; 2 more exceed the tolerance within the real set's sampling noise.
- `gigaam-v3-e2e-ctc@onnx-int8` punct_any_rate: real 0.859, sim 0.766 (gap -0.092, tol 0.08, 2·SE 0.068, n=106)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` punct_any_rate: real 0.575, sim 0.486 (gap -0.089, tol 0.08, 2·SE 0.078, n=160)
