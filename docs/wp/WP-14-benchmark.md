# WP-14 Benchmark runner (`:core:benchmark`)

Wave 1 (against fakes; real numbers on the emulator once WP-06/07/10 land). Read first: `docs/wp/README.md`;
ARCHITECTURE.md §10, §8.2; DESIGN.md §6.4 "Benchmark flow" and frames `bench-run`, `bench-result`,
`bench-result-llm`; `docs/research/npu-runtime.md` §8 (metric definitions); `docs/research/llm-models.md` §10;
`docs/research/asr-models.md` §7.3; DECISIONS ADR-051.

## Goal

"Every model has a Benchmark button" (BRIEF): a short (< 60 s per unit on the phone), honest measurement of each
usable compute unit of a model on this phone, through the production processes, with a correctness gate, stored and
used for the default unit and the cleanup budget (D2).

## Owns

`core/benchmark/**` (JVM; bundled clips and pages as resources under `core/benchmark/src/main/resources/bench/`).

## Contracts

Implement `BenchmarkRunner.run(entry, candidates): Flow<BenchProgress>` and `stop()` over `Engines`
(`asr`, `llm`, `ocr`, `asrHost`, `llmHost` for `shutdown()` cold starts and `memory()`), `BenchmarkRepository`
(save), `UnitHealthRepository` (`clear` after a valid record), `MurmurClock`. Results are `BenchmarkRecord` /
`BenchmarkResultData` (`core/store/.../Models.kt`; `valid`, `invalidReason`, ASR `tailMs`, LLM `guardAction`,
`stopReason`). `WordErrorRate`, `BenchSummary`, `BenchValidity` (thresholds, tested) and `BenchClip(languages)` exist.
For the LLM fixed case you need the renderer and the guard: take `PromptRenderer`, `OutputGuard`, `PromptAssets` as
constructor parameters.

## Deliver

1. Inputs: ASR clips of ≈ 10-12 s each — ru, en, mixed ru/en — from FLEURS dev (CC-BY-4.0; record source ids, licence,
   sha256 in `core/benchmark/src/main/resources/bench/SOURCES.md`) as 16 kHz PCM16 + reference text; one OCR page per
   display size (two of `ml/ocr/golden` PNGs — synthetic renders, licence-free; record which) with their golden texts;
   one fixed LLM cleanup case (the real profile prefix + a 60-word ru transcript + a 200-token ctx v2 from
   `prompts/examples/ctxv2_chat_ru.input.json`). Keep the resources < 3 MB.
2. Protocol per (artifact, unit) (ADR-051): `host.shutdown()` → load (cold, timed) → warm-up → measured pass →
   unload → `memory()` peak (VmHWM, PSS, availMem drop) → validity gate → record with `appVersion` (injected) + build
   fingerprint (injected) + thermal note (injected provider).
   - ASR: only clips whose `languages` are all in `entry.languages.codes`; resample to 8 kHz for `sampleRate = 8000`;
     push the PCM through `AsrSession.accept` **as fast as it accepts** (not real time), one warm-up + one measured
     pass per clip; RTF× = audio ms / Σ decode ms; `tailMs` = `finish()` latency; WER vs reference;
     `LoadInfo.deviceName` → "Ran on"; `valid` by `BenchValidity.asr(medianWer, cpuWer of the latest CPU record)`.
   - LLM: fixed case TTFT / prefill tok/s / decode tok/s / end-to-end ms from `LlmStats`; synthetic pp512/tg128 via
     `LlmEngine.bench`; `valid` by `BenchValidity.llm(output, guard ACCEPT, stopReason, expectCyrillic = true)`.
   - OCR: ms per page, boxes, load ms; valid when boxes > 0 and texts match the golden within the ml/ocr tolerance.
3. `stop()` cancels between steps and leaves no process loaded. A valid record → `UnitHealth.clear(artifact, unit)`.
4. Score function for `ArtifactSelector` (`BenchLookup`, put it here as `BenchScores.lookup(records)`): ASR =
   processing ms per audio second; LLM = e2e ms; **null for invalid records**. `LlmSpeed` for `ActiveLlm.speed` =
   (prefill tok/s, decode tok/s) of the newest record of (artifact, unit); an invalid one gives no speed or score (ADR-071).

## Acceptance (checkable here)

- `tools/gw :core:benchmark:test` green: runner on `FakeEngines` produces the step sequence of DESIGN `bench-run`
  (Load model / Russian sample / English sample / Unload and measure memory) for a ru+en model and skips the English
  clip for a ru-only catalog entry (GigaAM); medians from scripted timings with virtual time; the fast path does not
  wait in real time (a 12 s clip finishes in < 1 s of virtual time with a zero-delay fake); a stopped run emits no
  `Finished`; records saved; cold start calls `shutdown()` before each unit; a fake ASR that returns garbage gives
  `valid = false, invalidReason = "wer_high"` and `BenchScores.lookup` returns null for it; a valid record clears
  `FakeUnitHealth`.
- On the emulator once WP-06/10 land: a debug entry point (instrumented test) benchmarks
  `gigaam-v3-e2e-rnnt@onnx-int8` (CPU) and `qwen3.5-0.8b@q4_0` (CPU) end to end; report the records and the wall time
  per unit (numbers are emulator numbers, labelled as such, never shown as phone numbers).

## Out of scope

The bench sheet UI (WP-18), device numbers (DEVICE_CHECKLIST DC9).

## Build/test

`tools/gw :core:benchmark:test`.

## Integration notes (wave-1 integration pass 1; ADR-064..077 in DECISIONS)

Merged on `integration/wave1`: WP-01, 05, 06, 07, 08, 09, 11, 12, 13. This package is still in work; check these when it merges.
- ADR-071: selection, `CleanupBudget` speed and the adaptive timeouts use the **newest** record of (artifact, unit);
  an invalid newest record gives no score and no speed. `BenchmarkRepository.latestValid` is for display only.
  `data/models` already has a `BenchScores` (DefaultModelResolver.kt) with this rule: keep one implementation (yours in
  `core:benchmark` or that one) and point the other at it when this package merges.
- The `:testing` `InMemoryBenchmarkRepository.latest` now returns the newest record per unit, newest first (as Room
  does), not every record in insertion order; tests written against the old fake may change.
- `OcrTolerance.Suite.ok` gates the CER on the non-identical lines (ADR-077). Cold loads use `BinderHost.shutdown()`
  (ADR-069). An OutOfMemory never marks `UnitHealth` (ADR-066, Preparing included); a valid record still clears it.
- Handle `MurmurError.DownloadPaused` and `MicLost` in any exhaustive `when` over `MurmurError`.
