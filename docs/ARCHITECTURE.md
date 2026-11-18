# Murmur architecture

Version 1 · 2026-11-19 · Owner: architect. Binding for all work packages (`docs/wp/`). Conflicts and their
evidence: `docs/DECISIONS.md` (ADR-nnn). Everything that only the phone can prove: `docs/DEVICE_CHECKLIST.md`.

Evidence words used here: **VERIFIED** (run on this server, command and output named in DECISIONS or the spike
report), **UNVERIFIED-ON-DEVICE** (needs the Fold7; listed in section 18 and in DEVICE_CHECKLIST), **[I]**
(inference or estimate, to be replaced by a measurement).

---

## 0. Decisions

1. **Three processes.** `:main` (UI, accessibility service, bubble, microphone, database, downloads; no native
   model code), `:asr` (sherpa-onnx + ONNX Runtime 1.32 + Silero VAD + OCR + QNN 2.52) and `:llm` (llama.cpp
   b12520 + ggml-hexagon, alone). Both inference processes are bound on bubble press, unload and **exit** after
   an idle timeout (default 60 s). ADR-001, ADR-005.
2. **Binder AIDL with JSON payloads**, PCM over a `ParcelFileDescriptor` pipe, screenshots over `SharedMemory`.
   One API version number checked at bind. ADR-001.
3. **One dictation state machine**, a pure reducer (`DictationMachine.reduce`) plus an effect executor
   (`DictationOrchestrator`) on one serial coroutine context. Section 5.
4. **ASR during dictation (D4):** batch models get VAD pause-cut segments decoded as they close (asr-ocr spike A6:
   no word loss, better than whole-clip); streaming models stream. One `AsrSession` interface hides both. At stop
   only the open tail is decoded. The LLM runs once, at the end, over the full transcript. ADR-025, ADR-037.
5. **Default models:** ASR Parakeet TDT 0.6B v3 (NPU context binary on SM8750, CPU int8 elsewhere and as
   fallback); cleanup LFM3 1.7B Q4_0 (NPU by default) per the catalog, Qwen3.5 0.8B preferred on CPU. Final unit
   per model from the on-phone benchmark. ADR-014, ADR-029.
6. **Catalog-driven hub (D3):** one JSON catalog (`core/catalog/src/main/resources/catalog/catalog.json`,
   generated from the verified research catalogs, every file re-checked against the HF / GitHub APIs) drives
   the hub, downloads, unit selection and benchmarks. No model-specific code outside the catalog and the runtime
   adapters. ADR-030.
7. **Screen context = OCR (D11):** window screenshot about 0.6 s after start, OCR in `:asr` on the ORT CPU EP with
   the exact `ml/ocr` pipeline (parity tests), ctx v2, Kotlin port of `prompts/render.py`. Screenshot and OCR text
   live in memory only. ADR-013, ADR-023.
8. **Prompts packaged from `prompts/` at build time (D8)**; the Kotlin renderer and guard are byte-exact ports with
   golden tests. Users edit the system-prompt instructions and "My preferences", never the request layout. ADR-019.
9. **Privacy (D9):** network only for model downloads (Hugging Face + GitHub release assets, ADR-016); the ONNX
   Runtime telemetry provider is removed from the manifest (ADR-022); no analytics; context never persisted;
   history auto-deletes per calendar day.
10. **Manual DI, Navigation 3, material3-adaptive, Room + KSP, typed DataStore, kotlinx.serialization.** Every
    version is the latest stable and was resolved by `tools/gw assembleDebug`. ADR-010, ADR-012, ADR-032.
11. **minSdk 34, target/compile 37 (37.2).** Release ABI arm64-v8a; debug adds x86_64 with CPU engines (D6).
12. **The phone comes last (D12):** every work package is accepted on this server (JVM tests, x86_64 emulator,
    fakes, small real models). Phone-only checks are collected in `docs/DEVICE_CHECKLIST.md` as one pass.

---

## 1. Overview

```
 any app ──focus──▶ :main ────────────────────────────────────────────────────────────────────────────────────
                    MurmurAccessibilityService ── OverlayHost (bubble, TYPE_ACCESSIBILITY_OVERLAY)
                          │ press                         ▲ DictationBubble (StateFlow)
                          ▼                               │
                    DictationOrchestrator (reducer + effects, serial) ── Room/DataStore (history, metrics, settings)
                     │ AudioCapture (FGS mic, 16 kHz PCM) ── AudioStore (Opus) ── LevelMeter ─▶ bubble bars
                     │ TargetReader (snapshot, window screenshot) ── Inserter (commitText → paste → setText)
                     │ PromptRenderer + OutputGuard (pure Kotlin ports)
                     │
          binder ────┼───────────────────────────────┬─────────────────────────────────────────────────────────
                     ▼                               ▼
   :asr  AsrService: sherpa-onnx (QNN | CPU),     :llm  LlmService: llama.cpp + ggml-hexagon (NPU | GPU | CPU)
         Silero VAD + Segmenter, PP-OCR (ORT CPU)        prefix snapshot cache, greedy decode
         ◀── PCM pipe, SharedMemory frame               ◀── prefix + suffix text, ──▶ streamed pieces
```

---

## 2. Module graph and dependency rules

26 Gradle modules + one test app (`:testapps:target`). Convention plugins in `build-logic/` (`murmur.jvm.library`,
`murmur.android.library`, `murmur.android.compose`). Namespaces `app.murmur.<path>`.

| Layer | Module | Kind | Contents (contract now, implementation by the WP named) |
|---|---|---|---|
| core | `:core:model` | JVM | ids, `ComputeUnit`, `UnitPreference`, `ModelKind`, `Language`, `MurmurClock`, `MurmurError` taxonomy, `Outcome`, generated `ProductName` |
| core | `:core:catalog` | JVM | catalog schema + parser + validator + bundled `catalog.json` + `ArtifactSelector` + custom-model contract (WP-13) |
| core | `:core:ocr` | JVM | OCR types, `OcrConfig` (= `ml/ocr/config.py`), pure pipeline port, `OcrModels` seam (WP-07) |
| core | `:core:context` | JVM | ctx v2 types (= `prompts/context.schema.v2.json`), `A11ySnapshot`, privacy decision, field kinds, `ContextAssembler` (WP-08) |
| core | `:core:prompt` | JVM | prompt types, packaged `prompts/`, `PromptRenderer`/`OutputGuard` ports (WP-04) |
| core | `:core:audio` | JVM | PCM format, `PcmChunk`, `VadModel`, `Segmenter` (WP-06), `LevelMeter` (WP-09) |
| core | `:core:engine` | JVM | `AsrEngine/AsrSession/AsrEvent/Transcript`, `LlmEngine/LlmRequest/LlmStats`, `OcrService`, `InferenceHost`, `LoadSpec`, `Engines` |
| core | `:core:store` | JVM | repositories + records: history, metrics, settings, install state, benchmark, audio store, `RetentionPolicy`, `StatsCalculator` (WP-12) |
| core | `:core:benchmark` | JVM | `BenchmarkRunner`, steps, WER, summary sentence (WP-14) |
| core | `:core:dictation` | JVM | states/events/effects, `BubbleMapping`, `DictationConfig`, ports, `DictationOrchestrator` (WP-11) |
| test | `:testing` | JVM | deterministic fakes of every contract + `FakeWorld` |
| infer | `:infer:api` | Android | AIDL, `InferApi` (JSON, versions), `ServiceConnector`, binder clients `AsrClient/LlmClient/BinderEngines` (WP-05) |
| infer | `:infer:asr` | Android | `AsrService` (`:asr`), sherpa adapters, Silero VAD (WP-06), `ocr/` ORT sessions (WP-07); ORT 1.32.0 AAR |
| infer | `:infer:llm` | Android | `LlmService` (`:llm`), `LlmNative` JNI facade (WP-10); NPU/FastRPC manifest entries |
| data | `:data:db` | Android | Room DB (history, metrics, benchmark, custom_model), DataStore settings, retention job (WP-12) |
| data | `:data:models` | Android | storage layout, UIDT download job, verify/extract, catalog repo, model resolver, HF custom models (WP-13) |
| platform | `:platform:a11y` | Android | accessibility service, target snapshot, screenshot, insertion ladder, clipboard, bridges (WP-02) |
| platform | `:platform:overlay` | Android+Compose | bubble visuals, visibility policy, placement/snap math, overlay window host (WP-03) |
| platform | `:platform:audio` | Android | AudioRecord capture, microphone FGS, Opus history store (WP-09) |
| ui | `:ui:design` | Android+Compose | tokens generated from `docs/design/tokens.json`, `MurmurTheme`, fonts, icons, components (WP-01) |
| ui | `:feature:history` / `dashboard` / `models` / `settings` / `onboarding` | Android+Compose | screens + view models (WP-16..19, WP-15) |
| app | `:app` | Android app | `MurmurApp`, `AppContainer` (wiring), `MainActivity`, `AppShell` (nav), manifest (WP-15 shell, WP-20 wiring) |
| test | `:testapps:target` | Android app | "another app" with a text field for emulator end-to-end tests (WP-20) |

**Dependency rules** (reviewers reject violations; `./gradlew :app:dependencies` shows the graph):

1. `core:*` and `:testing` are pure Kotlin/JVM: no `android.*` imports. They depend only on other `core:*` modules
   along this order: `model` ← `catalog`, `ocr`, `audio`, `store` ← `context`(ocr) ← `prompt`(context) ←
   `engine`(audio, ocr) ← `benchmark`(engine, store, catalog) ← `dictation`(everything core).
2. `infer:*` depend only on `core:*` (+ `infer:api`). They never depend on `data`, `platform`, `ui`, `feature`.
3. `data:*` depend on `core:*` (`data:models` may use `data:db`). `platform:*` depend on `core:*` and
   `platform:overlay` → `ui:design`; `platform:a11y` → `platform:overlay`.
4. `feature:*` depend on `ui:design` and `core:*` only. **Features never depend on each other**, on `data:*`,
   `infer:*` or `platform:*`; they receive interfaces through a `XxxDeps` class built by `:app`.
5. Only `:app` sees every module. Only `AppContainer` constructs implementations.
6. A contract change (any public type in `core:*` or `infer:api`) needs an ADR line in DECISIONS and a note to the
   consuming WPs; the implementing WP may add members, not break existing ones.

### 2.1 Build and native libraries

- Kotlin 2.4.21 via AGP 9.5.1 built-in Kotlin (TOOLCHAIN D1), JVM target 17, JDK 21.
- Native runtimes are **prebuilt by scripts, never by Gradle** (ADR-008/009): `native/llama/build_android.sh`
  and `native/sherpa/build-android.sh` write `<murmur.nativeLibsDir>/{llama,sherpa}/<abi>/*.so`
  (default `.cache/native/jniLibs`). `:infer:llm` / `:infer:asr` add those directories as `jniLibs` sources when
  present; without them the APK still builds and the runtime reports `EngineRuntimeMissing`.
- ONNX Runtime comes once, from Maven (`onnxruntime-android:1.32.0`); sherpa-onnx is linked against that exact
  `libonnxruntime.so` (asr-ocr spike: byte-identical, `cmp`).
- `packaging.jniLibs.useLegacyPackaging = true` (ADR-007): ggml-hexagon's DSP skel and the CPU-variant loader
  need real directories.
- QNN 2.52 (`com.qualcomm.qti:qnn-runtime`) is added by WP-06 behind `-Pmurmur.qnn=true` once the licence is
  read (needsFromOwner); then only `libQnnHtp`, `libQnnSystem`, `libQnnHtpV79Stub`, `libQnnHtpV79Skel` are kept.
- Debug APK today: 70 MB (ORT for two ABIs dominates). Release (arm64 only, with llama + sherpa + QNN) [I] ≈ 60-70 MB.

---

## 3. Product surfaces (what each module renders)

DESIGN.md is binding (D7). History is the start tab; cover display = bottom bar, inner = navigation rail, chosen
by `WindowSizeClass` (`currentWindowAdaptiveInfoV2()`), never by device detection. List-detail on the inner display
for History, Models, Settings. The bubble follows the host app's UI mode.

---

## 4. Process model and IPC

### 4.1 Processes

| Process | Declared in | Holds | Started | Ends |
|---|---|---|---|---|
| `:main` (`app.murmur`) | `:app` | Application, AppContainer, accessibility service + overlay, AudioRecord + `RecordingFgs`, Room, DataStore, UIDT download job, UI | by the system (accessibility binding keeps it alive) | never while the service is enabled; crash → bubble gone 11 s (platform spike g) |
| `:asr` | `:infer:asr` manifest (`android:process=":asr"`) | `AsrService`: sherpa-onnx, ORT 1.32 (+QNN 2.52), Silero VAD, OCR sessions | `bindService(BIND_AUTO_CREATE or BIND_IMPORTANT)` on bubble press, from the service context | `onUnbind` → unload → `killProcess` after the idle timeout |
| `:llm` | `:infer:llm` manifest | `LlmService`: llama.cpp + ggml-hexagon + OpenCL + CPU variants | same, in parallel with `:asr` when cleanup is on and memory allows | same; process exit is the only way to drop the FastRPC session and rpcmem (llm spike D7) |

Why two inference processes (D5): QNN and ggml-hexagon each open their own FastRPC session and skel on the cDSP;
two QNN versions or QNN + ggml-hexagon in one process are untested on a DSP (asr-ocr 5.2), and each process must be
killable on its own to release memory. The `Application` runs in all three processes; `MurmurApp` builds the
`AppContainer` only when `Application.getProcessName() == packageName`. Each service installs a quiet crash handler
(`InferProcessLifecycle`) so that a crash never shows a dialog (platform spike f).

### 4.2 Lifecycle (D5)

```
press ─▶ connect(:asr) ─▶ loadAsr(asr, vad) ─▶ startSession(pipe) ─▶ …segments… ─▶ finish
     └─▶ connect(:llm) ─▶ load(llm) ─▶ setPrefix(cache key) ──────────────────────▶ generate ─▶ Done
                                                     (both in parallel, ASR first priority)
after Done/Failed: scheduleIdleExit(idleUnloadSec, default 60 s) on both hosts
idle expired: unbind ─▶ service.onUnbind: unloadAll() ─▶ (native) mallopt(M_PURGE_ALL) ─▶ killProcess(myPid)
next press within the idle window: no load at all (warm)
benchmark: shutdown() first (cold load), same code path
memory pressure (onTrimMemory BACKGROUND in :main, or LLM load refused by MemoryGate): shutdown(:llm) first
```

OCR is loaded in `:asr` right after the ASR model (it is 10 MB) and used once per dictation.

### 4.3 IPC surface (`infer/api/src/main/aidl/app/murmur/infer/api/`)

| AIDL | Method | Payload (JSON of `:core:engine` types, `InferApi.json`) | Client timeout (`BinderTimeouts`) |
|---|---|---|---|
| `IAsrService` | `apiVersion()` | `InferApi.API_VERSION` must match, else `Internal("api")` | bind 3 s |
| | `probe()` / `memory()` | `ProcessProbe` / `ProcessMemory` | 2 s |
| | `loadAsr(asr, vad, cb)` | `LoadSpec` ×2 → `LoadInfo` / `MurmurError` | 15 s |
| | `loadOcr(det, rec, cb)` | `LoadSpec` ×2 → `LoadInfo` | 15 s |
| | `startSession(options, pcmRead, cb)` → `IAsrSession` | `AsrSessionOptions`; PCM16 LE mono 16 kHz on the pipe; events `AsrEvent` | 2 s |
| | `IAsrSession.finish(cb)` / `cancel()` | → `Transcript` | `asrFinalTimeoutMs(tail)` = 4 s + 0.5 s per tail second |
| | `recognizeScreen(SharedMemory, w, h, exclude, cb)` | ARGB_8888 ints, read-only mapping → `ScreenText` | 4 s (`ocrBudgetMs`) |
| | `unloadAll()` | — | 2 s |
| `ILlmService` | `load(spec, cb)` | `LoadSpec` → `LoadInfo` | 30 s |
| | `setPrefix(prefixSpec, cb)` | `PrefixSpec{text, cacheKey}` → `PrefixInfo` | 30 s |
| | `generate(request, ILlmStreamCallback)` | `LlmRequest` → `onPiece(text)`*, `onDone(text, LlmStats)` / `onError` | adaptive (5.3) |
| | `cancel()` / `unload()` | — | 2 s |

Rules: every method returns immediately; work runs on the service's engine thread; results come back through
`oneway` callbacks. Text is a `String` over binder (UTF-16 parcel, emoji-safe) and UTF-8 bytes at JNI (llm spike
D11). Binder transactions stay far below 1 MB (largest: prompt prefix ≈ 25 KB). The 17 MB frame never goes
through a parcel: `:main` copies the `ARGB_8888` bitmap into `SharedMemory`, `setProtect(PROT_READ)`, passes the
fd, closes its copy after the callback.

**PCM path.** The capture thread in `:main` writes each 20 ms frame to (a) the session's pipe (`ParcelFileDescriptor
.createPipe()`, write end in `:main`), (b) the history `AudioWriter`, (c) an in-memory session buffer (≤ 9.6 MB for
5 min) used for replay after a crash, (d) the `LevelMeter`. The `:asr` pipe reader buffers until the model is
loaded, so audio is never dropped (asr-models 5 "mandatory").

### 4.4 Crash and timeout handling

| Event | Detection in `:main` | Action |
|---|---|---|
| `:asr` dies while Listening | `onServiceDisconnected`/`onBindingDied`, `EPIPE` on the pipe | keep recording; re-bind once; new session; replay the session buffer from sample 0; second death → stop, `Failed(ProcessDied(ASR))`, entry kept with audio ("Try again") |
| `:asr` dies or times out in Finishing | same / `Timeout(ASR_FINAL)` | `shutdown()`, re-bind once, replay full PCM to a fresh session, `finish()`; else Failed with audio |
| ASR load fails (`EngineLoadFailed`, `UnitUnavailable(NPU)`) | `loadAsr` error | NPU artifact → retry once with the CPU artifact if installed (bubble keeps Listening); none → Failed "Couldn't load the speech model" with audio kept |
| `:llm` dies or load fails | disconnect / `loadLlm` error | cleanup skipped: "Inserted, no cleanup", `cleanup_outcome = skipped:<code>` |
| `:llm` timeout in Polishing | adaptive timer | `cancel()`; if no `onDone` within 1 s → `shutdown(:llm)`; insert the raw transcript (guard `fallback_text`) |
| OCR late or failed | `ocrBudgetMs` or error | cleanup runs without `screen` (ctx v2 allows it) |
| binder call exceeds its timeout | coroutine `withTimeout` | treated as death of that process: `shutdown()` (kill) then the row above |
| `:main` dies | (system) | dictation lost; service restarts after ~11 s; at next start orphaned audio files (no history row) are deleted |
| accessibility service disconnected mid-dictation | `A11yBridge.service == null` | stop capture, save as Failed with audio (`perm.a11y`), Dashboard/Settings banner |

Every async completion carries the `SessionId`; the reducer drops stale ones (invariant I2).

---

## 5. Dictation state machine

Types: `core/dictation/src/main/kotlin/app/murmur/core/dictation/State.kt`. Reducer: `DictationMachine.reduce(state,
event, config) → Transition(state, effects)` (WP-11). The orchestrator runs effects and feeds completions back as
events, all on one serial dispatcher.

### 5.1 States

| State | Meaning | Bubble (DESIGN 5.3) |
|---|---|---|
| `Idle` | no dictation; visibility policy decides | Hidden / Rest / Ready (policy, `BubblePolicy.merge`) |
| `Arming(mode)` | press accepted; FGS + AudioRecord starting; engines binding | Listening (tap) or Hold to talk, immediately on press |
| `Listening(mode, warn)` | audio flowing to the ASR session | Listening / Hold to talk; timer turns warn at 4:30 |
| `Finishing(showTranscribing)` | stop received, ASR tail decoding | Listening visuals for 300 ms, then Transcribing |
| `Polishing(transcript)` | LLM cleanup running | Polishing |
| `Delivering(text, cleaned)` | clipboard + insertion + history write | unchanged from the previous working state |
| `Done(outcome)` | result shown, dismiss timer | Inserted / Inserted, no cleanup / Copied only / Error / (Discarded → Idle) |

Dragging is overlay-local and allowed in every working state; taps on working pills are ignored (DESIGN 5.3).

### 5.2 Transitions

`*` = any state. "eff" = effects emitted. Events from a stale session are ignored everywhere.

| From | Event | To | Effects / notes |
|---|---|---|---|
| Idle | `Input(Tap)` / `NotificationStart` | Arming(TAP) | preflight (sync): service connected, mic permission, ASR model resolved, field not password, not excluded, storage > 50 MB, thermal not critical; fail → `Done(Failed(e))` without starting anything. eff: `StartCapture`, `PrepareEngines`, `CaptureContext(600 ms)`, `StartTimer(MIC_START)`, `Haptic(CONFIRM)` |
| Idle | `Input(HoldStart)` (≥ 250 ms on Ready, setting on) | Arming(HOLD) | same |
| Arming | `MicStarted` | Listening | eff: `CancelTimer(MIC_START)`, `StartTimer(WARN 270 s)`, `StartTimer(MAX 300 s)` |
| Arming | `MicFailed(e)` / `TimerFired(MIC_START)` | Done(Failed(MicBusy / MicStartFailed)) | eff: `CancelEngines`, `DiscardCapture`, `Haptic(REJECT)` |
| Arming, Listening | `Input(Cancel)` / `HoldSlideOff` | Done(Discarded) → Idle | eff: `DiscardCapture`, `CancelEngines` (keep processes warm), no history |
| Listening(TAP) | `Input(Tap)` | Finishing | eff: `StopCapture`, `FinishAsr`, `StartTimer(TRANSCRIBING_LABEL 300 ms)`, `StartTimer(ASR_FINAL)` |
| Listening(HOLD) | `Input(HoldRelease)` | Finishing | same |
| Listening | `TimerFired(WARN)` | Listening(warn) | — |
| Listening | `TimerFired(MAX)` | Finishing | auto stop, normal processing (DESIGN 5.5) |
| Listening | `AsrLoadFailed(e)` | Listening | try the CPU artifact once (4.4); keep recording; if no fallback, remember the error for Finishing |
| Listening | `ProcessDied(ASR)` | Listening | re-bind + replay (4.4) |
| Listening | `ExternalStop(call / service off)` | Finishing (call) or Done(Failed(AccessibilityOff)) | audio kept |
| Finishing | `TimerFired(TRANSCRIBING_LABEL)` | Finishing(showTranscribing) | — |
| Finishing | `TranscriptReady(t)` with `t.speechMs < 500` or empty text | Done(Failed(NoSpeech)) | eff: `RecordFailure(keepAudio=true)` (DESIGN: saved as Failed, re-runnable) |
| Finishing | `TranscriptReady(t)`; cleanup on, LLM ready or loading, not password, precheck passes | Polishing(t) | eff: `RunCleanup(t)`, `StartTimer(LLM adaptive)` |
| Finishing | `TranscriptReady(t)`; cleanup None / LLM failed / low memory / password / precheck skip | Delivering(t.text, cleaned=false) | eff: `Deliver(raw, skipReason)`; precheck `skip` → `Done(Failed(NoSpeech))` instead (noise/hallucination, nothing inserted) |
| Finishing | `AsrFailed(e)` / `TimerFired(ASR_FINAL)` / second `ProcessDied(ASR)` | Done(Failed(e)) | eff: `RecordFailure(keepAudio=true)` ("Try again") |
| Polishing | `CleanupDone(text, cleaned, reason)` | Delivering | eff: `CancelTimer(LLM)`, `Deliver(text, raw, cleaned, reason)` |
| Polishing | `TimerFired(LLM)` / `ProcessDied(LLM)` | Delivering(raw, cleaned=false) | eff: `Deliver(raw, skipReason="timeout"/"llm_died")` (the executor cancels generation) |
| Delivering | `Delivered(Inserted)` | Done(Inserted(noCleanup = !cleaned)) | eff: `Haptic(CLOCK_TICK)`, `StartTimer(RESULT_DISMISS 1.2 s)`, `ScheduleIdleUnload` |
| Delivering | `Delivered(CopiedOnly)` | Done(Copied) | `StartTimer(RESULT_DISMISS 4 s)`, `ScheduleIdleUnload` |
| Delivering | `Delivered(DiscardedPassword)` | Done(Discarded) → Idle | nothing stored, nothing copied |
| Done | `TimerFired(RESULT_DISMISS)` / next `Input(Tap)` | Idle | a tap during Done starts a new dictation (Arming) |
| Done(Copied/Error) | `Input(ResultTap)` | Idle | opens the entry (Copied) or runs the action (Retry → Arming; Open → Models) |
| * | `Input(Tap)` while Finishing/Polishing/Delivering | unchanged | ignored (prevents a double stop) |

### 5.3 Timeouts (`DictationConfig`)

| Timer | Value | Source |
|---|---|---|
| MIC_START | 2 s | spike: FGS 8-90 ms + first buffer 67-209 ms |
| screenshot delay | 600 ms after press; ≥ 340 ms between captures of one window | D11, platform spike (333 ms limit) |
| OCR budget | 4 s after the screenshot | [I] |
| WARN / MAX | 4:30 / 5:00 | DESIGN 5.5 |
| TRANSCRIBING_LABEL | 300 ms | DESIGN 5.3 |
| ASR_FINAL | 4 s + 0.5 s × tail seconds | [I]; NPU = one 20 s window call |
| LLM | `min(20 s, 4 s + 1.5 × expected)`; expected from the latest benchmark of the active artifact/unit; 20 s without a benchmark | ADR-015 (replaces DESIGN's fixed 6 s) |
| DELIVERY | 2 s | [I] |
| RESULT_DISMISS | Inserted 1.2 s, Copied 4 s, Error 6 s | DESIGN 5.3 |
| idle unload | 60 s (`Settings.idleUnloadSec`) | D5 |

### 5.4 Cancellation

- User cancel (×, slide-off) in Arming/Listening: capture discarded, ASR session cancelled, LLM generation never
  started, nothing stored. Processes stay warm (idle timer).
- Cancel is impossible after the stop (working states ignore taps, DESIGN 5.3).
- LLM cancel latency: CPU = one graph node, NPU/GPU = one `llama_process` call (≤ one 512-token chunk) (llm spike).

### 5.5 Failure paths and what the user sees

| Case (DESIGN 5.5) | Error | Bubble | History |
|---|---|---|---|
| < 0.5 s speech | `NoSpeech` | "Didn't catch that" + Retry | Failed, audio kept |
| mic busy / silenced | `MicBusy`, `MicSilenced`, `MicStartFailed` | "Microphone is busy" | none |
| no speech model | `NoAsrModel` | "Download a speech model first" + Open | none |
| ASR load failed | `EngineLoadFailed(ASR)`, `OutOfMemory(ASR)`, `EngineRuntimeMissing` | "Couldn't load the speech model" + Retry | Failed, audio kept |
| ASR crashed/timed out twice | `ProcessDied(ASR)`, `Timeout(ASR_FINAL)` | transcription failed + Open entry | Failed, audio kept |
| cleanup failed/timed out/low memory | `LlmFailed`, `Timeout(LLM)`, `OutOfMemory(LLM)`, guard fallback | "Inserted, no cleanup" | Inserted/Copied, `cleanup_outcome = skipped:<code>` or `fallback:<rule>` |
| insertion rejected / no field / app changed | `InsertRejected`, `NoEditableField`, `TargetChanged` | "Copied. Paste it anywhere" | Copied |
| password field at delivery | — | nothing (Idle) | nothing |
| accessibility service killed | `AccessibilityOff` | no bubble; red banner in Dashboard/Settings | Failed if mid-dictation |

### 5.6 Invariants

- I1: one session at a time (`SessionId` increments per press).
- I2: every completion event carries its session; stale ones are dropped.
- I3: the FGS lives only while AudioRecord records.
- I4: the screenshot, OCR lines and ctx v2 exist only in memory of the current session and are dropped at `Done`.
- I5: text is always copied to the clipboard before the insertion attempt (BRIEF flow 7), except password fields.
- I6: `Done(Failed)` with audio is the only path that keeps audio without text.

---

## 6. End-to-end sequence and latency budget (Fold7)

### 6.1 Sequence (times from the press, overlapped work in brackets)

```
t=0       press → preflight (<5 ms) → Arming; bubble Listening; haptic
t+10..90  RecordingFgs started; AudioRecord.startRecording        (platform spike: 8-90 ms)
t+70..210 first buffer → Listening (PCM buffered in :main + pipe)
[t+0..0.3 s] bind :asr + :llm (process start) [I]
[t+0.3..1.3 s] :asr loads Parakeet QNN context (+VAD, OCR)        (0.5-1.2 s SM8850 sherpa log [I])
[t+0.3..3 s] :llm loads LFM3 Q4_0 into rpcmem + restores prefix  (prefix restore 4-11 ms VERIFIED host; load UNVERIFIED-ON-DEVICE)
[t+0.6 s] window screenshot (6-70 ms call + copy), SharedMemory → :asr OCR (det 960 + ~20 lines) [I] 0.4-1 s
[ongoing] each VAD pause closes a segment → decoded (NPU) → Committed
stop tap → Finishing: tail decode → Transcript → precheck → render (ctx v2) → generate → guard → Delivering
          → clipboard + commitText + read-back → Done(Inserted) → history + metrics rows → idle timer
```

### 6.2 After-stop budget, 10 s dictation (≈ 25 Russian words; 34 tokens Qwen3.5, 46 tokens LFM3; llm-models 6)

| Step | NPU path (Parakeet NPU + LFM3 NPU) | CPU path (Parakeet CPU + Qwen3.5 0.8B CPU) | Source |
|---|---|---|---|
| VAD flush + tail decode | 0.10-0.20 s (one 20 s window) | 0.3-0.6 s (≤ 5 s tail at RTF 0.06-0.1) | AI Hub encoder 38 ms; S24 CPU RTF 0.064-0.104 [I] |
| precheck + render + binder | 0.02 s | 0.02 s | [I] |
| LLM suffix prefill (Style + ≤1,400-char context ≈ 500 tok + transcript) | 560 tok / 2,597 tok/s = 0.22 s | 500 tok / 569 tok/s = 0.88 s | AI Hub S25 perf (VERIFIED as published) |
| LLM decode (≈ transcript length) | 46 / 40.9 tok/s = 1.12 s | 34 / 83.8 tok/s = 0.41 s | same |
| guard + insertion + read-back | 0.05-0.15 s | 0.05-0.15 s | [I] |
| **Total after stop** | **≈ 1.5-1.7 s** | **≈ 1.7-2.1 s** | design sample "1.4 s" |

### 6.3 After-stop budget, 60 s dictation (≈ 150 words; 204 tokens Qwen3.5, 278 tokens LFM3)

| Step | NPU path | CPU path (Qwen3.5 0.8B) |
|---|---|---|
| tail decode | 0.10-0.20 s (earlier segments already decoded) | 0.3-0.6 s |
| LLM prefill | 790 tok / 2,597 = 0.30 s | 710 tok / 569 = 1.25 s |
| LLM decode | 278 / 40.9 = **6.8 s** | 204 / 83.8 = **2.4 s** |
| insertion | 0.1 s | 0.1 s |
| **Total after stop** | **≈ 7.3 s** | **≈ 4.1 s** |

Conclusions: decode dominates long dictations; Qwen3.5 0.8B on CPU wins there by Qualcomm's numbers, LFM3 on NPU
wins short ones by prefill. That is why the unit and model are picked by the on-phone benchmark (D2) and why the LLM
timeout is adaptive (ADR-015). Optional optimisation behind a flag, off by default: prefill the user message up to
`<transcript>\n` while the user speaks, accepted only if `tokenize(prefix)+tokenize(suffix) == tokenize(full)` on
the golden corpus (WP-10). Chunked cleanup per segment would cut the 60 s case but contradicts D4; it is listed as an
owner decision (ADR-037). Every number above is UNVERIFIED-ON-DEVICE and replaced by DEVICE_CHECKLIST step results.

---

## 7. Memory budget (12 GB device)

| Item | Resident while dictating | Basis |
|---|---|---|
| Android + One UI + launcher + foreground app | ≈ 5-7 GB used; MemAvailable typically 4-6 GB [I] | UNVERIFIED-ON-DEVICE (device step D1 prints MemAvailable) |
| `:main` idle | target < 120 MB PSS | android-platform 10 [I] |
| `:main` dictating | + 17 MB frame + 17 MB SharedMemory copy (freed after OCR) + ≤ 10 MB PCM buffer + encoder ≈ 170 MB | computed |
| `:asr` Parakeet NPU + VAD + OCR | ≈ 0.6-1.0 GB (context binaries 653 MB on disk) + ORT arenas 60-140 MB [I] | catalog memoryMb 900 |
| `:asr` Parakeet CPU int8 fallback | 1.3 GB | host peak RSS VERIFIED (asr-ocr 3) |
| `:llm` LFM3 Q4_0 NPU | ≈ 1.1-1.2 GB rpcmem (weights copied, not mmapped) + 77 MiB compute + 48 MB KV (4,096 ctx) + 17 MB snapshot | llm spike 3.3, 5.4 |
| `:llm` LFM3 CPU (`use_mmap=0`) | 1.25 GB | VERIFIED host (1,243 MB peak) |
| `:llm` Qwen3.5 0.8B CPU | 0.71 GB | VERIFIED host |
| **Peak total** | **≈ 2.4 GB (NPU+NPU) … 2.9 GB (CPU+CPU)** | sum |
| Idle (after 60 s) | `:main` only, ≈ 100 MB | D5 |

Policy (`MemoryGate`, WP-11): after the ASR load starts, the LLM loads in parallel only if
`availMem − artifact.memoryMb[unit] ≥ 600 MB`. Otherwise the LLM loads after the ASR tail is done and `:asr` has
been shut down (adds the LLM load to the after-stop time), or, if still short, cleanup is skipped ("Inserted, no
cleanup", reason `low_memory`). CPU LLMs load with `use_mmap=0` (ADR-031). `n_outputs_max = n_seq_max` (llm spike
D10). Android 17 MemoryLimiter kills are watched through `ApplicationExitInfo` (REASON_OTHER, "MemoryLimiter")
and counted in the device report.

---

## 8. Model catalog, downloads, storage

### 8.1 Schema (`core/catalog/.../CatalogSchema.kt`, schema 1)

`Catalog{schema, catalogVersion, generated, defaults{asr{ru+en,en,ru}, llm, vad, ocrDet, ocrRec}, packs[],
models[]}`. `ModelEntry{id, kind(asr|llm|ocr-det|ocr-rec|vad), displayName, publisher, family, baseRepo,
description, params, paramsLabel, languages{codes, count, mode}, license{id,url,note}, tier, status, asr{streaming,
punctuation, casing, sampleRate, previewOnly}, llm{template, promptProfile, nCtx}, ocr{stage, pipelineVersion},
artifacts[], followUps[{id:"npu-port", note}], notes, evidence, origin(bundled|refreshed|custom)}`.
`Artifact{id, runtime (RuntimeKeys), format, quant, units[npu|gpu|cpu], preferredUnit, socs[], files[], layout{role →
path}, maxWindowSec, memoryMb{unit → MB}}`. `FileSpec{name, url, bytes, sha256 (mandatory), source(huggingface|github),
repo, revision (full commit sha or release tag), path, archive(tar.bz2|zip)}`.

`CatalogValidator` enforces: unique ids, defaults and packs reference existing models, known runtime keys, every
file has a 64-hex sha256 and bytes > 0, URL host in {huggingface.co, github.com}, HF URLs pinned to the revision,
safe local names. The bundled catalog (27 models, 63 files) passes (`BundledCatalogTest`), and its generator
`core/catalog/tools/build_catalog.py` re-verified every size and sha256 against `POST /api/models/<repo>/paths-info/<rev>`
and the GitHub release-asset digest (VERIFIED 2026-11-19, log `.cache/spikes/architect/build_catalog.log`).

### 8.2 Unit selection (D2)

`ArtifactSelector.candidates(entry, device)` lists every (artifact × unit) with a blocker
(`RUNTIME_NOT_SHIPPED`, `WRONG_SOC`, `UNIT_NOT_AVAILABLE`). `choose()` returns: the pinned unit if usable; else
the lowest benchmark score (ASR: processing time per audio second; LLM: end-to-end ms of the fixed case); else the
artifact's `preferredUnit`; else NPU > GPU > CPU. The hub shows the claimed units as badges, the chosen unit, "NPU
not supported yet" for models without an NPU artifact (followUp `npu-port`), and "Ran on" from the benchmark.

### 8.3 Storage layout (`data/models/.../Storage.kt`)

`noBackupFilesDir/models/<artifactId>/` holds the files, `<name>.part` while downloading (its length is the resume
offset), archives extracted in place then deleted, `installed.json` written last and atomically (the artifact is
installed iff it exists, with the verified sha256 of each file), and `prefix-<key>.state` LLM snapshots. Never in
backups, never in the APK (BRIEF). Peak disk = archive + extracted (×2 for the NPU tarballs).

### 8.4 Device profile

On first launch per app version and after a crash-free NPU load failure, `:main` binds both processes and calls
`probe()`: implemented runtime keys, units that really initialised (QNN HTP backend created; ggml HTP0 / GPUOpenCL
registered), facts (SoC, HTP arch, QNN build, CPU variant). Cached in DataStore; feeds `DeviceProfile`. On the
emulator: soc null, units {CPU}.

### 8.5 Download, verify, install (WP-13; lifted from the platform spike's UIDT job)

1. Pre-check free space ≥ peak install bytes + 200 MB, Wi-Fi-only setting.
2. Schedule a user-initiated data transfer job (`JobInfo.setUserInitiated`, `RUN_USER_INITIATED_JOBS`); one artifact
   per job; progress notification via `setNotification(DETACH)`; `updateTransferredNetworkBytes`.
3. Per file: HTTPS GET with `Range: bytes=<part length>-`; every redirect host must end with an allowed suffix
   (`huggingface.co`, `.hf.co`, `github.com`, `.githubusercontent.com`); stream SHA-256; `fsync`; on mismatch delete
   the part and report `ChecksumMismatch` ("Download was damaged. Murmur deleted it.").
4. Extract archives with path-traversal checks (commons-compress, ADR-017); verify `layout` paths exist.
5. Write `installed.json`; state → `Preparing` while the first load creates QNN contexts / the LLM prefix snapshot;
   → `Installed`.

### 8.6 Custom models (D3)

The hub's "Add a model" sheet takes a Hugging Face repo id (+ optional revision and file). `HfCustomModelResolver`
resolves the revision to a commit sha, lists files with `paths-info` (sizes + LFS sha256; small non-LFS files are
hashed after download), detects the layout (`SherpaLayout` for ASR; one GGUF for the LLM, Q4_0 preferred), asks
for the chat template when the GGUF's architecture is not one of the four known templates, and stores the entry
(origin `custom`) in Room. It then behaves like any catalog model, including Benchmark.

### 8.7 Catalog refresh

Bundled only in v1. The schema carries `catalogVersion`; a later refresh downloads the same document from an
owner-controlled HF repo (needs the owner's repo; open question) and replaces the bundled copy only if it validates
and is newer.

---

## 9. Context pipeline (D11, CONTEXT_V2)

### 9.1 Screenshot (WP-02)

At press: `A11ySnapshot` = focused `TYPE_APPLICATION` window (never `rootInActiveWindow` at tap time), package,
label (`<queries>` LAUNCHER; fallback window title), window title (conversation), focused editable via
`findFocus(FOCUS_INPUT)` with BFS fallback for Compose/WebView, `EditorInfo` from the accessibility IME,
`before_cursor` = `getSurroundingText(1000, 0, 0)` cut at the selection (0-8 ms), password flag, field bounds,
exclusion rects (IME, own overlay, status bar). At +600 ms: re-resolve the window, `takeScreenshotOfWindow`
(contains only app pixels: no keyboard, bubble or bars); error 3 → one retry after ≥ 340 ms; error 5 →
`findFocus(...).windowId`, then `takeScreenshot(display)` + exclusion rects; error 6 or a black frame →
`SecureWindow` (no screen). HardwareBuffer → `ARGB_8888` copy → `SharedMemory`. No PNG on the dictation path.

### 9.2 Privacy filters (WP-08, before any screenshot)

| Condition | Decision |
|---|---|
| focused field is a password (`isPassword` or inputType variation) | bubble hidden (DESIGN 5.2); if focus becomes a password field later: no screenshot, `privacy none`, LLM skipped, nothing inserted, copied or stored |
| package in the built-in denylist (`BuiltInDenylist`: SystemUI, Settings, permission controller, biometrics) or the user's "Hidden in" list | bubble hidden; no dictation |
| "Use what's on screen" off | no screenshot; `privacy app_only` (no screen, no field text) |
| secure window / screenshot failed / OCR empty or late | `screen` absent; `privacy full` (field context kept) |
| Murmur's own UI | bubble hidden (onboarding uses an in-app bubble) |

### 9.3 OCR (WP-07)

`OcrPipeline` = Kotlin port of `ml/ocr` (`OcrConfig` defaults == `ml/ocr/config.py`, drift test
`OcrConfigParityTest`): BGR input, detector long side 960 (multiple of 32, never upscale), DB post-processing
axis-aligned (0.2 / 0.4 / unclip 1.4), exclusion rects, recogniser crops from the original image at height 48 padded
to width buckets, batch 1 on CPU, greedy CTC, NFC + filters + homoglyph repair + geometric and textual de-dup +
two-pane reading order. Parity: post-processing fed with `golden/*.json stages.rec` must reproduce `ocr` exactly; the
full pipeline on the x86_64 emulator within the README tolerances (same line count, boxes ±2 px, ≥ 98 % identical
texts, CER ≤ 0.5 % on the rest, conf ±0.02). Runtime ORT 1.32 default CPU EP (no XNNPACK: segfault on the recogniser,
asr-ocr A7). NPU OCR is a catalog follow-up decided by device step D5.

### 9.4 ctx v2 → prompt (WP-08, WP-04)

`ContextAssembler.assemble(snapshot, decision, screen, asrLanguage)` builds `CtxV2{app{package,label,category},
field{kind, hint, before_cursor, box}, screen{size, lines}, language{asr}, privacy}`; category from
`prompts/tones/app_categories.v1.json`. The renderer (`render.py` port) does all trimming: 1,400-character budget,
nearest-to-field order, clock/status/low-confidence/field-text lines dropped, `<`/`>` neutralised. Request =
cached prefix (system + preferences + few-shot turns) + suffix (`Style:` line, `<context>`, `<transcript>`,
assistant opener with thinking off). Prefix cache key = sha256(model file sha256 + prefix text + unit) (ADR-011).

---

## 10. Benchmark (WP-14, UI in WP-18)

Runs through the production processes and code (same services, same JNI) after `shutdown()` of the target process
(cold load). Protocol: load (cold) → 1 warm-up → 3 measured runs → median (min/max kept) → unload → memory. Each
usable unit of the model is measured when the user taps "Run benchmark" on a model with several candidates.

| Model kind | Measured | Shown (DESIGN 6.4) |
|---|---|---|
| ASR | cold/warm load ms; per bundled clip (ru, en, mixed; FLEURS dev CC-BY-4.0, ≈ 30 s each, in `core/benchmark` resources) audio ms / processing ms (fed in real time through the same `AsrSession`), WER vs the reference text (sanity check for broken NPU numerics); peak memory (VmHWM + PSS + system availMem drop); unit actually used (`LoadInfo.deviceName`) | Faster than real time (×), Load time, Peak memory, Ran on, per-sample times |
| LLM | cold load; synthetic pp (512 tokens) and tg (128 tokens) via `mllm_bench`; fixed cleanup case (real system prefix + ≈ 60-word ru transcript + 200-token context): TTFT, prefill tok/s, decode tok/s, end-to-end ms; op placement share on HTP (`GGML_HEXAGON_VERBOSE` counts) | Writing speed, Reading speed, First word, Peak memory, "A 60-word dictation is cleaned up in about N s" (`BenchSummary`) |
| OCR | ms per bundled screenshot (cover 1080×2520 and inner 1968×2184), boxes found, load, memory | ms per screenshot (in the OCR model detail) |

Results: `BenchmarkRecord(artifact, unit, appVersion, Build.FINGERPRINT, time, result)` in Room; the latest record
per (artifact, unit) feeds `ArtifactSelector` and the adaptive LLM timeout. The phone must stay awake and Murmur in
the foreground during a run (DESIGN). Thermal status before/after is stored in the record notes.

---

## 11. Insertion ladder and delivery (WP-02 ladder, WP-11 rules)

1. Re-read focus (`TargetReader.current()`). Password → discard everything (I5 exception).
2. `copyToClipboard(text)` (ClipData, `EXTRA_IS_SENSITIVE=false`; background write VERIFIED, read is denied).
3. If the focused package ≠ snapshot package or no editable node → `CopiedOnly(TargetChanged/NoEditableField)`.
4. Join with `JoinPolicy` (leading space rules) against the current `before_cursor`.
5. Ladder: `AccessibilityInputConnection.commitText` (accessibility IME, API 33) → `ACTION_PASTE` →
   `ACTION_SET_TEXT` (+ `ACTION_SET_SELECTION` to restore the caret). After each: read back with
   `getSurroundingText` (node text is stale on WebView, platform spike e). First verified method wins.
6. All fail → `CopiedOnly(InsertRejected)`. DB write failures never block delivery (text is already out).

---

## 12. History, retention and stats data model (WP-12)

Tables (`data/db/.../Entities.kt`, Room schema exported to `data/db/schemas/`): `history` (one row per dictation:
app, conversation, status, final/raw text, language, models + units, prompt version, cleanup outcome, tone, audio ref,
timings, insert method, failure code, revision, `context_words` = a count only, ADR-043, pending_delete), `metrics` (numbers only, see below), `benchmark`,
`custom_model`. Settings: one typed DataStore document (`Settings`, section 13).

Retention: `RetentionPolicy` — entries from local day D are deleted (rows + audio files) at 00:00 of D + N (N = 1/3/7,
default 7; 0 = "Don't keep history": nothing is written). Runs at app start, at the local midnight job
(`RetentionJob`, rescheduled daily and on time-zone change), and immediately after shortening N (after the confirm
dialog). Metrics rows are kept `STATS_DAYS = 90` (owner decision pending). Undo: soft delete (`pending_delete`)
committed when the snackbar times out; audio files are deleted before rows.

**Dashboard derivation** (every value from `metrics` rows of the last 7 days, `StatsCalculator`):

| Dashboard element (DESIGN 6.2) | Columns |
|---|---|
| Today, words | Σ words_final where local_day = today and status ∈ {INSERTED, COPIED} |
| Speaking minutes | Σ speech_ms (record_ms when speech_ms = 0) today |
| Time saved | words / typingWpm − speaking minutes (hidden if ≤ 0) |
| Last 7 days | Σ words_final per local_day |
| Speaking pace | median over dictations with record_ms ≥ 3 s of words_raw / (speech_ms / 60 000) |
| Ready after you stop | median and p90 of stop_to_done_ms |
| Typed into the app | count(INSERTED) / count(INSERTED + COPIED); top pkg of COPIED |
| Changed by cleanup | Σ edit_distance_words / Σ words_raw; Σ fillers_removed; Σ fixes |
| Languages | count per language, "Mixed" when mixed_language |
| Where you dictate | Σ words_final per pkg, top 5 + others |

`words_*`, `edit_distance_words`, `fillers_removed` (filler lexicon = `prompts/guard.py FILLERS`), `fixes` and
`mixed_language` are computed once at delivery by WP-11 (pure functions in `core/store`, WP-12).

---

## 13. Settings model

`core/store/.../Settings.kt` is the whole model (defaults = product defaults): dictation (languages ru+en, auto-detect,
hold-to-talk on, feedback vibration, typing wpm 40), bubble (when typing, tuck-in on, size standard 52 dp, hidden-in
packages, per-display positions, hidden-until), cleanup (prompt override {systemText, baseProfile}, preferences ≤
1,000 chars, tone per app, use screen on), history (retention 7 days, keep recordings on), models (ASR model, cleanup
choice Default/None/Model, live-preview model, unit preference per artifact, Wi-Fi only on, idle unload 60 s),
onboarding done, debug fake engines (debug only). "Edited" = `promptOverride != null`; Reset deletes the override
(tone per app kept, DESIGN 6.5). Device profile and benchmark results are not settings (DataStore cache / Room).

---

## 14. Threading and coroutines policy

| Where | Thread / dispatcher | Rule |
|---|---|---|
| `:main` UI + overlay | main | Compose only; no binder calls to inference processes, no disk |
| accessibility events | main (system) → post to `a11y-io` HandlerThread | node reads, tree walks, screenshot calls on `a11y-io` (synchronous binder to the target app) |
| orchestrator | `Dispatchers.Default.limitedParallelism(1)` | the only place that mutates dictation state; never blocks; every port call is `suspend` and main-safe |
| audio capture | dedicated thread, `THREAD_PRIORITY_URGENT_AUDIO` | writes pipe, encoder, buffer, meter; never suspends |
| DB / files / DataStore | `Dispatchers.IO` | inside repositories only |
| binder callbacks (`:main`) | binder threads | convert to `DictationEvent` and `trySend` into the orchestrator channel; nothing else |
| `:asr` | one ASR engine thread, one OCR thread, one pipe reader per session | binder threads only enqueue; `finish` waits for the reader to drain |
| `:llm` | one engine executor thread (owns `llama_context`) | `cancel()` from any thread flips an atomic; `unload` is submitted to the executor (never races cancel) |

Coroutine scopes: `AppContainer.appScope` (SupervisorJob) in `:main`; ViewModels use `viewModelScope`; services
use a scope cancelled in `onDestroy`. Cold flows for one-shot streams (`LlmEngine.generate`), `StateFlow` for state.
Tests use `kotlinx-coroutines-test` (`runTest`, virtual time for every timer in section 5.3).

---

## 15. Error taxonomy

`MurmurError` (`core/model/.../Errors.kt`) is the only error type across module and process boundaries. `code` is
stable and stored (`history.failure_code`, `cleanup_outcome`), `retryable` drives the Retry action, `detail` never
contains user text, screen text or paths. Families: `perm.*`, `model.*`, `ctx.*`, `sys.*`, `audio.*`, `engine.*`
(load, runtime missing, unit unavailable, process died, OOM), `timeout.<stage>`, `asr.*`, `llm.*` (incl.
`llm.guard.<rule>`), `ocr.*`, `insert.*`, `dl.*`, `catalog.*`, `data.*`, `internal`. Mapping to bubble and history:
section 5.5; to hub rows: `InstallState.Paused/Failed(error)`.

---

## 16. Testing strategy

| Level | Where | What | Command |
|---|---|---|---|
| JVM unit | `core/*/src/test`, `testing/src/test` | reducer table (every row of 5.2 with virtual time), selection, catalog validation, retention, stats, segmenter on synthetic probabilities, join policy, WER | `tools/gw testDebugUnitTest` (JVM modules alias `test`) |
| Parity / golden | `core/prompt` (prompts/examples + generated corpus from `prompts/eval/sets/*.v2.jsonl`), `core/prompt` guard (`prompts/tests/test_guard.py` cases), `core/ocr` (`ml/ocr/golden`), `core/context` (schema round-trip), `core/ocr` config drift | byte-exact or README tolerances | same |
| Android unit | `*/src/test` of Android modules | pure logic inside Android modules (policies, mappers) | same |
| Instrumented, fakes | `*/src/androidTest`, API 36 `google_apis` x86_64 AVDs `murmur_fold7_cover` (compact) and `murmur_fold7_inner` (medium) | Compose UI against `:testing` fakes; screenshots compared with `docs/design/screens/*.png` by eye + automated layout checks (no overflow, targets ≥ 48 dp) | `tools/gw :<module>:assembleDebugAndroidTest` then `adb install` + `am instrument` (TOOLCHAIN 4.4) |
| Room | `data/db/src/androidTest` | DAOs, migrations (schema JSON), retention on an in-memory DB | same |
| IPC | `infer/api/src/androidTest` with a test-only service in a separate process | timeouts, death → re-bind, pipe replay, SharedMemory | same |
| Native on emulator | `infer/asr`, `infer/llm` androidTest | sherpa x86_64 with GigaAM / Parakeet CPU (asr-ocr: correct text on emulator, RTF 0.42 / 0.65); llama x86_64 with Qwen3.5 0.8B (llm spike: works, ivybridge variant) | same, models pushed by a test script |
| End-to-end | `app/src/androidTest` + `:testapps:target` | press bubble over the target app (UiAutomator) → debug WAV audio source (the emulator mic cannot be fed, platform spike) → real `:asr` (GigaAM or Parakeet CPU) + VAD + OCR + real `:llm` (Qwen3.5 0.8B CPU) → insertion into the target EditText (read back from the target's `TGT` log) → history row; plus the same flow on fakes for speed | WP-20 script `tools/e2e/run_emulator_e2e.sh` |
| Device | `tools/device/*.sh` + `docs/DEVICE_CHECKLIST.md` | NPU paths, real latency/memory, One UI behaviour, real OCR screenshots, fold/unfold | owner, one pass |

LLM outputs are never compared as exact strings across devices or kernels (llm spike: punctuation differed between
AVX2 / ivybridge / icelake); tests assert guard verdicts, non-emptiness, scripts and known substrings.

---

## 17. Requirement traceability (every line of `docs/BRIEF.md`)

| BRIEF line | Modules | WP |
|---|---|---|
| Product 1: local models only, Whisper-like ASR + small LLM for error fixing and context rewriting | infer:asr, infer:llm, core:prompt, core:context | WP-06, WP-10, WP-04, WP-08 |
| Product 2: target Galaxy Z Fold7 12 GB; other devices only if easy | catalog `socs` + CPU artifacts; minSdk 34 | WP-13, DEVICE_CHECKLIST |
| Product 3: clean, bright, minimal, production quality | ui:design, feature:*, platform:overlay | WP-01, WP-03, WP-15..19 |
| Product 4: floating bubble to start/stop from any app | platform:a11y, platform:overlay, core:dictation | WP-02, WP-03, WP-11 |
| Product 5: Samsung NPU for all models (ASR, LLM, OCR) | catalog units + selector; sherpa QNN; ggml-hexagon; OCR NPU follow-up | WP-06, WP-10, WP-07, WP-14; DEVICE_CHECKLIST D2-D5 |
| Screens: 4 bottom tabs | app shell | WP-15 |
| Screen 1: Dashboard with useful usage stats | feature:dashboard, core:store metrics | WP-17, WP-12 |
| Screen 2: History, main tab, auto-deleted after 1 week | feature:history, data:db retention | WP-16, WP-12 |
| Screen 3: Model hub, user configures any model | feature:models, data:models (custom models) | WP-18, WP-13 |
| Screen 4: Settings: retention, UI features, post-processing prompts; good default, editable | feature:settings, core:prompt | WP-19, WP-04 |
| Hub ASR: Whisper (sizes) | catalog: whisper-small (NPU+CPU), whisper-medium (NPU), large-v3-turbo (CPU), distil-large-v3.5 | WP-13, WP-06 |
| Hub ASR: Parakeet | parakeet-tdt-0.6b-v3 (NPU+CPU), parakeet-ultra (CPU) | WP-06 |
| Hub ASR: GigaAM | gigaam-v3 RNN-T and CTC (CPU; NPU port follow-up) | WP-06 |
| Hub ASR: T-one | t-one (CPU, streaming; NPU follow-up) | WP-06 |
| Hub ASR: Qwen3-ASR | qwen3-asr-0.6b (CPU; LiteRT NPU artifact listed, runtime not shipped), 1.7B (GPU, not shipped) | WP-06, WP-13 |
| Hub ASR: parameter count + languages | `params`, `paramsLabel`, `languages`, "No Russian" badge | WP-18 |
| Hub LLM: LFM 2.5 series | lfm2.5-1.2b-instruct, lfm2.5-350m, lfm2.5-2.6b | WP-10, WP-13 |
| Hub LLM: LFM 3 series | lfm3-1.7b | WP-10 |
| Hub LLM: small Qwen 3.5-3.6 | qwen3.5-0.8b, qwen3.5-2b (+ qwen3 0.6b/1.7b hedge) | WP-10 |
| Hub LLM: fine-tuned LFM3 + Qwen3.5 (LoRA) | murmur-cleanup-* entries (status in_training; artifacts added by a catalog update when ML ships GGUFs) | ML team; WP-13 |
| Hub LLM: Q4 inference | Q4_0 GGUF default artifacts (ADR-009) | WP-10 |
| Hub LLM: None | `CleanupChoice.None` (first row) | WP-18, WP-11 |
| Rule: models not baked into the APK, downloaded from HF on demand | data:models; nothing under assets | WP-13 |
| Rule: NPU inference for all models | see Product 5 | — |
| Rule: Benchmark button per model, short stats | core:benchmark, feature:models | WP-14, WP-18 |
| Rule: models must not hold RAM 24/7 | process exit after idle (4.2) | WP-05, WP-06, WP-10 |
| Flow 1: tap bubble → dictation starts | 5.2 Idle→Arming | WP-11, WP-03 |
| Flow 2: 0.5-1 s after start screenshot → small multilingual OCR | `CaptureContext(600 ms)`, OCR in :asr | WP-02, WP-07 |
| Flow 3: context from Accessibility: current app | `A11ySnapshot` | WP-02, WP-08 |
| Flow 4: start loading the audio model | `PrepareEngines` | WP-11, WP-05, WP-06 |
| Flow 5: start loading the LLM | `PrepareEngines` (parallel, memory gate) | WP-11, WP-10 |
| Flow 6: transcribe during speech if streaming / splittable, else at stop | segmenter + streaming adapters (D4) | WP-06 |
| Flow 7: system prompt + context + text → LLM; insert via Accessibility and copy to clipboard | renderer, guard, delivery, insertion ladder | WP-04, WP-11, WP-02 |
| Flow 8: save output + audio to history | `Deliver` → history + Opus audio | WP-11, WP-12, WP-09 |
| Constraint: ru + en first-class incl. code-switching | Parakeet + VAD segments (4.5 % mixed WER host), Cyrillic OCR + homoglyph repair, ru strings | WP-06, WP-07, all UI |
| Constraint: no Fold7 attached; flag device-only items | `docs/DEVICE_CHECKLIST.md`, section 18 | all |
| Constraint: shared build server, don't disturb | tools/gw, tools/heavy, read-only emulator instances | all |
| Constraint: Hermes holds HF token / RunPod | fine-tune pipeline (ML); HF mirror of GitHub assets (ADR-016) via the orchestrator | ML, owner |
| Constraint: suggested datasets | ML plan (docs/ml) | ML |
| Repo layout (research/ml/design/dev/prompts/Gradle) | unchanged; Gradle now multi-module | this doc |

---

## 18. What cannot be verified without the phone

All of these are steps in `docs/DEVICE_CHECKLIST.md` with the script, the expected output and the decision each feeds:

1. HTP0 registration for ggml-hexagon from an app process (unsigned PD on One UI 9), op acceptance for lfm3/qwen35
   (FA head dim 256), NPU vs GPU vs CPU pp/tg, NPU load time and rpcmem footprint.
2. QNN 2.52 loading sherpa's QAIRT-2.40 SM8750 binaries on the real HTP, Parakeet/Nemotron NPU latency and accuracy
   vs CPU int8, QNN + ggml-hexagon sessions coexisting on the cDSP, `ADSP_LIBRARY_PATH` with `useLegacyPackaging`.
3. OCR CPU vs QNN times on arm64; XNNPACK behaviour on arm64.
4. Real microphone samples (the emulator cannot be fed), Samsung FGS/while-in-use handling, Bluetooth headsets.
5. One UI: restricted-settings dialog for adb/file installs, Auto Blocker, battery "Unrestricted" path, Samsung
   Keyboard composing behaviour vs `commitText`, real Telegram/Samsung apps insertion.
6. Fold/unfold with the bubble (BLAST stall, same vs new display), real densities and dp on both displays, taskbar.
7. Real-screenshot OCR quality on both displays (dark themes, icons, app fonts) vs the synthetic pages.
8. End-to-end latency for 10 s / 60 s dictations, memory peaks (`MemAvailable`, PSS, rpcmem), thermal, Android 17
   MemoryLimiter behaviour, idle exit releasing DSP memory.
9. Android 17 screenshot behaviour (the API 37 ATD emulator crashes on pixel readback).
10. Legal: Hexagon SDK CE skel redistribution; QNN runtime licence (not technical, but blocks shipping NPU libs).

---

## 19. Work packages and waves

| Wave | WP | Title | Owns |
|---|---|---|---|
| 1 | WP-01 | Design system | `ui/design/**` |
| 1 | WP-02 | Accessibility service, screen capture, insertion | `platform/a11y/**` |
| 1 | WP-03 | Bubble overlay | `platform/overlay/**` |
| 1 | WP-04 | Prompt renderer + output guard ports | `core/prompt/**` |
| 1 | WP-05 | Inference IPC | `infer/api/**` |
| 1 | WP-06 | ASR engine (sherpa, VAD, segmenter) | `infer/asr/**` except `ocr/`, `native/sherpa/**`, `core/audio/**/Segmenter*.kt`, `tools/device/asr_npu_smoke.sh` |
| 1 | WP-07 | OCR engine + parity | `core/ocr/**`, `infer/asr/src/*/kotlin/app/murmur/infer/asr/ocr/**` |
| 1 | WP-08 | Context assembly + privacy | `core/context/**` |
| 1 | WP-09 | Audio capture, FGS, history audio | `platform/audio/**`, `core/audio/**` except Segmenter |
| 1 | WP-10 | LLM engine (llama.cpp, prefix cache) | `infer/llm/**`, `native/llama/**`, `tools/device/llm_npu_smoke.sh` |
| 1 | WP-11 | Dictation orchestrator + delivery | `core/dictation/**` |
| 1 | WP-12 | History, metrics, settings, retention data layer | `data/db/**`, `core/store/**` |
| 1 | WP-13 | Catalog, downloads, storage, custom models | `data/models/**`, `core/catalog/**` |
| 1 | WP-14 | Benchmark runner | `core/benchmark/**` |
| 2 | WP-15 | App shell, navigation, onboarding | `app/src/main/kotlin/app/murmur/{MainActivity,AppShell}.kt`, `app/src/main/res/**`, `feature/onboarding/**` |
| 2 | WP-16 | History UI | `feature/history/**` |
| 2 | WP-17 | Dashboard UI | `feature/dashboard/**` |
| 2 | WP-18 | Model hub UI + benchmark sheet | `feature/models/**` |
| 2 | WP-19 | Settings UI (prompt editor, tone, retention) | `feature/settings/**` |
| 3 | WP-20 | Integration, end-to-end tests, device tooling | `app/src/main/kotlin/app/murmur/{AppContainer,MurmurApp}.kt`, `app/src/{debug,release,androidTest}/**`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `testapps/**`, `tools/e2e/**`, `tools/device/murmur_app_smoke.sh`, `docs/DEVICE_CHECKLIST.md` |

Wave 1 packages depend only on contracts and `:testing` fakes, so all 14 start now. Wave 2 needs WP-01's
components (they may start view models and state tests during wave 1). WP-20 integrates when the wave-1 packages it
wires have landed; it may start the test target, the scripts and the fake-engine end-to-end test immediately.
Shared files (root `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `build-logic/**`) stay
with the architect: request changes (new dependency = ADR line).
