# Murmur architecture

Version 2 · 2026-11-19 · Owner: architect. Binding for all work packages (`docs/wp/`). Owner decisions D1-D12 are
quoted verbatim at the top of `docs/DECISIONS.md`; conflicts and their evidence are ADR-nnn there; review findings and
their resolution are under "Review responses" there. Everything that only the phone can prove: `docs/DEVICE_CHECKLIST.md`
(steps DC1-DC10).

Evidence words used here: **VERIFIED** (run on this server, command and output named in DECISIONS or the spike
report), **UNVERIFIED-ON-DEVICE** (needs the Fold7; section 18 and DEVICE_CHECKLIST), **[I]** (inference or estimate,
to be replaced by a measurement).

---

## 0. Decisions

1. **Three processes.** `:main` (UI, accessibility service, bubble, microphone, database, downloads; no native
   model code), `:asr` (sherpa-onnx + ONNX Runtime 1.32 + Silero VAD + OCR + QNN 2.52) and `:llm` (llama.cpp
   b12520 + ggml-hexagon, alone). Both inference processes are bound on bubble press, unload and **exit** after
   an idle timeout (default 60 s, revisited after DC2/DC8 measure the cold NPU load). ADR-001, ADR-005.
2. **Binder AIDL with JSON payloads**, PCM over a `ParcelFileDescriptor` pipe, screenshots over `SharedMemory`.
   One API version number checked at bind. Timeouts that depend on the work (prefix prefill, benchmark) scale with
   it. ADR-001, ADR-049.
3. **One dictation state machine**, a pure reducer (`DictationMachine.reduce`) plus an effect executor
   (`DictationOrchestrator`) on one serial coroutine context. Every (state, event) pair has a row (section 5.2).
4. **ASR during dictation (D4):** batch models get VAD pause-cut segments decoded as they close (asr-ocr spike A6:
   no word loss, better than whole-clip); streaming models stream. One `AsrSession` interface hides both. At stop
   only the open tail is decoded. The LLM runs once, at the end, over the full transcript. ADR-025, ADR-037.
5. **Default models:** ASR Parakeet TDT 0.6B v3 (NPU context binary on SM8750, CPU int8 elsewhere and as
   fallback); cleanup LFM3 1.7B Q4_0 (NPU by default) per the catalog, Qwen3.5 0.8B preferred on CPU. Final unit
   per model from the on-phone benchmark. ADR-014, ADR-029.
6. **The context window fits the shipped prompt (ADR-045).** The default.v4 prefix is 3,129-3,269 tokens (VERIFIED
   with `llama-tokenize` on the real GGUFs), so every LLM gets `nCtx = roundUp(prefixTokens + 5,200, 1,024)` (stock
   9,216, fine-tunes 6,144): room for a 5-minute dictation with its screen context and output. A pure
   `CleanupBudget` decides before every call whether the request fits and finishes in time; otherwise the raw
   transcript is inserted ("Inserted, no cleanup", `skipped:too_long`).
7. **Load failures fall back without a download (ADR-050).** The resolver hands the executor a primary and a
   fallback spec (LLM: same GGUF on the next unit; ASR: the model's other installed artifact). A failed
   (artifact, unit) is remembered (`UnitHealth`) and skipped until the app version changes or a benchmark of that
   unit passes. Every NPU artifact is proven by a real load at install ("Preparing"); a failure queues the CPU
   artifact before the first dictation.
8. **Catalog-driven hub (D3):** one JSON catalog (`core/catalog/src/main/resources/catalog/catalog.json`,
   generated from the verified research catalogs, every file re-checked against the HF / GitHub APIs) drives
   the hub, downloads, unit selection and benchmarks. Custom GGUFs get their template from the GGUF architecture
   and their prompt profile from GGUF metadata (fine-tunes: `finetuned.v1`). ADR-030, ADR-046.
9. **Screen context = OCR (D11):** window screenshot about 0.6 s after start, OCR in `:asr` on the ORT CPU EP with
   the exact `ml/ocr` pipeline (parity tests), ctx v2, Kotlin port of `prompts/render.py`. Cleanup waits at most
   800 ms for a late OCR. Screenshot and OCR text live in memory only. ADR-013, ADR-023.
10. **Prompts packaged from `prompts/` at build time (D8)**; the Kotlin renderer and guard are byte-exact ports with
    golden tests over all four templates. Users edit the system-prompt instructions and "My preferences"; the edit
    applies only to the stock profile it was written for, never to fine-tunes. ADR-019, ADR-046.
11. **Privacy (D9):** network only for model downloads (Hugging Face + GitHub release assets, ADR-016); the ONNX
    Runtime telemetry provider is removed from the manifest (ADR-022); no analytics; context never persisted;
    history auto-deletes per calendar day.
12. **Manual DI, Navigation 3, material3-adaptive, Room + KSP, typed DataStore, kotlinx.serialization.** Every
    version is the latest stable and was resolved by `tools/gw assembleDebug`. ADR-010, ADR-012, ADR-032.
13. **minSdk 34, target/compile 37 (37.2).** Release ABI arm64-v8a; debug adds x86_64 with CPU engines (D6).
    Qualcomm-licensed DSP binaries (QNN, the ggml HTP skel) ship only behind `-Pmurmur.qnn` / `-Pmurmur.htp` until
    the owner reads the licences (ADR-048).
14. **No live preview in v1 (ADR-047).** Preview-only streaming models (Nemotron) are listed but not selectable.
15. **The phone comes last (D12):** every work package is accepted on this server (JVM tests, x86_64 emulator,
    fakes, small real models, emulator fold/call/kill simulation). Phone-only checks are one pass in
    `docs/DEVICE_CHECKLIST.md`.

---

## 1. Overview

```
 any app ──focus──▶ :main ────────────────────────────────────────────────────────────────────────────────────
                    MurmurAccessibilityService ── OverlayHost (bubble, TYPE_ACCESSIBILITY_OVERLAY)
                          │ press                         ▲ DictationBubble (StateFlow)
                          ▼                               │
                    DictationOrchestrator (reducer + effects, serial; also EnginePreparer) ── Room/DataStore
                     │ AudioCapture (FGS mic, 16 kHz PCM) ── AudioStore (Opus) ── LevelMeter ─▶ bubble bars
                     │ TargetReader (snapshot, window screenshot) ── Inserter (commitText → paste → setText)
                     │ PromptRenderer + OutputGuard (pure Kotlin ports) ── CleanupBudget
                     │
          binder ────┼───────────────────────────────┬─────────────────────────────────────────────────────────
                     ▼                               ▼
   :asr  AsrService: sherpa-onnx (QNN | CPU),     :llm  LlmService: llama.cpp + ggml-hexagon (NPU | GPU | CPU)
         Silero VAD + Segmenter, OcrHandler         prefix snapshot cache (n_ctx 9,216), greedy decode
         ◀── PCM pipe, SharedMemory frame          ◀── prefix + suffix text, ──▶ streamed pieces
```

---

## 2. Module graph and dependency rules

26 Gradle modules + one test app (`:testapps:target`). Convention plugins in `build-logic/` (`murmur.jvm.library`,
`murmur.android.library`, `murmur.android.compose`). Namespaces `app.murmur.<path>`.

| Layer | Module | Kind | Contents (contract now, implementation by the WP named) |
|---|---|---|---|
| core | `:core:model` | JVM | ids (`AudioKey` for audio files), `ComputeUnit`, `UnitPreference`, `ModelKind`, `Language`, `MurmurClock`, `MurmurError` taxonomy, `Outcome`, generated `ProductName` |
| core | `:core:catalog` | JVM | catalog schema + parser + validator + bundled `catalog.json` + `ArtifactSelector` (`ranked`, fallback) + `UnitHealth` + `DeviceProfileRepository` + `MemoryEstimate` + custom-model contract and `GgufTemplates` (WP-13) |
| core | `:core:ocr` | JVM | OCR types, `OcrConfig` (= `ml/ocr/config.py`), pure pipeline port, `OcrModels` seam (WP-07) |
| core | `:core:context` | JVM | ctx v2 types (= `prompts/context.schema.v2.json`), `A11ySnapshot`, privacy decision, field kinds, `ContextAssembler` (WP-08) |
| core | `:core:prompt` | JVM | prompt types, packaged `prompts/`, `PromptRenderer`/`OutputGuard` ports, `PromptEditRules` (WP-04) |
| core | `:core:audio` | JVM | PCM format, `PcmChunk`, `VadModel`, `Segmenter` (WP-06), `LevelMeter` (WP-09) |
| core | `:core:engine` | JVM | `AsrEngine/AsrSession/AsrEvent/Transcript`, `LlmEngine/LlmRequest/LlmStats`, `PrefixCacheKey`, `LoadOptions`, `OcrService`, `InferenceHost`, `LoadSpec`, `Engines` |
| core | `:core:store` | JVM | repositories + records: history, metrics, `DeliveryMetrics`, settings, install state, benchmark (with validity), audio store, `RetentionPolicy`, `StatsCalculator` (WP-12) |
| core | `:core:benchmark` | JVM | `BenchmarkRunner`, steps, `BenchValidity`, WER, summary sentence (WP-14) |
| core | `:core:dictation` | JVM | states/events/effects, `BubbleMapping`, `DictationConfig`, `CleanupBudget`, ports (capture, target, inserter, resolver, navigator, permissions, status notifier, preparer, in-app target), `DictationOrchestrator` (WP-11) |
| test | `:testing` | JVM | deterministic fakes of every contract + `FakeWorld` + `VirtualClock` (WP-11 owns; `contrib/` per WP) |
| infer | `:infer:api` | Android | AIDL, `InferApi` (JSON, versions, adaptive `BinderTimeouts`), `ServiceConnector`, binder clients `AsrClient/LlmClient/BinderEngines` (WP-05) |
| infer | `:infer:asr` | Android | `AsrService` (`:asr`), sherpa adapters, Silero VAD (WP-06), `ocr/` `OcrHandler` + ORT sessions (WP-07); ORT 1.32.0 AAR |
| infer | `:infer:llm` | Android | `LlmService` (`:llm`), `LlmNative` JNI facade (WP-10); NPU/FastRPC manifest entries |
| data | `:data:db` | Android | Room DB (history, metrics, benchmark, custom_model), DataStore settings, retention job (WP-12) |
| data | `:data:models` | Android | storage layout, UIDT download job, verify/extract, catalog repo, model resolver, device-profile probe, unit health, HF custom models (WP-13) |
| platform | `:platform:a11y` | Android | accessibility service, target snapshot, screenshot, insertion ladder, clipboard, bridges, `RestrictedSettingsProbe` (WP-02) |
| platform | `:platform:overlay` | Android+Compose | bubble visuals, visibility policy, placement/snap math, overlay window host, `Feedback` (WP-03) |
| platform | `:platform:audio` | Android | AudioRecord capture + signals, microphone FGS, Opus history store, status notification (WP-09) |
| ui | `:ui:design` | Android+Compose | tokens generated from `docs/design/tokens.json`, `MurmurTheme`, fonts, icons, components, `AppInfoProvider` contract (WP-01) |
| ui | `:feature:history` / `dashboard` / `models` / `settings` / `onboarding` | Android+Compose | screens + view models (WP-16..19, WP-15) |
| app | `:app` | Android app | `MurmurApp`, `AppContainer` (wiring), `MainActivity`, `AppShell` (nav), manifest (WP-15 in wave 2, WP-20 in wave 3) |
| test | `:testapps:target` | Android app | "another app" with text fields for emulator end-to-end tests (WP-20) |

**Dependency rules** (reviewers reject violations; `./gradlew :app:dependencies` shows the graph):

1. `core:*` and `:testing` are pure Kotlin/JVM: no `android.*` imports. They depend only on other `core:*` modules
   along this order: `model` ← `catalog`, `ocr`, `audio`, `store` ← `context`(ocr) ← `prompt`(context) ←
   `engine`(audio, ocr) ← `benchmark`(engine, store, catalog, prompt) ← `dictation`(everything core).
2. `infer:*` depend only on `core:*` (+ `infer:api`). They never depend on `data`, `platform`, `ui`, `feature`.
3. `data:*` depend on `core:*` (`data:models` may use `data:db`). `platform:*` depend on `core:*` and
   `platform:overlay` → `ui:design`; `platform:a11y` → `platform:overlay`.
4. `feature:*` depend on `ui:design` and `core:*` only. **Features never depend on each other**, on `data:*`,
   `infer:*` or `platform:*`; they receive interfaces through a `XxxDeps` class built by `:app` (every member a
   screen needs is in its Deps: app labels/icons via `AppInfoProvider`, permissions via `PermissionMonitor`, device
   profile, retention runner, practice dictation). `:testing` is allowed as `testImplementation`,
   `androidTestImplementation` and `debugImplementation` (fake deps for previews and screenshot tests).
5. Only `:app` sees every module. Only `AppContainer` constructs implementations.
6. A contract change (any public type in `core:*` or `infer:api`) needs an ADR line in DECISIONS and a note to the
   consuming WPs; the implementing WP may add members, not break existing ones.

### 2.1 Build and native libraries

- Kotlin 2.4.21 via AGP 9.5.1 built-in Kotlin (TOOLCHAIN D1), JVM target 17, JDK 21. `ANDROID_HOME` must be set
  (`/home/private/.local/share/android-sdk`); `tools/gw` does not set it (docs/wp/README rule 11).
- Native runtimes are **prebuilt by scripts, never by Gradle** (ADR-008/009): `native/llama/build_android.sh`
  and `native/sherpa/build-android.sh` write `<murmur.nativeLibsDir>/{llama,sherpa}/<abi>/*.so`
  (default `.cache/native/jniLibs`; in a worktree pass `-Pmurmur.nativeLibsDir=/home/private/projects/murmur/.cache/native/jniLibs`).
  `:infer:llm` / `:infer:asr` add those directories as `jniLibs` sources when present; without them the APK still
  builds and the runtime reports `EngineRuntimeMissing`. The sherpa install dir holds only `libsherpa-onnx-jni.so`
  (its `libonnxruntime.so` would duplicate the AAR's; the script fails if it appears).
- ONNX Runtime comes once, from Maven (`onnxruntime-android:1.32.0`); sherpa-onnx is linked against that exact
  `libonnxruntime.so` (asr-ocr spike: byte-identical, `cmp`).
- `packaging.jniLibs.useLegacyPackaging = true` (ADR-007): ggml-hexagon's DSP skel and the CPU-variant loader
  need real directories.
- **Licence gates (ADR-048):** QNN 2.52 (`com.qualcomm.qti:qnn-runtime`) is added by WP-06 only with
  `-Pmurmur.qnn=true` (then only `libQnnHtp`, `libQnnSystem`, `libQnnHtpV79Stub`, `libQnnHtpV79Skel`);
  `libggml-htp-*.so` is excluded from the APK unless `-Pmurmur.htp=true` (`app/build.gradle.kts`; without it the
  `:llm` probe reports no HTP0). The DC8 device build sets both; such APKs are local and never redistributed.
- Debug APK today: ~70 MB (ORT for two ABIs dominates). Release (arm64 only, with llama + sherpa + QNN) [I] ≈ 60-70 MB.

---

## 3. Product surfaces (what each module renders)

DESIGN.md is binding (D7). History is the start tab; cover display = bottom bar, inner = navigation rail, chosen
by `WindowSizeClass` (`currentWindowAdaptiveInfoV2()`), never by device detection. List-detail on the inner display
for History, Models, Settings. The bubble follows the host app's UI mode.

UI pieces with no frame in `docs/design/screens/` (composed from WP-01 components, screenshot sent to the design
owner, open question): the unit picker (WP-18), the "Add a model" sheet (WP-18), the "Support models" rows for VAD/OCR
(WP-18), the "What the model also receives" panel (WP-19, ADR-019), the "preview" mark on named tones (WP-19,
ADR-020), the "Your edit was made for an older prompt" notice (WP-19, ADR-046).

---

## 4. Process model and IPC

### 4.1 Processes

| Process | Declared in | Holds | Started | Ends |
|---|---|---|---|---|
| `:main` (`app.murmur`) | `:app` | Application, AppContainer, accessibility service + overlay, AudioRecord + `RecordingFgs`, status notification, Room, DataStore, UIDT download job, UI | by the system (accessibility binding keeps it alive) | never while the service is enabled; crash → bubble gone 11 s (platform spike g) |
| `:asr` | `:infer:asr` manifest (`android:process=":asr"`) | `AsrService`: sherpa-onnx, ORT 1.32 (+QNN 2.52), Silero VAD, OCR sessions | `bindService(BIND_AUTO_CREATE or BIND_IMPORTANT)` on bubble press (and during Preparing), from the service context | `onUnbind` → unload → `killProcess` after the idle timeout |
| `:llm` | `:infer:llm` manifest | `LlmService`: llama.cpp + ggml-hexagon + OpenCL + CPU variants | same, in parallel with `:asr` when cleanup is on and memory allows | same; process exit is the only way to drop the FastRPC session and rpcmem (llm spike D7) |

Why two inference processes (D5): QNN and ggml-hexagon each open their own FastRPC session and skel on the cDSP;
two QNN versions or QNN + ggml-hexagon in one process are untested on a DSP (asr-ocr 5.2), and each process must be
killable on its own to release memory. The `Application` runs in all three processes; `MurmurApp` builds the
`AppContainer` only when `Application.getProcessName() == packageName`. Each service installs a quiet crash handler
(`InferProcessLifecycle`) so that a crash never shows a dialog (platform spike f). QNN + ggml-hexagon sessions on the
cDSP at the same time are proven only by DC8 (forced run).

### 4.2 Lifecycle (D5)

```
press ─▶ preflight ─▶ connect(:asr) ─▶ loadAsr(asr, vad) ─▶ startSession(pipe) ─▶ …segments… ─▶ finish
     └────────────▶ connect(:llm) ─▶ load(llm, n_ctx) ─▶ setPrefix(cache key: restore snapshot) ─▶ generate ─▶ Done
                                                     (both in parallel, ASR first priority)
after Done/Failed: scheduleIdleExit(idleUnloadSec, default 60 s) on both hosts
idle expired: unbind ─▶ service.onUnbind: unloadAll() ─▶ (native) mallopt(M_PURGE_ALL) ─▶ killProcess(myPid)
next press within the idle window: no load at all (warm)
benchmark: shutdown() first (cold load), same code path
memory pressure (onTrimMemory BACKGROUND in :main, or LLM load refused by MemoryGate): shutdown(:llm) first
Preparing (EnginePreparer, never during a dictation; a press cancels it):
  after an artifact installs: load on every usable unit (+1 s ASR decode, + LLM prefix snapshot built and saved)
  after a prompt / preferences / unit / model change: rebuild the active LLM's prefix snapshot (debounced 5 s)
```

OCR is loaded in `:asr` right after the ASR model (it is 10 MB) and used once per dictation.

### 4.3 IPC surface (`infer/api/src/main/aidl/app/murmur/infer/api/`)

| AIDL | Method | Payload (JSON of `:core:engine` types, `InferApi.json`) | Client timeout (`BinderTimeouts`, × `scale`) |
|---|---|---|---|
| `IAsrService` | `apiVersion()` | `InferApi.API_VERSION` must match, else `Internal("api")` | bind 3 s |
| | `probe()` / `memory()` | `ProcessProbe` (facts incl. `native.build`) / `ProcessMemory` | 2 s |
| | `loadAsr(asr, vad, cb)` | `LoadSpec` ×2 → `LoadInfo` / `MurmurError` | 15 s |
| | `loadOcr(det, rec, cb)` | `LoadSpec` ×2 → `LoadInfo` (forwarded to `OcrHandler`) | 15 s |
| | `startSession(options, pcmRead, cb)` → `IAsrSession` | `AsrSessionOptions`; PCM16 LE mono 16 kHz on the pipe; events `AsrEvent` | 2 s (client call is `suspend`) |
| | `IAsrSession.finish(cb)` / `cancel()` | → `Transcript` | `asrFinalTimeoutMs(tail)` = 4 s + 0.5 s per tail second |
| | `recognizeScreen(SharedMemory, w, h, exclude, cb)` | ARGB_8888 ints, read-only mapping → `ScreenText` | 4 s (`ocrBudgetMs`) |
| | `unloadAll()` | — | 2 s |
| `ILlmService` | `load(spec, cb)` | `LoadSpec` (options `n_ctx`, `threads`, `use_mmap`, `power_mode`) → `LoadInfo` | 30 s |
| | `setPrefix(prefixSpec, cb)` | `PrefixSpec{text, cacheKey}` → `PrefixInfo{tokens, restoredFromDisk}` | `prefixMs(tokens)` = max(30 s, 2 × tokens / pp), pp = benchmark prefill or 40 tok/s |
| | `generate(request, ILlmStreamCallback)` | `LlmRequest{suffix, maxTokens, minTokens}` → `onPiece(text)`*, `onDone(text, LlmStats)` / `onError` | the adaptive LLM timer (5.3) |
| | `bench(nPrompt, nGen, reps, cb)` | → `SyntheticBench` | `benchMs` = max(30 s, (nPrompt/40 + nGen/5) × reps × 2 s) |
| | `cancel()` / `unload()` | — | 2 s |

Rules: every method returns immediately; work runs on the service's engine thread; results come back through
`oneway` callbacks. Text is a `String` over binder (UTF-16 parcel, emoji-safe) and UTF-8 bytes at JNI (llm spike
D11). Binder transactions stay far below 1 MB (largest: prompt prefix ≈ 12 KB of text). The 17 MB frame never goes
through a parcel: `:main` copies the `ARGB_8888` bitmap into `SharedMemory`, `setProtect(PROT_READ)`, passes the
fd, closes its copy after the callback. Clients bind with `bindContext()` = the accessibility service when connected,
else the application. Debug builds multiply every timeout by `Settings.debugTimeoutScale` (emulator CPU engines).

**PCM path.** The capture thread in `:main` writes each 20 ms frame to (a) the session's pipe (`ParcelFileDescriptor
.createPipe()`, write end in `:main`), (b) the history `AudioWriter` (file named by `AudioKey` = creation time +
session, never the bare session id), (c) an in-memory session buffer (≤ 9.6 MB for 5 min) used for replay after a
crash or a unit fallback, (d) the `LevelMeter`. The `:asr` pipe reader buffers until the model is loaded, so audio
is never dropped (asr-models 5 "mandatory").

**`:llm` context check.** After tokenising the suffix, `:llm` computes `room = n_ctx − n_past − prompt tokens`; if
`room < LlmRequest.minTokens` it answers `LlmContextOverflow` without decoding (murmur_llm `MLLM_E_OVERFLOW` is never
reached); otherwise `maxTokens = min(maxTokens, room)`. A generation that stops on `max_tokens` is treated as
truncated and never inserted (`fallback:truncated`).

### 4.4 Crash, timeout and fallback handling

| Event | Detection in `:main` | Action |
|---|---|---|
| `:asr` dies while Listening | `onServiceDisconnected`/`onBindingDied`, `EPIPE` on the pipe → `ProcessDied(ASR)` | keep recording; re-bind once; new session; replay the session buffer from sample 0; second death → stop at the user's stop, `Failed(ProcessDied(ASR))`, entry kept with audio ("Try again") |
| `:asr` dies or times out in Finishing | same / `Timeout(ASR_FINAL)` | first time: `shutdown()`, re-bind, replay full PCM to a fresh session, `finish()`; second: Failed with audio |
| ASR load fails (`EngineLoadFailed`, `UnitUnavailable(NPU)`, process death during load) | `loadAsr` error | executor: `UnitHealth.markFailed(artifact, unit)`, load `ActiveModels.asrFallback` (the model's other installed artifact) and replay the buffer; bubble keeps Listening. No fallback installed → `AsrLoadFailed(UnitUnavailable(NPU))`: keep recording, at stop "Couldn't load the speech model" + Open → hub row "Download the CPU version (671 MB)"; audio kept |
| LLM load fails on a unit (`EngineLoadFailed`, rpcmem/VTCM/op rejection, PD refusal, death during load) | `load` error or `ProcessDied(LLM)` during load | executor: `markFailed(artifact, unit)`, load `ActiveLlm.fallback` (same GGUF on GPU, then CPU: no download), restore or build the prefix; cleanup proceeds if ready within `LLM_READY`; all failed → "Inserted, no cleanup", `skipped:llm_load` |
| `:llm` dies after it was ready (before cleanup) | `ProcessDied(LLM)` | reload once (`ReloadLlm`: re-bind, load, prefix from disk); second death → no cleanup |
| `:llm` dies or times out in Polishing | `ProcessDied(LLM)` / adaptive timer | `cancel()`; no `onDone` within 1 s → `shutdown(:llm)`; insert the raw transcript (`skipped:llm_died` / `skipped:timeout`) |
| LLM still loading when the transcript is ready | `LLM_READY` timer (10 s) | insert raw (`skipped:llm_not_ready`); the load continues so the next dictation is warm |
| request too long for the context or the time budget | `CleanupBudget.plan` in the reducer, or `LlmContextOverflow` from `:llm` | insert raw, `skipped:too_long` |
| OCR late or failed | `ocrBudgetMs`, `CONTEXT_WAIT` (≤ 800 ms after the transcript) or error | cleanup runs without `screen` (ctx v2 allows it) |
| binder call exceeds its timeout | coroutine `withTimeout` | treated as death of that process: `shutdown()` (kill) then the row above |
| incoming/outgoing call, mic lost, mic silenced, screen off mid-recording | `CaptureHandle.signals` (WP-09) → `ExternalStop(CALL / MIC_LOST / MIC_SILENCED / SCREEN_OFF)` | stop capture and process what was recorded (normal Finishing) |
| accessibility service disconnected mid-dictation | `A11yServiceHost.onServiceState(false)` → `orchestrator.onAccessibilityLost()` → `ExternalStop(A11Y_OFF)` | Listening: stop, Failed with audio (`perm.a11y`); later states: finish processing, store the text, `NotDelivered` (nothing inserted or copied); Dashboard/Settings banner |
| `:main` dies | (system) | dictation lost; service restarts after ~11 s; at next start orphaned audio files (no history row) are deleted |

Every async completion carries the `SessionId`; the reducer drops stale ones (invariant I2).

---

## 5. Dictation state machine

Types: `core/dictation/src/main/kotlin/app/murmur/core/dictation/State.kt`. Reducer: `DictationMachine.reduce(state,
event, config) → Transition(state, effects)` (WP-11). The orchestrator runs effects and feeds completions back as
events, all on one serial dispatcher. Every non-terminal state carries `EngineStatus{asr, llm, context,
asrCrashes, llmCrashes}` and the `SessionPlan` from preflight.

### 5.1 States

| State | Meaning | Bubble (DESIGN 5.3, `BubbleMapping`) |
|---|---|---|
| `Idle` | no dictation; visibility policy decides | Hidden / Rest / Ready (policy, `BubblePolicy.merge`) |
| `Arming(mode, plan?, micStartedAt?, stopRequested)` | press accepted; preflight, FGS + AudioRecord, engine binding in parallel | Listening (tap) or Hold to talk, immediately on press |
| `Listening(mode, warn)` | audio flowing to the ASR session | Listening / Hold to talk; timer turns warn at 4:30 |
| `Finishing(showTranscribing)` | stop received, ASR tail decoding | Listening visuals with the timer frozen at the stop for 300 ms, then Transcribing |
| `Polishing(transcript, cleanupStarted, contextWaitOver)` | transcript ready, cleanup planned: waiting for context / LLM readiness, then the LLM runs | Polishing |
| `Delivering(text, cleaned, shownAs)` | clipboard + insertion + history write | `shownAs` = the previous working bubble (no flash) |
| `Done(outcome)` | result shown, dismiss timer | Inserted / Inserted, no cleanup / Copied only / Error / (Discarded → Idle) |

`StartMode` = TAP, HOLD, NOTIFICATION, IN_APP. Dragging is overlay-local and allowed in every working state; taps on
working pills are ignored (DESIGN 5.3).

### 5.2 Transitions

S = the session. "eff" = effects emitted. **Readiness rows apply in every non-terminal state** (Arming, Listening,
Finishing, Polishing): `AsrReady`, `AsrLoadFailed`, `LlmReady`, `LlmLoadFailed`, `ContextReady` update
`EngineStatus` and change nothing else unless a row below says so. Every row gets one reducer test (WP-11).

| # | From | Event (guard) | To | Effects / notes |
|---|---|---|---|---|
| 1 | Idle | `Input(Tap)` | Arming(TAP) | `Preflight(S, TAP)`, `StartCapture`, `StartTimer(MIC_START 2 s)`, `Haptic(CONFIRM)`; sound per setting |
| 2 | Idle | `Input(HoldStart)` (hold-to-talk on) | Arming(HOLD) | same |
| 3 | Idle | `Input(NotificationStart)` | Arming(NOTIFICATION) | same, no haptic; preflight takes the target snapshot only after the shade collapsed (`snapshotAfterShade(1 s)`: first focused non-SystemUI application window) |
| 4 | Idle | `startInApp(target)` | Arming(IN_APP) | same; target = `InAppTarget` (Murmur's own package allowed only here), privacy `app_only`, no screenshot, no history |
| 5 | Arming | `PreflightPassed(plan)` | Arming(plan) | `PrepareEngines(S, plan)`; `CaptureContext(S, 600 ms)` if `plan.screenshot`; `llm` = Loading if `plan.cleanup` else `NotRequested(reason)`; `context` = Pending if `plan.screenshot` else NotRequested |
| 6 | Arming | `PreflightFailed(e)` | Done(Failed(e)) | `DiscardCapture`, `CancelTimer(MIC_START)`, `CancelEngines`, `Haptic(REJECT)`; no history. Preflight checks, in order: service connected (or IN_APP), mic permission, target snapshot, not a password field (`PasswordField`), not excluded (`AppExcluded`), models resolved (`NoAsrModel`, `NoVadModel`), free storage ≥ 50 MB (`LowStorage`), thermal not critical (`ThermalCritical`) |
| 7 | Arming | `MicStarted(at)` | Arming(micStartedAt) | `CancelTimer(MIC_START)` |
| 8 | Arming | completion: `plan` and `micStartedAt` both known (rows 5/7 in either order), not `stopRequested` | Listening(startedAt = micStartedAt) | `StartTimer(WARN 270 s)`, `StartTimer(MAX 300 s)` |
| 9 | Arming | completion with `stopRequested` | Finishing | effects of row 14 |
| 10 | Arming | `Input(Tap)` / `Input(HoldRelease)` ≥ 300 ms after the press | Arming(stopRequested) | — ; < 300 ms: ignored (double-tap protection) |
| 11 | Arming | `MicFailed(e)` / `TimerFired(MIC_START)` | Done(Failed(MicBusy / MicStartFailed / e)) | `CancelEngines`, `DiscardCapture`, `Haptic(REJECT)`; no history |
| 12 | Arming, Listening | `Input(Cancel)` / `Input(HoldSlideOff)` | Done(Discarded) → Idle | `DiscardCapture`, `CancelEngines` (processes stay warm), `ScheduleIdleUnload`; no history |
| 13 | Arming | `ExternalStop(r)` | Done(Failed) | `DiscardCapture`, `CancelEngines`; CALL → `MicBusy`, A11Y_OFF → `AccessibilityOff`, MIC_* / SCREEN_OFF → `MicStartFailed`; no history |
| 14 | Listening(TAP / NOTIFICATION / IN_APP) | `Input(Tap)` | Finishing | `StopCapture`, `FinishAsr`, `StartTimer(TRANSCRIBING_LABEL 300 ms)`, `StartTimer(ASR_FINAL)`, `CancelTimer(WARN, MAX)`, `Haptic(CONFIRM)`. If `asr` is Failed(e): Done(Failed(e)) instead, eff `StopCapture`, `RecordFailure(e, keepAudio)` |
| 15 | Listening(HOLD) | `Input(HoldRelease)` | Finishing | same as 14 |
| 16 | Listening | `TimerFired(WARN)` | Listening(warn) | — |
| 17 | Listening | `TimerFired(MAX)` | Finishing | same as 14 (auto stop, DESIGN 5.5) |
| 18 | Listening | `ExternalStop(CALL / MIC_LOST / MIC_SILENCED / SCREEN_OFF)` | Finishing | same as 14: the audio so far is processed |
| 19 | Listening | `ExternalStop(A11Y_OFF)` | Done(Failed(AccessibilityOff)) | `StopCapture`, `CancelEngines`, `RecordFailure(keepAudio = true)` |
| 20 | Listening | `AsrLoadFailed(e)` | Listening(asr = Failed(e)) | keep recording (audio kept for "Try again"); the executor already tried the fallback |
| 21 | Listening | `ProcessDied(ASR)`, `asrCrashes` = 0 | Listening(asr = Loading, asrCrashes = 1) | `RestartAsr(S, thenFinish = false)` |
| 22 | Listening | `ProcessDied(ASR)`, `asrCrashes` ≥ 1 | Listening(asr = Failed(ProcessDied(ASR))) | keep recording; row 14 fails at the stop |
| 23 | Arming, Listening, Finishing | `ProcessDied(LLM)`, cleanup planned, `llmCrashes` = 0 | same(llm = Loading, llmCrashes = 1) | `ReloadLlm(S)` |
| 24 | Arming, Listening, Finishing | `ProcessDied(LLM)`, `llmCrashes` ≥ 1 | same(llm = Failed(ProcessDied(LLM))) | — |
| 25 | Finishing | `TimerFired(TRANSCRIBING_LABEL)` | Finishing(showTranscribing) | — |
| 26 | Finishing | `TranscriptReady(t)` with `t.speechMs` < 500, blank text, or `precheckSkip` | Done(Failed(NoSpeech)) | `CancelTimer(ASR_FINAL)`, `RecordFailure(keepAudio = true)` (DESIGN: saved as Failed, re-runnable) |
| 27 | Finishing | `TranscriptReady(t)`, `llm` ∈ {NotRequested, Failed} | Delivering(t.text, cleaned = false) | `CancelTimer(ASR_FINAL)`, `Deliver(raw, cleanupOutcome = "skipped:<reason>")` with reason none / no_model / password / low_memory / llm_load / llm_died |
| 28 | Finishing | `TranscriptReady(t)`, `llm` ∈ {Loading, Ready} | Polishing(t, cleanupStarted = false) | `CancelTimer(ASR_FINAL)`; `StartTimer(CONTEXT_WAIT, min(800 ms, OCR deadline left))` if `context` Pending; `StartTimer(LLM_READY 10 s)` if `llm` Loading; then the start rule (row 31) |
| 29 | Finishing | `AsrFailed(ProcessDied / Timeout)` / `TimerFired(ASR_FINAL)` / `ProcessDied(ASR)`, `asrCrashes` = 0 | Finishing(asrCrashes = 1) | `RestartAsr(S, thenFinish = true)`, `StartTimer(ASR_FINAL)` |
| 30 | Finishing | the same with `asrCrashes` ≥ 1, or any other `AsrFailed(e)` | Done(Failed(e)) | `RecordFailure(keepAudio = true)`, `CancelEngines` ("Try again") |
| 31 | Polishing(!started) | **start rule**: (`context` ≠ Pending or `contextWaitOver`) and `llm` = Ready | Polishing(started) or Delivering | `plan = CleanupBudget.plan(chars, prefixTokens, nCtx, speed ?: defaultSpeed[unit])`; fits → eff `RunCleanup(S, t, plan)`, `StartTimer(LLM, plan.timeoutMs)`, `CancelTimer(CONTEXT_WAIT, LLM_READY)`; does not fit → Delivering(raw), `Deliver(skipped:too_long)` |
| 32 | Polishing(!started) | `TimerFired(CONTEXT_WAIT)` | Polishing(contextWaitOver) | start rule (render without the screen) |
| 33 | Polishing(!started) | `ContextReady` / `LlmReady` | Polishing(updated) | start rule |
| 34 | Polishing(!started) | `TimerFired(LLM_READY)` (llm still Loading) | Delivering(raw) | `Deliver(skipped:llm_not_ready)`; the load continues |
| 35 | Polishing(!started) | `LlmLoadFailed(e)` / `ProcessDied(LLM)` | Delivering(raw) | `Deliver(skipped:llm_load / llm_died)` |
| 36 | Polishing(started) | `CleanupDone(text, cleaned, outcome)` | Delivering(text, cleaned) | `CancelTimer(LLM)`, `Deliver(text, raw, cleaned, outcome)`; outcome = accept / fallback:<rule> / fallback:truncated / skipped:too_long (from `LlmContextOverflow`) |
| 37 | Polishing(started) | `TimerFired(LLM)` | Delivering(raw) | `Deliver(skipped:timeout)` (the executor cancels generation; no `onDone` within 1 s → `shutdown(:llm)`) |
| 38 | Polishing(started) | `ProcessDied(LLM)` | Delivering(raw) | `Deliver(skipped:llm_died)` |
| 39 | Delivering | `Delivered(Inserted)` | Done(Inserted(noCleanup = !cleaned)) | `Haptic(CLOCK_TICK)`, `StartTimer(RESULT_DISMISS 1.2 s)`, `ScheduleIdleUnload` |
| 40 | Delivering | `Delivered(CopiedOnly)` | Done(Copied) | `StartTimer(RESULT_DISMISS 4 s)`, `ScheduleIdleUnload` |
| 41 | Delivering | `Delivered(DiscardedPassword)` | Done(Discarded) → Idle | `ScheduleIdleUnload`; nothing stored, nothing copied |
| 42 | Delivering | `Delivered(NothingToInsert)` (guard EMPTY: only fillers) | Done(Failed(NoSpeech, entry)) | `StartTimer(RESULT_DISMISS 6 s)`, `ScheduleIdleUnload` |
| 43 | Delivering | `Delivered(NotDelivered(entry, e))` (service gone) | Done(Failed(e, entry)) | same |
| 44 | Delivering | `TimerFired(DELIVERY 2 s)` | Done(Copied) | the text is already on the clipboard (I5); the executor still writes the history row |
| 45 | Done | `TimerFired(RESULT_DISMISS)` | Idle | — |
| 46 | Done | `Input(Tap)` / `Input(HoldStart)` | Arming(TAP / HOLD) | as rows 1/2: a new session; the pill is replaced |
| 47 | Done(Copied(entry)) | `Input(ResultTap)` | Idle | `Open(Entry(entry))` (no entry when retention = 0: dismiss only) |
| 48 | Done(Failed) | `Input(ResultTap)` | Idle | dismiss |
| 49 | Done(Failed(e, entry)) | `Input(ResultAction)`, action RETRY | Arming(TAP) for NoSpeech; else Idle | NoSpeech: new dictation (row 1). Otherwise `RetryEntry(entry)` (re-transcribe the stored audio) |
| 50 | Done(Failed) | `Input(ResultAction)`, action OPEN_MODELS / OPEN_ENTRY | Idle | `Open(Models(ASR))` / `Open(Entry(entry))` |
| 51 | Finishing, Polishing, Delivering | any `Input` | unchanged | ignored (prevents a double stop; DESIGN 5.3) |
| 52 | Finishing, Polishing, Delivering | `ExternalStop(*)` | unchanged | capture is already stopped; delivery reports `NotDelivered` if the service is gone |
| 53 | Idle, Done | `ProcessDied(*)`, readiness events, timers of the session | unchanged | dropped; the host reconnects at the next press |
| 54 | * | any event of another session | unchanged | dropped (I2) |

### 5.3 Timeouts (`DictationConfig`, all × `timeoutScale`)

| Timer | Value | Source |
|---|---|---|
| MIC_START | 2 s | spike: FGS 8-90 ms + first buffer 67-209 ms |
| screenshot delay | 600 ms after press; ≥ 340 ms between captures of one window | D11, platform spike (333 ms limit) |
| shade collapse (NOTIFICATION) | ≤ 1 s | [I] |
| OCR budget | 4 s after the screenshot | [I] |
| CONTEXT_WAIT | min(800 ms, OCR deadline left) after the transcript | review 2026-11-19; [I] |
| WARN / MAX | 4:30 / 5:00 | DESIGN 5.5 |
| TRANSCRIBING_LABEL | 300 ms | DESIGN 5.3 |
| ASR_FINAL | 4 s + 0.5 s × tail seconds | [I]; NPU = one 20 s window call |
| LLM_READY | 10 s after the transcript, while the LLM loads / prefills | covers a cold NPU load [I] and a CPU prefix prefill (LFM3 8.8 s) |
| LLM | started with RunCleanup: `min(64 s, 4 s + 1.5 × expected)`, expected = suffix tokens / prefill tok/s + 1.1 × transcript tokens / decode tok/s from the latest valid benchmark (else `defaultSpeed[unit]`); `expected > 40 s` → not attempted (`skipped:too_long`) | ADR-015 revised by ADR-045 (replaces DESIGN's fixed 6 s) |
| DELIVERY | 2 s | [I] |
| RESULT_DISMISS | Inserted 1.2 s, Copied 4 s, Error 6 s | DESIGN 5.3 |
| idle unload | 60 s (`Settings.idleUnloadSec`) | D5; revisit after DC2/DC8 |

### 5.4 Cancellation

- User cancel (×, slide-off) in Arming/Listening: capture discarded, ASR session cancelled, LLM generation never
  started, nothing stored. Processes stay warm (idle timer).
- Cancel is impossible after the stop (working states ignore taps, DESIGN 5.3).
- A press during Preparing cancels the preparation (it resumes later); the dictation never waits for it.
- LLM cancel latency: CPU = one graph node, NPU/GPU = one `llama_process` call (≤ one 512-token chunk) (llm spike).

### 5.5 Failure paths and what the user sees

| Case (DESIGN 5.5) | Error | Bubble | History |
|---|---|---|---|
| < 0.5 s speech, noise, only fillers | `NoSpeech` | "Didn't catch that" + Retry (new dictation) | Failed, audio kept |
| mic busy / silenced at start | `MicBusy`, `MicSilenced`, `MicStartFailed` | "Microphone is busy" | none |
| no speech model / no VAD for a batch model | `NoAsrModel`, `NoVadModel` | "Download a speech model first" + Open | none |
| ASR load failed, no fallback installed | `UnitUnavailable(NPU)` | "Couldn't load the speech model" + Open (hub: "Download the CPU version") | Failed, audio kept |
| ASR load failed otherwise | `EngineLoadFailed(ASR)`, `OutOfMemory(ASR)`, `EngineRuntimeMissing` | "Couldn't load the speech model" + Retry | Failed, audio kept |
| ASR crashed/timed out twice | `ProcessDied(ASR)`, `Timeout(ASR_FINAL)` | transcription failed + Open entry | Failed, audio kept |
| cleanup skipped / failed / timed out / too long / low memory | `LlmFailed`, `Timeout(LLM)`, `OutOfMemory(LLM)`, `LlmContextOverflow`, guard fallback | "Inserted, no cleanup" | Inserted/Copied, `cleanup_outcome = skipped:<code>` or `fallback:<rule>` |
| insertion rejected / no field / app changed | `InsertRejected`, `NoEditableField`, `TargetChanged` | "Copied. Paste it anywhere" | Copied |
| password field at delivery | — | nothing (Idle) | nothing |
| accessibility service killed | `AccessibilityOff` | no bubble; red banner in Dashboard/Settings | Failed with audio if Listening; text kept if later |

### 5.6 Invariants

- I1: one session at a time (`SessionId` increments per press; it restarts with `:main`, so it is never a
  persistent key).
- I2: every completion event carries its session; stale ones are dropped.
- I3: the FGS lives only while AudioRecord records (the idle status notification is a separate, plain notification).
- I4: the screenshot, OCR lines and ctx v2 exist only in memory of the current session (inside the executor, not in
  the reducer state) and are dropped at `Done`.
- I5: text is always copied to the clipboard before the insertion attempt (BRIEF flow 7), except password fields and
  `NotDelivered`.
- I6: `Done(Failed)` with audio is the only path that keeps audio without text.
- I7: the LLM timer never runs while the LLM is loading or prefilling its prefix.

---

## 6. End-to-end sequence and latency budget (Fold7)

### 6.1 Sequence (times from the press, overlapped work in brackets)

```
t=0       press → Arming; bubble Listening; haptic; preflight (snapshot ≤ 150 ms, resolve, decide) in parallel
t+10..90  RecordingFgs started; AudioRecord.startRecording        (platform spike: 8-90 ms)
t+70..210 first buffer → MicStarted; PreflightPassed → Listening (PCM buffered in :main + pipe)
[t+0..0.3 s] bind :asr + :llm (process start) [I]
[t+0.3..1.3 s] :asr loads Parakeet QNN context (+VAD, OCR)        (0.5-1.2 s SM8850 sherpa log [I])
[t+0.3..3 s] :llm loads LFM3 Q4_0 (n_ctx 9,216) into rpcmem + restores the prefix snapshot from disk
             (snapshot ≈ 50-60 MB for 3.1-3.2k tokens [I, scaled from 17-34 MB at 1.1k, VERIFIED host 4-11 ms]; load UNVERIFIED-ON-DEVICE)
[t+0.6 s] window screenshot (6-70 ms call + copy), SharedMemory → :asr OCR (det 960 + ~20 lines) [I] 0.4-1 s
[ongoing] each VAD pause closes a segment → decoded (NPU) → Committed
stop tap → Finishing: tail decode → Transcript → precheck → (wait ≤ 800 ms for OCR) → CleanupBudget → render (ctx v2)
          → generate → guard → Delivering → clipboard + commitText + read-back → Done(Inserted) → history + metrics → idle timer
```

### 6.2 After-stop budget, 10 s dictation, warm (≈ 25 Russian words; 34 tokens Qwen3.5, 46 tokens LFM3; llm-models 6)

The prefix (3,129-3,240 tokens) is never prefilled here: it is restored from the snapshot built at Preparing.

| Step | NPU path (Parakeet NPU + LFM3 NPU) | CPU path (Parakeet CPU + Qwen3.5 0.8B CPU) | Source |
|---|---|---|---|
| VAD flush + tail decode | 0.10-0.20 s (one 20 s window) | 0.3-0.6 s (≤ 5 s tail at RTF 0.06-0.1) | AI Hub encoder 38 ms; S24 CPU RTF 0.064-0.104 [I] |
| precheck + render + binder + prefix restore | 0.03 s | 0.03 s | [I]; restore 3-5 ms per 17-34 MB VERIFIED host |
| LLM suffix prefill (Style + ≤ 1,400-char context ≈ 500 tok + transcript) | 560 tok / 2,597 tok/s = 0.22 s | 500 tok / 569 tok/s = 0.88 s | AI Hub S25 perf (VERIFIED as published) |
| LLM decode (≈ transcript length) | 46 / 40.9 tok/s = 1.12 s | 34 / 83.8 tok/s = 0.41 s | same |
| guard + insertion + read-back | 0.05-0.15 s | 0.05-0.15 s | [I] |
| **Total after stop** | **≈ 1.5-1.7 s** | **≈ 1.7-2.1 s** | design sample "1.4 s" |

### 6.3 After-stop budget, 60 s dictation, warm (≈ 150 words; 204 tokens Qwen3.5, 278 tokens LFM3)

Context check (ADR-045): LFM3 prefix 3,240 + suffix ≈ 790 + output ≤ 1.5 × 278 + 64 = 481 → 4,511 tokens ≤ n_ctx 9,216.
With the old n_ctx 4,096 this case could not run (review blocker, VERIFIED by token count).

| Step | NPU path | CPU path (Qwen3.5 0.8B) |
|---|---|---|
| tail decode | 0.10-0.20 s (earlier segments already decoded) | 0.3-0.6 s |
| LLM prefill (suffix only) | 790 tok / 2,597 = 0.30 s | 710 tok / 569 = 1.25 s |
| LLM decode | 278 / 40.9 = **6.8 s** | 204 / 83.8 = **2.4 s** |
| insertion | 0.1 s | 0.1 s |
| **Total after stop** | **≈ 7.3 s** | **≈ 4.1 s** |

### 6.4 Cold paths (after the 60 s idle exit, or after the prefix changed)

| Case | What happens after the stop | After-stop time [I] |
|---|---|---|
| A. 3 s dictation, first after > 60 s idle, NPU path | LLM load (1.05 GB copied into rpcmem, mmap off; UNVERIFIED-ON-DEVICE, estimate 1-3 s) + snapshot load from disk started at the press; after stop = max(0, load_end − stop) + 10 s budget rows | 1.3-1.6 s + 0-0.3 s wait |
| B. same on the CPU path (Qwen3.5, `use_mmap=0`, 0.5 GB read + repack) | load ≈ 0.5-1.5 s, finished before the stop | ≈ 1.2-1.6 s |
| C. first dictation after a prompt / preferences edit or a unit change, before the background re-prepare finished | full prefix prefill: LFM3 NPU 3,240 / 2,597 = 1.25 s; LFM3 CPU 3,240 / 368 = 8.8 s; Qwen3.5 CPU 3,129 / 569 = 5.5 s (AI Hub S25) | + max(0, prefill_end − stop); > 10 s → "Inserted, no cleanup" (`skipped:llm_not_ready`) |
| D. NPU load fails (first time on this phone, if install-time Preparing did not catch it) | fallback to GPU/CPU of the same GGUF (no download) | + one CPU load (≈ 1-2 s [I]) |

Preparing (8.5) makes C rare: it rebuilds the snapshot within ≈ 5 s of the edit while no dictation runs. DC8
measures A-D ("first dictation after 70 s idle, 3 s long" is a scripted case). The 60 s idle default is re-evaluated
with DC2's NPU load time: if the cold NPU load exceeds the typical dictation length, the default grows (memory
cost: the 1.2-1.4 GB of `:llm` stays resident longer).

### 6.5 Cleanup budget and the longest dictation that is cleaned up (owner-visible rule, ADR-045)

`CleanupBudget.plan` (pure, `core/dictation/Config.kt`) estimates tokens at 0.40 tokens/char (measured 0.31-0.34 on
the ctx v2 goldens with both tokenizers, VERIFIED), reserves 1,850 chars for the context block, requires room for
1.1 × transcript tokens of output (caps at 1.5 ×, the guard's R4 limit) and an expected time ≤ 40 s.

| Path (AI Hub S25 speeds, [I] until DC2/DC9) | Longest cleaned dictation | Beyond it |
|---|---|---|
| Qwen3.5 0.8B CPU (569 / 83.8 tok/s) | the full 5:00 (limit is the context: ≈ 6.4 min) | — |
| LFM3 1.7B NPU (2,597 / 40.9 tok/s) | ≈ 3.7 min (limit is the 40 s time budget) | "Inserted, no cleanup" (`skipped:too_long`) |

Chunked cleanup per segment would remove the limit but contradicts D4 (ADR-037); it is an open owner question with
these numbers.

Optional optimisation behind a flag, off by default: prefill the user message up to `<transcript>\n` while the user
speaks, accepted only if `tokenize(prefix)+tokenize(suffix) == tokenize(full)` holds on the golden corpus for every
shipped template (WP-10 host test). Every number in section 6 is UNVERIFIED-ON-DEVICE and replaced by DC8/DC9 results.

---

## 7. Memory budget (12 GB device)

| Item | Resident while dictating | Basis |
|---|---|---|
| Android + One UI + launcher + foreground app | ≈ 5-7 GB used; MemAvailable typically 4-6 GB [I] | UNVERIFIED-ON-DEVICE (DC1 prints MemAvailable) |
| `:main` idle | target < 120 MB PSS | android-platform 10 [I] |
| `:main` dictating | + 17 MB frame + 17 MB SharedMemory copy (freed after OCR) + ≤ 10 MB PCM buffer + encoder ≈ 170 MB | computed |
| `:asr` Parakeet NPU + VAD + OCR | ≈ 0.6-1.0 GB (context binaries 653 MB on disk) + ORT arenas 60-140 MB [I] | catalog memoryMb 900 |
| `:asr` Parakeet CPU int8 fallback | 1.3 GB | host peak RSS VERIFIED (asr-ocr 3) |
| `:llm` LFM3 Q4_0 NPU | ≈ 1.1-1.2 GB rpcmem (weights copied, not mmapped) + 77 MiB compute + KV 9,216 × 12 KB = 108 MB + prefix snapshot in RAM ≈ 50 MB | llm spike 3.3, 5.4; KV formula VERIFIED (GGUF dims) |
| `:llm` LFM3 CPU (`use_mmap=0`) | 1.25 GB at n_ctx 4,096 VERIFIED + 60 MB more KV = 1.31 GB | catalog memoryMb |
| `:llm` Qwen3.5 0.8B CPU | 0.71 GB VERIFIED + 60 MB KV = 0.77 GB (+ ≈ 20 MB DeltaNet state in the snapshot) | catalog memoryMb |
| `:llm` Qwen3 0.6B/1.7B (full attention, 112 KB/token) | + 1.0 GB KV at 9,216 | catalog memoryMb 1,760 (hedge models) |
| **Peak total** | **≈ 2.5 GB (NPU+NPU) … 2.3 GB (Parakeet CPU + Qwen3.5 CPU) … 2.8 GB (Parakeet CPU + LFM3 CPU)** | sum |
| Idle (after 60 s) | `:main` only, ≈ 100 MB | D5 |

Policy (`MemoryGate`, WP-11): after the ASR load starts, the LLM loads in parallel only if
`availMem − MemoryEstimate.mb(llm) ≥ 600 MB`. Otherwise the LLM loads after the ASR tail is done and `:asr` has
been shut down (adds the LLM load to the after-stop time, inside `LLM_READY`), or, if still short, cleanup is
skipped ("Inserted, no cleanup", reason `low_memory`). `MemoryEstimate` = the catalog `memoryMb[unit]`, or for
custom models file bytes × 1.2 + KV(nCtx) (LLM) / file bytes × 1.5 (others); `build_catalog.py` fills every bundled
value (estimated ones say so in `notes`). CPU LLMs load with `use_mmap=0` (ADR-031). `n_outputs_max = n_seq_max`
(llm spike D10). Android 17 MemoryLimiter kills are watched through `ApplicationExitInfo` (REASON_OTHER,
"MemoryLimiter") and counted in the device report. Debug builds can ignore the gate (`Settings.debugIgnoreMemoryGate`)
so that the 3 GB emulator exercises the LLM path.

---

## 8. Model catalog, downloads, storage

### 8.1 Schema (`core/catalog/.../CatalogSchema.kt`, schema 1)

`Catalog{schema, catalogVersion, generated, defaults{asr{ru+en,en,ru}, llm, vad, ocrDet, ocrRec}, packs[],
models[]}`. `ModelEntry{id, kind(asr|llm|ocr-det|ocr-rec|vad), displayName, publisher, family, baseRepo,
description, params, paramsLabel, languages{codes, count, mode}, license{id,url,note}, tier, status, asr{streaming,
punctuation, casing, sampleRate, previewOnly}, llm{template, promptProfile, nCtx, prefixTokens, kvBytesPerToken},
ocr{stage, pipelineVersion}, artifacts[], followUps[{id:"npu-port", note}], notes, evidence,
origin(bundled|refreshed|custom)}`. `Artifact{id, runtime (RuntimeKeys), format, quant, units[npu|gpu|cpu],
preferredUnit, socs[], files[], layout{role → path}, maxWindowSec, memoryMb{unit → MB}}`. `FileSpec{name, url, bytes,
sha256 (mandatory), source(huggingface|github), repo, revision (full commit sha or release tag), path,
archive(tar.bz2|zip)}`.

`CatalogValidator` enforces: unique ids, defaults and packs reference existing models, `defaults.asr` never a
`previewOnly` model, known runtime keys, every file has a 64-hex sha256 and bytes > 0, URL host in {huggingface.co,
github.com}, HF URLs pinned to the revision, safe local names, LLM `nCtx ≥ prefixTokens + 5,200`, every bundled
artifact has `memoryMb` for every unit. The bundled catalog (27 models, 63 files) passes (`BundledCatalogTest`, 13
tests), and its generator `core/catalog/tools/build_catalog.py` re-verified every size and sha256 against
`POST /api/models/<repo>/paths-info/<rev>` and the GitHub release-asset digest (VERIFIED 2026-11-19, network run, log
`.cache/spikes/architect/build_catalog3.log`; the OCR entries' `pipelineVersion` is read from `ml/ocr/config.py`). K-quant artifacts claim NPU too (ADR-009; preferredUnit unchanged, Q4_0
stays the default artifact). Fine-tune entries: `murmur-cleanup-lfm3-1.7b`, `-qwen3.5-0.8b`, `-qwen3.5-2b` (status
in_training, profile `finetuned.v1`, nCtx 6,144).

### 8.2 Unit selection (D2)

`ArtifactSelector.candidates(entry, device, health)` lists every (artifact × unit) with a blocker
(`RUNTIME_NOT_SHIPPED`, `WRONG_SOC`, `UNIT_NOT_AVAILABLE`, `UNIT_FAILED`). `ranked()` orders the usable ones: a pinned
unit first; then the lowest **valid** benchmark score (ASR: processing ms per audio second; LLM: end-to-end ms of the
fixed case; `BenchLookup` returns null for invalid records); then the catalog's artifact order (default artifact
first, so a fallback is the same file on another unit); then `preferredUnit`; then NPU > GPU > CPU. `choose()` = first,
`fallbackAfter()` = the next. A pinned unit whose artifact is not installed is offered for download, not an error. The
hub shows the claimed units as badges, the chosen unit, "NPU not supported yet" for models without an NPU artifact
(followUp `npu-port`), "Failed to load on NPU, using CPU" for `UNIT_FAILED`, "Results look wrong on <unit>" for invalid
benchmark records, and "Ran on" from the benchmark. The unit preference is stored per **model** (`Settings.unitPreference[modelId]`).

### 8.3 Storage layout (`data/models/.../Storage.kt`)

`noBackupFilesDir/models/<artifactId>/` holds the files, `<name>.part` while downloading (its length is the resume
offset), archives extracted in place then deleted, `installed.json` written last and atomically (the artifact is
installed iff it exists, with the verified sha256 of each file), and `prefix-<key>.state` LLM snapshots
(`PrefixCacheKey.of(model sha256, sha256(prefix), unit, native build id, n_ctx, kv type, n_batch)`; a load error
deletes the file and rebuilds it). `unit-health.json` (reset per app version). Never in backups, never in the APK
(BRIEF). Peak disk = archive + extracted (×2 for the NPU tarballs).

### 8.4 Device profile

`DeviceProfileRepository` (WP-13 only) binds both processes and calls `probe()` on first launch per app version (and
on `reprobe()`): implemented runtime keys, units that really initialised (QNN HTP backend created; ggml HTP0 /
GPUOpenCL registered), facts (SoC, HTP arch, QNN build, CPU variant, `native.build`). Cached in noBackupFilesDir;
feeds `DeviceProfile`. `ensure()` never throws: a failed probe yields `DeviceProfile.cpuOnly`. The resolver and
onboarding's pack sizes call `ensure()`. On the emulator: soc null, units {CPU}.

### 8.5 Download, verify, install, prepare (WP-13; lifted from the platform spike's UIDT job)

1. Pre-check free space ≥ peak install bytes + 200 MB, Wi-Fi-only setting.
2. Schedule a user-initiated data transfer job (`JobInfo.setUserInitiated`, `RUN_USER_INITIATED_JOBS`); one artifact
   per job; progress notification via `setNotification(DETACH)`; `updateTransferredNetworkBytes`.
3. Per file: HTTPS GET with `Range: bytes=<part length>-`; every redirect host must end with an allowed suffix
   (`huggingface.co`, `.hf.co`, `github.com`, `.githubusercontent.com`); stream SHA-256; `fsync`; on mismatch delete
   the part and report `ChecksumMismatch` ("Download was damaged. Murmur deleted it.").
4. Extract archives with path-traversal checks (commons-compress, ADR-017); verify `layout` paths exist.
5. Write `installed.json`; state → `Preparing`: `EnginePreparer.prepareArtifact` (the orchestrator) loads the artifact
   on every usable unit (ASR: + 1 s decode of a bundled clip; LLM: + build and save the prefix snapshot), marking
   `UnitHealth` proven or failed; → `Installed`. If an NPU ASR artifact fails and the model's CPU artifact is not
   installed, the report's `needsCpuArtifact` makes WP-13 queue it (onboarding shows the extra size).

**What a download queues** (pack semantics, ADR-050): a pack lists model ids; each model resolves to
`ArtifactSelector.choose(entry, device, Auto)` (one artifact per model). In addition: an ASR artifact with a batch
runtime (`sherpa/offline-*`) queues the default VAD; turning on "Use what's on screen" (default on) queues the OCR
pair; an NPU ASR artifact that failed Preparing queues the model's CPU artifact. Choosing a model in the hub follows
the same rules. A selected but uninstalled LLM is not an error: cleanup is skipped (`skipped:no_model`) and the hub
shows "Download to use".

### 8.6 Custom models (D3, ADR-046)

The hub's "Add a model" sheet takes a Hugging Face repo id (+ optional revision, file, prompt profile).
`HfCustomModelResolver` resolves the revision to a commit sha, lists files with `paths-info` (sizes + LFS sha256;
small non-LFS files are hashed after download), detects the layout (`SherpaLayout` for ASR; one GGUF for the LLM,
Q4_0 preferred), reads the GGUF header with a Range request: `general.architecture` → template
(`GgufTemplates`: qwen35 → qwen3.5, qwen3 → qwen3, lfm3 → lfm3, lfm2 → lfm2.5; anything else is rejected: render.py has
no other template), prompt profile = `murmur.prompt_profile` key (requested from ML for `ml/train/export.py`), else
`finetuned.v1` when `general.name` starts with `murmur-cleanup-`, else `default.v4`. nCtx = `GgufTemplates.nCtxFor`
of the prefix tokens measured at Preparing (9,216 until then). The entry (origin `custom`) is stored in Room and
behaves like any catalog model, including Preparing and Benchmark.

### 8.7 Catalog refresh

Bundled only in v1. The schema carries `catalogVersion`; a later refresh downloads the same document from an
owner-controlled HF repo (needs the owner's repo; open question) and replaces the bundled copy only if it validates
and is newer. Until then, new fine-tunes reach users through a new APK or the custom-model path (ADR-046).

---

## 9. Context pipeline (D11, CONTEXT_V2)

### 9.1 Screenshot (WP-02)

At press: `A11ySnapshot` = focused `TYPE_APPLICATION` window (never `rootInActiveWindow` at tap time), package,
label (`<queries>` LAUNCHER; fallback window title), window title (conversation), focused editable via
`findFocus(FOCUS_INPUT)` with BFS fallback for Compose/WebView, `EditorInfo` from the accessibility IME,
`before_cursor` = `getSurroundingText(1000, 0, 0)` cut at the selection (0-8 ms), password flag, field bounds,
exclusion rects (IME, own overlay, status bar). NOTIFICATION start: the same after the shade collapsed
(`snapshotAfterShade`). At +600 ms: re-resolve the window, `takeScreenshotOfWindow` (contains only app pixels: no
keyboard, bubble or bars); error 3 → one retry after ≥ 340 ms; error 5 → `findFocus(...).windowId`, then
`takeScreenshot(display)` + exclusion rects; error 6 or a black frame → `SecureWindow` (no screen). HardwareBuffer →
`ARGB_8888` copy → `SharedMemory`. No PNG on the dictation path.

### 9.2 Privacy filters (WP-08, before any screenshot)

| Condition | Decision |
|---|---|
| focused field is a password (`isPassword` or inputType variation) | bubble hidden (DESIGN 5.2, also with "Show always"); if focus becomes a password field later: no screenshot, `privacy none`, LLM skipped, nothing inserted, copied or stored |
| package in the built-in denylist (`BuiltInDenylist`: SystemUI, Settings, permission controller, biometrics) or the user's "Hidden in" list | bubble hidden; no dictation |
| "Use what's on screen" off | no screenshot; `privacy app_only` (no screen, no field text) |
| secure window / screenshot failed / OCR empty or late | `screen` absent; `privacy full` (field context kept) |
| Murmur's own UI | bubble hidden; only the in-app practice (`StartMode.IN_APP`) dictates into Murmur, `app_only` |

### 9.3 OCR (WP-07)

`OcrPipeline` = Kotlin port of `ml/ocr` (`OcrConfig` defaults == `ml/ocr/config.py`, drift test
`OcrConfigParityTest`): BGR input, detector long side 960 (multiple of 32, never upscale), DB post-processing
axis-aligned (0.2 / 0.4 / unclip 1.4), exclusion rects, recogniser crops from the original image at height 48 padded
to width buckets, batch 1 on CPU, greedy CTC, NFC + filters + homoglyph repair + geometric and textual de-dup +
two-pane reading order. Parity: post-processing fed with `golden/*.json stages.rec` must reproduce `ocr` exactly; the
full pipeline on the x86_64 emulator within the README tolerances (same line count, boxes ±2 px, ≥ 98 % identical
texts, CER ≤ 0.5 % on the rest, conf ±0.02). Runtime ORT 1.32 default CPU EP (no XNNPACK: segfault on the recogniser,
asr-ocr A7) behind the `OcrHandler` seam in `:asr`. NPU OCR (ORT QNN plugin EP) is prepared by WP-21 and adopted
only if device step **DC4** shows a clear win.

### 9.4 ctx v2 → prompt (WP-08, WP-04)

`ContextAssembler.assemble(snapshot, decision, screen, asrLanguage)` builds `CtxV2{app{package,label,category},
field{kind, hint, before_cursor, box}, screen{size, lines}, language{asr}, privacy}`; category from
`prompts/tones/app_categories.v1.json`. The renderer (`render.py` port) does all trimming: 1,400-character budget,
nearest-to-field order, clock/status/low-confidence/field-text lines dropped, `<`/`>` neutralised. Request =
cached prefix (system + preferences + few-shot turns) + suffix (`Style:` line, `<context>`, `<transcript>`,
assistant opener with thinking off). `RenderedPrompt.prefixKey` = sha256(prefix); `:main` builds the snapshot key with
`PrefixCacheKey.of(...)` (8.3). The user's edited instructions replace `system/<stem>.md` (after `rstrip("\n")`,
before the preferences block) only when `PromptEditRules.overrideApplies(profile, override.baseProfile)`: the stock
profile it was written for, never `finetuned.*` (ADR-046).

---

## 10. Benchmark (WP-14, UI in WP-18)

Runs through the production processes and code (same services, same JNI) after `shutdown()` of the target process
(cold load). Protocol per usable unit: cold load → warm-up → measured pass(es) → unload → memory → validity gate.
Target: < 60 s per unit on the phone; every usable unit is measured when the user taps "Run benchmark".

| Model kind | Measured | Validity gate (`BenchValidity`) | Shown (DESIGN 6.4) |
|---|---|---|---|
| ASR | cold/warm load ms; clips (FLEURS dev CC-BY-4.0, ≈ 10-12 s each: ru, en, mixed; only clips whose languages are all in `languages.codes`; resampled for 8 kHz models) pushed through the same `AsrSession` **as fast as `accept()` allows**, one warm-up + one measured pass per clip; RTF× = audio ms / Σ decode ms; tail ms of the last segment; WER vs the reference; peak memory (VmHWM + PSS + system availMem drop); unit actually used (`LoadInfo.deviceName`) | median WER ≤ 20 % and ≤ the same model's CPU WER + 5 pp when a CPU record exists | Faster than real time (×), Load time, Peak memory, Ran on, per-sample times |
| LLM | cold load; synthetic pp (512 tokens) and tg (128 tokens) via `mllm_bench`; fixed cleanup case (real system prefix + ≈ 60-word ru transcript + 200-token context): TTFT, prefill tok/s, decode tok/s, end-to-end ms; op placement share on HTP | non-empty, guard ACCEPT, mostly Cyrillic, stop reason EOG | Writing speed, Reading speed, First word, Peak memory, "A 60-word dictation is cleaned up in about N s" (`BenchSummary`) |
| OCR | ms per bundled screenshot (cover 1080×2520 and inner 1968×2184), boxes found, load, memory | boxes > 0 and texts match the golden within the README tolerance | ms per screenshot (OCR rows under "Support models") |

Results: `BenchmarkRecord(artifact, unit, appVersion, Build.FINGERPRINT, time, result{…, valid, invalidReason})` in
Room. The latest **valid** record per (artifact, unit) feeds `ArtifactSelector`, the LLM speed of `CleanupBudget` and
the adaptive timeouts; a valid record clears `UnitHealth` for that unit; invalid ones are shown ("Results look wrong
on NPU") and ignored. The phone must stay awake and Murmur in the foreground during a run (DESIGN). Thermal status
before/after is stored in the record notes.

---

## 11. Insertion ladder and delivery (WP-02 ladder, WP-11 rules)

1. Re-read focus (`TargetReader.current()`). Password → discard everything (I5 exception). Service gone →
   `NotDelivered` (text stored in history, not inserted or copied).
2. `copyToClipboard(text)` (ClipData, `EXTRA_IS_SENSITIVE=false`; background write VERIFIED, read is denied).
3. If the focused package ≠ snapshot package or no editable node → `CopiedOnly(TargetChanged/NoEditableField)`.
4. Join with `JoinPolicy` (leading space rules) against the current `before_cursor`.
5. Ladder: `AccessibilityInputConnection.commitText` (accessibility IME, API 33) → `ACTION_PASTE` →
   `ACTION_SET_TEXT` (+ `ACTION_SET_SELECTION` to restore the caret). After each: read back with
   `getSurroundingText` (node text is stale on WebView, platform spike e). First verified method wins.
6. All fail → `CopiedOnly(InsertRejected)`. DB write failures never block delivery (text is already out).
7. IN_APP: `InAppTarget.insert(text)`; no clipboard, no history.

---

## 12. History, retention and stats data model (WP-12)

Tables (`data/db/.../Entities.kt`, Room schema exported to `data/db/schemas/`): `history` (one row per dictation:
app, conversation, status, final/raw text, language, models + units, prompt version, cleanup outcome, tone, audio ref,
timings, insert method, failure code, revision, `context_words` = a count only, ADR-043, pending_delete), `metrics`
(numbers only, see below), `benchmark`, `custom_model`. Settings: one typed DataStore document (`Settings`, section 13).
Audio files are named by `AudioKey` (`<createdAtEpochMs>-<session>`), so a `:main` restart never overwrites an older
entry's audio.

Retention: `RetentionPolicy` — entries from local day D are deleted (rows + audio files) at 00:00 of D + N (N = 1/3/7,
default 7). N = 0 ("Don't keep history"): nothing is written (no rows, no audio, Failed included) and the immediate
run deletes everything, today included (`firstKeptDay(today, 0)` = tomorrow). Runs at app start, at the local
midnight job (`RetentionJob`, rescheduled daily and on time-zone change), and immediately after shortening N (after
the confirm dialog). Metrics rows are kept `STATS_DAYS = 90` (owner decision pending). Undo: soft delete
(`pending_delete`) committed when the snackbar times out; audio files are deleted before rows. Orphan audio files
(`AudioStore.listAll()` without a row) are deleted at app start.

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
| Where you dictate | Σ words_final per pkg, top 5 + others (labels/icons via `AppInfoProvider`) |

`words_*`, `edit_distance_words`, `fillers_removed` (lexicon = `OutputGuard.fillers`, the `prompts/guard.py FILLERS`
port), `fixes` and `mixed_language` are computed once at delivery by WP-11 through `DeliveryMetrics` (`core/store`,
implemented by WP-12).

---

## 13. Settings model

`core/store/.../Settings.kt` is the whole model (defaults = product defaults): dictation (languages ru+en, auto-detect,
hold-to-talk on, feedback vibration, typing wpm 40), bubble (when typing, tuck-in on, size standard 52 dp, hidden-in
packages, per-display positions, hidden-until), cleanup (prompt override {systemText, baseProfile}, preferences ≤
1,000 chars, tone per app, use screen on), history (retention 7 days, keep recordings on), models (ASR model, cleanup
choice Default/None/Model, unit preference **per model id**, Wi-Fi only on, idle unload 60 s), onboarding done, debug
only: fake engines, timeout scale, ignore memory gate. "Edited" = `promptOverride != null`; Reset deletes the override
(tone per app kept, DESIGN 6.5); an override whose `baseProfile` is not the active profile is shown as "made for an
older prompt" and not applied. A prompt, preferences, unit or model change triggers `EnginePreparer.invalidatePrefix()`.
Device profile, unit health and benchmark results are not settings (files / Room). No live-preview setting (ADR-047).

---

## 14. Threading and coroutines policy

| Where | Thread / dispatcher | Rule |
|---|---|---|
| `:main` UI + overlay | main | Compose only; no binder calls to inference processes, no disk |
| accessibility events | main (system) → post to `a11y-io` HandlerThread | node reads, tree walks, screenshot calls on `a11y-io` (synchronous binder to the target app) |
| orchestrator | `Dispatchers.Default.limitedParallelism(1)` | the only place that mutates dictation state; never blocks; every port call is `suspend` and main-safe (incl. `AsrEngine.startSession`) |
| audio capture | dedicated thread, `THREAD_PRIORITY_URGENT_AUDIO` | writes pipe, encoder, buffer, meter; never suspends |
| DB / files / DataStore | `Dispatchers.IO` | inside repositories only |
| binder callbacks (`:main`) | binder threads | convert to `DictationEvent` and `trySend` into the orchestrator channel; nothing else |
| `:asr` | one ASR engine thread, one OCR thread, one pipe reader per session | binder threads only enqueue; `finish` waits for the reader to drain |
| `:llm` | one engine executor thread (owns `llama_context`) | `cancel()` from any thread flips an atomic; `unload` is submitted to the executor (never races cancel) |

Coroutine scopes: `AppContainer.appScope` (SupervisorJob) in `:main`; ViewModels use `viewModelScope`; services
use a scope cancelled in `onDestroy`. Cold flows for one-shot streams (`LlmEngine.generate`), `StateFlow` for state.
Tests use `kotlinx-coroutines-test` (`runTest`, virtual time for every timer in section 5.3; `VirtualClock { testScheduler.currentTime }`).

---

## 15. Error taxonomy

`MurmurError` (`core/model/.../Errors.kt`) is the only error type across module and process boundaries. `code` is
stable and stored (`history.failure_code`, `cleanup_outcome`), `retryable` drives the Retry action, `detail` never
contains user text, screen text or paths. Families: `perm.*`, `model.*` (asr_missing, vad_missing), `ctx.*`, `sys.*`,
`audio.*`, `engine.*` (load, runtime missing, unit unavailable, process died, OOM), `timeout.<stage>` (incl.
llm_prefix, llm_ready, bench), `asr.*`, `llm.*` (incl. `llm.too_long`, `llm.guard.<rule>`), `ocr.*`, `insert.*`,
`dl.*`, `catalog.*`, `data.*`, `internal`. Cleanup outcomes: `accept`, `fallback:<guard rule>`, `fallback:truncated`,
`skipped:{none, no_model, password, low_memory, llm_load, llm_died, llm_not_ready, timeout, too_long}`. Mapping to bubble
and history: section 5.5; to hub rows: `InstallState.Paused/Failed(error)`.

---

## 16. Testing strategy

| Level | Where | What | Command |
|---|---|---|---|
| JVM unit | `core/*/src/test`, `testing/src/test` | reducer table (every row of 5.2 with virtual time), selection + fallback, catalog validation (nCtx rule, memoryMb), cleanup budget, retention, stats, segmenter on synthetic probabilities, join policy, WER, validity gate | `tools/gw testDebugUnitTest` (JVM modules alias `test`) |
| Parity / golden | `core/prompt` (prompts/examples + generated corpus over the 4 templates × profiles, with preferences, style override and system override cases), guard (`prompts/tests/test_guard.py` cases), `core/ocr` (`ml/ocr/golden`), `core/context` (schema round-trip), `core/ocr` config drift | byte-exact or README tolerances | same |
| Tokenizer | WP-10 host test with `llama-tokenize` on every shipped GGUF vocabulary | prefix token counts = catalog `prefixTokens`; tokens(prefix) + tokens(suffix) == tokens(full) for every golden | `native/llama` script, recorded in the WP-10 report |
| Android unit | `*/src/test` of Android modules | pure logic inside Android modules (policies, mappers, `RestrictedSettingsProbe`) | same |
| Instrumented, fakes | `*/src/androidTest`, API 36 `google_apis` x86_64 AVDs `murmur_fold7_cover` (compact) and `murmur_fold7_inner` (medium) | Compose UI against `:testing` fakes; screenshots compared with `docs/design/screens/*.png` by eye + automated layout checks (no overflow, targets ≥ 48 dp) | `tools/gw :<module>:assembleDebugAndroidTest` then `adb install` + `am instrument` (TOOLCHAIN 4.4) |
| Room | `data/db/src/androidTest` | DAOs, migrations (schema JSON), retention on an in-memory DB | same |
| IPC | `infer/api/src/androidTest` with a test-only service in a separate process | timeouts, death → re-bind, pipe replay, SharedMemory | same |
| Native on emulator | `infer/asr`, `infer/llm` androidTest | sherpa x86_64 with GigaAM / Parakeet CPU (asr-ocr: correct text on emulator, RTF 0.42 / 0.65); llama x86_64 with Qwen3.5 0.8B (llm spike: works, ivybridge variant); LLM tests run the emulator with `-qemu -cpu host` (ADR-038) | same; models via `tools/models/fetch` |
| End-to-end | `app/src/androidTest/kotlin/app/murmur/e2e` + `:testapps:target` | press bubble over the target app (UiAutomator) → debug WAV audio source (the emulator mic cannot be fed) → real `:asr` (GigaAM RNN-T CPU) + VAD + OCR + real `:llm` (Qwen3.5 0.8B CPU) → insertion into the target EditText (read back from the target's `TGT` log) → history row; the same flow on fakes for speed; **emulator-simulated platform events during Listening**: fold/unfold (`wm size` + `adb emu sensor set hinge-angle0`), rotation, incoming call (`adb emu gsm call`), `kill -9` of `:asr` and `:llm`, service disable, notification-shade start | WP-20 script `tools/e2e/run_emulator_e2e.sh` |
| Device | `tools/device/*.sh` + `docs/DEVICE_CHECKLIST.md` | NPU paths, real latency/memory, One UI behaviour, real OCR screenshots, real fold hardware | owner, one pass |

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
| Product 5: Samsung NPU for all models (ASR, LLM, OCR) | catalog units + selector; sherpa QNN; ggml-hexagon; ORT QNN EP for OCR and GigaAM | WP-06, WP-10, WP-21, WP-14; DC2-DC4. **Deviation needs owner sign-off:** v1 ships NPU for Parakeet, Nemotron, Whisper and the LLMs; OCR/GigaAM NPU are prepared (WP-21) and adopted after DC4; VAD and T-one stay CPU (no NPU path, needsFromOwner) |
| Screens: 4 bottom tabs | app shell | WP-15 |
| Screen 1: Dashboard with useful usage stats | feature:dashboard, core:store metrics | WP-17, WP-12 |
| Screen 2: History, main tab, auto-deleted after 1 week | feature:history, data:db retention | WP-16, WP-12 |
| Screen 3: Model hub, user configures any model | feature:models, data:models (custom models with template/profile detection) | WP-18, WP-13 |
| Screen 4: Settings: retention, UI features, post-processing prompts; good default, editable | feature:settings, core:prompt | WP-19, WP-04 |
| Hub ASR: Whisper (sizes) | catalog: whisper-small (NPU+CPU), whisper-medium (NPU), large-v3-turbo (CPU), distil-large-v3.5 | WP-13, WP-06 |
| Hub ASR: Parakeet | parakeet-tdt-0.6b-v3 (NPU+CPU), parakeet-ultra (CPU) | WP-06 |
| Hub ASR: GigaAM | gigaam-v3 RNN-T and CTC (CPU; NPU port WP-21) | WP-06, WP-21 |
| Hub ASR: T-one | t-one (CPU, streaming; no NPU path: strict compile fails, asr-ocr 5.4) | WP-06 |
| Hub ASR: Qwen3-ASR | qwen3-asr-0.6b (CPU; LiteRT NPU artifact listed, runtime not shipped), 1.7B (GPU, not shipped) | WP-06, WP-13 |
| Hub ASR: parameter count + languages | `params`, `paramsLabel`, `languages`, "No Russian" badge | WP-18 |
| Hub LLM: LFM 2.5 series | lfm2.5-1.2b-instruct, lfm2.5-350m, lfm2.5-2.6b | WP-10, WP-13 |
| Hub LLM: LFM 3 series | lfm3-1.7b | WP-10 |
| Hub LLM: small Qwen 3.5-3.6 | qwen3.5-0.8b, qwen3.5-2b (+ qwen3 0.6b/1.7b hedge) | WP-10 |
| Hub LLM: fine-tuned LFM3 + Qwen3.5 (LoRA) | murmur-cleanup-lfm3-1.7b, -qwen3.5-0.8b, -qwen3.5-2b (status in_training, profile finetuned.v1; artifacts by a catalog update or the custom path) | ML team; WP-13 |
| Hub LLM: Q4 inference | Q4_0 GGUF default artifacts (ADR-009) | WP-10 |
| Hub LLM: None | `CleanupChoice.None` (first row) | WP-18, WP-11 |
| Rule: models not baked into the APK, downloaded from HF on demand | data:models; nothing under assets | WP-13 |
| Rule: NPU inference for all models | see Product 5 | WP-21, owner |
| Rule: Benchmark button per model, short stats | core:benchmark (< 60 s per unit, validity gate), feature:models | WP-14, WP-18 |
| Rule: models must not hold RAM 24/7 | process exit after idle (4.2) | WP-05, WP-06, WP-10 |
| Flow 1: tap bubble → dictation starts | 5.2 rows 1-9 | WP-11, WP-03 |
| Flow 2: 0.5-1 s after start screenshot → small multilingual OCR | `CaptureContext(600 ms)`, OCR in :asr | WP-02, WP-07 |
| Flow 3: context from Accessibility: current app | `A11ySnapshot` | WP-02, WP-08 |
| Flow 4: start loading the audio model | `PrepareEngines` | WP-11, WP-05, WP-06 |
| Flow 5: start loading the LLM | `PrepareEngines` (parallel, memory gate, fallback) | WP-11, WP-10 |
| Flow 6: transcribe during speech if streaming / splittable, else at stop (owner wants to discuss) | segmenter + streaming adapters (D4, ADR-025); **open owner question** | WP-06 |
| Flow 7: system prompt + context + text → LLM; insert via Accessibility and copy to clipboard | renderer, guard, budget, delivery, insertion ladder | WP-04, WP-11, WP-02 |
| Flow 8: save output + audio to history | `Deliver` → history + Opus audio | WP-11, WP-12, WP-09 |
| Constraint: ru + en first-class incl. code-switching | Parakeet + VAD segments (4.5 % mixed WER host), Cyrillic OCR + homoglyph repair, ru strings | WP-06, WP-07, all UI |
| Constraint: no Fold7 attached; flag device-only items | `docs/DEVICE_CHECKLIST.md`, section 18 | all |
| Constraint: shared build server, don't disturb | tools/gw, tools/heavy, worktrees, read-only emulator instances | all |
| Constraint: Hermes holds HF token / RunPod | fine-tune pipeline (ML); HF mirror of GitHub assets (ADR-016) via the orchestrator | ML, owner |
| Constraint: suggested datasets | ML plan (docs/ml) | ML |
| Repo layout (research/ml/design/dev/prompts/Gradle) | unchanged; Gradle now multi-module | this doc |

---

## 18. What cannot be verified without the phone

All of these are steps in `docs/DEVICE_CHECKLIST.md` with the script, the expected output and the decision each feeds:

1. HTP0 registration for ggml-hexagon from an app process (unsigned PD on One UI 9), op acceptance for lfm3/qwen35
   (FA head dim 256), NPU vs GPU vs CPU pp/tg, NPU cold load time and rpcmem footprint (DC2, DC8).
2. QNN 2.52 loading sherpa's QAIRT-2.40 SM8750 binaries on the real HTP, Parakeet/Nemotron NPU latency and accuracy
   vs CPU int8, QNN + ggml-hexagon sessions coexisting on the cDSP, `ADSP_LIBRARY_PATH` with `useLegacyPackaging`
   (DC3, DC8).
3. OCR CPU vs QNN times on arm64; XNNPACK behaviour on arm64 (DC4).
4. Real microphone samples (the emulator cannot be fed), Samsung FGS/while-in-use handling, Bluetooth headsets (DC5).
5. One UI: restricted-settings dialog for adb/file installs, Auto Blocker, battery "Unrestricted" path, Samsung
   Keyboard composing behaviour vs `commitText`, real Telegram/Samsung apps insertion (DC6, DC10).
6. Real fold hardware: the BLAST stall, same vs new display ids, taskbar (the emulator covers the size and posture
   changes, section 16).
7. Real-screenshot OCR quality on both displays (dark themes, icons, app fonts) vs the synthetic pages (DC7).
8. End-to-end latency for 10 s / 60 s dictations and the cold cases of 6.4, memory peaks (`MemAvailable`, PSS, rpcmem),
   thermal, Android 17 MemoryLimiter behaviour, idle exit releasing DSP memory (DC8).
9. Android 17 screenshot behaviour (the API 37 ATD emulator crashes on pixel readback) (DC5).
10. Legal: Hexagon SDK CE skel redistribution; QNN runtime licence (not technical, but blocks shipping NPU libs).

---

## 19. Work packages and waves

| Wave | WP | Title | Owns |
|---|---|---|---|
| 1 | WP-01 | Design system | `ui/design/**` |
| 1 | WP-02 | Accessibility service, screen capture, insertion | `platform/a11y/**` |
| 1 | WP-03 | Bubble overlay + feedback | `platform/overlay/**` |
| 1 | WP-04 | Prompt renderer + output guard ports | `core/prompt/**` |
| 1 | WP-05 | Inference IPC | `infer/api/**` |
| 1 | WP-06 | ASR engine (sherpa, VAD, segmenter) | `infer/asr/**` except `…/asr/ocr/**` and `src/androidTest/assets/ocr/**`, `native/sherpa/**`, `core/audio/**/Segmenter*.kt`, `tools/device/asr_npu_smoke.sh` |
| 1 | WP-07 | OCR engine + parity | `core/ocr/**`, `infer/asr/src/*/kotlin/app/murmur/infer/asr/ocr/**`, `infer/asr/src/androidTest/assets/ocr/**` |
| 1 | WP-08 | Context assembly + privacy | `core/context/**` |
| 1 | WP-09 | Audio capture, FGS, history audio, status notification | `platform/audio/**`, `core/audio/**` except Segmenter |
| 1 | WP-10 | LLM engine (llama.cpp, prefix cache, tokenizer tests) | `infer/llm/**`, `native/llama/**`, `tools/device/llm_npu_smoke.sh` |
| 1 | WP-11 | Dictation orchestrator, delivery, preparation, shared fakes | `core/dictation/**`, `testing/**` except `testing/src/main/kotlin/app/murmur/testing/contrib/**` |
| 1 | WP-12 | History, metrics, settings, retention data layer | `data/db/**`, `core/store/**` |
| 1 | WP-13 | Catalog, downloads, storage, device profile, unit health, custom models | `data/models/**`, `core/catalog/**` |
| 1 | WP-14 | Benchmark runner | `core/benchmark/**` |
| 1 (early) → 3 | WP-20 | Integration, end-to-end tests, device tooling | waves 1-2: `testapps/**`, `tools/e2e/**`, `tools/device/murmur_app_smoke.sh`, `app/src/androidTest/kotlin/app/murmur/e2e/**`, `docs/DEVICE_CHECKLIST.md`; wave 3 adds `app/**` except WP-15's shell files |
| 2 | WP-15 | App shell, navigation, onboarding, wiring for screens | `app/build.gradle.kts`, `app/src/main/kotlin/app/murmur/**`, `app/src/main/res/**`, `app/src/main/AndroidManifest.xml`, `app/src/{debug,release,test}/**`, `app/src/androidTest/kotlin/app/murmur/shell/**`, `feature/onboarding/**` |
| 2 | WP-16 | History UI | `feature/history/**` |
| 2 | WP-17 | Dashboard UI | `feature/dashboard/**` |
| 2 | WP-18 | Model hub UI + benchmark sheet | `feature/models/**` |
| 2 | WP-19 | Settings UI (prompt editor, tone, retention) | `feature/settings/**` |
| 3 | WP-21 | NPU ports: ORT QNN plugin EP for OCR and GigaAM | `infer/asr/src/*/kotlin/app/murmur/infer/asr/qnnep/**`, `infer/asr/src/*/kotlin/app/murmur/infer/asr/ocr/**` (handed over by WP-07), `core/catalog/tools/build_catalog.py` + `catalog.json` (handed over by WP-13), `tools/device/ort_qnn_smoke.sh`, `native/qnn-contexts/**` |

`testing/src/main/kotlin/app/murmur/testing/contrib/` holds fakes added by other packages, one file per package
named `<WpId>*.kt` (disjoint by name). Wave 1 packages depend only on contracts and `:testing` fakes, so all 14 (plus
WP-20's early paths) start now. Wave 2 needs WP-01's components (view models and state tests may start in wave 1).
In wave 3 WP-20 takes over `AppContainer.kt`, `MurmurApp.kt`, the manifest and `app/build.gradle.kts` from WP-15 and
wires the real modules; WP-21 needs WP-06/07/13 landed. Shared files (root `settings.gradle.kts`, `build.gradle.kts`,
`gradle.properties`, `gradle/libs.versions.toml`, `build-logic/**`, `tools/models/**`) stay with the architect:
request changes (new dependency = ADR line).
