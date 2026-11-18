# WP-11 Dictation state machine, orchestrator, delivery (`:core:dictation`)

Wave 1 (works entirely against `:testing` fakes). Read first: `docs/wp/README.md`; ARCHITECTURE.md §5 (all — the
transition table is the spec), §4.4 (crash handling), §6, §7 (memory gate), §11 (delivery), §12 (metrics computed at
delivery), §14 (threading); DESIGN.md §5.3, §5.5; `prompts/README.md` "App flow around the LLM"; DECISIONS ADR-015,
ADR-024, ADR-037.

## Goal

One dictation from press to history, every timeout and failure path handled, as a pure reducer plus an effect
executor that is fully testable on the host JVM with virtual time.

## Owns

`core/dictation/**` (JVM).

## Contracts

Implement `DictationMachine.reduce` (pure, total) and `DictationOrchestrator` (`state`, `bubble`, `levels`,
`onInput`, `retry`, `repolish`) over `DictationDeps` (all ports already defined in `Ports.kt`, `Orchestrator.kt`).
Implement `JoinPolicy`. Use only the ports: `AudioCapture`, `TargetReader`, `Inserter`, `ModelResolver`, `Engines`
(asr, llm, ocr, hosts), `ContextAssembler`, `PromptRenderer`, `OutputGuard`, `HistoryRepository`,
`MetricsRepository`, `AudioStore`, `Feedback`, `MemoryGate`, `SettingsRepository`, `MurmurClock`.
`BubbleMapping` and `DictationConfig` exist (tested); keep them consistent with your reducer.

## Deliver

1. Reducer: every row of ARCHITECTURE 5.2, preflight in `Idle → Arming`, stale-session drop (I2).
2. Orchestrator: serial event loop (`limitedParallelism(1)` on the injected dispatcher), timers on the injected
   clock/scheduler (virtual time in tests), effect execution:
   - `PrepareEngines`: resolve models; connect + load ASR (+VAD) then OCR on `:asr`; in parallel, if cleanup on and
     `MemoryGate.availableMb() − llm.memoryMb ≥ config.llmMemoryMarginMb`, connect + load LLM + `setPrefix`
     (`renderer.cachePrefix`, cache key = sha256(model file sha256 + prefix + unit)); else defer or skip (§7).
   - Audio: subscribe `CaptureHandle.chunks` → ASR session; keep the session buffer for replay after `ProcessDied`.
   - `CaptureContext(600 ms)`: snapshot (at press) → `decide` → if allowed `screenshot` → `ocr.recognize` within
     `ocrBudgetMs` → `assemble` → `ContextReady` (ctx kept in memory only, dropped at Done — I4).
   - `RunCleanup`: `guard.precheck` → `renderer.render` → `llm.generate` (collect pieces, adaptive timeout) →
     `guard.guard` → `CleanupDone`; maxTokens = `min(1024, 2·transcript_tokens + 64)` with tokens ≈ chars/2.5.
   - `Deliver`: §11 steps (current focus, password discard, clipboard first, target change → Copied, JoinPolicy,
     ladder) → history row + metrics row (words, edit distance, fillers via `prompts/guard.py FILLERS` port —
     coordinate with WP-04 who owns the filler list, mixed-language flag) → audio ref; `retentionDays == 0` → no row.
   - `ScheduleIdleUnload` on both hosts with `Settings.idleUnloadSec`.
3. `retry(entry)`: decode stored audio (`AudioStore.readPcm`) → new ASR session fed at once → normal flow from
   Finishing; `repolish(entry)`: re-run cleanup on `rawText` with the current settings, update `finalText`,
   `revision + 1` (no context: screen context is never stored).
4. Hold-to-talk, notification start, 4:30 warn, 5:00 auto-stop, cancel, double-tap protection.

## Acceptance (checkable here)

- `tools/gw :core:dictation:test` green with:
  - a table-driven reducer test with **one test per row of ARCHITECTURE 5.2** (name the row in the test name);
  - orchestrator tests on `FakeWorld` (`:testing`) with `runTest` virtual time: happy path (Inserted, history +
    metrics rows, clipboard written before insert, idle unload scheduled with 60 s); cleanup None (no LLM call,
    bubble never Polishing); NoSpeech (Failed with audio, Retry action); mic busy; no ASR model (Open Models); ASR
    load failure on NPU → CPU fallback; `:asr` crash while Listening → re-bind + replay (fake host crash) → Inserted;
    second crash → Failed; LLM timeout → Inserted no cleanup with `skipped:timeout`; LLM process died; low memory →
    no LLM load, `skipped:low_memory`; OCR late → cleanup without screen; secure window; password at delivery →
    nothing stored or copied; target changed → Copied; insertion rejected → Copied; 5:00 auto stop; cancel during
    Listening → nothing stored; stale events from a previous session ignored; `retentionDays = 0` → no row;
  - `JoinPolicy` table (start of field, after space, after letter, after `(`, after newline, Cyrillic/Latin).
- Bubble sequence assertions: for the happy path the `bubble` flow emits Listening → Transcribing (only when the tail
  takes > 300 ms) → Polishing → Inserted → Idle, exactly (DESIGN 5.3).
- No Android imports.

## Out of scope

Port implementations (WP-02, 05, 06, 07, 08, 09, 10, 12, 13), UI.

## Build/test

`tools/gw :core:dictation:test :testing:test`.
