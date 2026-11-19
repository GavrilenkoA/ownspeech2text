# native/llama: the post-processing LLM runtime

llama.cpp at a pinned commit plus `libmurmur_llm.so` (C ABI, `include/murmur_llm.h`), built for
arm64-v8a with the Hexagon NPU backend, for arm64-v8a CPU-only, and for x86_64 Android (emulator).
Decisions and evidence: `docs/research/verify/llm-npu.md`.

| File | Purpose |
|---|---|
| `versions.env` | the only place versions live: llama.cpp commit, Hexagon SDK URL + sha256, OpenCL sources, NDK/CMake |
| `build_android.sh` | reproducible build; downloads everything it needs from public URLs (no login) |
| `CMakeLists.txt` | llama.cpp via `add_subdirectory` (or FetchContent at the pinned commit) + `murmur_llm` + `murmur-llm-bench` |
| `include/murmur_llm.h` | the C ABI the JNI shim calls (load / prefix snapshot / generate+stream / cancel / bench / unload) |
| `src/murmur_llm.cpp` | its implementation on the new `llama_batch_ext` / `llama_process` API |
| `include/murmur_llm_jni.h`, `src/murmur_llm_jni.cpp` | the JNI shim of the `:llm` process (`libmurmur_llm_jni.so`, Kotlin `app.murmur.infer.llm.LlmNative`) |
| `src/murmur_llm_bench.cpp` | CLI that runs the app flow and prints JSON lines (used by `tools/device/llm_npu_smoke.sh`) |
| `tools/make_prompt.py` | renders a `prompts/` profile (default: the catalog's stock profile, default.v1 since ADR-085) and splits it into the cacheable prefix and the per-dictation suffix; `--assets` writes the `:infer:llm` instrumented-test prompts |
| `tools/tokenizer_check.py` | mandatory host test (ADR-045): catalog `prefixTokens` and prefix + suffix additivity for every shipped vocabulary |
| `patches/` | local patches (none at the pinned commit) |
| `gradle/llm-cpu-only.build.gradle.kts` | verified AGP module config for a CPU-only Gradle build (not the recommended path) |

## Build

```bash
native/llama/build_android.sh                 # npu + cpu-arm64 + cpu-x86_64, then the APK libs (fold7 profile)
native/llama/build_android.sh npu             # just the phone build
native/llama/build_android.sh cpu-x86_64 jnilibs   # emulator build + install into <nativeLibsDir>/llama/x86_64
HEXAGON_SDK_DIR=.cache/spikes/llm-npu/work/hexagon-sdk native/llama/build_android.sh npu   # reuse an extracted SDK
native/llama/build_android.sh host            # x86_64 Linux build for host measurements
PROFILE=all native/llama/build_android.sh     # jniLibs with every CPU variant and HTP v73..v85
```

Defaults: work dir `.cache/native-llama/` (gitignored), outputs in `.cache/native-llama/out/<variant>/{lib,bin}`
with `MANIFEST.txt` (size, LOAD alignment, NEEDED, sha256, build time) and `apk-libs.txt`. The APK set (including
`libmurmur_llm_jni.so`) is installed into `$MURMUR_NATIVE_LIBS_DIR/llama/{arm64-v8a,x86_64}/` (default
`<repo>/.cache/native/jniLibs`, the Gradle `-Pmurmur.nativeLibsDir` layout `:infer:llm` packages), with the installed
sizes and sha256 in `.cache/native-llama/out/jniLibs-<abi>.txt`.
Every cmake/ninja call runs through `tools/heavy` (own memory scope); `JOBS` defaults to 6.

What the `npu` variant needs, all public:
- Hexagon SDK 6.7.0.0 Community Edition, trimmed by Qualcomm's `snapdragon-toolchain` GitHub org
  (742 MB `.tar.xz`, 3.3 GB extracted, sha256 pinned). It is the same tarball their
  `ghcr.io/snapdragon-toolchain/arm64-android:v0.8` image installs; we use it directly with the local NDK r30
  instead of the image's NDK, so the app and the LLM libs share one NDK/libc++.
- Khronos OpenCL headers + ICD loader v2025.07.22 (the loader is only a link stub for `libggml-opencl.so`;
  at runtime the vendor `/vendor/lib64/libOpenCL.so` is used).
- KleidiAI v1.32.0 (fetched by ggml's own CMake, MD5-pinned upstream).

Measured on this server (shared; wall clock, `-j6`): `npu` clean 1,082 s at load 50-72 (359 s + 97 s for the skels at
load 15-40), `cpu-arm64` 416 s, `cpu-x86_64` 423 s, `host` about 5 min; incremental rebuild after a source edit 2 s per variant.
2026-11-19 (WP-10, load ~15-18): `cpu-x86_64 jnilibs` clean 203 s (incl. the llama.cpp fetch), `npu jnilibs` 294 s.

Regenerate anything deleted: re-run the script; it re-downloads into `$MURMUR_LLAMA_WORK/dl` and checks sha256.

## What an APK ships (arm64-v8a, `PROFILE=fold7`)

`libmurmur_llm_jni.so libmurmur_llm.so libllama.so libggml.so libggml-base.so libggml-cpu-android_armv8.0_1.so
libggml-cpu-android_armv8.6_1.so libggml-hexagon.so libggml-htp-v79.so libggml-opencl.so libc++_shared.so`
(sizes and sha256 in `out/jniLibs-arm64-v8a.txt`; `libggml-htp-v79.so` only with `-Pmurmur.htp=true`, ADR-048). `libggml-htp-v79.so` is a Hexagon DSP ELF, not an
ARM library: the cDSP loads it over FastRPC from a real file path.

## App integration rules (from reading the backend source; details in the verification doc)

1. `packaging.jniLibs.useLegacyPackaging = true` (extract native libs). `libggml-hexagon.so` finds the DSP
   library with `dladdr()` + `std::filesystem` on its own directory, and ggml's CPU-variant loader scans a
   directory. Both fail when libs stay inside the APK. GenieX's own sample app sets the same flag.
   Alternative if the toolchain owner wants to keep `false`: copy `libggml-htp-v79.so` to `filesDir` and pass
   that dir as `mllm_init_params.htp_lib_dir`, and load the CPU backend by explicit file name.
2. Manifest: `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>` and the same for
   `libOpenCL.so`; targetSdk 37 also requires `<uses-feature android:name="android.hardware.npu" android:required="false"/>`.
3. Run the LLM in its own process (`:llm`), call `mllm_global_init()` before anything touches ggml, kill the
   process to release the FastRPC session and rpcmem.
4. One worker thread owns the engine; only `mllm_cancel()` is thread-safe.

## Gradle

Used: prebuilt (ADR-008). `:infer:llm` adds `<murmur.nativeLibsDir>/llama` as a `jniLibs` source dir and sets
`packaging.jniLibs.useLegacyPackaging = true`; the JNI shim `libmurmur_llm_jni.so` is a target of this CMake project
(Android variants only) and ships with the rest, so Gradle compiles no native code. Text crosses JNI as
UTF-8 `byte[]`, not `jstring` (see `include/murmur_llm_jni.h`). Reasons: the Hexagon skels need the 3.3 GB Hexagon SDK;
AGP would rebuild llama.cpp per ABI and build type (clean CPU-only arm64 under AGP: 397 s; the script: 2 s incremental).

CPU-only Gradle path (CI without Qualcomm SDK): `gradle/llm-cpu-only.build.gradle.kts`, verified with AGP 9.5.1.
Two rules it encodes, both learned the hard way:
- list the CPU backend modules in `targets` (`ggml-cpu-android_armv8.0_1`, `ggml-cpu-android_armv8.6_1`; x86_64:
  `ggml-cpu-x64`, `ggml-cpu-ivybridge`, `ggml-cpu-haswell`). They are `dlopen`ed MODULEs, so AGP does not package them
  otherwise and the app ends up with no CPU backend;
- `CMakeLists.txt` appends `-gline-tables-only` for Debug/RelWithDebInfo on aarch64, because NDK r30 clang 21 crashes on
  `ggml-cpu/arch/arm/repack.cpp` (armv9.0 + SVE2 variant) with full `-g`.

## Tokenizer test (mandatory before any prompt, template or catalog change, ADR-045)

```bash
tools/heavy python3 -I native/llama/tools/tokenizer_check.py            # full corpus, ~12 min cold, seconds cached
tools/heavy python3 -I native/llama/tools/tokenizer_check.py --quick --assets infer/llm/src/androidTest/assets/prompts
```

It uses the host `llama-tokenize` (`<main>/.cache/ml/llm/bin/llama-b12503/`) and every catalog LLM's GGUF
(`tools/models/fetch`, or a byte-identical copy in `<main>/.cache/ml/`), prints the prefix-token table and the
additivity table, and exits 1 on any mismatch.

## Host checks (x86_64, not phone numbers)

```bash
B=.cache/native-llama/out/host
python3 native/llama/tools/make_prompt.py --template lfm3 --profile default.v1 --out-prefix /tmp/p.txt --out-suffix /tmp/s.txt
LD_LIBRARY_PATH=$B/lib tools/heavy $B/bin/murmur-llm-bench -m .cache/ml/llm/LFM3-1.7B-QAD-Q4_0.gguf --ctx 9216 \
  --backend-dir $B/lib --prefix-file /tmp/p.txt --suffix-file /tmp/s.txt -t 6 --modes ram,seqcp,none --reps 3 --print-output
MLLM_TRACE_TOKENS=1 ...   # prints the first generated token ids incl. special tokens (checks that no <think> is emitted)
```
