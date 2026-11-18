# Noise sampler calibration

Out-of-sample: tables mined on 84 rows, simulated and compared on 56 held-out rows (by probe_id), 5 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance. Values in brackets rest on fewer than 10 real observations and are shown but not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.162→0.203 | 0.000→0.030 | 0.178→0.161 | 0.526→0.505 | 0.000→0.053 | (0.000→0.000) | (1.000→1.000) | (1.000→1.000) | (1.000→1.000) | (1.000→1.000) | – | 0.000→0.000 | (0.000→0.000) | (0.000→0.000) |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.260→0.184 ⚠ | 0.090→0.030 ⚠ | 0.197→0.160 | 0.292→0.583 ⚠ | 0.375→0.075 ⚠ | 0.000→0.000 | 1.000→1.000 | 1.000→0.980 | 1.000→0.980 | (0.000→0.050) | – | 0.788→0.809 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.240→0.244 | 0.030→0.034 | 0.213→0.211 | 0.500→0.483 | 0.125→0.117 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | (0.000→0.000) | – | 0.714→0.708 | 0.000→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.070→0.044 | 0.060→0.028 ⚠ | 0.020→0.014 | 0.708→0.858 ⚠ | 0.250→0.117 ⚠ | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→1.000 | (1.000→0.900) | – | 0.972→1.000 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.284→0.346 ⚠ | 0.013→0.049 ⚠ | 0.268→0.278 | 0.316→0.284 | 0.053→0.147 ⚠ | (1.000→1.000) | (0.000→0.000) | (0.000→0.000) | (0.000→0.000) | (0.000→0.000) | – | 0.000→0.000 | (0.000→0.000) | (0.000→0.000) |
| `whisper-large-v3-turbo@onnx-int8` | 0.060→0.128 ⚠ | 0.040→0.116 ⚠ | 0.068→0.126 ⚠ | 0.833→0.879 | 0.042→0.060 | 0.000→0.000 | 1.000→1.000 | 1.000→0.979 | 0.900→0.920 | (0.750→1.000) | – | 0.973→1.000 | 0.100→0.200 ⚠ | 0.000→0.040 |

Flagged gaps: 14 of 60.
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` wer: real 0.260, sim 0.184 (gap -0.076, tol 0.05, n=100; mined-on rows 0.298, sampler gap -0.114)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` wer_style_aware: real 0.090, sim 0.030 (gap -0.060, tol 0.03, n=100; mined-on rows 0.041, sampler gap -0.011)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate: real 0.292, sim 0.583 (gap +0.292, tol 0.08, n=24; mined-on rows 0.476, sampler gap +0.107)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_error_rate: real 0.375, sim 0.075 (gap -0.300, tol 0.08, n=24; mined-on rows 0.095, sampler gap -0.020)
- `qwen3-asr-0.6b@onnx-int8` wer_style_aware: real 0.060, sim 0.028 (gap -0.032, tol 0.03, n=100; mined-on rows 0.035, sampler gap -0.007)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate: real 0.708, sim 0.858 (gap +0.150, tol 0.08, n=24; mined-on rows 0.762, sampler gap +0.096)
- `qwen3-asr-0.6b@onnx-int8` term_error_rate: real 0.250, sim 0.117 (gap -0.133, tol 0.08, n=24; mined-on rows 0.119, sampler gap -0.002)
- `t-one@onnx` wer: real 0.284, sim 0.346 (gap +0.062, tol 0.05, n=74; mined-on rows 0.465, sampler gap -0.119)
- `t-one@onnx` wer_style_aware: real 0.013, sim 0.049 (gap +0.035, tol 0.03, n=74; mined-on rows 0.061, sampler gap -0.012)
- `t-one@onnx` term_error_rate: real 0.053, sim 0.147 (gap +0.095, tol 0.08, n=19; mined-on rows 0.129, sampler gap +0.018)
- `whisper-large-v3-turbo@onnx-int8` wer: real 0.060, sim 0.128 (gap +0.068, tol 0.05, n=100; mined-on rows 0.117, sampler gap +0.011)
- `whisper-large-v3-turbo@onnx-int8` wer_style_aware: real 0.040, sim 0.116 (gap +0.076, tol 0.03, n=100; mined-on rows 0.105, sampler gap +0.011)
- `whisper-large-v3-turbo@onnx-int8` cer: real 0.068, sim 0.126 (gap +0.058, tol 0.05, n=100; mined-on rows 0.103, sampler gap +0.022)
- `whisper-large-v3-turbo@onnx-int8` tail_rate: real 0.100, sim 0.200 (gap +0.100, tol 0.05, n=10; mined-on rows 0.125, sampler gap +0.075)

Simulated held-out vs the rows the tables were mined from (different texts, so this mixes sampler error with the difference in text mix; indicative only): 12 metric(s) outside tolerance.
- `gigaam-v3-e2e-rnnt@onnx-int8` term_exact_rate: mined 0.419, sim 0.505 (gap +0.086)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` wer: mined 0.298, sim 0.184 (gap -0.114)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` cer: mined 0.251, sim 0.160 (gap -0.090)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` term_exact_rate: mined 0.476, sim 0.583 (gap +0.107)
- `parakeet-tdt-0.6b-v3@onnx-int8` wer: mined 0.404, sim 0.244 (gap -0.160)
- `parakeet-tdt-0.6b-v3@onnx-int8` cer: mined 0.345, sim 0.211 (gap -0.134)
- `parakeet-tdt-0.6b-v3@onnx-int8` term_exact_rate: mined 0.357, sim 0.483 (gap +0.126)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate: mined 0.762, sim 0.858 (gap +0.096)
- `t-one@onnx` wer: mined 0.465, sim 0.346 (gap -0.119)
- `t-one@onnx` cer: mined 0.384, sim 0.278 (gap -0.106)
- `whisper-large-v3-turbo@onnx-int8` term_exact_rate: mined 0.763, sim 0.879 (gap +0.116)
- `whisper-large-v3-turbo@onnx-int8` tail_rate: mined 0.125, sim 0.200 (gap +0.075)
