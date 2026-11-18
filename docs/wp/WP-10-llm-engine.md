# WP-10 LLM engine: llama.cpp + ggml-hexagon, prefix cache (`:infer:llm`, `native/llama`)

Wave 1. Read first: `docs/wp/README.md`; `docs/research/verify/llm-npu.md` (all: D1-D11, §6 JNI surface and
threading, §5 measurements); `native/llama/README.md`, `include/murmur_llm.h`, `include/murmur_llm_jni.h`,
`src/murmur_llm.cpp`; `prompts/README.md` "Request assembly"; ARCHITECTURE.md §4, §6, §7, §9.4; DECISIONS ADR-007,
ADR-009, ADR-011, ADR-029, ADR-031, ADR-038.

## Goal

The `:llm` process loads a GGUF on NPU / GPU / CPU, restores the cached system prefix in milliseconds, streams a
greedy cleanup and exits on idle — with the same code on the phone (arm64, Hexagon) and on the emulator (x86_64 CPU).

## Owns

`infer/llm/**`; `native/llama/**`; `tools/device/llm_npu_smoke.sh`.

## Contracts

Implement the `ILlmService` binder in `LlmService` (`probe`, `memory`, `load`, `setPrefix`, `generate`, `cancel`,
`unload`) and the JNI shim behind `LlmNative` (`native/llama/include/murmur_llm_jni.h`). Payloads: `LoadSpec`,
`LoadInfo`, `PrefixSpec`, `PrefixInfo`, `LlmRequest`, `LlmStats` (`core/engine/.../Llm.kt`). Runtime key `llama/gguf`.
Load options in `LoadSpec.options`: `n_ctx` (default 4096), `threads` (default 4), `use_mmap` (CPU: 0),
`power_mode` (NPU: burst).

## Deliver

1. JNI shim `native/llama/src/murmur_llm_jni.cpp` (≈ 200 lines over `murmur_llm.h`), built by
   `native/llama/build_android.sh jnilibs` into `<murmur.nativeLibsDir>/llama/<abi>/` together with the
   prebuilt set (arm64: CPU variants incl. `android_armv8.6_1`, OpenCL, `libggml-hexagon.so`,
   `libggml-htp-v79.so`; x86_64: x64 + ivybridge + haswell). Text in/out as UTF-8 `byte[]` (D11). Attach the engine
   thread once; cache the sink method id.
2. `LlmService`: one single-thread engine executor; `mllm_global_init` once (backend dir = `nativeLibraryDir`,
   `htp_lib_dir` = same, on-disk because of `useLegacyPackaging`); `load` maps `ComputeUnit` → `mllm_device`;
   `n_outputs_max = n_seq_max` kept; `setPrefix` = `prefixLoad(file)` when `models/<artifact>/prefix-<cacheKey>.state`
   exists, else `prefixSet` + `prefixSave`; `generate` restores, prefills the suffix, streams pieces, stops at EOG /
   `<|im_end|>` / `maxTokens`, returns `LlmStats` with the unit; `cancel` flips the atomic under the handle lock;
   `unload` on the executor; `onUnbind` → unload → exit.
3. Probe: registered devices (`mllm_devices`: CPU always; `HTP0` / `GPUOpenCL` only when they register) →
   `ProcessProbe.units`; facts: CPU variant loaded, HTP arch.
4. Thinking off by template string (render.py does it; assert the first generated piece is not `<think>`).
5. Optional (flag, default off): prefill the user message up to `<transcript>\n` during dictation; enable only after
   a test proves `tokenize(a)+tokenize(b) == tokenize(a+b)` on WP-04's golden corpus for the shipped templates.
6. Keep `tools/device/llm_npu_smoke.sh` working against the new build output path.

## Acceptance (checkable here)

- Host: `native/llama/build_android.sh cpu-x86_64 jnilibs` reproducible (record time, sha256 list); the JNI shim
  compiles for arm64 and x86_64.
- x86_64 emulator (start with `-qemu -cpu host` if you want 3-4× speed, ADR-038; report which), real model
  `qwen3.5-0.8b@q4_0` (download with the catalog URL into `.cache/models/`, verify sha256, push with `run-as`):
  instrumented test binds `LlmService` (`:llm`) and asserts:
  - `load(CPU)` succeeds, `LoadInfo.deviceName == "CPU"`, peak RSS reported;
  - `setPrefix(cache_prefix of default.v4 for qwen3.5)` creates the state file; a second process start loads it in
    < 100 ms (`PrefixInfo.restoredFromDisk`) and gives the same output as without cache;
  - `generate` on the two `prompts/examples/ctxv2_*` suffixes returns non-empty Russian text in Cyrillic, no
    `<think>`, stop reason EOG, TTFT and tok/s reported;
  - an emoji in the transcript survives the round trip (UTF-8 byte path);
  - `cancel()` during decode returns within 200 ms with stop reason 3; the next `generate` works;
  - unbind → `pidof app.murmur:llm` empty within 2 s.
- Same with `lfm3-1.7b@q4_0-qad` (functional only; it is slow on the emulator).
- NPU/GPU: UNVERIFIED-ON-DEVICE (DEVICE_CHECKLIST D2); on the emulator the probe reports CPU only.

## Out of scope

Prompt rendering (WP-04), choosing the model/unit (WP-13/WP-14), binder client (WP-05), GenieX Plan B (only if the
device check fails; owner decision).

## Build/test

`tools/heavy native/llama/build_android.sh cpu-x86_64 jnilibs` (and `npu` for arm64 if the SDK cache exists:
`.cache/spikes/llm-npu/work/hexagon-sdk`); `tools/gw :infer:llm:assembleDebugAndroidTest :app:assembleDebug`.
