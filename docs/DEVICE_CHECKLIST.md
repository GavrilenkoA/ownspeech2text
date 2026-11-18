# Device checklist (Galaxy Z Fold7, one pass)

Everything that only the phone can prove, in the order to run it (D12). Each step: command, time, expected output,
what decision it feeds. Steps D1-D7 use scripts that already exist and need no app build; D8-D10 need the app built
by the end of wave 3 (`tools/device/murmur_app_smoke.sh`, WP-20). Owner of this file after hand-off: WP-20.

Before you start (5 min):
- Phone: unlock, Settings → About phone → Software information → tap Build number 7× → Developer options → USB
  debugging on. Settings → Security and privacy → Auto Blocker **off** (it blocks adb installs and USB commands).
  Keep the phone on the charger, screen timeout ≥ 10 min, Wi-Fi on.
- Host: `adb devices` shows exactly one device (`export ANDROID_SERIAL=<serial>` otherwise).
- Disk on the phone: ≥ 6 GB free (scripts push up to 3 GB and remove it afterwards).
- Send back the whole `.cache/device-reports/` and `.cache/device/` folders after the pass (no personal data; the
  OCR step stores screenshots of stock apps only, see D7).

| Step | Run | Time | Expected (pass) | Feeds |
|---|---|---|---|---|
| D1 Device facts | `adb shell getprop ro.product.model; adb shell getprop ro.soc.model; adb shell getprop ro.build.version.sdk; adb shell cat /proc/meminfo \| head -3; adb shell wm size; adb shell wm density` (once folded, once unfolded) | 1 min | `SM-F966*`, `SM8750`, SDK 37, MemTotal ≈ 11.5 GB, MemAvailable (note it), sizes 1080×2520 / 1968×2184, densities | memory budget (ARCHITECTURE 7), real dp for layouts (DESIGN 2), `tools/emulator/create_avds.sh` densities |
| D2 LLM on NPU/GPU/CPU | `tools/device/llm_npu_smoke.sh` (`--quick` 5-8 min; add `--geniex` for Qualcomm's cross-check) | 20-30 min | `devices.txt` lists `HTP0` and `GPUOpenCL`; report.md rows CPU/HTP0/GPU pp512/tg64 for LFM3 and Qwen3.5-0.8B; app-flow TTFT with prefix snapshot; peak RSS mmap vs no-mmap; NPU op acceptance 0 rejected (or the list); CPU variant `libggml-cpu-android_armv8.6_1.so`; thermal before/after | ADR-029 default unit per LLM; ADR-007/ADR-009 (HTP works from shell); LLM timeout calibration; if HTP0 missing and `--geniex` also fails → One UI blocks unsigned PDs: ship CPU/GPU only |
| D3 ASR on NPU vs CPU | `ONLY=asr tools/device/asr_npu_smoke.sh` | 15-25 min (first run downloads ≈ 3 GB) | rc=0 for `parakeet-npu-20s` and `nemotron-npu-560ms` with both qnn240 and qnn252 libs; NPU decode faster than `parakeet-cpu-int8`; same text as CPU; peak RSS per run | ADR-005/006 (QNN 2.52 loads 2.40 binaries on the real HTP); default ASR artifact; ASR_FINAL timeout; memory budget |
| D4 OCR CPU vs XNNPACK vs QNN | `ONLY=ocr tools/device/asr_npu_smoke.sh` | 5-10 min | `ort_bench` medians for det 960×416 / 960×864 / native and rec 48×320 / 48×640 on cpu, xnnpack, qnn-ctx; QNN parity rows argmax_agree 1.0, binmap 1.0; no "no QNN EP device" error; `libcdsprpc.so` present in device_info | OCR NPU follow-up (catalog `npu-port` on the OCR models): adopt only if QNN is clearly faster; XNNPACK stays off if it crashes |
| D5 Platform behaviour | `tools/device/platform_smoke.sh` unfolded, then again folded (cover). Interactive: fold/unfold once, tap the bubble once **and speak**, tap into 4 fields | 10 min × 2 | report in `.cache/device/platform_<serial>_<ts>.md`: One UI / SDK / density; fold events (`config_changed` vs `display_added`); FGS `code:UID_VISIBLE`; recording `nonZero > 0`, `silenced=false`, peak well above 8; window screenshot OK at ≈ 400 ms; ctx2 lines with the keyboard rect; interval sweep ≈ 333 ms; insertion ladder `verified=true` in all fields; `:infer` gone after idle; UIDT `RESULT_SUCCESS` | ADR-034 (no SAW step), ADR-024 (mic in `:main`), screenshot fallbacks (ARCHITECTURE 9.1), fold handling in WP-03 |
| D6 One UI enable flow | Uninstall the spike, install `app-debug.apk` with `adb install`; enable Murmur in Settings → Accessibility → Installed apps. Note whether a "Restricted setting" dialog appears and the exact menu path; also try Settings → Apps → Murmur → Battery → Unrestricted | 5 min | dialog appears or not (screenshot), menu labels | onboarding copy `ob-restricted`, `ob-battery` (DESIGN 6.1), `RestrictedSettingsProbe` |
| D7 Real-screenshot OCR | `ml/ocr/device_probe.sh` (cover first, unfold when asked) | 10 min | `.cache/ml/ocr/real/fold7/report.json` aggregate cer, unmatched_ocr_line_share for ≈ 30 screenshots; metrics.txt with real sizes/densities | CONTEXT_V2 realism check 3; OCR filters; renderer budget. Delete the folder after copying the numbers |
| D8 App end to end | `tools/device/murmur_app_smoke.sh` (WP-20): installs the app + test target, enables the service via adb, downloads the recommended pack on Wi-Fi, runs 3 scripted dictations (≈ 10 s ru, ≈ 10 s en, ≈ 60 s mixed) by speaking prompts it shows, reads timings from history | 25 min | per dictation: unit actually used for ASR and LLM, stop→done ms, ASR tail ms, LLM prefill/decode ms, OCR ms, inserted text in the target field, `:asr`/`:llm` PSS peaks, system MemAvailable drop; after 60 s idle both processes gone and MemAvailable back | latency budget ARCHITECTURE 6.2/6.3 replaced by measurements; MemoryGate margin; idle timeout default |
| D9 Benchmarks in the app | In the app: Models → each downloaded model → Run benchmark (all units) | 10 min | result grids filled; "Ran on" equals the unit; LLM sentence plausible | `ArtifactSelector` scores; hub defaults |
| D10 Real apps | Dictate once each into Telegram, Samsung Messages, Gmail, Chrome address bar, Samsung Notes, with Samsung Keyboard and with Gboard; fold/unfold during Listening once | 10 min | text inserted (or Copied with a reason) in each; no duplicate insertion with Samsung Keyboard composing; bubble keeps its edge after fold/unfold; no 2 s stall | insertion ladder order (ARCHITECTURE 11), JoinPolicy, overlay re-attach |

Legal items that block shipping NPU libraries (not technical, owner reads once):
- Hexagon SDK Community Edition licence for redistributing `libggml-htp-v79.so` (needs a Qualcomm account to read).
- `com.qualcomm.qti:qnn-runtime` licence PDF (POM names the "Qualcomm AI Hub Model License"; npu-runtime.md quotes
  an "AI Stack License" with an export clause naming Russia).

Expected total time: ≈ 2.5 h including downloads.
