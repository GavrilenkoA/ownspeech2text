# LLM on the Fold7 NPU: verification spike (llm-npu)

Date 2026-11-18. Pinned llama.cpp **b12520 = `ff6b8d176186ce1472a71800fcf3e6b221fcbb7b`** (2026-11-18).
Owned outputs: `native/llama/` (build recipe + C ABI + JNI sketch), `tools/device/llm_npu_smoke.sh`, this file.
Scratch: `.cache/spikes/llm-npu/` (build trees, SDK, logs, host results).

Status words: **VERIFIED** = I ran it here and quote the output; **REFUTED** = I ran it and it contradicts the claim;
**UNVERIFIABLE-HERE** = needs something this server lacks (named); **UNVERIFIED-ON-DEVICE** = only a phone can prove it
(`tools/device/llm_npu_smoke.sh` measures it). No Fold7 was attached (`adb devices` empty), so nothing below ran on a
phone or on a Hexagon DSP. Every throughput number from this server is **x86_64 (Xeon E-2388G, 8C/16T), CPU only,
on a shared machine with load average 13-48 from other tenants**: use it for relative comparisons, never as phone numbers.

---

## 1. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | **Runtime = upstream llama.cpp b12520 with `GGML_HEXAGON=ON` + `GGML_OPENCL=ON` + all Android arm64 CPU variants**, built by `native/llama/build_android.sh` with the local NDK r30 and the public Hexagon SDK 6.7.0.0 "Community Edition" tarball. No Docker image, no Qualcomm account. One GGUF runs on NPU, GPU and CPU. | Build VERIFIED (arm64 npu, arm64 cpu, x86_64 cpu, host). Running on the HTP: UNVERIFIED-ON-DEVICE |
| D2 | **Ship the prebuilt `.so` set from the script into `jniLibs`, not a Gradle CMake build of llama.cpp.** The app compiles only a thin JNI shim (`native/llama/include/murmur_llm_jni.h`) against `libmurmur_llm.so`. `useLegacyPackaging = true`. | Rationale VERIFIED from source + build times (§6) |
| D3 | **Weights: Q4_0 GGUF** ("Q4" in the hub = Q4_0; Liquid's `QAD-Q4_0` where it exists). Q4_K_M is *accepted* by the HTP matmul at this commit (K-quants are tiled types), so it is no longer a correctness problem, but Q4_0 stays the default: smallest tile (576 B per 32x32), the type Qualcomm benchmarks, and what Qualcomm's own manifests point to. | Source VERIFIED; relative speed of K-quants on HTP UNVERIFIED-ON-DEVICE |
| D4 | **Device per model is chosen by the on-device Benchmark, defaulting to: LFM3 -> NPU, Qwen3.5 -> CPU.** Qualcomm's own S25 data (same SoC, same llama.cpp-Hexagon path, GenieX v0.10.0) gives LFM3 7.1x prefill on NPU and 23% slower decode; Qwen3.5-0.8B 1.7x prefill and **half** the CPU decode. Dictation cleanup is decode-bound, so "NPU for all models" costs Qwen3.5 users latency. **Owner decision needed** (§10). | Qualcomm numbers VERIFIED as published; our phone numbers UNVERIFIED-ON-DEVICE |
| D5 | **Prefix cache = per-sequence state snapshot** (`llama_state_seq_get_data_ext` / `llama_state_seq_set_data_ext`) taken once after prefilling the ~1.1k-token system prefix; restored per dictation in 2-8 ms (x86). Persist it across `:llm` restarts with `llama_state_seq_save_file` / `llama_state_seq_load_file` keyed by (model sha, prompt version). Works for both hybrid architectures (identical greedy output with and without the cache). | VERIFIED on host (§5) |
| D6 | **Thinking off by prompt string, not by a runtime flag**: Qwen3.5 generation prefix `<|im_start|>assistant\n<think>\n\n</think>\n\n`, LFM3 `<|im_start|>assistant\n<think></think>` (LFM3's template defaults `enable_thinking` to **true**, so the off form must be written explicitly). The first generated token is answer text in every host run. | VERIFIED on host (§5.3) |
| D7 | **`:llm` is its own process**; `mllm_global_init()` runs before anything touches ggml; unload = let the process exit (only way to drop the FastRPC session + rpcmem). Manifest: `uses-native-library libcdsprpc.so` and `libOpenCL.so` (required=false), `uses-feature android.hardware.npu` (required=false). | Manifest entries VERIFIED in GenieX AAR + Android API ref; behaviour on One UI 9 UNVERIFIED-ON-DEVICE |
| D8 | **Plan B = GenieX AAR `com.qualcomm.qti:geniex-android:0.10.0`** (same llama.cpp-Hexagon code path, GGUF input, 230.7 MB of native libs). Nothing else today runs LFM3 or Qwen3.5 on the Hexagon NPU from a sideloaded app. | VERIFIED (§4) |

---

## 2. Build: what is really in the llama.cpp tree (deliverable 1)

### 2.1 Facts at b12520 (all VERIFIED by reading/building `.cache/spikes/llm-npu/work/llama.cpp`)

| Claim to check | What is true |
|---|---|
| Backend name / option | `ggml/src/ggml-hexagon/`, CMake option `GGML_HEXAGON` (`ggml/CMakeLists.txt:275`, default OFF). Also needs `HEXAGON_SDK_ROOT`, optional `HEXAGON_TOOLS_ROOT` (else read from `hexagon_sdk.json`), `PREBUILT_LIB_DIR=android_aarch64`, Python 3. Upstream preset: `docs/backend/snapdragon/CMakeUserPresets.json` (`arm64-android-snapdragon-release`). |
| Produces `libggml-htp-v79.so` | Yes. `libggml-hexagon.so` is the AArch64 host backend; `libggml-htp-v73/v75/v79/v81/v85.so` are **Hexagon DSP ELFs** (`llvm-readelf -h`: `Machine: Qualcomm Hexagon`, `Flags: 0x79` for v79), each an `ExternalProject` (`htp-vNN` targets) compiled with the SDK's `hexagon-clang 21.0.04`. They are **not** build dependencies of `ggml-hexagon`: building only `llama` or `ggml` silently omits them (the first npu build here produced none; `build_android.sh` now names the `htp-v*` targets explicitly). |
| Docker image `ghcr.io/snapdragon-toolchain/arm64-android` | Exists, anonymous pull works: tags `v0.1 v0.2 v0.3 v0.6 v0.7 v0.8`; `v0.8` amd64 manifest = 11 layers, **2.46 GB compressed** (registry API, token from `ghcr.io/token`). Its Dockerfile (`github.com/snapdragon-toolchain/docker`) installs NDK (`ARG r29` at HEAD), Hexagon SDK 6.7.0.0 from the GitHub tarball below, LLVM 21, Khronos OpenCL. **Not needed**: the same SDK tarball works with our NDK r30. Not pulled (disk). |
| Hexagon SDK source | `https://github.com/snapdragon-toolchain/hexagon-sdk/releases/download/v6.7.0.0/hexagon-sdk-v6.7.0.0-amd64-lnx.tar.xz`, 741,882,016 B, sha256 `00725fc4…5dc70`, **no login** (HTTP 302 -> 200). Repo README: "Hexagon SDK Community Edition releases trimmed and repackaged for GitHub Actions"; the full SDK is on Qualcomm Software Center (account). `hexagon_sdk.json` inside says build 6.7.0.1. 3.3 GB extracted. |
| OpenCL | `GGML_OPENCL` needs CL headers + a `libOpenCL.so` to link. Khronos `OpenCL-Headers/CLHPP/ICD-Loader v2025.07.22` (sha256 pinned); the ICD loader is a link stub, the phone's `/vendor/lib64/libOpenCL.so` is used at runtime. Adreno kernels embedded (`GGML_OPENCL_USE_ADRENO_KERNELS=ON`, `GGML_OPENCL_EMBED_KERNELS=ON`). |
| CPU | `GGML_BACKEND_DL=ON GGML_CPU_ALL_VARIANTS=ON` builds 7 Android arm64 variants (`android_armv8.0_1` … `android_armv9.2_2`) + KleidiAI v1.32.0 (fetched by ggml's CMake, MD5-pinned). The loader picks the best variant by HWCAP at runtime. |
| Old API | `llama_decode` / `llama_batch_get_one` are `DEPRECATED` at this commit; the new path is `llama_batch_ext_*` + `llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch)`. `murmur_llm.cpp` uses the new one. |

### 2.2 Recipe (`native/llama/`)

`versions.env` (single source of truth: commit, archive sha256, SDK URL + sha256, OpenCL sha256s, NDK/CMake),
`build_android.sh [npu|cpu-arm64|cpu-x86_64|host|jnilibs]`, `CMakeLists.txt` (llama.cpp via `add_subdirectory`, or
FetchContent of the sha256-pinned GitHub source archive when `MURMUR_LLAMA_SRC` is empty), `patches/` (none needed),
`include/murmur_llm.h` (C ABI), `include/murmur_llm_jni.h` (JNI sketch), `src/murmur_llm.cpp`, `src/murmur_llm_bench.cpp`,
`tools/make_prompt.py`. All cmake/ninja calls go through `tools/heavy`; `JOBS=6`.

Reproducibility checks (VERIFIED): (1) `rm -rf build/npu out/npu` then one `build_android.sh npu` invocation: configure 6 s, build 1,082 s wall (26 m 52 s CPU) at load average 50-72, 1000/1000 ninja steps including the 5 `htp-v*` skels (`.cache/spikes/llm-npu/work/clean-npu-rebuild.log`). (2) After editing `murmur_llm_bench.cpp` and the jniLibs profile, `build_android.sh npu cpu-arm64 cpu-x86_64 host` = 12.4 s total, 2 s build per variant (`work/incremental-rebuild.log`). Default work dir is `.cache/native-llama/`; this spike used `MURMUR_LLAMA_WORK=.cache/spikes/llm-npu/work`.

### 2.3 Output: the `.so` set an APK ships (VERIFIED, `MANIFEST.txt` per variant: size, PT_LOAD align, NEEDED, sha256)

`PROFILE=fold7` (default) `out/jniLibs/`:

| arm64-v8a (phone) | bytes | | x86_64 (emulator, CPU only) | bytes |
|---|---:|---|---|---:|
| `libllama.so` | 4,361,272 | | `libllama.so` | 4,403,384 |
| `libggml-opencl.so` | 4,046,712 | | `libggml-cpu-haswell.so` (AVX2) | 1,452,824 |
| `libc++_shared.so` | 1,423,696 | | `libc++_shared.so` | 1,390,752 |
| `libggml-htp-v79.so` (Hexagon DSP ELF) | 1,039,080 | | `libggml-cpu-ivybridge.so` (AVX+F16C, what the emulator exposes) | 1,341,384 |
| `libggml-cpu-android_armv8.6_1.so` (dotprod+fp16+i8mm) | 966,104 | | `libggml-cpu-x64.so` (baseline) | 1,087,664 |
| `libggml-cpu-android_armv8.0_1.so` (baseline) | 869,008 | | `libggml-base.so` | 808,856 |
| `libggml-base.so` | 742,688 | | `libmurmur_llm.so` | 57,760 |
| `libggml-hexagon.so` | 455,488 | | `libggml.so` | 54,000 |
| `libmurmur_llm.so` | 33,584 | | | |
| `libggml.so` | 32,056 | | | |
| **total** | **13,969,688** (5.06 MB gzip -9) | | **total** | **10,596,624** (3.97 MB gzip -9) |

- The API 36 emulator on this server exposes AVX + F16C but not AVX2/FMA (guest `/proc/cpuinfo`; the host has AVX2/AVX-512), so ggml loads `libggml-cpu-ivybridge.so` there (smoke log: `loaded CPU backend from /data/local/tmp/murmur-llm/lib/libggml-cpu-ivybridge.so`). Without it the emulator falls back to the SSE baseline, so it was added to the fold7 profile.
- `PROFILE=all` adds the other 5 arm64 CPU variants (~950 KB each) and `htp-v73/v75/v81/v85` (~1.0 MB each): `npu` variant total 22,961,048 B. v81 (SM8850) is a cheap "other devices" add.
- Oryon cores (8 Elite) have dotprod/fp16/i8mm but, as far as I know, no SVE/SME, so `armv8.6_1` should be the variant picked; the smoke log line `load_backend: loaded CPU backend from …` will show it (UNVERIFIED-ON-DEVICE).
- All AArch64 libs: PT_LOAD align `0x4000` (16 KB pages OK). The DSP ELF has `0x1000`; it is loaded by the cDSP loader, not by bionic, so this does not matter.
- `libggml-hexagon.so` NEEDED = `liblog libdl libggml-base libm libc++_shared libc`; it `dlopen`s `libcdsprpc.so` at runtime (string present) and contains no QNN reference (0 `qnn` strings). It does not conflict with the ASR process's QNN libs.

Build times (wall clock, `-j6`, load average 15-40 from other tenants): `npu` 359 s for CPU+OpenCL+host backend, +97 s
for the 5 HTP skels; `cpu-arm64` 416 s; `cpu-x86_64` 423 s; `host` ~5 min. Incremental rebuild after editing
`murmur_llm*.cpp`: 1-3 s.

---

## 3. Backend source review (deliverable 2)

### 3.1 Op coverage

Every op in the Qwen3.5 and LFM3 graphs maps to an HTP opcode (`htp_op_remap()` in `htp-kparams.cpp`); acceptance is
then decided per node by `ggml_backend_hexagon_device_supports_op()` = size check (each tensor ≤ session `max_vmem`) +
`htp_kparams_solve()` (type/shape/VTCM constraints). Op mix from a `GGML_SCHED_DEBUG=2` dump of the real graphs
(host CPU run, `.cache/spikes/llm-npu/ops/*.sched.log`) checked against those constraints:

| Op (count in dump) | Used by | HTP constraint at b12520 | Our models |
|---|---|---|---|
| MUL_MAT (2244 / 2156) | both | weight type must be a tiled type (list in §3.2); `ne0 % 32 == 0`; VTCM fit | all weights are tiled types; OK |
| GATED_DELTA_NET (216) | Qwen3.5 | all F32, `S_v ≤ 128`, contiguous rows, `GGML_HEXAGON_GDN_SELECT ≥ 1` | `S_v = ssm.inner_size / time_step_rank = 2048/16 = 128`: OK (at the limit) |
| SSM_CONV (216 / 182) | both | F32, `d_conv ≤ 32` | Qwen3.5 `conv_kernel = 4`, LFM3 short conv: OK |
| FLASH_ATTN_EXT (72 / 154) | both | Q F16/F32, K/V F16 or Q8_0, mask F16, no logit softcap, VTCM fit | KV cache F16 default: OK; Qwen3.5 head dim **256** vs LFM3 128: VTCM fit only checkable on device |
| ROPE (144 / 308) | both | F32, modes NORMAL/NEOX/MROPE/VISION/IMROPE, `n_dims` even | Qwen3.5 uses multi-section rope (IMROPE family): supported |
| SET_ROWS / GET_ROWS / CPY / CONT / DUP / CONCAT / SCALE / ADD / MUL / RMS_NORM | both | F32/F16 (Q8_0 dst for SET_ROWS) | OK |
| UNARY SILU / SIGMOID / SOFTPLUS, GLU SWIGLU | both | all have HTP kernels | OK |

Expected placement: **everything on HTP0 except the input embedding lookup**, which llama.cpp always keeps on the CPU
(`src/llama-model.cpp:1817` "there is very little benefit to offloading the input layer, so always keep it on the CPU").
The tied output projection (Q6_K) is offloaded. An op that fails its runtime check falls back to the CPU backend and
creates a graph split. **UNVERIFIED-ON-DEVICE**: the real accept/reject list needs a live DSP session
(`supports_op` calls `dev_ctx->session()`), so smoke step 5 runs with `GGML_HEXAGON_VERBOSE=1` and counts
`supports-op …|yes/no` lines per op.

Not supported anywhere in this backend: the ggml abort callback (only CPU and Metal implement
`ggml_backend_set_abort_callback`), `mmap` of weights (`mmap_support = false`), `buffer_from_host_ptr` (NULL).

### 3.2 Weight quantizations

- HTP-tiled weight types (`htp-repack.h: htp_is_tiled_weight_type`): **Q4_0, Q4_1, Q5_0, Q5_1, Q8_0, Q2_K, Q3_K, Q4_K, Q5_K, Q6_K, IQ4_NL, IQ2_XXS, MXFP4, NVFP4, Q1_0, Q2_0, F16, F32, BF16**. Q4_K is dequantized with the Q4_1 tile kernel (`matmul-ops.c:2352`).
- Activations are never 4-bit: HVX matmul quantizes activations to Q8_0 (Q8_1 for weight types with offsets), the HMX path uses FP16 activations with weights dequantized to FP16 in VTCM (`matmul-ops.c:812-830`, `htp_matmul_is_hmx_eligible`). Kernel choice is per op by eligibility + VTCM fit, not strictly "decode = HVX, prefill = HMX".
- Q4_0 tile = 576 B per 32x32 (same bytes as GGUF Q4_0); Q3_K..Q6_K tiles store uncompressed scales and are **larger in memory than the file** (`htp_tiled_row_size`).
- So: Q4_0 is the right "Q4"; Q8_0 is the quality escape hatch (HTP-native, ~2x size); F16 works (tiled as F16) but is 4x the bytes and pointless on a bandwidth-bound decode; Q4_K_M runs on the HTP at this commit.

### 3.3 Memory and session limits

- Weights are copied (and repacked into tiles) into rpcmem buffers allocated with `rpcmem_alloc2(RPCMEM_HEAP_ID_SYSTEM, …)` and mapped to the DSP with `fastrpc_mmap`; they are not file-backed and not evictable. Budget: GGUF size (+ K-quant tile overhead) + compute buffers, in addition to anything the CPU side keeps.
- Per-session DSP VA window: `HTP_OP_MAX_VMEM_DEFAULT = 3,355,443,200 B` for arch ≥ v75 (3000 MiB for v73); larger models use delayed/dynamic map-unmap. Our ≤1.3 GB models fit in one session without that.
- `get_memory()` reports `GGML_HEXAGON_MBUF` (0 = unconstrained), so llama.cpp's auto-fit cannot size anything for HTP0.
- Arch: queried with `remote_handle_control(DSPRPC_GET_DSP_INFO, ARCH_VER)`, clamped to [v73, v85], override `GGML_HEXAGON_ARCH`. Default power mode `burst` (`GGML_HEXAGON_POWER_MODE`: `burst`, `sustained_high_performance`, `balanced`, …). Other knobs: `GGML_HEXAGON_{NHVX,NHMX,VTCM_SIZE,OPBATCH,OPFILTER,VERBOSE,PROFILE,HOSTBUF,DEVICES,NDEV,FA_SELECT,GDN_SELECT,SSM_SELECT,MM_SELECT,RT_REPACK,…}`.
- The startup log still says `Hexagon backend (experimental)` (`ggml-hexagon.cpp:3180`).

### 3.4 How the DSP library gets loaded on a retail phone (source: `htp-drv.cpp`, `ggml-hexagon.cpp:2180-2262`)

1. `libcdsprpc.so` is `dlopen`ed by name (not a NEEDED entry). On Android 7+ an app may only open vendor libs listed in `/vendor/etc/public.libraries.txt`, and from Android 12 must also declare `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`. GenieX's AAR manifest declares exactly `libadsprpc.so`, `libcdsprpc.so`, `libOpenCL.so`, all `required="false"` (VERIFIED, unzipped).
2. **Unsigned PD**: every session calls `remote_session_control(DSPRPC_CONTROL_UNSIGNED_MODULE, enable=1)`; failure throws (`failed to enable unsigned PD`). Nothing is signed; no Qualcomm signing step exists in the build. Whether Samsung's One UI 9 firmware allows unsigned cDSP PDs for third-party apps: **UNVERIFIED-ON-DEVICE** (Qualcomm's own GenieX app relies on it on S25-class phones).
3. Skel location, in order: (a) URI `file:///<dir>/libggml-htp-v79.so?htp_iface_skel_handle_invoke&_modver=1.0` (+`&_dom=cdsp&_session=N`) with an **absolute directory**; (b) `FASTRPC_MANAGE_DSP_LIBRARY_PATH` with that dir; (c) `ADSP_LIBRARY_PATH` / `CDSP_LIBRARY_PATH` env. `<dir>` = `GGML_HEXAGON_LIBHTP_DIR`, else the directory of `libggml-hexagon.so` found via `dladdr()` (or `./lib`, `../lib`), and it must contain a `libggml-htp-*` file.
4. Consequences: with `extractNativeLibs=false` (AGP default) the libs stay inside the APK, `dladdr` returns `…/base.apk!/lib/arm64-v8a/…`, which is not a directory, and the DSP cannot open the skel. ggml's CPU-variant loader also scans a directory. Hence `jniLibs.useLegacyPackaging = true` (GenieX's sample app sets it: VERIFIED in `ai-hub-apps/geniex_chat_android/build.gradle`), or copy `libggml-htp-v79.so` to `filesDir` and pass that dir as `htp_lib_dir`. `ADSP_LIBRARY_PATH` is not needed for llama.cpp's own skel (absolute URI first); the Samsung `/vendor/lib64/rfs/dsp/snap` path matters for QNN (ASR), not here.
5. Android 17: `PackageManager.FEATURE_NEURAL_PROCESSING_UNIT = "android.hardware.npu"`, "Added in API level 37"; summary page: "Apps that target Android 17 must declare the android.hardware.npu feature if they need to access NPUs" (VERIFIED, developer.android.com). Whether Samsung enforces it for FastRPC access: UNVERIFIED-ON-DEVICE.

### 3.5 The three GGUFs (VERIFIED with `gguf-py` from the pinned tree, `.cache/spikes/llm-npu/gguf_inspect.txt`)

| File (bytes) | arch | layers / d_model | attention | tensor types | special tokens | template (embedded, chars) |
|---|---|---|---|---|---|---|
| `LFM3-1.7B-QAD-Q4_0.gguf` (1,049,615,680) | `lfm3` | 24 / 2048 | 16 heads, KV on 11 of 24 layers (n_embd_k_gqa 512), head 128; rest short-conv | **pure Q4_0** (153 tensors, 825 MB) + `token_embd` Q6_K (215 MB, tied output) + F32 norms | BOS `<|startoftext|>` 124894 (`add_bos_token = false`), EOS `<|im_end|>` 124900 | 12,129; `enable_thinking | default(true)`; gen prompt `<|im_start|>assistant\n` + `<think></think>` when off |
| `Qwen3.5-0.8B-Q4_0.gguf` (507,154,688) | `qwen35` | 24 / 1024 | full attention every 4th layer (6), 8 q / 2 kv heads, head **256**; 18 Gated-DeltaNet layers (state 128, 16 groups) | **mixed**: Q4_0 (252 MB) + `ffn_down` Q4_1, `ssm_out` Q5_K, `ssm_alpha/beta` Q8_0, `token_embd` Q6_K (209 MB) | no BOS, EOS `<|im_end|>` 248046, pad 248055 | 7,816; thinking only if `enable_thinking is true`; else `<think>\n\n</think>\n\n` |
| `Qwen3.5-2B-Q4_0.gguf` (1,214,873,856) | `qwen35` | 24 / 2048 | same layout | same mix (Q4_0 708 MB, Q6_K 417 MB) | same | identical to 0.8B (`cmp`) |

Both architectures are known to our build (`strings libllama.so`: `lfm3`, `qwen35`) and to GenieX's bundled llama.cpp.

---

## 4. Fallbacks and alternatives (deliverable 3)

| Option | Artifact resolves today? (checked here) | Contents | License | Model format | User-chosen GGUF? | LFM3 / Qwen3.5 on NPU? | Rank |
|---|---|---|---|---|---|---|---|
| **llama.cpp b12520 + ggml-hexagon (ours)** | built here (§2) | 14.0 MB arm64 set | MIT; DSP skel built with Hexagon SDK CE: SDK redistribution terms not in the tarball (UNVERIFIABLE-HERE) | GGUF | **yes** | yes (all ops have kernels; §3.1) | **1** |
| **GenieX AAR** `com.qualcomm.qti:geniex-android:0.10.0` | yes: Maven Central `latest/release 0.10.0`, lastUpdated 2026-10-28; AAR 89,045,966 B downloaded, sha256 `3a40def4…` | 52 arm64 `.so`, **230,671,576 B uncompressed** (`unzip -l`): its own `libllama.so` + `libggml-hexagon.so` + `libggml-htp-v73…v85.so` + `libggml-opencl.so`, `libgeniex*.so`, plugins `llama_cpp` and `qairt`, full QNN HTP runtime (`libQnnHtp.so`, `libQnnHtpV79/V81{,Stub,Skel}.so`, `libQnnHtpPrepare.so` 85 MB). Kotlin API `LlmWrapper` (`generateStreamFlow`, `stopStream`, `applyChatTemplate`, `reset`), `LlmCreateInput(model_path, tokenizer_path, config, runtime_id, compute_unit)`, `ModelConfig(nCtx, nThreads, …, power_mode)`. Manifest: `uses-native-library` libadsprpc/libcdsprpc/libOpenCL. GitHub tag v0.11.0 exists, not on Maven | POM: BSD-3-Clause **and** "Qualcomm Terms of Use"; QNN libs under QTI license | GGUF (llama_cpp runtime) or AI Hub bundles (qairt) | yes (path to GGUF) | yes: Qualcomm's own S25 numbers for both are on this runtime | **2 (Plan B)**: same kernels, 16x the APK weight, closed release cadence, no prefix-state API visible in the Kotlin surface |
| QAIRT / Genie context binaries (AI Hub) | `qualcomm/LFM3-1.7B`, `qualcomm/Qwen3.5-0.8B`, `qualcomm/Qwen3.5-2B` `release_assets.json` list **only** `q4_0 / geniex_llamacpp -> the GGUF`. Genie w4a16 bundles exist for Qwen3-1.7B (`…snapdragon_8_elite_for_galaxy.zip`, 1,534,157,948 B, QAIRT 2.50) | per-chipset context-binary shards | QTI license | Genie bundle | no (own weights need AIMET w4a16 on a CUDA GPU + AI Hub token) | **no bundles exist** | 4 (only for a Qwen3 hedge) |
| LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.19.1` + `litert-npu-runtime-qualcomm:2.3.2` | yes (Google Maven). `litertlm-android` AAR: `liblitertlm_jni.so` 22.7 MB; NPU AAR: only `libLiteRtCompilerPlugin_Qualcomm.so` + `libLiteRtDispatch_Qualcomm.so` (QNN libs separate) | | Apache-2.0 (+QTI for QNN) | `.litertlm` | no | `litert-community/LFM3-1.7B-LiteRT`: int8 / mixed_int4 only; `Qwen3.5-0.8B/2B`: int8 / gpu composite; **no Qualcomm/SM8750 file** | 5 (CPU/GPU only for these) |
| ExecuTorch QNN `org.pytorch:executorch-android-qnn:1.5.1` | yes (Maven Central, lastUpdated 2026-10-01) | depends on `qnn-runtime` | BSD-3 (POM) + QTI | `.pte`, per SoC | no | README model list: Llama 3.2, Gemma 2/3/4, Qwen2.5, **Qwen3** 0.6B/1.7B, Phi4-mini, SmolLM, Granite, GLM, InternVL3: **no LFM, no Qwen3.5**; hybrid export "1-4 hours" | 6 |
| MNN 3.6.2 Hexagon backend | source (sparse clone `40ad7e34`, 2026-11-17): `source/backend/hexagon/` + `htp-ops-lib` | own HVX/HMX kernels over FastRPC, no QNN | Apache-2.0 | `.mnn` (own converter; Qwen3.5 export documented) | no | LFM3: not documented; docs: needs FP16 HMX, DSP lib built with Hexagon SDK ("register a Qualcomm account" in their guide; the CE tarball would likely do) | 3 (credible second engine, but a second model format and pipeline) |

Ranking rationale: only paths 1 and 2 accept the GGUFs the hub downloads (including future LoRA fine-tunes quantized
on our CPU server) and run both target architectures on the HTP. 2 is the same code as 1 packaged by Qualcomm, so it is
a fallback against *our build* failing, not against the HTP path failing.

---

## 5. Host measurements (deliverable 4) — x86_64 CPU, NOT phone numbers

HOST_RESULTS_PLACEHOLDER

---

## 6. JNI surface and Gradle packaging (deliverable 5)

**C ABI** (`native/llama/include/murmur_llm.h`, implemented in `src/murmur_llm.cpp`, exercised by `murmur-llm-bench`):
`mllm_global_init`, `mllm_devices`, `mllm_load`, `mllm_prefix_set` (warm-up: prefill + snapshot), `mllm_prefix_save/load`,
`mllm_generate` (streaming callback, stop strings, returns timing stats), `mllm_cancel` (any thread), `mllm_bench`,
`mllm_tokenize_count`, `mllm_last_error`, `mllm_unload`.

**JNI sketch** (`native/llama/include/murmur_llm_jni.h`): one `external fun` per C call on `object LlmNative`,
streaming through `fun interface TokenSink { fun onPiece(utf8: ByteArray): Boolean }`, stats returned in `DoubleArray`s.

Threading and cancellation (from the code, VERIFIED by reading; cancel latency on HTP UNVERIFIED-ON-DEVICE):
- One engine thread owns the engine (single-thread executor in the `:llm` service). `llama_context` is not thread-safe; the C layer does not lock. Only `cancel()` may come from another thread (atomic flag).
- `generate()` calls the sink on the engine thread for each complete UTF-8 piece (never splits a code point; text that could be the start of a stop string is held back). Return `false` to stop.
- Cancel is polled before every prefill chunk (`n_batch` = 512 tokens) and every decoded token, and through ggml's abort callback, which **only the CPU backend honours**. Worst case: CPU = one graph node; NPU/GPU = one `llama_process` call (one 512-token chunk or one token).
- `init()` is process-wide and irreversible (env vars + `dlopen` of backends). Release = `unload()` + process exit.

**Gradle: ship the script's prebuilt `.so` set (D2).** The app module adds `out/jniLibs` as a `jniLibs` source dir,
sets `packaging.jniLibs.useLegacyPackaging = true`, and compiles only the JNI shim (its own small CMake project,
`libmurmur_llm.so` as an IMPORTED library + `murmur_llm.h`). Evidence:

| Build path | What it builds | Clean | Incremental / no-op | Source |
|---|---|---:|---:|---|
| `build_android.sh npu` | arm64: 7 CPU variants + OpenCL + Hexagon host lib + 5 DSP skels | 1,082 s (load 50-72); 359 s + 97 s skels (load 15-40) | 2 s | `work/clean-npu-rebuild.log`, `work/incremental-rebuild.log` |
| `build_android.sh cpu-arm64` / `cpu-x86_64` | CPU only, all variants | 416 s / 423 s | 2 s | `work/cpu-builds.log` |
| AGP 9.5.1 / Gradle 9.9.1 `externalNativeBuild` + CMake FetchContent (pinned 39 MB archive) | arm64 CPU only, 2 CPU variants, `:llm:assembleRelease` | 397 s (load ~28) | 7 s (no-op) | `.cache/spikes/llm-npu/gradle-probe/probe.log` |

Why not Gradle-built llama.cpp:
- The NPU build needs `HEXAGON_SDK_ROOT` (3.3 GB extracted SDK, hexagon-clang); no Gradle machine has it, and without it the APK has no `libggml-htp-v79.so`.
- AGP builds per ABI and per build type (debug + release = 2x, x86_64 adds another 2x) and re-runs CMake configure when anything in `externalNativeBuild` changes; the script builds once and the app build stays seconds.
- Two real failures of the AGP path, found by the probe (both now handled):
  1. **NDK r30 clang 21.0.0 aborts** (`malloc(): invalid size (unsorted)` in the AArch64 Assembly Printer, function `ggml_gemm_q8_0_4x8_q8_0`) compiling `ggml-cpu/arch/arm/repack.cpp` for the `android_armv9.0_1` variant (`-march=armv8.6-a+dotprod+fp16+i8mm+sve2`) **with full `-g`**, which AGP's default `RelWithDebInfo` passes. Reproduced 3/3 on the single compile command; `-g0` and `-gline-tables-only` compile (rc=0). The script builds `Release` (no `-g`) and never hit it. Fix in `native/llama/CMakeLists.txt`: for Debug/RelWithDebInfo on aarch64 append `-gline-tables-only` (clang: last `-g*` wins; compile command now `-g -O2 -g -gline-tables-only`, rc=0).
  2. **AGP packaged no CPU backend.** With `targets += listOf("murmur_llm", "ggml")` the AAR held `libllama/libggml/libggml-base/libmurmur_llm/libc++_shared` but no `libggml-cpu-*.so`: AGP packages the listed targets plus their link-time deps, and ggml backends are `MODULE` libraries loaded with `dlopen`. At runtime that is "no CPU backend". Listing `ggml-cpu-android_armv8.0_1`, `ggml-cpu-android_armv8.6_1` as targets fixed it (AAR now 7 libs, 3,063,679 B). Also note llama.cpp's top-level CMake forces `CMAKE_{RUNTIME,LIBRARY}_OUTPUT_DIRECTORY=${CMAKE_BINARY_DIR}/bin`, so outputs do not land in AGP's `obj/` dir; AGP still finds them via the CMake file API.

If someone still wants the Gradle path (CPU-only CI, no Qualcomm SDK), the working module config is the probe's
`native/llama/gradle/llm-cpu-only.build.gradle.kts` (copied from the probe that produced the numbers above).

---

## 7. Device smoke test (deliverable 6): `tools/device/llm_npu_smoke.sh`

Run with the phone on USB (debugging on): `native/llama/build_android.sh npu` (or reuse `.cache/spikes/llm-npu/work/out/npu`,
picked automatically), then `tools/device/llm_npu_smoke.sh` (`--quick` ~5 min; default pushes LFM3 + Qwen3.5-0.8B,
`--model X.gguf` to choose, `--extended` adds power modes). It records device facts + vendor FastRPC/OpenCL visibility
(`public.libraries.txt`, `/dev/fastrpc-*`, Samsung DSP dir), pushes to `/data/local/tmp/murmur-llm`, lists ggml devices,
runs `llama-bench` pp512/tg64 on CPU (4 and 6 threads), HTP0 and OpenCL, runs the app flow (prefix snapshot -> TTFT ->
decode) on CPU and NPU, dumps NPU op acceptance per op, and thermal status before/after. Report:
`.cache/device-reports/llm_npu_smoke-<ts>/report.md` + `report.json`; each failing step is recorded and the next runs.

Fixed in this session: the script ran upstream `llama-bench` from `/data/local/tmp/murmur-llm` with the backends in
`lib/`. `ggml_backend_load_all()` searches only the executable's dir and the cwd (`ggml-backend-reg.cpp:486-505`), so
it would load no backend, `--list-devices` would show no HTP0, and every NPU step would be skipped. The host run of
the same layout failed with `llama_bench: error: CPU backend is not loaded` (`run1-highload/*.llama-bench.stderr.log`);
with cwd = `lib/` it prints `load_backend: loaded CPU backend from …/lib/libggml-cpu-icelake.so`. All llama-bench calls
now run from `lib/`. `murmur-llm-bench` passes `--backend-dir` and was not affected. The script itself was only
syntax-checked (`bash -n`): no device here.

---

## 8. Claims from phase-1 docs

N = `docs/research/npu-runtime.md`, L = `docs/research/llm-models.md`. "Source" = read at b12520 in
`.cache/spikes/llm-npu/work/llama.cpp`.

| # | Claim (where) | Status | Evidence |
|---|---|---|---|
| 1 | Fold7 NPU = Hexagon HTP **v79** (N §1) | UNVERIFIABLE-HERE | needs the phone; backend auto-detects the arch via `DSPRPC_GET_DSP_INFO` and we ship the v79 skel; smoke `devices.txt`/verbose log prints it |
| 2 | Plan A = upstream llama.cpp + `ggml-hexagon`, one GGUF Q4_0 for NPU/GPU/CPU (N §0.2) | VERIFIED (build) / UNVERIFIED-ON-DEVICE (run) | `build_android.sh npu` produced `libggml-hexagon.so` + `libggml-htp-v79.so` + `libggml-opencl.so` + CPU variants from one tree; same GGUF loads on host CPU |
| 3 | NPU prefill "5–14x" CPU, decode ≈ CPU, slower for Qwen3.5 (N §0.2) | REFUTED as a range | AI Hub `perf.yaml` v0.66.0, S25, ctx 512: LFM3 2596.9/368.4 = **7.0x**, LFM2.5-1.2B 6.6x, but Qwen3.5-0.8B 979.8/569.0 = **1.7x**, Qwen3.5-2B 2.3x. Decode part VERIFIED: NPU 40.9 vs CPU 53.4 (LFM3), 42.2 vs 83.8 (Qwen3.5-0.8B) |
| 4 | GenieX `geniex-android:0.10.0` wraps the same llama.cpp-Hexagon path + Genie/QAIRT (N §0.2) | VERIFIED | AAR unzipped: `libggml-hexagon.so`, `libggml-htp-v73…v85.so`, `libgeniex_plugin_llama_cpp.so`, `libgeniex_plugin_qairt.so`, QNN HTP libs |
| 5 | "4-bit" on this NPU = weights only, no W4A4 (N §0.5, §3) | VERIFIED (llama.cpp path) | `matmul-ops.c:812-830`: activations -> Q8_0/Q8_1 (HVX) or F16 (HMX) |
| 6 | Do not use Q4_K_M: "some tensors fall back to CPU" (N §0.5, §3; L §7 "K-quants run on GPU/CPU") | REFUTED at b12520 source | `htp_is_tiled_weight_type` includes Q2_K…Q6_K; MUL_MAT and GET_ROWS solvers accept them; Q4_K uses the Q4_1 tile kernel. (GenieX's bundled, older llama.cpp may differ.) Speed vs Q4_0 UNVERIFIED-ON-DEVICE |
| 7 | Supported HTP weight types list (N §3) | VERIFIED | plus IQ2_XXS, F16, F32, BF16 |
| 8 | Decode = HVX W4A8, prefill = HMX FP16 (N §3) | VERIFIED with correction | both kernel families exist; selection is per op by `htp_matmul_is_hmx_eligible` + VTCM fit, not by phase |
| 9 | Android 17 apps must declare `android.hardware.npu` (N §1) | VERIFIED | developer.android.com 17 summary quote; `PackageManager.FEATURE_NEURAL_PROCESSING_UNIT = "android.hardware.npu"`, API 37. Samsung enforcement UNVERIFIED-ON-DEVICE |
| 10 | GenieX v0.11.0 on GitHub, AAR 0.10.0 (2026-10-28) (N §2.1) | VERIFIED | `git ls-remote --tags qualcomm/GenieX`: `v0.11.0`; Maven `maven-metadata.xml`: latest 0.10.0, lastUpdated 20261028002833 |
| 11 | ggml-hexagon "experimental"; supports v73/v75/v79/v81 (+v85 only in GenieX) (N §2.1) | VERIFIED / REFUTED (v85) | log string `Hexagon backend (experimental)`; upstream b12520 builds **v85 too** (`libggml-htp-v85.so`) |
| 12 | Build **needs** Docker image `ghcr.io/snapdragon-toolchain/arm64-android:v0.8` (NDK r28b + Hexagon SDK 6.7) (N §2.1) | REFUTED (need) | built without Docker: public SDK tarball + local NDK r30. Image exists (tags to v0.8, 2.46 GB compressed); its Dockerfile at HEAD uses NDK `r29`; NDK of the v0.8 image not checked (not pulled) |
| 13 | DSP skel redistribution terms `[U]` (N §2.1, §2.3) | UNVERIFIABLE-HERE | the trimmed SDK tarball contains no SDK license (only `tools/HEXAGON_Tools/21.0.04/NOTICE.txt`, which defers to "your license from Qualcomm"); the full SDK license needs a Qualcomm account |
| 14 | All our LLM ops supported incl. `SSM_CONV`, `GATED_DELTA_NET` (N §2.1) | VERIFIED (source) / UNVERIFIED-ON-DEVICE | §3.1: every op maps to an HTP kernel; GDN needs `S_v ≤ 128`, Qwen3.5 has exactly 128 |
| 15 | LiteRT-LM: no Qualcomm NPU file for LFM/Qwen3.5 (N §2.1, §4.2; L §7) | VERIFIED | HF API siblings listed in §4 |
| 16 | ExecuTorch QNN: Maven 1.5.1; no LFM/Qwen3.5 LLM recipe (N §2.1) | VERIFIED | `maven-metadata.xml`; `examples/qualcomm/oss_scripts/llama/README.md` model list |
| 17 | MNN 3.6.2 has a Hexagon backend (N §2.1) | VERIFIED | tags `3.6.2`; `source/backend/hexagon/` in clone `40ad7e34`; docs: FP16 HMX, Hexagon SDK for the DSP lib. "INT4 weights / FP16 activations" not checked |
| 18 | Samsung DSP libs at `/vendor/lib64/rfs/dsp/snap`; set `ADSP_LIBRARY_PATH` before first dlopen (N §2.2, §7.2) | not needed for llama.cpp (source) / UNVERIFIED-ON-DEVICE | ggml-hexagon opens its skel by absolute `file:///` URI first, env var only as last fallback (§3.4); relevant for QNN/ASR |
| 19 | `useLegacyPackaging = true` so `.so` files exist on disk (N §2.2) | VERIFIED (required by source) | `htp_lib_dir()` uses `dladdr` + `fs::is_directory`; GenieX sample `build.gradle`: `jniLibs.useLegacyPackaging = true` |
| 20 | Manifest `uses-native-library libcdsprpc.so` (N §2.2, §7.2) | VERIFIED | `libggml-hexagon.so` dlopens `libcdsprpc.so`; GenieX AAR manifest declares libadsprpc/libcdsprpc/libOpenCL `required="false"` |
| 21 | `.so` files 16 KB aligned (N §2.2) | VERIFIED for ours | every AArch64 lib in `MANIFEST.txt`: LOAD align `0x4000`; DSP ELF `0x1000` (not loaded by bionic) |
| 22 | AI Hub S25 table (N §4.1; L §5) | VERIFIED | re-fetched `perf.yaml` v0.66.0 for lfm3_1_7b, qwen3_5_0_8b, qwen3_5_2b, qwen3_1_7b: numbers match; runtime `RUNTIME_GENIEX_LLAMACPP`, `PRECISION_Q4_0`, geniex v0.10.0 |
| 23 | No Genie/QNN bundle for LFM3 / Qwen3.5 (N §4.2; L §7) | VERIFIED | `release_assets.json`: only `q4_0/universal_assets/geniex_llamacpp` -> GGUF URL |
| 24 | HTP session ~3.5 GB VA window; `mmap_support = false`; knobs; default `burst` (N §4.4) | VERIFIED | `HTP_OP_MAX_VMEM_DEFAULT 3355443200u` (v75+); `get_props` caps; `getenv` list; `opt_power_mode = HTP_POWER_MODE_BURST` |
| 25 | Output libs `htp-v73/v75/v79/v81` + `libggml-cpu.so` (N §4.4, §7.2) | VERIFIED with correction | b12520 also builds v85; with `GGML_BACKEND_DL` the CPU backend is several `libggml-cpu-android_armv*.so` |
| 26 | llama.cpp-Hexagon does not use QNN (N §6) | VERIFIED | NEEDED list; 0 `qnn` strings in `libggml-hexagon.so` |
| 27 | QNN PD cannot be re-acquired in-process; kill the process (N §7.1, §7.4) | UNVERIFIED-ON-DEVICE for ggml-htp | not testable without a DSP; the ggml backend registry and `mllm_global_init` are process-wide, so the design already unloads by process exit |
| 28 | LFM3 / Qwen3.5 GGUF file sizes (L §1, §3) | VERIFIED | `ls -l`: 1,049,615,680 / 507,154,688 / 1,214,873,856 |
| 29 | "Q4_0 is the format that lands on the Hexagon NPU" (L §1) | VERIFIED, not exclusive | Q4_0 is a tiled type; so are Q4_K…Q8_0 |
| 30 | Qwen3.5's NPU path is weak, CPU-first (L §1) | VERIFIED (Qualcomm data) | see #3 |
| 31 | Templates: LFM3 BOS 124894 / EOS 124900, gen prefix `…<think></think>`; Qwen3.5 no BOS, `<think>\n\n</think>\n\n`, stop `<|im_end|>` (L §4) | VERIFIED | GGUF metadata + embedded templates (§3.5); LFM3 thinks by default (`default(true)`) |
| 32 | LFM3 `enable_thinking=False` "can be inconsistent" (L §1) | UNVERIFIABLE-HERE (quote not re-fetched) | on host, 0 of all runs emitted a `<think>` token with the off prefix (§5.3) |
| 33 | "DeltaNet ops presumably fall back off the HTP" (L §5 obs. 2) | REFUTED (source) | `GATED_DELTA_NET` has an HTP kernel and Qwen3.5 satisfies its constraints; the slowness is kernel speed. Exact cause UNVERIFIED-ON-DEVICE (smoke step 5) |
| 34 | "Weights … are memory-mapped" (L §5 RAM) | REFUTED for NPU | `mmap_support = false`; weights are copied into rpcmem. True for the CPU path |
| 35 | KV for hybrids ≈ 12 KB/token (Qwen3.5, 6 attn layers) (L §5, §6) | VERIFIED | GGUF: 6 attn layers x 2 kv heads x 256 x 2 (K,V) x 2 B = 12 KB/token; measured snapshot 34,028,476 B at 1,123 tokens = 13.8 MB KV + ~20 MB DeltaNet/conv state. LFM3: 17,347,640 B at 1,145 tokens |
| 36 | In-process prefix cache via `llama_state_seq_get_data/set_data` (L §6) | VERIFIED | §5.1; greedy outputs identical to the uncached run for all three models |
| 37 | GenieX latest AAR 89,956,760 B (L §7) | UNVERIFIED | that size is for v0.11.0 (GitHub); the Maven 0.10.0 AAR is 89,045,966 B |
| 38 | Expected resident footprint 1.0–1.3 GB LFM3, ~1.0 GB Qwen3.5-0.8B (L §5) | see §5 (host CPU, mmap) | phone NPU footprint UNVERIFIED-ON-DEVICE |

---

## 9. What only the phone can answer (UNVERIFIED-ON-DEVICE), all measured by the smoke script

1. HTP0 registers on One UI 9 (FastRPC visible to a shell/app, unsigned PD allowed, `libcdsprpc.so` in `public.libraries.txt`).
2. Actual op acceptance for `qwen35` / `lfm3` (FA head dim 256 VTCM fit is the main doubt) and the number of graph splits.
3. NPU vs CPU vs GPU pp/tg per model, and app-flow TTFT with the prefix snapshot; whether Qualcomm's S25 ratios hold.
4. Cold load time on NPU (weights are repacked into rpcmem; no mmap) and peak memory (`VmHWM` misses DSP-mapped rpcmem, so the bench also prints the system `MemAvailable` drop).
5. Which CPU variant the loader picks on Oryon; thermal behaviour over repeated runs.
6. Not covered by the script (needs the app): `android.hardware.npu` enforcement at targetSdk 37, `useLegacyPackaging` path resolution inside an installed APK, concurrent QNN (ASR) + ggml-htp PDs on the cDSP.

---

## 10. Needs from owner, risks

NEEDS_PLACEHOLDER
