# Murmur status

As of 2026-11-20, after the last integration pass (pass 8). Every planned work package is merged into one tree
(WP-01..WP-21, in three waves). This page says what works, what has only been proven on the server and the emulator,
what still needs the phone, which answers are waiting for you, and where the model training stands.

## 1. In plain words

**What Murmur is today.** An Android dictation app for the Galaxy Z Fold7 that runs entirely on the phone. You tap
a floating bubble, speak, tap again; Murmur turns the speech into text, cleans it up with a small language model
(punctuation, filler words, the style of the app you are in, words it reads from the screen), and types the result
into the field you were in. Nothing leaves the phone. There is a model hub (download, benchmark and choose speech and
cleanup models), a History of dictations with audio playback and Retry, a Dashboard with your numbers, Settings
(prompt editor, tone per app, privacy), and an onboarding flow.

**What has been proven here (server and Android emulator, no phone):**
- The whole project builds and all 1,050 automated JVM tests pass (debug and release builds).
- The release build contains none of the Qualcomm NPU libraries and no test code, as the licence situation
  requires; a control build shows the check really sees them when they are switched on.
- On the emulator, the finished app installs, starts and passes its end-to-end tests: dictating into a test app with
  the real speech model (GigaAM, Russian), the real screen reading and the real cleanup model (Qwen3.5 0.8B), all on
  the emulator's CPU. In the latest run the text arrived 1.0-1.4 s after the end of speech (2.2-2.5 s in an earlier
  run on a busier server; up to 8 s when the first dictation's model loading is not finished by the time you stop). History, Dashboard, Models and Settings show
  the data of those dictations. No network traffic, no dictated words in the system log.
- Folding and unfolding, rotation, an incoming call, the notification shade, the speech or cleanup process being
  killed, the service being switched off: the app recovers and the dictation either finishes or keeps its audio for
  Retry.
- The NPU versions of screen reading and of GigaAM (new in this pass) were tested on a simulator of the phone's NPU:
  GigaAM is as accurate as on the CPU; screen reading on the NPU is not accurate enough, so both stay switched off
  and are not offered in the app.

**What is not proven yet:** everything that needs the real phone: the NPU and its speed, the real microphone,
Samsung's One UI behaviour, real apps (Telegram, Samsung Keyboard), the real hinge, battery and memory under real
use. Section 3 lists each with the phone step that checks it. One pass of the device checklist
(`docs/DEVICE_CHECKLIST.md`, about 4 hours with downloads) answers all of them.

**What waits for you:** the phone (USB debugging on), a few answers (section 4; none of them blocks the app on your
own phone), and your approval of the GPU rental for the model training (section 5).

## 2. Done and verified on the server and the emulator

| Area | Evidence (latest run) |
|---|---|
| Build and unit tests | `tools/gw --max-workers=6 assembleDebug testDebugUnitTest test :app:assembleRelease :app:assembleDebugAndroidTest :testapps:target:assembleDebug --rerun-tasks`: BUILD SUCCESSFUL, 1,050 tests, 0 failed, 0 skipped (integration pass 8) |
| Release APK content | `tools/e2e/check_release_apk.sh --control`: arm64 only; no `libggml-htp-*`, no `libQnn*`, no `libonnxruntime_providers_qnn.so`, no test classes; the control build with `-Pmurmur.qnn=true` contains the QNN libraries and the plugin EP |
| App on the cover AVD (fakes) | `run_emulator_e2e.sh --suite harness,wiring,fast,matrix` on `murmur_fold7_cover`: all PASS (harness-target 11, harness 9, no app network bytes, wiring 2, fast 2, matrix 7); cold start 742 ms, no crash (`.cache/e2e-reports/20261120-074358-emulator-5590`) |
| Speech process tests (incl. NPU port on the CPU path) | `:infer:asr` instrumented suite on the cover AVD: OK (17 tests); the probe reports units [CPU] and `qnnep.stage=no-libs` (the NPU path is absent, as intended) |
| App on the inner AVD, real engines on the CPU | `MURMUR_EMU_CPU_HOST=1 run_emulator_e2e.sh --suite wiring,fast,real` on `murmur_fold7_inner`: wiring 2, real 1 PASS (Russian clip WER 0, stop→inserted 1.05 s cold and warm, English 1.4 s; 4 of 4 tabs show the run's data; 0 network bytes; 0 of 19 transcript words in the app's log), fast 1 of 2 failed once because the emulator's own keyboard (Gboard) crashed while loading on the freshly booted AVD (Murmur correctly fell back to "Copied"), then 2 of 2 PASS twice (`.cache/e2e-reports/20261120-075237-emulator-5590`) |
| Device smoke script, dry run | `NONINTERACTIVE=1 tools/device/murmur_app_smoke.sh` on the inner AVD (CPU build, NPU asserts skipped): Failed asserts 0 (incl. tmo10: nothing inserted after the result, no failed unit; idle exit 60.5 s; ART prompt goldens OK 36) (`.cache/device-reports/dc8-emulator-5590-20261120-075841`) |
| Earlier passes (unchanged code paths) | events, events-real, matrix, bench suites and the screen comparisons with the design frames on both AVDs (integration passes 6-7, `.cache/e2e-reports/`, `.cache/spikes/integration/wave2/`) |
| NPU ports on the NPU simulator (WP-21) | GigaAM CTC WER 5.83 % on the simulated NPU vs 6.31 % on the CPU (same 20 s windows), within the 1-point bar. Screen reading: detector on the NPU 15 of 26 pages outside the tolerance, both stages 17 of 26 (92.4 % identical lines, bar 98 %): not adopted |
| ML: cleanup data and training pipeline | 3,000 / 300 / 300 training / dev / test records (`docs/ml/DATA_CARD.md`); training, export and evaluation scripts reviewed and dry-run on the CPU (`docs/ml/TRAINING_RUNBOOK.md`) |

## 3. UNVERIFIED-ON-DEVICE (needs the Fold7) and the step that verifies it

| What | Why it matters | Step |
|---|---|---|
| Phone facts: RAM, display sizes, densities | memory budget, layouts | DC1 |
| Cleanup model on the NPU / GPU / CPU: does the NPU register from the app, speeds, cold load time | which unit the cleanup runs on by default; how long a dictation can still be cleaned up | DC2 |
| Speech models on the NPU (Parakeet, Nemotron) vs CPU, same text | default speech model and unit; QNN library compatibility | DC3 |
| Screen reading on the phone CPU and NPU; GigaAM on the NPU; NPU load time inside the 15 s budget; memory | whether any NPU screen reading or NPU GigaAM is switched on (question 19) | DC4 |
| Real microphone, Samsung foreground-service rules, screenshots on Android 17, fold events | mic handling, screenshot fallbacks | DC5 |
| One UI "restricted setting" dialog when enabling the accessibility service; battery settings | onboarding copy and steps | DC6 |
| Screen reading quality on real screenshots (dark themes, app fonts) | how much the cleanup can trust screen words | DC7 |
| End to end on the phone with the NPU build: latency for 10 s / 60 s dictations, cold start, memory peaks, idle exit, both NPU users at once, the first device check's duration | the latency budget, memory margin, timeouts | DC8 |
| Benchmarks inside the app on every unit | which unit "Auto" picks | DC9 |
| Real apps (Telegram, Samsung Messages, Gmail, Chrome, Notes) with Samsung Keyboard and Gboard; the real hinge; a real call | insertion order and reliability | DC10 |
| Downloads and cleanup jobs in the background under One UI, overnight | download resume, retention | DC11 |
| Largest system font, TalkBack, haptics | accessibility and feel | DC12 |
| Android killing the cleanup model for memory while it loads | that such a kill never disables the model | DC13 |
| The four screens with your real data on both displays | design fidelity, History player behaviour | DC14 |

## 4. Open owner questions

All in `docs/OWNER_QUESTIONS.md` with the recommendation for each; "go with the recommendation" is a valid answer.
Only the licence items block anything outside your own phone.

- **Licences** (question 18 and "Still open"): Qualcomm's QNN runtime terms and the Hexagon SDK redistribution terms.
  Until they are read, builds with NPU libraries stay on your phone; the release build contains none.
- **Hosting** (questions 1 and 17): one public Hugging Face repo of yours for the fine-tuned cleanup models and the
  NPU files of screen reading and GigaAM. Blocks only downloading those inside the app.
- **NPU adoption** (question 19, ADR-059): keep NPU screen reading and NPU GigaAM off unless DC4 shows them accurate
  and faster; sign off that VAD and T-one stay on the CPU ("NPU for all models" is not fully met in v1).
- **Publishing the training data and fine-tunes** (question 16): stays private until you confirm the terms.
- Smaller items without a blocker: OCR reference precision (3), Whisper tiny/base (2), dialect counts (12), Retry
  counting (4), prompt display with a fine-tune (5), design questions (6-9, 13-15), tone texts (10), a fresh held-out
  set of your own dictations before the next prompt change (11).

## 5. ML training state

- **Data:** ready (3,000 training, 300 dev, 300 test records of dictation cleanup, built and verified; kept private).
- **Pipeline:** ready and reviewed: one-session plan on one RTX 5090 for about 2-3 GPU-hours (hard cap 4), with
  divergence stops, a budget guard, a GPU kernel smoke test and pinned base models (`docs/ml/TRAINING_RUNBOOK.md`,
  request text in `ml/pod/REQUEST.md`).
- **Pod:** not started. The GPU provider's agent is waiting for your own confirmation of the rental (one reply to
  the pending request); nothing else blocks the run. After it: CPU evaluation against the shipped prompt, then you
  pick, and the result is uploaded to a private repository only.
- The app ships with the stock cleanup prompt (default.v1) on stock models; the fine-tunes appear in the hub as
  "in training" until question 1 is answered and the training has run.

## 6. Technical references

Decisions: `docs/DECISIONS.md` (ADR-001..092; the last pass is ADR-092). Architecture: `docs/ARCHITECTURE.md` 2.4.
Device steps: `docs/DEVICE_CHECKLIST.md`. Integration log: `.cache/progress/integration-wave1.md`. E2e reports:
`.cache/e2e-reports/`; device dry runs: `.cache/device-reports/`.
