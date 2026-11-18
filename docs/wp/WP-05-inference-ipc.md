# WP-05 Inference IPC (`:infer:api`)

Wave 1. Read first: `docs/wp/README.md`; ARCHITECTURE.md §4 (all), §14; DECISIONS ADR-001, ADR-024;
`docs/research/verify/platform.md` §3 item f and §6 (`:infer` lifecycle evidence); spike
`spikes/platform/app/src/main/kotlin/app/murmur/spike/InferService.kt` (`InferService` + `InferClient`).

## Goal

`:main` talks to `:asr` and `:llm` through the `:core:engine` contracts as if they were local, with binding on
demand, idle exit, timeouts, death detection and one retry — so that WP-11 never sees binder details.

## Owns

`infer/api/**` (AIDL files, `InferApi`, `ServiceConnector`, `AsrClient`, `LlmClient`, `BinderEngines`,
`InferProcessLifecycle`). The services themselves are WP-06/07 (`:asr`) and WP-10 (`:llm`).

## Contracts

Implement `AsrEngine`, `OcrService`, `InferenceHost` (in `AsrClient`) and `LlmEngine`, `InferenceHost` (in
`LlmClient`) over the AIDL in `infer/api/src/main/aidl/app/murmur/infer/api/`. Payloads: JSON of `:core:engine`
types via `InferApi.json` (sealed classes use discriminator `type`). You may adjust the AIDL until WP-06/WP-10
implement it; after that, any change bumps `InferApi.API_VERSION` and is announced.

## Deliver

1. `ServiceConnector`: `bindService(intent, BIND_AUTO_CREATE or BIND_IMPORTANT)` from the context you are given
   (WP-20 passes the accessibility service when connected, else the application), await the binder with
   `BinderTimeouts.bindMs`, check `apiVersion()`, `HostState` transitions (Down → Starting → Up(pid) → Crashed(n)),
   `onServiceDisconnected` / `onBindingDied` / `DeadObjectException` → Crashed; `disconnect()` unbinds.
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
   → `cancel()`; `setPrefix` with its timeout.
6. Every call wrapped in `withTimeout(BinderTimeouts.*)`; a timeout → `shutdown()` of that process and
   `Err(Timeout(stage))` / `Err(ProcessDied)`; errors decoded with `InferApi.decodeError`.
7. `InferProcessLifecycle` (shared by the services): quiet crash handler, exit; add a JNI-free `mallopt` hook
   interface the native WPs call before exit.

## Acceptance (checkable here)

- JVM/Android unit tests: JSON round trip of every payload type (`InferApiJsonTest` exists), ring buffer (no
  blocking, no loss under a 50 Hz writer, overflow policy), timeout mapping.
- Instrumented tests in `infer/api/src/androidTest` with **test-only services** declared in the androidTest
  manifest with `android:process=":fakeasr"` / `":fakellm"` (implementing the AIDL with canned answers):
  - bind on demand; `pidof app.murmur:fakeasr` exists after `connect()`, is gone ≤ 2 s after `shutdown()`;
  - 10 s of PCM written through the pipe arrives byte-identical (checksum reported by the fake);
  - a 17 MB frame crosses through SharedMemory in < 50 ms on the emulator (report the time);
  - the fake service calls `Process.killProcess` mid-session → client sees `HostState.Crashed`, `finish()` returns
    `Err(ProcessDied)` within 1 s, a new `connect()` works;
  - a fake that never answers `loadAsr` → `Err(Timeout(ASR_LOAD))` after the configured timeout and the process is killed;
  - `generate` streams ≥ 50 pieces in order and `cancel()` stops it.

## Out of scope

The real runtimes (WP-06/07/10), what to load when (WP-11).

## Build/test

`tools/gw :infer:api:testDebugUnitTest :infer:api:assembleDebugAndroidTest`; run the test APK on an emulator.
