# WP-10 LLM engine: llama.cpp + ggml-hexagon, prefix cache, tokenizer tests (`:infer:llm`, `native/llama`)

Wave 1. Read first: `docs/wp/README.md`; `docs/research/verify/llm-npu.md` (all: D1-D11, §6 JNI surface and
threading, §5 measurements); `native/llama/README.md`, `include/murmur_llm.h`, `include/murmur_llm_jni.h`,
`src/murmur_llm.cpp`; `prompts/README.md` "Request assembly"; ARCHITECTURE.md §4.3 (`:llm` context check), §6, §7,
§8.3, §9.4; DECISIONS ADR-007, ADR-009, ADR-011, ADR-029, ADR-031, ADR-038, ADR-045, ADR-048, ADR-049, ADR-060.

## Goal

The `:llm` process loads a GGUF on NPU / GPU / CPU with the catalog's context window, restores the cached system
prefix in milliseconds, refuses requests that do not fit before decoding, streams a greedy cleanup and exits on idle
— with the same code on the phone (arm64, Hexagon) and on the emulator (x86_64 CPU).

## Owns

`infer/llm/**`; `native/llama/**`; `tools/device/llm_npu_smoke.sh`.

## Contracts

Implement the `ILlmService` binder in `LlmService` (`probe`, `memory`, `load`, `setPrefix`, `generate`, `bench`,
`cancel`, `unload`) and the JNI shim behind `LlmNative` (`native/llama/include/murmur_llm_jni.h`). Payloads: `LoadSpec`,
`LoadInfo`, `PrefixSpec`, `PrefixInfo`, `LlmRequest{suffix, maxTokens, minTokens}`, `LlmStats` (`core/engine/.../Llm.kt`).
Runtime key `llama/gguf`. Load options (`LoadOptions`): `n_ctx` (from the catalog, 9,216 for stock models; never a
hard-coded 4,096), `threads` (default 4), `use_mmap` (CPU: 0), `power_mode` (NPU: burst), `kv_type` (f16), `n_batch`.

## Deliver

1. JNI shim `native/llama/src/murmur_llm_jni.cpp` (≈ 200 lines over `murmur_llm.h`), built by
   `native/llama/build_android.sh jnilibs` into `<murmur.nativeLibsDir>/llama/<abi>/` together with the
   prebuilt set (arm64: CPU variants incl. `android_armv8.6_1`, OpenCL, `libggml-hexagon.so`,
   `libggml-htp-v79.so` — the APK drops the latter unless `-Pmurmur.htp=true`, ADR-048; x86_64: x64 + ivybridge +
   haswell). Text in/out as UTF-8 `byte[]` (D11). Attach the engine thread once; cache the sink method id.
2. `LlmService`: one single-thread engine executor; `mllm_global_init` once (backend dir = `nativeLibraryDir`,
   `htp_lib_dir` = same, on-disk because of `useLegacyPackaging`); `load` maps `ComputeUnit` → `mllm_device` and
   `n_ctx` from the spec; `n_outputs_max = n_seq_max` kept; `setPrefix` = `prefixLoad(file)` when
   `models/<artifact>/prefix-<cacheKey>.state` exists, else `prefixSet` + `prefixSave`; on `MLLM_E_STATE` (stale or
   corrupt file) delete it, prefill again and save (ADR-060); `PrefixInfo.tokens` is the exact prefix token count.
3. `generate`: tokenise the suffix, `room = n_ctx − n_past − prompt tokens`; if `room < request.minTokens` reply
   `onError(LlmContextOverflow(needed, n_ctx))` **without decoding** (never reach `MLLM_E_OVERFLOW`); else
   `max_tokens = min(request.maxTokens, room)`; restore, prefill, stream pieces, stop at EOG / `<|im_end|>` /
   `max_tokens`, return `LlmStats` with the unit and `stopReason` (`LlmStats.STOP_*`).
4. `cancel` flips the atomic under the handle lock; `unload` on the executor; `onUnbind` → unload → exit. A load error
   is reported as `UnitUnavailable(unit)` (device did not register / rpcmem or VTCM refused) or
   `EngineLoadFailed(LLM, code)`; the fallback to another unit is WP-11's executor (ADR-050).
5. Probe: registered devices (`mllm_devices`: CPU always; `HTP0` / `GPUOpenCL` only when they register) →
   `ProcessProbe.units`; facts: `native.build` = "llama.cpp b12520 <commit> ggml-hexagon <variant>", CPU variant
   loaded, HTP arch.
6. Thinking off by template string (render.py does it; assert the first generated piece is not `<think>`).
7. **Tokenizer host test (mandatory, ADR-045):** a script under `native/llama/tools/` that, with the host
   `llama-tokenize` (`.cache/ml/llm/bin/llama-b12503/`) and each shipped GGUF vocabulary (LFM3, Qwen3.5 0.8B/2B, LFM2.5
   1.2B/350M, Qwen3 0.6B; `tools/models/fetch` or `.cache/ml/llm`), asserts (a) the token count of
   `render.cache_prefix(profile, template)` for every catalog (profile, template) equals catalog `prefixTokens`
   (architect's measurement: default.v4 3,129 / 3,240 / 3,269 / 3,161; finetuned.v1 97 / 102), and (b) for every WP-04
   golden, `tokens(prefix) + tokens(suffix) == tokens(full)` (add_special = false, parse_special = true). It prints a
   compact table; a prompt or template change that breaks either fails it.
8. Optional (flag, default off): prefill the user message up to `<transcript>\n` during dictation; enable only after
   the same additivity test holds for that split.
9. Keep `tools/device/llm_npu_smoke.sh` working against the new build output path and make it run with
   `n_ctx 9216` and the default.v4 prefix (DC2).

## Acceptance (checkable here)

- Host: `native/llama/build_android.sh cpu-x86_64 jnilibs` reproducible (record time, sha256 list); the JNI shim
  compiles for arm64 and x86_64; the tokenizer test of item 7 passes (paste its table).
- x86_64 emulator **started with `-qemu -cpu host`** (ADR-038; report the CPU variant ggml picked), real model
  `qwen3.5-0.8b@q4_0` (`tools/models/fetch`, pushed with `run-as`): instrumented test binds `LlmService` (`:llm`) and
  asserts:
  - `load(CPU, n_ctx 9216)` succeeds, `LoadInfo.deviceName == "CPU"`, peak RSS reported;
  - `setPrefix(cache_prefix of default.v4 for qwen3.5)` creates the state file and reports 3,129 tokens; a second
    process start loads it in < 100 ms (`PrefixInfo.restoredFromDisk`) and gives the same output as without cache; a
    truncated state file is deleted and rebuilt (no crash);
  - `generate` on the two `prompts/examples/ctxv2_*` suffixes returns non-empty Russian text in Cyrillic, no
    `<think>`, stop reason EOG, TTFT and tok/s reported;
  - a request with `minTokens` larger than the room returns `LlmContextOverflow` in < 1 s with no decode;
  - an emoji in the transcript survives the round trip (UTF-8 byte path);
  - `cancel()` during decode returns within 200 ms with stop reason 3; the next `generate` works;
  - a `LoadSpec` with unit NPU on the emulator returns `UnitUnavailable(NPU)` (no crash);
  - unbind → `pidof app.murmur:llm` empty within 2 s.
- Same with `lfm3-1.7b@q4_0-qad` (functional only; the 3,240-token prefix prefill takes minutes on the emulator: use
  `debugTimeoutScale`, report the time).
- NPU/GPU: UNVERIFIED-ON-DEVICE (DC2, DC8); on the emulator the probe reports CPU only.

## Out of scope

Prompt rendering (WP-04), choosing the model/unit and the fallback (WP-13/WP-11), binder client (WP-05), GenieX Plan B
(only if the device check fails; owner decision).

## Build/test

`tools/heavy native/llama/build_android.sh cpu-x86_64 jnilibs` (and `npu` for arm64 if the SDK cache exists:
`.cache/spikes/llm-npu/work/hexagon-sdk`); `tools/gw :infer:llm:assembleDebugAndroidTest :app:assembleDebug`.
