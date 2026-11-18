# WP-11 Dictation state machine, orchestrator, delivery, preparation, shared fakes (`:core:dictation`, `:testing`)

Wave 1 (works entirely against `:testing` fakes). Read first: `docs/wp/README.md`; ARCHITECTURE.md §4.2, §4.4, §5
(all — the transition table is the spec, 54 rows), §6.4-6.5 (cold paths, cleanup budget), §7 (memory gate), §8.5
(Preparing, what downloads queue), §11 (delivery), §12 (metrics computed at delivery), §14 (threading); DESIGN.md §5.3,
§5.5; `prompts/README.md` "App flow around the LLM"; DECISIONS ADR-015, ADR-024, ADR-037, ADR-045, ADR-049, ADR-050,
ADR-053, ADR-057, ADR-061.

## Goal

One dictation from press to history, every timeout, fallback and failure path handled, as a pure reducer plus an
effect executor that is fully testable on the host JVM with virtual time; plus the background preparation of
installed models and prompt snapshots; plus the shared fakes every other package tests with.

## Owns

`core/dictation/**`; `testing/**` except `testing/src/main/kotlin/app/murmur/testing/contrib/**` (other packages add
their own fakes there as `<WpId>*.kt`; requests to change an existing fake come to you and are answered within the
wave).

## Contracts

Implement `DictationMachine.reduce` (pure, total) and `DictationOrchestrator` (`state`, `bubble`, `levels`,
`onInput`, `onAccessibilityLost`, `startInApp`, `retry`, `repolish`, `tryCleanup`, and `EnginePreparer`:
`prepareArtifact`, `invalidatePrefix`) over `DictationDeps` (all ports already defined in `Ports.kt`,
`Orchestrator.kt`): `AudioCapture` (+ `CaptureHandle.signals`), `TargetReader` (+ `snapshotAfterShade`), `Inserter`,
`ModelResolver` (`ActiveModels` with `asrFallback`, `ActiveLlm.fallback`, `nCtx`, `prefixTokens`, `modelSha256`,
`speed`, `llmUnavailable`), `Engines`, `ContextAssembler`, `PromptRenderer`, `OutputGuard` (+ `fillers`),
`HistoryRepository`, `MetricsRepository`, `DeliveryMetrics`, `AudioStore`, `Feedback` (haptic + sound),
`MemoryGate`, `UnitHealthRepository`, `AppNavigator`, `PermissionMonitor`, `SettingsRepository`, `MurmurClock`.
Implement `JoinPolicy`. `BubbleMapping`, `DictationConfig` and `CleanupBudget` exist (tested); keep them consistent
with your reducer. State, event and effect types (`State.kt`) are the contract: add, do not break.

## Deliver

1. Reducer: every row of ARCHITECTURE 5.2 (incl. the readiness rows and the start rule 31), stale-session drop (I2).
2. Orchestrator: serial event loop (`limitedParallelism(1)` on the injected dispatcher), timers on the injected
   clock/scheduler (virtual time in tests; every timer × `DictationConfig.timeoutScale`, fed from
   `Settings.debugTimeoutScale` in debug builds), effect execution:
   - `Preflight(S, mode)`: checks of row 6 in order, `snapshot()` (NOTIFICATION: `snapshotAfterShade(1 s)`; IN_APP: the
     `InAppTarget`), `ContextAssembler.decide`, `ModelResolver.resolve`, → `SessionPlan` (cleanup planned unless None /
     no model / password `skipLlm` / memory refused) → `PreflightPassed` / `PreflightFailed`.
   - `PrepareEngines`: connect + load ASR (+VAD) then OCR on `:asr`; in parallel, if the plan has cleanup and
     `MemoryGate.availableMb() − llm.memoryMb ≥ config.llmMemoryMarginMb` (or `Settings.debugIgnoreMemoryGate`),
     connect + load LLM (`n_ctx` = `ActiveLlm.nCtx`) + `setPrefix(renderer.cachePrefix(...), PrefixCacheKey.of(model
     sha, prefixKey, unit, native.build from the probe, nCtx))`; else load the LLM after the ASR tail and
     `shutdown(:asr)`, or `LlmLoadFailed(OutOfMemory(LLM))`. **Fallback (ADR-050):** a load error (or a process death
     during load) on the primary spec → `UnitHealth.markFailed(artifact, unit, code)` → load the fallback spec (ASR:
     new session, replay the session buffer from sample 0; LLM: same GGUF on the next unit) → `AsrReady/LlmReady(…,
     fellBack = true)`; no fallback → `AsrLoadFailed(UnitUnavailable(NPU) or e)` / `LlmLoadFailed(e)`.
   - Audio: subscribe `CaptureHandle.chunks` → ASR session; keep the session buffer for replay; map
     `CaptureHandle.signals` to `ExternalStop(S, CALL / MIC_LOST / MIC_SILENCED)`; `onAccessibilityLost()` →
     `ExternalStop(S, A11Y_OFF)` for the running session. Audio file name = `AudioKey.of(createdAt, session)`.
   - `CaptureContext(600 ms)`: decide → if allowed `screenshot` → `ocr.recognize` within `ocrBudgetMs` → `assemble` →
     keep the ctx in executor memory (never in the state, I4) → `ContextReady(S, hasScreen, contextWords)`.
   - `RunCleanup(S, t, budget)`: `renderer.render` (override only if `PromptEditRules.overrideApplies`) →
     `llm.generate(LlmRequest(suffix, budget.maxTokens, budget.minTokens))` (collect pieces) → `LlmContextOverflow`
     → `CleanupDone(raw, cleaned = false, "skipped:too_long")`; `stopReason == STOP_MAX_TOKENS` →
     `"fallback:truncated"` (raw); else `guard.guard` → `CleanupDone(text, action == ACCEPT, outcome)`.
   - `Deliver`: §11 steps (current focus, password discard, service gone → `NotDelivered`, clipboard first, target
     change → Copied, JoinPolicy, ladder; IN_APP → `InAppTarget.insert`) → history row + metrics row through
     `DeliveryMetrics` (words, edit distance, fillers with `guard.fillers`, fixes, mixed language) → audio ref;
     `retentionDays == 0` → no row and no audio (`RetentionPolicy.keepsHistory`); guard EMPTY → `NothingToInsert`.
   - `RestartAsr`, `ReloadLlm`, `ScheduleIdleUnload` (both hosts, `Settings.idleUnloadSec`), `Open(target)` →
     `AppNavigator.open`, `RetryEntry(entry)` → `retry(entry)`.
3. `EnginePreparer` (ADR-050): `prepareArtifact(artifact)` — never during a dictation (a press cancels it; it resumes
   afterwards) — loads the artifact on every usable unit (`ArtifactSelector.candidates`), ASR: decodes the bundled
   1 s clip (`core/dictation/src/main/resources/prepare/clip_1s.pcm`, add it; FLEURS CC-BY-4.0 or synthetic speech),
   LLM: builds and saves the prefix snapshot; marks `UnitHealth` proven/failed; reports `needsCpuArtifact` when an NPU
   ASR artifact failed and the model's CPU artifact is not installed. `invalidatePrefix()`: a settings observer you
   own calls it on any change of `promptOverride`, `preferences`, `unitPreference` or the active LLM; debounced 5 s,
   rebuilds the active LLM's snapshot in the background.
4. `retry(entry)`: decode stored audio (`AudioStore.readPcm`) → new ASR session fed at once → normal flow from
   Finishing; `repolish(entry)`: re-run cleanup on `rawText` with the current settings, update `finalText`,
   `revision + 1` (no context: screen context is never stored); `tryCleanup(sample, override, preferences)` (Settings
   "Try it"): render without context, generate, guard, return text, store nothing; `startInApp(target)`: row 4.
5. Hold-to-talk, notification start, 4:30 warn, 5:00 auto-stop, cancel, double-tap protection (row 10).
6. Fakes (`:testing`, you own them; already upgraded by the architect, keep them working): `FakeInferenceHost.crash()`
   fails in-flight work of its engines with `ProcessDied`; `FakeAsrEngine` (per-unit load errors, delays, Committed
   events); `FakeLlmEngine` (per-piece delay, `hang`, per-unit load errors, `stopReason`, `contextLimit`);
   `FakeModelResolver(npuFirst, cpuFallback, noVad, …)`; `FakeOcrService(delayMs, error)`;
   `FakeAudioCapture.signal(…)`; `FakeTargetReader` (shade waits); `FakeBenchmarkRunner`; `FakeCustomModelResolver`;
   `FakeDeviceProfileRepository`; `FakeUnitHealth`; `RecordingNavigator`; `FakePermissionMonitor`;
   `RecordingStatusNotifier`; `SimpleDeliveryMetrics`; `VirtualClock { testScheduler.currentTime }`; `FakeWorld`.

## Acceptance (checkable here)

- `tools/gw :core:dictation:test :testing:test` green with:
  - a table-driven reducer test with **one test per row of ARCHITECTURE 5.2** (name the row number in the test name);
  - orchestrator tests on `FakeWorld` with `runTest` + `VirtualClock`: happy path (Inserted, history + metrics rows,
    clipboard written before insert, idle unload scheduled with 60 s, audio key = createdAt-session); cleanup None (no
    LLM call, bubble never Polishing, `skipped:none`); NoSpeech (Failed with audio, Retry = new dictation); mic busy;
    no ASR model and no VAD (Open Models); **ASR NPU load failure → CPU fallback, buffer replayed, Inserted, UnitHealth
    marked**; NPU failure with no fallback → `UnitUnavailable` + OPEN_MODELS at stop; **LLM NPU load failure → CPU
    generate; a second dictation goes straight to CPU (selector skips the failed unit)**; `:asr` crash while Listening
    → re-bind + replay → Inserted; second crash → Failed; first `:asr` crash in Finishing → replay + finish → Inserted;
    LLM timeout (`hang`) → Inserted no cleanup `skipped:timeout`; LLM process died in Polishing; LLM still loading at
    transcript time beyond `LLM_READY` → `skipped:llm_not_ready`; the LLM timer starts only after LlmReady (assert with
    a 5 s load delay and a 2 s generation: no timeout); low memory → no parallel LLM load, `skipped:low_memory`; 5-min
    transcript on a slow speed → `skipped:too_long` without an LLM call; `LlmContextOverflow` from the fake →
    `skipped:too_long`; truncated output (`STOP_MAX_TOKENS`) → raw inserted, `fallback:truncated`; OCR late (beyond
    `CONTEXT_WAIT`) → cleanup without screen, OCR in time → with screen; secure window; password at delivery →
    nothing stored or copied; target changed → Copied; insertion rejected → Copied; service lost in Listening →
    Failed(AccessibilityOff) with audio; service lost in Polishing → NotDelivered with text stored; call signal while
    Listening → Finishing with the audio so far; 5:00 auto stop; cancel during Listening → nothing stored; tap during
    Arming (≥ 300 ms) → stops right after MicStarted; NOTIFICATION start waits for the shade; IN_APP practice inserts
    into the fake target and stores nothing; stale events from a previous session ignored; `retentionDays = 0` → no
    row, no audio; Copied pill tap → `Open(Entry)`; error action OPEN_MODELS → `Open(Models)`;
  - `EnginePreparer`: install of an NPU artifact that fails → `needsCpuArtifact`; a prompt edit → snapshot rebuilt
    once after 5 s of quiet; a press during preparation cancels it and the dictation proceeds;
  - `JoinPolicy` table (start of field, after space, after letter, after `(`, after newline, Cyrillic/Latin).
- Bubble sequence assertions: for the happy path the `bubble` flow emits Listening → (Listening frozen) →
  Transcribing (only when the tail takes > 300 ms) → Polishing → Inserted → Idle, exactly, with no Transcribing flash
  during Delivering (DESIGN 5.3).
- No Android imports.

## Out of scope

Port implementations (WP-02, 05, 06, 07, 08, 09, 10, 12, 13), UI, the hub's "Download the CPU version" action
(WP-18 via WP-13).

## Build/test

`tools/gw :core:dictation:test :testing:test`.
