# WP-14 Benchmark runner (`:core:benchmark`)

Wave 1 (against fakes; real numbers on the emulator once WP-06/07/10 land). Read first: `docs/wp/README.md`;
ARCHITECTURE.md §10, §8.2; DESIGN.md §6.4 "Benchmark flow" and frames `bench-run`, `bench-result`,
`bench-result-llm`; `docs/research/npu-runtime.md` §8 (metric definitions); `docs/research/llm-models.md` §10;
`docs/research/asr-models.md` §7.3.

## Goal

"Every model has a Benchmark button" (BRIEF): a short, honest measurement of each usable compute unit of a model on
this phone, through the production processes, stored and used for the default unit (D2).

## Owns

`core/benchmark/**` (JVM; bundled clips and pages as resources under `core/benchmark/src/main/resources/bench/`).

## Contracts

Implement `BenchmarkRunner.run(entry, candidates): Flow<BenchProgress>` and `stop()` over `Engines`
(`asr`, `llm`, `ocr`, `asrHost`, `llmHost` for `shutdown()` cold starts and `memory()`), `BenchmarkRepository`
(save), `MurmurClock`. Results are `BenchmarkRecord` / `BenchmarkResultData` (`core/store/.../Models.kt`).
`WordErrorRate` and `BenchSummary` exist.

## Deliver

1. Inputs: 3 ASR clips (ru, en, mixed ru/en, ≈ 30 s each) from FLEURS dev (CC-BY-4.0; record source ids, licence,
   sha256 in `core/benchmark/src/main/resources/bench/SOURCES.md`) as 16 kHz PCM16 + reference text; one OCR page per
   display size (take two of `ml/ocr/golden` PNGs — they are synthetic renders, licence-free; record which); one
   fixed LLM cleanup case (the real profile prefix + a 60-word ru transcript + a 200-token ctx v2 from
   `prompts/examples/ctxv2_chat_ru.input.json`). Keep the resources < 4 MB.
2. Protocol per (artifact, unit): `host.shutdown()` → load (cold, timed) → warm-up → 3 runs → median/min/max →
   unload → `memory()` peak (VmHWM, PSS, availMem drop) → record with `appVersion` (injected) + build fingerprint
   (injected) + thermal note (injected provider).
   - ASR: feed each clip in real time through `AsrSession` (as the app does), processing time = finish time − audio
     end + decode time during the clip; RTF×; WER vs reference; `LoadInfo.deviceName` → "Ran on".
   - LLM: fixed case TTFT / prefill tok/s / decode tok/s / end-to-end ms from `LlmStats`; synthetic pp512/tg128 via
     `LlmEngine.bench` (`ILlmService.bench` → `mllm_bench`, implemented by WP-05/WP-10).
   - OCR: ms per page, boxes, load ms.
3. `stop()` cancels between steps and leaves no process loaded.
4. Score function for `ArtifactSelector` (`BenchLookup`): ASR = processing ms per audio second; LLM = e2e ms.

## Acceptance (checkable here)

- `tools/gw :core:benchmark:test` green: runner on `FakeEngines` produces the step sequence of DESIGN `bench-run`
  (Load model / Russian sample / English sample / Unload and measure memory), medians from scripted timings, a
  stopped run emits no `Finished`, records saved, cold start calls `shutdown()` before each unit.
- On the emulator once WP-06/10 land: a debug entry point (instrumented test) benchmarks
  `gigaam-v3-e2e-rnnt@onnx-int8` (CPU) and `qwen3.5-0.8b@q4_0` (CPU) end to end; report the records (numbers are
  emulator numbers, labelled as such, never shown as phone numbers).

## Out of scope

The bench sheet UI (WP-18), device numbers (DEVICE_CHECKLIST D9).

## Build/test

`tools/gw :core:benchmark:test`.
