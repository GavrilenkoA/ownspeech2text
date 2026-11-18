# Noise sampler calibration

Out-of-sample: tables mined on 560 rows, simulated and compared on 277 held-out rows (by probe_id), 4 repeat(s), severity 1.

Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance. Values in brackets rest on fewer than 10 real observations and are shown but not judged.

| ASR model | wer | wer_style_aware | cer | term_exact_rate | term_error_rate | all_lower_rate | first_upper_rate | punct_any_rate | terminal_kept_rate | digits_kept_rate | words_to_digits_rate | latin_kept_rate | tail_rate | empty_rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `distil-large-v3.5@onnx-int8` | 0.114→0.079 | 0.073→0.047 | 0.059→0.015 | 0.417→0.646 ⚠ | 0.333→0.135 ⚠ | 0.000→0.000 | 1.000→0.983 | 0.933→1.000 | 0.933→1.000 | – | (1.000→0.000) | 0.985→1.000 | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-ctc@onnx-int8` | 0.308→0.338 | 0.254→0.232 | 0.170→0.218 | 0.204→0.190 | 0.611→0.630 | 0.000→0.000 | 1.000→1.000 | 0.950→0.925 | 0.350→0.338 | (1.000→1.000) | (0.500→0.625) | 0.364→0.073 ⚠ | 0.000→0.000 | 0.000→0.000 |
| `gigaam-v3-e2e-rnnt@onnx-int8` | 0.296→0.298 | 0.229→0.226 | 0.182→0.196 | 0.259→0.227 | 0.518→0.634 ⚠ | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 0.950→1.000 | (1.000→1.000) | (0.500→0.438) | 0.286→0.065 ⚠ | 0.000→0.000 | 0.000→0.000 |
| `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` | 0.270→0.270 | 0.198→0.207 | 0.156→0.152 | 0.231→0.295 | 0.487→0.500 | 0.029→0.043 | 0.971→0.936 | 0.657→0.664 | 0.200→0.179 | (0.500→0.000) | (0.000→0.000) | 0.923→0.937 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-tdt-0.6b-v3@onnx-int8` | 0.224→0.191 | 0.168→0.130 ⚠ | 0.119→0.103 | 0.397→0.436 | 0.372→0.388 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→0.993 | (1.000→1.000) | (0.600→0.333) | 0.968→0.938 | 0.000→0.000 | 0.000→0.000 |
| `parakeet-ultra-0.6b@onnx-int8` | 0.196→0.184 | 0.139→0.120 | 0.106→0.104 | 0.385→0.516 ⚠ | 0.385→0.343 | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→0.993 | (1.000→1.000) | (0.600→0.929) | 0.955→0.944 | 0.000→0.000 | 0.000→0.000 |
| `qwen3-asr-0.6b@onnx-int8` | 0.285→0.261 | 0.216→0.197 | 0.161→0.150 | 0.218→0.311 ⚠ | 0.551→0.471 ⚠ | 0.000→0.000 | 1.000→1.000 | 1.000→1.000 | 1.000→0.993 | (0.000→0.000) | (0.000→0.000) | 0.946→0.938 | 0.000→0.000 | 0.000→0.000 |
| `t-one@onnx` | 0.450→0.408 | 0.329→0.298 ⚠ | 0.246→0.249 | 0.204→0.232 | 0.630→0.630 | 1.000→1.000 | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 | (0.000→0.000) | (0.000→0.000) | 0.000→0.000 | 0.000→0.000 | 0.000→0.000 |
| `whisper-large-v3-turbo@onnx-int8` | 0.204→0.150 ⚠ | 0.171→0.115 ⚠ | 0.142→0.066 ⚠ | 0.483→0.534 | 0.397→0.349 | 0.000→0.000 | 1.000→0.989 | 0.955→0.977 | 0.727→0.864 ⚠ | (1.000→1.000) | (1.000→1.000) | 0.946→0.969 | 0.000→0.000 | 0.000→0.000 |
| `whisper-medium@onnx-int8` | 0.344→0.211 ⚠ | 0.271→0.195 ⚠ | 0.250→0.072 ⚠ | 0.273→0.318 | 0.500→0.682 ⚠ | (0.000→0.036) | (1.000→0.964) | (1.000→1.000) | (0.714→0.929) | (1.000→0.625) | (1.000→0.000) | 0.706→1.000 ⚠ | (0.000→0.000) | (0.000→0.000) |
| `whisper-small@onnx-int8` | 0.251→0.205 | 0.191→0.158 ⚠ | 0.143→0.104 | 0.319→0.413 ⚠ | 0.431→0.434 | 0.030→0.023 | 0.970→0.947 | 0.939→0.977 | 0.818→0.871 | (1.000→1.000) | (0.500→0.815) | 0.962→0.959 | 0.000→0.000 | 0.000→0.000 |

Flagged gaps: 21 of 126.
- `distil-large-v3.5@onnx-int8` term_exact_rate: real 0.417, sim 0.646 (gap +0.229, tol 0.08, n=24; mined-on rows 0.698, sampler gap -0.053)
- `distil-large-v3.5@onnx-int8` term_error_rate: real 0.333, sim 0.135 (gap -0.198, tol 0.08, n=24; mined-on rows 0.143, sampler gap -0.007)
- `gigaam-v3-e2e-ctc@onnx-int8` latin_kept_rate: real 0.364, sim 0.073 (gap -0.291, tol 0.1, n=32; mined-on rows 0.071, sampler gap +0.001)
- `gigaam-v3-e2e-rnnt@onnx-int8` term_error_rate: real 0.518, sim 0.634 (gap +0.116, tol 0.08, n=54; mined-on rows 0.573, sampler gap +0.061)
- `gigaam-v3-e2e-rnnt@onnx-int8` latin_kept_rate: real 0.286, sim 0.065 (gap -0.221, tol 0.1, n=32; mined-on rows 0.091, sampler gap -0.026)
- `parakeet-tdt-0.6b-v3@onnx-int8` wer_style_aware: real 0.168, sim 0.130 (gap -0.038, tol 0.03, n=459; mined-on rows 0.119, sampler gap +0.011)
- `parakeet-ultra-0.6b@onnx-int8` term_exact_rate: real 0.385, sim 0.516 (gap +0.131, tol 0.08, n=78; mined-on rows 0.545, sampler gap -0.029)
- `qwen3-asr-0.6b@onnx-int8` term_exact_rate: real 0.218, sim 0.311 (gap +0.093, tol 0.08, n=78; mined-on rows 0.365, sampler gap -0.055)
- `qwen3-asr-0.6b@onnx-int8` term_error_rate: real 0.551, sim 0.471 (gap -0.080, tol 0.08, n=78; mined-on rows 0.434, sampler gap +0.037)
- `t-one@onnx` wer_style_aware: real 0.329, sim 0.298 (gap -0.031, tol 0.03, n=240; mined-on rows 0.217, sampler gap +0.081)
- `whisper-large-v3-turbo@onnx-int8` wer: real 0.204, sim 0.150 (gap -0.054, tol 0.05, n=280; mined-on rows 0.117, sampler gap +0.034)
- `whisper-large-v3-turbo@onnx-int8` wer_style_aware: real 0.171, sim 0.115 (gap -0.056, tol 0.03, n=280; mined-on rows 0.090, sampler gap +0.025)
- `whisper-large-v3-turbo@onnx-int8` cer: real 0.142, sim 0.066 (gap -0.076, tol 0.05, n=280; mined-on rows 0.055, sampler gap +0.011)
- `whisper-large-v3-turbo@onnx-int8` terminal_kept_rate: real 0.727, sim 0.864 (gap +0.136, tol 0.1, n=22; mined-on rows 0.880, sampler gap -0.016)
- `whisper-medium@onnx-int8` wer: real 0.344, sim 0.211 (gap -0.133, tol 0.05, n=96; mined-on rows 0.121, sampler gap +0.089)
- `whisper-medium@onnx-int8` wer_style_aware: real 0.271, sim 0.195 (gap -0.075, tol 0.03, n=96; mined-on rows 0.113, sampler gap +0.082)
- `whisper-medium@onnx-int8` cer: real 0.250, sim 0.072 (gap -0.178, tol 0.05, n=96; mined-on rows 0.040, sampler gap +0.032)
- `whisper-medium@onnx-int8` term_error_rate: real 0.500, sim 0.682 (gap +0.182, tol 0.08, n=22; mined-on rows 0.357, sampler gap +0.325)
- `whisper-medium@onnx-int8` latin_kept_rate: real 0.706, sim 1.000 (gap +0.294, tol 0.1, n=28; mined-on rows 1.000, sampler gap +0.000)
- `whisper-small@onnx-int8` wer_style_aware: real 0.191, sim 0.158 (gap -0.033, tol 0.03, n=435; mined-on rows 0.152, sampler gap +0.005)
- `whisper-small@onnx-int8` term_exact_rate: real 0.319, sim 0.413 (gap +0.094, tol 0.08, n=72; mined-on rows 0.455, sampler gap -0.041)

Simulated held-out vs the rows the tables were mined from (different texts, so this mixes sampler error with the difference in text mix; indicative only): 16 metric(s) outside tolerance.
- `gigaam-v3-e2e-ctc@onnx-int8` wer: mined 0.189, sim 0.338 (gap +0.148)
- `gigaam-v3-e2e-ctc@onnx-int8` wer_style_aware: mined 0.157, sim 0.232 (gap +0.075)
- `gigaam-v3-e2e-ctc@onnx-int8` cer: mined 0.111, sim 0.218 (gap +0.108)
- `gigaam-v3-e2e-rnnt@onnx-int8` wer: mined 0.174, sim 0.298 (gap +0.123)
- `gigaam-v3-e2e-rnnt@onnx-int8` wer_style_aware: mined 0.149, sim 0.226 (gap +0.077)
- `gigaam-v3-e2e-rnnt@onnx-int8` cer: mined 0.102, sim 0.196 (gap +0.094)
- `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` punct_any_rate: mined 0.857, sim 0.664 (gap -0.193)
- `parakeet-ultra-0.6b@onnx-int8` wer: mined 0.131, sim 0.184 (gap +0.053)
- `t-one@onnx` wer: mined 0.275, sim 0.408 (gap +0.134)
- `t-one@onnx` wer_style_aware: mined 0.217, sim 0.298 (gap +0.081)
- `t-one@onnx` cer: mined 0.141, sim 0.249 (gap +0.108)
- `whisper-large-v3-turbo@onnx-int8` term_error_rate: mined 0.225, sim 0.349 (gap +0.124)
- `whisper-medium@onnx-int8` wer: mined 0.121, sim 0.211 (gap +0.089)
- `whisper-medium@onnx-int8` wer_style_aware: mined 0.113, sim 0.195 (gap +0.082)
- `whisper-medium@onnx-int8` term_exact_rate: mined 0.643, sim 0.318 (gap -0.325)
- `whisper-medium@onnx-int8` term_error_rate: mined 0.357, sim 0.682 (gap +0.325)
