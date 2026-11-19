# Noise sampler calibration

In-sample: simulated over the same 8878 rows the tables were mined from, 4 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance and also twice the standard error of the real value (binomial; rows as the sample for WER-like rates), i.e. when the difference is larger than the real set can measure. `~` marks a gap above the tolerance that is within that noise. Values in brackets rest on fewer than 10 real observations and are not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 0.096→0.087 | 0.063→0.060 | 0.028→0.025 | 0.525→0.524 | 0.238→0.236 | 0.013→0.019 | 0.958→0.944 | 0.987→0.985 | 0.974→0.971 | 0.957→0.980 | (1.000→1.000) | 0.999→1.000 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-ctc@onnx-int8` | 0.147→0.135 | 0.124→0.115 | 0.064→0.057 | 0.321→0.335 | 0.538→0.526 | 0.000→0.001 | 0.997→0.998 | 0.886→0.801 ⚠ | 0.239→0.283 | 0.877→0.903 | (0.333→0.500) | 0.698→0.721 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.143→0.134 | 0.117→0.110 | 0.070→0.066 | 0.326→0.334 | 0.516→0.513 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→0.999 | 0.900→0.903 | (0.667→0.583) | 0.632→0.652 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.189→0.185 | 0.135→0.139 | 0.093→0.097 | 0.337→0.337 | 0.416→0.425 | 0.002→0.005 | 0.991→0.988 | 0.665→0.598 | 0.283→0.318 | 0.127→0.156 | (0.667→0.667) | 0.958→0.956 | 0.000→0.000 | 0.000→0.000 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | 0.166→0.160 | 0.106→0.102 | 0.070→0.074 | 0.424→0.420 | 0.277→0.266 | 0.000→0.000 | 1.000→0.999 | 0.282→0.249 | 0.003→0.045 | 0.000→0.000 | (0.000→0.000) | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.133→0.126 | 0.093→0.094 | 0.052→0.047 | 0.434→0.437 | 0.343→0.352 | 0.001→0.001 | 0.998→0.997 | 0.999→1.000 | 0.999→0.998 | 0.868→0.841 | (0.750→0.625) | 0.982→0.981 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-ultra-0.6b@onnx-int8` | 0.120→0.110 | 0.082→0.079 | 0.047→0.042 | 0.455→0.459 | 0.322→0.319 | 0.001→0.002 | 0.997→0.997 | 1.000→1.000 | 1.000→0.999 | 0.859→0.886 | (1.000→1.000) | 0.981→0.979 | 0.000→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.221→0.213 | 0.166→0.168 | 0.105→0.111 | 0.334→0.341 | 0.450→0.454 | 0.004→0.009 | 0.994→0.986 | 0.998→0.997 | 0.997→0.992 | 0.049→0.085 | (0.000→0.000) | 0.970→0.968 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.211→0.205 | 0.160→0.155 | 0.128→0.137 | 0.205→0.207 | 0.586→0.596 | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | (0.000→0.000) | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | 0.098→0.093 | 0.066→0.065 | 0.037→0.036 | 0.535→0.533 | 0.252→0.253 | 0.002→0.005 | 0.995→0.992 | 0.995→0.990 | 0.980→0.979 | 0.977→0.989 | (1.000→1.000) | 0.982→0.984 | 0.000→0.000 | 0.000→0.000 |
| `whisper-medium@onnx-int8` | 0.112→0.102 | 0.073→0.072 | 0.044→0.040 | 0.512→0.523 | 0.273→0.272 | 0.004→0.009 | 0.981→0.979 | 0.992→0.991 | 0.983→0.985 | 0.979→0.979 | (1.000→1.000) | 0.986→0.987 | 0.000→0.000 | 0.000→0.000 |
| `whisper-small@onnx-int8` | 0.139→0.128 | 0.102→0.099 | 0.055→0.053 | 0.417→0.415 | 0.367→0.372 | 0.004→0.008 | 0.987→0.984 | 0.997→0.994 | 0.982→0.982 | 0.927→0.943 | (1.000→1.000) | 0.980→0.980 | 0.000→0.000 | 0.000→0.000 |

Per language (each language mined and simulated with its own rates):

| ASR model | lang | wer_style_aware | term_exact_rate | term_error_rate |
|---|---|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | en | 0.063→0.060 | 0.525→0.524 | 0.238→0.236 |
| `gigaam-v3-e2e-ctc@onnx-int8` | mixed | 0.167→0.148 | 0.168→0.186 | 0.675→0.658 |
| `gigaam-v3-e2e-ctc@onnx-int8` | ru | 0.071→0.073 | 0.515→0.522 | 0.364→0.359 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | mixed | 0.161→0.145 | 0.168→0.176 | 0.666→0.655 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | ru | 0.064→0.068 | 0.526→0.532 | 0.326→0.335 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | en | 0.128→0.130 | 0.341→0.347 | 0.324→0.327 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | mixed | 0.169→0.168 | 0.195→0.195 | 0.550→0.551 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | ru | 0.100→0.115 | 0.510→0.506 | 0.348→0.374 |
| `nemotron-speech-streaming-en-0.6b@onnx-int8-560ms` | en | 0.106→0.102 | 0.424→0.420 | 0.277→0.266 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | en | 0.074→0.076 | 0.481→0.471 | 0.259→0.271 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | mixed | 0.129→0.126 | 0.289→0.300 | 0.479→0.478 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | ru | 0.071→0.075 | 0.566→0.572 | 0.264→0.280 |
| `parakeet-ultra-0.6b@onnx-int8` | en | 0.064→0.063 | 0.487→0.494 | 0.256→0.250 |
| `parakeet-ultra-0.6b@onnx-int8` | mixed | 0.118→0.108 | 0.317→0.309 | 0.448→0.450 |
| `parakeet-ultra-0.6b@onnx-int8` | ru | 0.060→0.062 | 0.595→0.611 | 0.234→0.229 |
| `qwen3-asr-0.6b@onnx-int8` | en | 0.082→0.088 | 0.442→0.439 | 0.267→0.282 |
| `qwen3-asr-0.6b@onnx-int8` | mixed | 0.244→0.233 | 0.201→0.208 | 0.577→0.578 |
| `qwen3-asr-0.6b@onnx-int8` | ru | 0.170→0.183 | 0.383→0.400 | 0.488→0.486 |
| `t-one@onnx` | mixed | 0.208→0.196 | 0.029→0.018 | 0.734→0.738 |
| `t-one@onnx` | ru | 0.100→0.105 | 0.428→0.445 | 0.398→0.417 |
| `whisper-large-v3-turbo@onnx-int8` | en | 0.060→0.059 | 0.538→0.536 | 0.219→0.217 |
| `whisper-large-v3-turbo@onnx-int8` | mixed | 0.089→0.084 | 0.431→0.433 | 0.330→0.331 |
| `whisper-large-v3-turbo@onnx-int8` | ru | 0.045→0.047 | 0.662→0.657 | 0.191→0.194 |
| `whisper-medium@onnx-int8` | en | 0.056→0.055 | 0.565→0.563 | 0.221→0.216 |
| `whisper-medium@onnx-int8` | mixed | 0.098→0.094 | 0.449→0.450 | 0.358→0.365 |
| `whisper-medium@onnx-int8` | ru | 0.062→0.064 | 0.532→0.568 | 0.226→0.218 |
| `whisper-small@onnx-int8` | en | 0.075→0.071 | 0.487→0.481 | 0.270→0.264 |
| `whisper-small@onnx-int8` | mixed | 0.139→0.131 | 0.314→0.302 | 0.465→0.482 |
| `whisper-small@onnx-int8` | ru | 0.089→0.093 | 0.469→0.486 | 0.350→0.352 |

Flagged gaps: 1 of 156 pooled metrics, 0 of 87 per-language metrics; 0 more exceed the tolerance within the real set's sampling noise.
- `gigaam-v3-e2e-ctc@onnx-int8` punct_any_rate: real 0.886, sim 0.801 (gap -0.086, tol 0.08, 2·SE 0.025, n=660)
