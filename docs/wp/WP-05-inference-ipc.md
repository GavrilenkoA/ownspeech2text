# WP-05 Inference IPC (`:infer:api`)

Wave 1. Read first: `docs/wp/README.md`; ARCHITECTURE.md §4 (all), §14; DECISIONS ADR-001, ADR-024, ADR-049;
`docs/research/verify/platform.md` §3 item f and §6 (`:infer` lifecycle evidence); spike
`spikes/platform/app/src/main/kotlin/app/murmur/spike/InferService.kt` (`InferService` + `InferClient`).

## Goal

`:main` talks to `:asr` and `:llm` through the `:core:engine` contracts as if they were local, with binding on
demand, idle exit, work-scaled timeouts, death detection and one retry — so that WP-11 never sees binder details.

## Owns

`infer/api/**` (AIDL files, `InferApi`, `ServiceConnector`, `AsrClient`, `LlmClient`, `BinderEngines`,
`InferProcessLifecycle`). The services themselves are WP-06/07 (`:asr`) and WP-10 (`:llm`).

## Contracts

Implement `AsrEngine`, `OcrService`, `InferenceHost` (in `AsrClient`) and `LlmEngine`, `InferenceHost` (in
`LlmClient`) over the AIDL in `infer/api/src/main/aidl/app/murmur/infer/api/`. Payloads: JSON of `:core:engine`
types via `InferApi.json` (sealed classes use discriminator `type`); `LlmRequest` carries `maxTokens` and `minTokens`.
Clients take `bindContext: () -> Context` (read at every bind: the accessibility service when connected, else the
application; wired in `:app`). `AsrEngine.startSession` is `suspend`. `BinderTimeouts` (exists): fixed
bind/load/OCR/call timeouts, `prefixMs(tokens, prefillTps)`, `benchMs(nPrompt, nGen, reps)`, `scale`. You may adjust
the AIDL until WP-06/WP-10 implement it; after that, any change bumps `InferApi.API_VERSION` and is announced.

## Deliver

1. `ServiceConnector`: `bindService(intent, BIND_AUTO_CREATE or BIND_IMPORTANT)` from `bindContext()`, await the
   binder with `BinderTimeouts.bindMs`, check `apiVersion()`, `HostState` transitions (Down → Starting → Up(pid) →
   Crashed(n)), `onServiceDisconnected` / `onBindingDied` / `DeadObjectException` → Crashed and a death signal that
   WP-11's executor observes; `disconnect()` unbinds.
2. Idle exit: `scheduleIdleExit(ms)` (re-armed by every use) → `unloadAll()`/`unload()` → unbind (service kills
   itself in `onUnbind`). `shutdown()` = unload now + unbind + wait until the pid is gone (≤ 2 s), used by the
   benchmark for cold loads.
3. ASR session over a pipe: `ParcelFileDescriptor.createPipe()`; the write end stays in `:main`; `accept(chunk)`
   copies PCM16 LE into a lock-free ring drained by a writer thread (never blocks the capture thread); EOF on
   `finish()`; events from `IAsrSessionCallback` → `Flow<AsrEvent>`; session buffer for replay is WP-11's
   (you expose a way to start a new session and feed it a whole `ShortArray`).
4. OCR: `ScreenImage` → `SharedMemory.create("murmur-frame", w*h*4)`, write pixels, `setProtect(PROT_READ)`, call
   `recognizeScreen`, close after the callback or timeout.
5. LLM: `generate` = `callbackFlow` over `ILlmStreamCallback` (pieces, done, error), cancellation of the collector
   → `cancel()`; `setPrefix` with `prefixMs(estimated tokens = text.length × 0.4, benchmark prefill if known)`;
   `bench` with `benchMs`.
6. Every call wrapped in `withTimeout(…)` (× `scale`); a timeout → `shutdown()` of that process and
   `Err(Timeout(stage))` (`LLM_PREFIX`, `BENCH`, …) / `Err(ProcessDied)`; errors decoded with `InferApi.decodeError`.
7. `InferProcessLifecycle` (shared by the services): quiet crash handler, exit; add a JNI-free `mallopt` hook
   interface the native WPs call before exit.

## Acceptance (checkable here)

- JVM/Android unit tests: JSON round trip of every payload type (`InferApiJsonTest` exists; add `LlmRequest.minTokens`,
  `ProcessProbe.facts`), ring buffer (no blocking, no loss under a 50 Hz writer, overflow policy), timeout mapping,
  `prefixMs` / `benchMs` / `scale` arithmetic (3,240 tokens at the 40 tok/s floor → 162 s; 3,240 was LFM3's first measurement, catalog 2026-11-20.1 says 3,226 → 161 s).
- Instrumented tests in `infer/api/src/androidTest` with **test-only services** declared in the androidTest
  manifest with `android:process=":fakeasr"` / `":fakellm"` (implementing the AIDL with canned answers):
  - bind on demand; `pidof app.murmur:fakeasr` exists after `connect()`, is gone ≤ 2 s after `shutdown()`;
  - the bind context is re-read per bind (a test lambda switching contexts is called again after `shutdown()`);
  - 10 s of PCM written through the pipe arrives byte-identical (checksum reported by the fake);
  - a 17 MB frame crosses through SharedMemory in < 50 ms on the emulator (report the time);
  - the fake service calls `Process.killProcess` mid-session → client sees `HostState.Crashed`, `finish()` returns
    `Err(ProcessDied)` within 1 s, a new `connect()` works;
  - a fake that never answers `loadAsr` → `Err(Timeout(ASR_LOAD))` after the configured timeout and the process is killed;
  - a fake `setPrefix` that takes 40 s succeeds with a 3,000-token prefix (timeout 150 s), and fails with
    `Timeout(LLM_PREFIX)` when `scale = 0.1`;
  - `generate` streams ≥ 50 pieces in order and `cancel()` stops it.

## Out of scope

The real runtimes (WP-06/07/10), what to load when and the fallback order (WP-11 with WP-13's specs).

## Build/test

`tools/gw :infer:api:testDebugUnitTest :infer:api:assembleDebugAndroidTest`; run the test APK on an emulator.
