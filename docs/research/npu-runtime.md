# NPU runtime for Murmur on Galaxy Z Fold7 (ASR, LLM, OCR)

Research date: 2026-11-18. Role: Android / on-device inference. Scope: how Murmur runs every model on the Fold7 NPU from a sideloaded third-party APK, with a fallback that always works.

Markers used below:
- **[V]**: verified in this session from the linked primary source (vendor docs, GitHub source, Maven/HF API, model cards).
- **[I]**: my inference from verified facts.
- **[U]**: UNVERIFIED. I could not confirm it, and it must be checked before anyone relies on it.

---

## 0. Decisions / Recommendation (read this first)

1. **"Samsung NPU" on the Fold7 means the Qualcomm Hexagon NPU (HTP arch v79)** in the Snapdragon 8 Elite for Galaxy (SM8750-AC). Every region gets the same chip. Samsung does not offer third parties an NPU SDK. Every workable path goes through Qualcomm's stack: QNN/QAIRT HTP, or llama.cpp's own Hexagon kernels, reached over FastRPC to the cDSP.
2. **LLM (post-processing): Plan A is upstream llama.cpp with the `ggml-hexagon` backend, running GGUF `Q4_0`.** We build it ourselves from a pinned tag. The same file runs on the NPU (HTP), the GPU (OpenCL) and the CPU, so the fallback ladder needs no second artifact. Qualcomm measured this exact path on a Galaxy S25, which has the same SoC (§4.1). NPU prefill is 5–14× faster than CPU, which matters most for our long system prompt plus context. Decode speed is about the same as CPU, and slower for Qwen3.5. Ship `LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf`, `LFM3-1.7B-QAD-Q4_0.gguf`, `Qwen3.5-0.8B-Q4_0.gguf` and `Qwen3.5-2B-Q4_0.gguf`. Fine-tuned LoRA variants are merged and then quantized to Q4_0 on our CPU build server. No GPU is needed.
   **Plan B:** Qualcomm GenieX (`com.qualcomm.qti:geniex-android:0.10.0`). It wraps the same llama.cpp Hexagon path and adds Qualcomm's own Genie/QAIRT runtime. Use it if our own build of the Hexagon backend fails to come up on the device.
3. **ASR: Plan A is ONNX Runtime with Qualcomm's QNN execution-provider plugin (HTP), loading precompiled QNN context binaries built for `snapdragon-8-elite-for-galaxy`.**
   - Whisper (tiny…large-v3-turbo, float and w8a16): ready-made artifacts from Qualcomm AI Hub.
   - Parakeet-TDT-0.6B-v3: AI Hub ONNX, which we compile to a context binary.
   - GigaAM-v3 and T-one: we compile them ourselves (AI Hub or offline QAIRT).
   - CPU fallback: the same ORT runtime with the source ONNX, or sherpa-onnx for GigaAM/T-one.

   **Plan B / Qwen3-ASR:** LiteRT `CompiledModel` with the Qualcomm NPU runtime and the `litert-community` `*_Qualcomm_SM8750.tflite` files. This is the only ready NPU artifact for Qwen3-ASR. Risk is high: Google's own sample app lists NPU for Qwen3-ASR as unsupported (§5.2).
4. **OCR: EasyOCR (CRAFT detector + CRNN recognizer) on ORT + QNN EP.** Qualcomm's published export is English-only (`LANG_LIST = ["en"]`), so we re-export it with `["ru","en"]` via `qai-hub-models` and compile for SM8750. Measured on S25: detector 7.3 ms and recognizer 8.4 ms per crop at w8a8 on the NPU (§5.3).
5. **"Q4" and "NPU for all models" are compatible only for the LLM.** On this NPU, "4-bit" means 4-bit **weights** only. Activations stay 8/16-bit or fp16, and the NPU has no W4A4 path. Use Q4_0 (or QAD-Q4_0) GGUF for the LLM. **Do not use Q4_K_M** if the goal is to run on the NPU: Qualcomm's GenieX docs say it partly falls back to CPU. Use w8a16 or fp16 for ASR and w8a8 for OCR. See §3.
6. **Process model: every native ML runtime lives in a separate, killable Android process (`:asr`, `:llm`).** We release RAM by tearing the process down. Reasons: QnnHtp stays loaded for the life of the process, `ADSP_LIBRARY_PATH` applies to the whole process and must be set before the first `dlopen`, and a lost QNN protection domain cannot be re-acquired in the same process (§7).
7. **Validation without the phone:** use Qualcomm AI Hub Workbench, which has real Galaxy S25-family devices with the identical SoC; Qualcomm Device Cloud (Snapdragon 8 Elite Android sessions, APK install); and Samsung Remote Test Lab (real Galaxy devices over ADB; Fold7 availability [U]). Host tests use ORT/llama.cpp on CPU and the QAIRT x86 HTP emulator. What cannot be validated without a Fold7 is listed in §9.
8. **Before any of this goes in an APK:** read the QTI "AI Stack License" that ships with `qnn-runtime`. It allows redistribution in object code inside our app. Its export-control clause names the Russian Federation, among others. The owner's languages suggest a Russia nexus, so this needs a legal check (§2.3, Risks).

### Runtime per model class

| Class | Plan A (NPU) | Plan B (NPU) | GPU fallback | CPU fallback |
|---|---|---|---|---|
| LLM (LFM2.5, LFM3, Qwen3.5) | llama.cpp + ggml-hexagon, GGUF Q4_0 / QAD-Q4_0 | GenieX AAR (`llama_cpp` runtime) | llama.cpp ggml-opencl, same GGUF | llama.cpp CPU, same GGUF |
| ASR Whisper | ORT + QNN EP, AI Hub `precompiled_qnn_onnx` / context binary for 8 Elite for Galaxy | LiteRT + `whisper_tiny_30s_f32_Qualcomm_SM8750.tflite` (tiny only) | (none worth it) | ORT CPU, source ONNX (int8), or whisper.cpp |
| ASR Parakeet-TDT v3 | ORT + QNN EP, our context binary compiled from AI Hub ONNX | LiteRT + `parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8750.tflite` | LiteRT GPU | LiteRT CPU `..._i8_stateful.tflite`, or ORT/sherpa-onnx CPU |
| ASR GigaAM-v3 | ORT + QNN EP, **our compile** (fixed window) | LiteRT AOT (our compile) | n/a | sherpa-onnx / ORT CPU (`istupakov/gigaam-v3-onnx`) |
| ASR T-one | ORT + QNN EP, **our compile** (streaming chunk graph) | n/a | n/a | sherpa-onnx CPU (`sherpa-onnx-streaming-t-one-russian-2025-09-08`) |
| ASR Qwen3-ASR-0.6B | LiteRT + `qwen3_asr_0.6b_5s_f32_Qualcomm_SM8750.tflite` | own QNN compile (high effort) | LiteRT-LM Omni GPU | LiteRT-LM Omni CPU `qwen3_asr_0.6b_5s_i8.litertlm` |
| OCR | ORT + QNN EP, EasyOCR re-exported ru+en | LiteRT (AI Hub also emits tflite) | n/a | ORT CPU, same ONNX |

---

## 1. Target hardware and OS: established facts

| Fact | Value | Source |
|---|---|---|
| SoC | Snapdragon 8 Elite for Galaxy, "globally" (all regions) [V] | [Qualcomm press release, Jul 2025](https://www.qualcomm.com/news/releases/2025/07/the-most-powerful-snapdragon-mobile-platform-fuels-samsung-galax) |
| Part number | SM8750 family. Geekbench shows "QTI SM8750" for SM-F966B/U/N [V via report]. The "-AC" Galaxy bin comes from third-party spec tables [U] | [GSMArena](https://m.gsmarena.com/samsung_galaxy_z_fold7_appears_on_geekbench_with_snapdragon_8_elite-news-67993.php) |
| NPU | Qualcomm Hexagon, HTP arch **v79** [V: triangulated]. QAIRT device table lists SM8750 = V79 ([QAIRT 2.44 docs mirror](https://qairt-2-44-docs.pages.dev/), not an official domain). llama.cpp log on 8 Elite prints `Hexagon Arch version v79` ([llama.cpp Snapdragon docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md)). A third-party S25 Ultra report says V79 | — |
| RAM | 12 GB (256/512 GB), 16 GB only with 1 TB [V] | [SamMobile](https://www.sammobile.com/news/official-samsung-galaxy-z-fold-7-processor-ram-and-storage-options/) |
| OS today | **One UI 9 / Android 17** stable since late Sep 2026 (Korea), then the US in early Oct 2026. Some regions may still be on One UI 8.5 / Android 16, which went stable in May 2026 [V via news] | [gHacks 2026-09-29](https://www.ghacks.net/2026/09/29/samsung-rolls-out-stable-one-ui-9-to-the-galaxy-s25-z-fold-7-and-z-flip-7/), [Nokiamob 2026-10-05](https://nokiamob.net/2026/10/05/samsung-rolls-out-one-ui-9-and-android-17-to-galaxy-z-flip-7-and-fold-7-globally/), [SamMobile One UI 8.5](https://www.sammobile.com/news/galaxy-z-fold-7-stable-one-ui-8-5-update-reaches-more-regions/) |
| Samsung's own NPU SDK | Samsung Neural SDK "is no longer provided to third-party developers" [V]. Exynos tooling is irrelevant on a Snapdragon device [I] | [developer.samsung.com/neural](https://developer.samsung.com/neural) |

**What "Samsung NPU" means in practice [I]:** the Hexagon v79 HTP in the cDSP subsystem, driven from user space by Qualcomm libraries. Those are either QNN (`libQnnHtp.so` plus the `libQnnHtpV79Stub.so` CPU stub plus the `libQnnHtpV79Skel.so` DSP skeleton) or llama.cpp's `libggml-hexagon.so` plus the `libggml-htp-v79.so` DSP library. Both reach the DSP through FastRPC (`libcdsprpc.so` from `/vendor`). NNAPI is not a viable path.

**Android 17 platform change [V]:** "Apps that target Android 17 must declare the `android.hardware.npu` feature if they need to access NPUs" ([Android 17 changes summary](https://developer.android.com/about/versions/17/summary)). Declare `<uses-feature android:name="android.hardware.npu" android:required="false"/>` whatever targetSdk we pick. The exact element form is [I]. Enforcement on Samsung's build is [U].

---

## 2. Survey: every realistic way to reach the HTP from a sideloaded app

### 2.1 Summary table

| Stack | Latest (date) | License / redistributable in APK | Model format | Artifact production | SoC-bound artifact? | NPU for our models today | Verdict |
|---|---|---|---|---|---|---|---|
| **QAIRT / QNN direct** (HTP backend) | `com.qualcomm.qti:qnn-runtime:2.52.0` on Maven Central (2026-11-18) [V] | QTI "AI Stack License": object-code redistribution "as incorporated in Your software application" allowed; standalone redistribution not allowed; no reverse engineering; export-control clause [V] (LICENSE.pdf inside the AAR) | `.bin` context binary (SoC-specific), `.dlc` (portable, prepared on device), QNN model `.so` | QAIRT SDK on x86_64 Linux (converter, quantizer, `qnn-context-binary-generator`). The public zip download works without login (2.50.0.260828 reachable [V]). Or AI Hub cloud | Context binaries: yes (per HTP arch/SoC and QAIRT version). DLC: no, but needs `libQnnHtpPrepare.so` (~90 MB) and on-device prepare time | Whisper, Parakeet, EasyOCR, Zipformer from AI Hub | Foundation under ORT/LiteRT/GenieX. Do not program raw QNN ourselves |
| **Genie** (QAIRT GenAI runtime, LLM) | Ships in QAIRT. AI Hub bundles built with QAIRT 2.50 [V] | Same QTI license [I] | Genie bundle (context-binary shards + JSON) | AI Hub / `qai-hub-models export`. Quantizing your own weights to w4a16 needs a CUDA GPU with 40 GB VRAM for 3B [V] | Yes, per chipset | Qwen3-0.6B/1.7B/4B w4a16 for `snapdragon-8-elite-for-galaxy` exist. **No LFM, no Qwen3.5** [V] | Not for our LLM list |
| **GenieX** (formerly Nexa SDK) | v0.11.0 (2026-11-12) on GitHub; Android AAR `geniex-android:0.10.0` (2026-10-28) [V] | BSD-3 + Qualcomm Terms of Use. The AAR also bundles QNN libs (QTI license) [V] | GGUF (llama.cpp runtime) or AI Hub Genie bundles (qairt runtime) | GGUF: standard tooling; qairt: AI Hub | GGUF no; qairt yes | LFM2.5, LFM3, Qwen3.5 (GGUF Q4_0) measured on Galaxy S25 [V] | Plan B for LLM. "Developer preview" status [V] |
| **llama.cpp + ggml-hexagon** | Merged upstream (PR #16547), marked "experimental"; supports v73/v75/v79/v81 (+v85 in GenieX build); latest release tag v1.0.0 (2026-11-13) [V] | MIT. The DSP-side `libggml-htp-v79.so` is built with the Hexagon SDK (its license terms for redistribution: [U]) | GGUF | `convert_hf_to_gguf.py` + `llama-quantize` on x86 CPU. Build needs the Docker image `ghcr.io/snapdragon-toolchain/arm64-android:v0.8` (NDK r28b + Hexagon SDK 6.7) [V]; the build server has Docker and our user is in the `docker` group [V] | **No.** One GGUF runs everywhere; weights are repacked into HTP tiles at load | Yes, all our LLMs (ops incl. `SSM_CONV` for LFM, `GATED_DELTA_NET` for Qwen3.5) [V] | **Plan A for LLM** |
| **LiteRT** + Qualcomm NPU | LiteRT v2.3.2 (2026-10-26); `com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.2` (dispatch + compiler plugin only, Apache-2.0) [V]. QNN libs come separately | Apache-2.0 + QTI license for the QNN libs | `.tflite` (AOT per SoC, or JIT-compiled on device with cache) | `ai-edge-litert` / `litert-torch` on Ubuntu x86_64. AOT needs QAIRT [V docs] | AOT: yes (`_Qualcomm_SM8750`). JIT: no, but first run pays the compile cost | Parakeet-TDT v3, Qwen3-ASR-0.6B, whisper-tiny, moonshine-tiny SM8750 files exist [V] | Plan B for ASR; only NPU route for Qwen3-ASR |
| **LiteRT-LM** | v0.19.1 (2026-11-05), `com.google.ai.edge.litertlm:litertlm-android:0.19.1` [V] | Apache-2.0 | `.litertlm` | litert-torch | NPU builds are per SoC with a fixed context (e.g. 1280) [V] | NPU `.litertlm` only for Gemma3-1B, gemma-3-270m, FastVLM, embedding models. **LFM/Qwen3.5 litert-community repos have no Qualcomm file** [V] | Not for LLM on NPU. Useful as the CPU/GPU runner for Qwen3-ASR |
| **ONNX Runtime + QNN EP** | `onnxruntime-android:1.32.0` (2026-11-13). QNN EP is now a Qualcomm-maintained **plugin**: GitHub `onnxruntime/onnxruntime-qnn` v2.8.0 (2026-11-12), Maven `com.qualcomm.qti:onnxruntime-android-qnn:2.7.0` [V] | ORT MIT; QNN EP MIT; QNN libs QTI | ONNX; EPContext ONNX (embeds or references a QNN context binary) | AI Hub `precompiled_qnn_onnx`; or offline on x86_64 Linux with the `onnxruntime-qnn` wheel (`ep.context_enable=1`). Linux x86_64 = "AOT compilation" [V]. Flexible Context Binary = one artifact for several SoCs (QAIRT ≥ 2.48, x86_64 host only) [V] | EPContext: yes (or multi-SoC via FCB) | Whisper, Parakeet, EasyOCR, Zipformer on AI Hub with ORT 1.30 [V] | **Plan A for ASR and OCR** |
| **ExecuTorch + QNN backend** | v1.6.0 (2026-11-12): QNN 2.51, FCB, 8-bit KV I/O [V]. Maven `org.pytorch:executorch-android-qnn:1.5.1` [V] | BSD-style (GitHub API reports NOASSERTION; not re-verified) + QTI | `.pte` | x86_64 Linux AOT, `--compile_only` with no device [V]. LLM export in hybrid mode needs ≥80 GB RAM+swap [V] | Yes (`--soc_model`) | LLM list: Llama, Gemma, Qwen2.5/Qwen3, Phi4-mini, SmolLM, Granite. **No LFM, no Qwen3.5** [V] | Not chosen |
| **sherpa-onnx** | v2.0.1 [V] | Apache-2.0 | ONNX (CPU); QNN builds use `model.bin` / `libmodel.so` | QNN SDK + NDK build with `SHERPA_ONNX_ENABLE_QNN=ON` [V] | Prebuilt QNN models only for SM8850 per docs [V] | QNN models: SenseVoice/Paraformer/Zipformer (zh). GigaAM v3 and T-one only as CPU ONNX [V] | **CPU fallback** for GigaAM/T-one |
| **whisper.cpp** | v1.9.5 (2026-10-06) [V] | MIT | GGML | CPU | n/a | README documents AMD Ryzen AI NPU, Ascend, etc.; **no Hexagon support documented**. The synced ggml tree contains `ggml-hexagon`, but whether Whisper's ops run there is [U] | CPU fallback only |
| **MNN** | 3.6.2 (2026-10-29) [V] | Apache-2.0 | `.mnn` | MNN tools | — | Has `qnn` and a new `hexagon` backend (3.6.1, Jul 2026): "symmetric INT4 weights with FP16 activations" for LLM [V]. LFM3/Qwen3.5 support on that backend [U] | Plan C only |
| **MLC-LLM** | No release since `v0.1.dev0` [V] | Apache-2.0 | MLC | TVM | — | Android = "OpenCL on Adreno GPU"; no NPU [V] | No |
| **Liquid LEAP SDK** | **Deprecated**; "no longer receives new releases"; last Maven `ai.liquid.leap:leap-sdk-android:0.10.9` (2026-05-29) [V] | — | GGUF via llama.cpp | — | — | Liquid now points to llama.cpp directly [V] | No. Use llama.cpp |
| **Samsung-specific** | Neural SDK closed to third parties [V] | — | — | — | — | — | None |

Notes:
- **Nexa SDK is now GenieX [V/I].** The GitHub API for `NexaAI/nexa-sdk` returned the same repository data as `qualcomm/GenieX` (same stars, push time, v0.11.0 release), i.e. a redirect. GenieX's citation lists the Nexa founders as first authors.
- `microsoft/onnxruntime` returned HTTP 404 on github.com during this session [U: cause unknown]. The Maven artifacts and the QNN plugin repo are current, and the plugin's README still links that repo.

### 2.2 Can a third-party sideloaded app load the vendor libraries on a retail device?

Yes, per strong evidence. It has not been proven on a Fold7 running One UI 9.
- Qualcomm publishes Android AARs with all HTP stubs and skels on Maven Central (`qnn-runtime`, `geniex-android`, `onnxruntime-android-qnn`) [V]. The GenieX Android docs require only "a phone running Snapdragon 8 Elite (SM8750)" and `minSdk = 27` [V] ([install](https://geniex.aihub.qualcomm.com/en/run/android/install.md)).
- Qualcomm's AI Hub publishes NPU measurements from "Samsung Galaxy S25" devices for llama.cpp-Hexagon and QNN runtimes [V] (§4.1).
- An independent developer got a LiteRT-LM NPU model running in his own app on a retail **Galaxy S25 Ultra (SM8750-AC, V79, Android 16)** after six fixes [V, anecdotal] ([dev.to, Jul 2026](https://dev.to/jdshah/what-i-learned-getting-an-npu-to-actually-initialize-six-silent-failures-oa1)). The fixes that matter to us:
  1. Pair the runtime with exactly matching QNN libs (a version mismatch fails silently).
  2. **Samsung keeps DSP libs at `/vendor/lib64/rfs/dsp/snap/`**, so set `ADSP_LIBRARY_PATH` to include the app's `nativeLibraryDir` and the Samsung path in `Application.onCreate()`, before the first `dlopen`.
  3. Use `useLegacyPackaging = true` so the `.so` files exist on disk.
  4. After switching NPU→CPU→NPU in one process, the QNN protection domain could not be re-acquired (error 1002). The only fix was restarting the process.
- Manifest: Qualcomm's own Android tutorial adds `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>` [V] ([QNN Java tutorial](https://docs.qualcomm.com/bundle/publicresource/80-63442-10/topics/tutorial_java_application.md)).
- 16 KB page-size readiness: every `.so` I unpacked (`libQnnHtp`, `libQnnSystem`, `libQnnHtpV79Stub`, `libonnxruntime_providers_qnn`, `libLiteRtDispatch_Qualcomm`, `libgeniex`, `libggml-hexagon`, `libllama`) has PT_LOAD alignment 16384 [V].

### 2.3 Licensing summary for the APK

| Component | License | Ship in APK? |
|---|---|---|
| llama.cpp / ggml (incl. ggml-hexagon CPU side) | MIT [V] | Yes |
| `libggml-htp-v79.so` (DSP side, built with Hexagon SDK) | MIT source; Hexagon SDK runtime terms [U] | GenieX ships the same files on Maven Central, which is practical precedent [V] |
| QNN runtime libs (`libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so`) | QTI AI Stack License: "distribute and sublicense the Software solely in object code format and as incorporated in Your software application"; no standalone redistribution; no reverse engineering; **export-control section names Russia, Belarus, Crimea/DNR/LNR and others and requires a "not a military end-user" certification for persons in Russia/Belarus/China etc.** [V] | Yes, legally, if the export clause is satisfied → **legal check** |
| ORT, QNN EP plugin | MIT [V] | Yes |
| LiteRT, LiteRT NPU dispatch | Apache-2.0 [V] | Yes |
| Models | Parakeet CC-BY-4.0 (attribution), Whisper MIT/Apache, Qwen Apache-2.0, GigaAM MIT, T-one Apache-2.0, LFM "lfm1.0" (custom) [V from HF metadata] | Downloaded, not bundled |

---

## 3. "Q4" and "NPU for all models": how they interact

**What the HTP computes [V]:** the HMX matrix engine works on 32×32 **FP16** tiles. The HVX vector units do integer dot products. QNN/Genie expose quantization schemes as `wXaY`: weights X bits, activations Y bits.
- `w4a16`: "Weights int4, activations int16". `w4`: "Weights int4, activations float" ([GenieX precisions](https://geniex.aihub.qualcomm.com/en/models/supported.md)).
- ExecuTorch QNN: 8a8w (default), 16a16w, 16a8w, 16a4w, 16a4w_block. LPBQ block-wise 4-bit needs ≥V69 ([ExecuTorch QNN docs](https://docs.pytorch.org/executorch/stable/backends-qualcomm.html)).
- No source offers 4-bit activations. **4-bit on this NPU always means weight-only 4-bit** [V/I].

**llama.cpp Hexagon "Q4" [V, from source]:**
- GGUF Q4_0 (32-weight blocks, one fp16 scale) is repacked at load into 32×32 tiles (576 B/tile).
- Decode (matrix×vector): HVX integer kernels `tiled_vec_dot_q4_0_*` against Q8_0-quantized activations. Effectively **W4A8**.
- Prefill: weights are dequantized to FP16 in VTCM and fed to HMX. Effectively **W4A16 (fp16)**.
- Supported HTP weight types in master: Q4_0, Q4_1, Q5_0/1, Q8_0, Q2_K…Q6_K, IQ4_NL, MXFP4, NVFP4, Q1_0, Q2_0 ([htp-ops.h](https://github.com/ggml-org/llama.cpp/blob/master/ggml/src/ggml-hexagon/htp/htp-ops.h), [matmul-ops.c](https://github.com/ggml-org/llama.cpp/blob/master/ggml/src/ggml-hexagon/htp/matmul-ops.c)). Q4_K reuses the Q4_1 tile kernel.

**GGUF Q4_K_M** is a *mixed* recipe: most tensors Q4_K (256-weight super-blocks with 6-bit sub-scales), some Q6_K. On CPU/GPU it is the usual quality/size sweet spot. On the NPU:
- GenieX docs: "`Q4_K_M`, `Q5_K_M`, etc. → GPU / CPU. Not optimized for Hexagon NPU". Also: "Q4_K_M is a suboptimal quant for HTP; some tensors fall back to CPU. Use Q4_0 / Q8_0 for a clean NPU run" [V] ([run.md](https://github.com/qualcomm/GenieX/blob/main/notes/run.md)).
- Upstream master has since added K-quant tiles [V], so this is improving. Default to Q4_0 anyway.

**What to ship:**
- **LLM:** Q4_0 GGUF. Liquid publishes **`QAD-Q4_0`** (quantization-aware distilled) files, which are the best quality at Q4_0 size. Prefer them, and that is what Qualcomm's own LFM3 manifest points to [V].
  - Our fine-tuned variants will not have QAD. Plain Q4_0 after LoRA merge will lose some quality versus Q4_K_M [I]. The ML track should measure this. Q8_0 is the quality escape hatch (NPU-supported [V], about 2× size).
  - The model-hub UI label "Q4" maps to `Q4_0`.
- **ASR:** do not use 4-bit. Use `w8a16` (Whisper-*-Quantized) or float/fp16 (Parakeet `float`/`mixed_with_float`, Whisper float). Encoder accuracy is sensitive, and the NPU is already 10–100× faster than real time at 8/16-bit (§5).
- **OCR:** `w8a8` (AI Hub default for EasyOCR).
- **"NPU for all models"** is achievable at the artifact level. It is not always the fastest choice:
  - Qualcomm's S25 data shows **NPU decode ≤ CPU decode** for most of our LLMs (e.g. Qwen3.5-2B at 512 ctx: NPU 24.8 vs CPU 40.4 tok/s).
  - NPU prefill is far faster (e.g. LFM2.5-1.2B: NPU 3,749 vs CPU 567 tok/s).
  - For dictation cleanup (prompt ≈ 600–1,500 tokens, output ≈ 50–300 tokens), NPU wins on total latency for LFM2.5/LFM3 [I]. For Qwen3.5 the answer depends on prompt length, so the per-model Benchmark button should pick the default backend (open question for the owner).

---

## 4. LLM: concrete artifacts and numbers

### 4.1 Measured on Samsung Galaxy S25 (Snapdragon 8 Elite for Galaxy, same SoC as Fold7) by Qualcomm

Source: `perf.yaml` files in [qualcomm/ai-hub-models v0.66.0](https://github.com/qualcomm/ai-hub-models/tree/v0.66.0/src/qai_hub_models/models). The model cards on HF (`qualcomm/<model>`) show the same numbers without the compute-unit column. tg = decode tok/s, pp = prefill tok/s, TTFT range in ms [V].

| Model | Runtime / precision | Unit | ctx | tg | pp | TTFT ms |
|---|---|---|---|---|---|---|
| LFM2.5-1.2B-Instruct | GenieX llama.cpp Q4_0 (geniex v0.8.0) | **NPU** | 512 | 57.0 | 3,749 | 34–137 |
| | | GPU | 512 | 72.3 | 1,111 | 115–461 |
| | | CPU | 512 | 80.4 | 567 | 226–903 |
| | | NPU | 4096 | 46.2 | 3,100 | 41–1,322 |
| LFM3-1.7B | llama.cpp Q4_0 (geniex v0.10.0) | **NPU** | 512 | 40.9 | 2,597 | 49–198 |
| | | CPU | 512 | 53.4 | 368 | 348–1,390 |
| Qwen3.5-0.8B | llama.cpp Q4_0 | **NPU** | 512 | 42.2 | 980 | 131–524 |
| | | CPU | 512 | 83.8 | 569 | 225–901 |
| Qwen3.5-2B | llama.cpp Q4_0 | **NPU** | 512 | 24.8 | 644 | 199–796 |
| | | CPU | 512 | 40.4 | 286 | 448–1,792 |
| Qwen3-1.7B (reference, not in hub list) | Genie w4a16 | NPU | 4096 | 56.7 | 1,223 | 29–942 |
| | GenieX qairt w4a16 | NPU | 512 | 40.2 | 3,273 | 39–156 |

Observations [I]:
1. Qwen3.5's gated-delta-net layers get much less NPU prefill benefit (980 tok/s) than LFM's conv+attention (3,749 tok/s).
2. Genie/QAIRT w4a16 is not faster than llama.cpp-Hexagon in prefill. It is faster in decode only at long context.
3. These are steady-state numbers. Cold load is not reported anywhere (see §8).

### 4.2 Artifacts (exact repos/files seen via HF API)

| Model | NPU-ready artifact for SM8750 | File (size) | Notes |
|---|---|---|---|
| LFM2.5-1.2B-Instruct | `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` (Qualcomm's manifest `qualcomm/LFM2.5-1.2B-Instruct/release_assets.json` points to the Q4_0 file) | `LFM2.5-1.2B-Instruct-Q4_0.gguf` (696 MB), `LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf` (696 MB), `…Q4_K_M.gguf` (731 MB), `…Q8_0.gguf` (1,246 MB) | License `lfm1.0` |
| LFM3-1.7B | `LiquidAI/LFM3-1.7B-GGUF` (Qualcomm manifest → QAD-Q4_0) | `LFM3-1.7B-QAD-Q4_0.gguf` (1,050 MB), `LFM3-1.7B-Q4_K_M.gguf` (1,100 MB), `…Q8_0.gguf` (1,846 MB) | No Genie/QNN bundle exists |
| Qwen3.5-0.8B | `unsloth/Qwen3.5-0.8B-GGUF` (Qualcomm manifest) | `Qwen3.5-0.8B-Q4_0.gguf` (507 MB) | Apache-2.0 |
| Qwen3.5-2B | `unsloth/Qwen3.5-2B-GGUF` (Qualcomm manifest) | `Qwen3.5-2B-Q4_0.gguf` (1,215 MB) | Apache-2.0 |
| "Qwen3.6 small" | **Does not exist.** `Qwen/Qwen3.6-*` = 27B, 35B-A3B (+FP8) only [V] | — | Owner question |
| LiteRT variants | `litert-community/LFM2.5-1.2B-Instruct`, `LFM3-1.7B-LiteRT`, `Qwen3.5-0.8B`, `Qwen3.5-2B` exist, but **no file with Qualcomm/SM8750 in its name** [V] | — | CPU/GPU only |

### 4.3 Conversion path for our fine-tuned LLMs (LoRA on LFM3 / Qwen3.5)

1. Merge the LoRA into the base weights (HF transformers/PEFT). The GPU pod is used only for training.
2. `convert_hf_to_gguf.py` → F16 GGUF, then `llama-quantize … Q4_0` (and Q8_0). This runs on our x86 CPU server and takes minutes [I].
3. Host check: llama.cpp CPU on x86 with the same chat template and greedy outputs on a fixed eval set, compared against the HF model.
4. Optional: AI Hub cannot profile llama.cpp. NPU numbers come only from a device (QDC/RTL/Fold7).

Effort: low. Risk: low-medium. The architectures are already supported upstream (Qualcomm measured LFM3 and Qwen3.5 on the NPU). Quality loss of plain Q4_0 without QAD is the open item.

The Genie/QAIRT alternative for our fine-tunes needs AIMET w4a16 quantization on a 40 GB+ CUDA GPU [V], a model architecture that `qai-hub-models` supports (no LFM/Qwen3.5 recipe exists [V]), and per-chipset compiles. Effort: high. Not recommended.

### 4.4 llama.cpp-Hexagon operational facts [V]

- An HTP session has a ~3.5 GB 32-bit VA window. Bigger models get dynamic map/unmap, which is irrelevant for ≤2B Q4_0.
- Weights live in shared DDR (rpcmem/FastRPC) and the backend reports `mmap_support = false`. The GGUF is read and repacked into DSP-mapped buffers at load. Example from the upstream doc on 8 Elite: Llama-3.2-1B Q4_0 used "HTP0 model buffer 504 MiB, Host 225 MiB" and "load time = 617.94 ms" ([docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md)).
- `GGML_HEXAGON_*` environment knobs exist: devices/sessions, NHVX, HOSTBUF, power mode, OPFILTER, VERBOSE, PROFILE. GenieX adds a power-mode setter patch, and its default is `burst`.
- Building in Docker: `cmake --preset arm64-android-snapdragon-release` with `GGML_HEXAGON=ON GGML_OPENCL=ON GGML_OPENMP=OFF`. Output: `libggml-hexagon.so`, `libggml-htp-v73/v75/v79/v81.so`, `libggml-opencl.so`, `libggml-cpu.so`, `libggml-base.so`, `libggml.so`, `libllama.so`.

---

## 5. ASR and OCR: concrete artifacts and numbers

### 5.1 Measured on Galaxy S25 by Qualcomm AI Hub (QAIRT 2.50, all layers on NPU) [V]

| Model (AI Hub) | Precision | Component latency on NPU | Peak mem range | Implied RTF for 30 s audio [I] |
|---|---|---|---|---|
| Whisper-Small (multilingual) | float | enc 65.4 ms / 30 s window; dec 8.3 ms/token | enc ≤ 9 MB (ctx bin) | ~0.03 (with 100 tokens) |
| Whisper-Small-Quantized | w8a16 | enc 34.4 ms; dec 4.7 ms/token | ≤ 31 MB | ~0.017 |
| Whisper-Medium-Quantized | w8a16 | enc 97.4 ms; dec 13.4 ms/token | ≤ 36 MB | ~0.048 |
| Whisper-Large-V3-Turbo | float | enc 305 ms; dec 6.7 ms/token | ≤ 16 MB | ~0.033 |
| Whisper-Large-V3-Turbo-Quantized | w8a16 | enc 150.7 ms; dec 3.5 ms/token | ≤ 23 MB | ~0.017 |
| Parakeet-TDT-0.6B-v3 | float (QNN DLC) | enc 38.0 ms per **13.2 s** static window (`MAX_TIME_STEPS = 1320` frames); decoder 0.24 ms, joint 0.22 ms per step | enc up to 948 MB | ~0.005 |
| EasyOCR (en) | w8a8 (ONNX) | detector 7.3 ms (608×800); recognizer 8.4 ms per crop | det ≤ 157 MB, rec ≤ 719 MB | — |

The "peak memory" figures for context binaries are runtime working memory. The weights' on-device footprint is roughly the artifact size [I]. AI Hub Whisper exports use a static 30 s input (80×3000 mel for small, 128×3000 for large-v3-turbo) and **max 200 decoded tokens per window** [V], with MHA rewritten to SHA and linears rewritten as convs for the HTP. Sources: `perf.yaml` and model cards under [qualcomm on HF](https://huggingface.co/qualcomm).

### 5.2 Ready-made SM8750 artifacts per ASR family

| Family | Repo / asset (exact) | Size | Runtime | Status |
|---|---|---|---|---|
| Whisper tiny/base/small/medium/large-v3-turbo (float), small/medium/large-v3-turbo (w8a16) | `qualcomm/Whisper-*` → `release_assets.json` → chipset `qualcomm-snapdragon-8-elite-for-galaxy` with runtimes `precompiled_qnn_onnx`, `qnn_context_binary`, `voice_ai`. Example: `whisper_large_v3_turbo_quantized-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_for_galaxy.zip`, `whisper_small_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8_elite_for_galaxy.zip` | 824 MB / 285 MB / small float ctx-bin 572 MB [V HEAD] | ORT + QNN EP (precompiled ONNX), or raw QNN | **Ready**. Hosted on `qaihub-public-assets.s3.us-west-2.amazonaws.com`, **not on HF** (must be mirrored, see Requests) |
| Whisper tiny (LiteRT) | `litert-community/whisper-tiny` → `whisper_tiny_30s_f32_Qualcomm_SM8750.tflite` | 120 MB | LiteRT NPU | Ready. whisper-base/large-v3-turbo in litert-community have no NPU files [V] |
| Parakeet-TDT-0.6B-v3 (25 EU langs incl. ru, uk) | `qualcomm/Parakeet-TDT-0.6B-v3` → universal `parakeet_tdt-onnx-float.zip`, `parakeet_tdt-qnn_dlc-float.zip`, `…mixed_with_float` (W8A8 decoder/joint + fp16) | 2.27 GB each | ORT + QNN EP | Semi-ready: universal float graph. **We compile an SM8750 EPContext once** (AI Hub or offline) to avoid on-device prepare and halve the size [I] |
| Parakeet-TDT-0.6B-v3 (LiteRT) | `litert-community/parakeet-tdt-0.6b-v3` → `parakeet_tdt_0.6b_v3_5s_f32_Qualcomm_SM8750.tflite`, `parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8750.tflite` | 1,238 / 1,275 MB | LiteRT NPU | Ready. Google's sample app lists Parakeet-TDT NPU as verified on **Pixel 10** only, and Parakeet-CTC NPU on Galaxy S23/S24 ([litert-samples](https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition)) |
| Qwen3-ASR-0.6B (30 langs incl. ru) | `litert-community/Qwen3-ASR-0.6B` → `qwen3_asr_0.6b_5s_f32_Qualcomm_SM8750.tflite` | 1,897 MB | LiteRT NPU (standalone graph; host supplies mel + detokenization; 5 s windows; KV max 512) | Artifact exists, **but the same Google sample lists Qwen3-ASR as CPU-only** → high risk. CPU/GPU path: `qwen3_asr_0.6b_5s_i8.litertlm` (960 MB) with LiteRT-LM Omni. Note: HF safetensors metadata counts **0.94 B** params for "0.6B" [V] |
| Qwen3-ASR-1.7B | `litert-community/Qwen3-ASR-1.7B` → `Qwen3-ASR-1.7B.litertlm`, `Qwen3-ASR-1.7B-15s.litertlm` | 2.7 GB | LiteRT-LM CPU/GPU | No NPU artifact |
| GigaAM-v3 (ru) | None for NPU. CPU: `istupakov/gigaam-v3-onnx` (`v3_e2e_rnnt_encoder.onnx` 885 MB / `.int8.onnx` 225 MB, CTC variants), sherpa-onnx `sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2`; GGUF `handy-computer/gigaam-v3-e2e-rnnt-gguf` | — | — | **Conversion needed** |
| T-one (ru, streaming, 71.7 M params) | None for NPU. `t-tech/T-one` `model.onnx` (144 MB, Apache-2.0); sherpa-onnx `sherpa-onnx-streaming-t-one-russian-2025-09-08.tar.bz2` | — | — | **Conversion needed** |

### 5.3 OCR

`qualcomm/EasyOCR`: universal assets `easyocr-onnx-w8a8.zip` (38.6 MB), `easyocr-tflite-w8a8.zip`, `easyocr-onnx-float.zip`, `easyocr-qnn_dlc-float.zip` [V]. The export hardcodes `LANG_LIST = ["en"]`, so the recognizer is Latin-only. The model wrapper takes a `lang_list` parameter, which makes a ru+en re-export a supported configuration [V] ([model.py](https://github.com/qualcomm/ai-hub-models/blob/v0.66.0/src/qai_hub_models/models/easyocr/model.py)).

The recognizer needed an "Unrolled LSTM… to run on NPU via LiteRT" [V]. Under TFLite the recognizer ran **on CPU** (135 layers on CPU), but under ONNX/QNN it ran on the NPU (1,722 of 1,725 layers on NPU). That is another reason for ORT+QNN as Plan A [V].

### 5.4 Conversion paths where nothing is ready

| Model | Path | Effort | Main risks |
|---|---|---|---|
| Parakeet SM8750 ctx-bin | AI Hub `submit_compile_job` (ONNX → QNN context binary embedded in ONNX for "Samsung Galaxy S25 (Family)") or offline with the `onnxruntime-qnn` x86_64 wheel (`ep.context_enable=1`, `soc_model` = SM8750) | 0.5–1 day | QAIRT version coupling (see §6) |
| GigaAM-v3 (Conformer, RNNT/CTC) | Export encoder with a **fixed window** (e.g. 10–15 s) from the official repo or the `istupakov` ONNX; keep the RNNT decoder/joint as separate tiny graphs (same split as AI Hub's Parakeet); quantize w8a16 (AI Hub quantize job, GPU-backed in the cloud [V]) or run fp16; compile for SM8750 | 3–5 days | Relative-position/rotary attention ops, `Where`/masking with dynamic lengths, LayerNorm/Softmax precision at a16, CPU reference parity. Use the AI Hub Parakeet export code as a template [I] |
| T-one (streaming conformer CTC) | Export the chunk-step graph with explicit state tensors in/out (fixed 300 ms-class chunk), fp16 or w8a16 | 2–4 days | Many small graph invocations per second → FastRPC overhead may dominate. NPU may not beat CPU for a 72 M model [I]. Keep CPU as the default until benchmarked |
| EasyOCR ru+en | `qai-hub-models export easyocr` with `lang_list=["ru","en"]` (needs AI Hub token); w8a8 calibration on real screenshots | 1–2 days | Cyrillic recognizer accuracy at w8a8, fixed 608×800 detector input (letterbox the screenshot) |
| Qwen3-ASR on QNN (if LiteRT fails) | Encoder: static 5 s graph. Decoder: a 28-layer Qwen3 LM with KV cache, i.e. an LLM export (Genie-style or GGUF via llama.cpp if Qwen3-ASR gets ggml support [U]) | 2–3 weeks | Autoregressive decoder, KV cache I/O, tokenizer/prompt template, two graphs sharing state. Highest risk; defer |

General rules for any QNN compile:
- Static shapes only, no Loop/If [V] ([QNN EP doc](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)).
- MatMul on HTP supports uint8/uint16 pairs; float graphs run as fp16 (`enable_htp_fp16_precision` default 1) [V].
- Gather indices must be positive [V].
- Tokenizers and mel front-ends always stay on CPU in our own C++/Kotlin code [I].

---

## 6. Version coupling (the thing most likely to waste a week)

- AI Hub context binaries are tagged with the QAIRT version used (e.g. 2.50.0.260828221209) [V]. A user report: artifacts built with QAIRT 2.45 did not run on a 2.42 runtime [V, forum] ([Radxa forum](https://forum.radxa.com/t/qcs6490-radxa-q6a-qairt-sdk-version-mismatch-ai-hub-artifacts-2-45-x-incompatible-with-runtime-2-42-0-251225/30908)).
  - **Rule:** the on-device `libQnnHtp*.so` must be **≥** the QAIRT version every bundled context binary was compiled with. Pin one QAIRT runtime for the whole app (e.g. `qnn-runtime:2.52.0`) and compile everything with ≤ that version [I].
- ORT QNN EP 2.8.0 was "built and tested with QAIRT SDK 2.52.0" and ORT 1.29.0, compatible with ORT ≥ 1.24.1 [V] ([plugin doc](https://github.com/onnxruntime/onnxruntime-qnn/blob/main/docs/execution_providers/QNN-ExecutionProvider.md)).
- LiteRT's Qualcomm guides use QAIRT 2.42.0.251225 [V]. The S25 Ultra report failed until it used exactly that set [V]. **Do not load LiteRT-QNN and ORT-QNN in the same process.** Both want `libQnnHtp.so` (same soname), and "one runtime per process" applies [V GenieX run.md].
- llama.cpp-Hexagon does not use QNN at all. Its DSP library is `libggml-htp-v79.so`, so it has no conflict with QNN [V].
- AI Hub removed `--target_runtime qnn_context_binary` on 2026-09-14. Use `submit_compile_job` / `submit_compile_and_link_jobs` (and the ONNX-embedded form) [V] ([AI Hub release notes](https://workbench.aihub.qualcomm.com/docs/hub/release_notes.html)).

---

## 7. App architecture: native modules, JNI, load/unload

### 7.1 Processes

| Process | Hosts | Why separate |
|---|---|---|
| main (UI, bubble, accessibility, mic FGS) | No ML native libs | Must never be killed for memory |
| `:asr` | ORT + QNN EP + QNN libs: ASR sessions, OCR session | One QAIRT runtime per process; kill to free DSP mappings |
| `:llm` | llama.cpp (+ ggml-hexagon/opencl/cpu) | Different DSP library, large buffers, independent lifetime |
| `:asr_litert` (only if Qwen3-ASR or a LiteRT-only artifact is selected) | LiteRT + its QNN lib set | Avoids a QNN soname/version clash with `:asr` |

Each is a bound `Service` with `android:process`. Talk to it over AIDL. Audio frames and screenshots travel in `SharedMemory`/`ParcelFileDescriptor`, and results come back on `oneway` callbacks.

Concurrency of two cDSP sessions (QNN PD + llama.cpp PD) at the same time is [U]. It must be tested on device (§9). If it fails, serialize: ASR finishes → `:asr` releases its HTP session → LLM gets the NPU. The LLM can still pre-read its GGUF into RAM while ASR runs.

### 7.2 Native libraries (arm64-v8a only; `packaging.jniLibs.useLegacyPackaging = true`)

- `:llm`: `libmurmur_llm_jni.so`, `libllama.so`, `libggml.so`, `libggml-base.so`, `libggml-cpu.so`, `libggml-opencl.so`, `libggml-hexagon.so`, `libggml-htp-v79.so`. Add `libggml-htp-v81.so` only if we decide to support SM8850 devices; it's a cheap "other devices" win. All are built by us from one pinned llama.cpp tag in the snapdragon-toolchain Docker image.
- `:asr`:
  - `libonnxruntime.so` (from `com.microsoft.onnxruntime:onnxruntime-android`, pin the version the QNN EP was validated with: 1.29.0)
  - `libonnxruntime_providers_qnn.so` (`com.qualcomm.qti:onnxruntime-android-qnn`)
  - `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so` (from `com.qualcomm.qti:qnn-runtime`). Strip the other V* Stub/Skel files (~20 MB each) and **omit `libQnnHtpPrepare.so` (~90 MB)** as long as every graph ships as a precompiled context binary.
  - `libmurmur_asr_jni.so`: our C++ for log-mel, CTC/TDT/RNNT greedy and Whisper decode loops, tokenizers, OCR pre/post.
- CPU-only fallback for GigaAM/T-one: sherpa-onnx's JNI `.so` (it can share the same `libonnxruntime.so` if versions are aligned [U]; otherwise run it in its own process).
- Manifest:
  - `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`
  - `<uses-feature android:name="android.hardware.npu" android:required="false"/>`
  - Each ML process sets `ADSP_LIBRARY_PATH = "<nativeLibraryDir>;/vendor/lib64/rfs/dsp/snap;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp"` **in the service's process before the first native load**. The Samsung path comes from the S25 report; the other default paths are [I].
- Optional for Plan B: GenieX AAR (`geniex-android:0.10.0`, about 230 MB of uncompressed native libs [V]) only in `:llm`, replacing our own llama.cpp build.

### 7.3 JNI surface (C ABI behind thin Kotlin wrappers)

```
// LLM (:llm)
long   llm_load(String ggufPath, int backend /*NPU,GPU,CPU*/, int nCtx, int nThreads, String powerMode); // throws with error code
void   llm_generate(long h, String prompt, GenParams p, TokenCallback cb); // streaming, cancellable
void   llm_cancel(long h);
LlmStats llm_last_stats(long h);   // load_ms, ttft_ms, prefill_tps, decode_tps, n_prompt, n_gen, backend_used, ops_on_npu/total
void   llm_unload(long h);

// ASR (:asr)
long   asr_load(String manifestJson /*graphs, provider, chunk_sec, vocab*/);
void   asr_push_pcm(long h, short[] pcm16, int n);          // or via SharedMemory ring buffer
String asr_partial(long h);  String asr_finalize(long h);   // JSON: text, segments, timings
AsrStats asr_last_stats(long h);                            // load_ms, rtf, first_partial_ms, provider_per_graph
void   asr_unload(long h);

// OCR (:asr)
String ocr_run(String detPath, String recPath, HardwareBuffer bmp); // load→run→unload in one call (tiny models)
```

### 7.4 On-demand load/unload

1. **Bubble tap:** the main process starts the mic FGS and binds `:asr` and `:llm` with `BIND_AUTO_CREATE`. Each starts `load` immediately. Steps 4–5 of the brief run in parallel; with the serialization caveat above, the LLM goes to the HTP after ASR.
2. **t+0.5–1 s:** the accessibility screenshot goes to `:asr`, which runs `ocr_run` as a one-shot. OCR graphs are loaded and freed inside the call (≈ 40 MB w8a8).
3. **During speech:** feed PCM. With NPU RTFs of 0.005–0.05, VAD-chunked transcription during speech is essentially free [I]. Window limits per model: Whisper 30 s / ≤200 tokens, Parakeet 13.2 s (AI Hub) or 5 s (LiteRT), Qwen3-ASR 5 s, T-one native streaming.
4. **Stop:** finalize ASR → **unload ASR immediately** → LLM generate → insert/copy → unload LLM.
5. **Teardown:** each service calls `stopSelf()` after an idle grace period (default 30 s, setting 0–120 s, so a quick second dictation skips cold load). After unbind/stop, the process exits explicitly (`Process.killProcess(myPid())` in `onDestroy`). That releases ORT/QNN/ggml allocations, rpcmem/dma-buf DSP mappings and the FastRPC session, which an in-process `unload` does not reliably do (QnnHtp stays loaded; PD re-acquire issues) [V/I].
6. `onTrimMemory(TRIM_MEMORY_RUNNING_LOW+)` in an ML process → unload now. The main process holds no models, so nothing holds RAM while idle.
7. **Failure:** if NPU init fails (QNN error 1002, missing skel, timeout > N s), kill the process, mark `npu_failed[model, firmware_build]`, and respawn on the next rung of the ladder (§7.5). Never retry the NPU in the same process.

### 7.5 Fallback ladder (works from day one for every hub model)

Hub manifest per model: a list of artifacts, each `{runtime, unit, socConstraint (e.g. SM8750/htp v79), qairtVersion, url, sha256, size}`. The resolver walks:

1. **NPU artifact** whose `socConstraint` matches `Build.SOC_MODEL` (API 31+, reads `ro.soc.model` [I]) and a cached NPU probe passed (first launch: load a tiny graph in `:asr` and enumerate `HTP0` in `:llm`).
2. **GPU:** llama.cpp OpenCL (same GGUF); LiteRT GPU for Parakeet/Qwen3-ASR.
3. **CPU:** llama.cpp CPU (same GGUF); ORT CPU / sherpa-onnx with the int8 ONNX; LiteRT-LM CPU for Qwen3-ASR.

For LLMs there is one download. For ASR the CPU artifact is a separate (usually smaller int8) file, fetched lazily only when a fallback is actually needed. The UI shows which unit ran ("NPU", "NPU (partial)", "GPU", "CPU") from the runtime's own report.

---

## 8. Benchmark button: what and how to measure

All runs go through the production process and code path (same service, same JNI), never a separate harness.

| Class | Metric | How |
|---|---|---|
| All | **Load time (cold)** | Kill the ML process first (`am`-equivalent: unbind + kill), then measure from `bind` to `load()` return: wall clock split into process start, file read, backend init / context-binary deserialize / GGUF repack |
| All | **Load time (warm)** | Second load with the page cache warm |
| All | **Peak memory** | In the ML process: `VmHWM` from `/proc/self/status` (peak RSS) plus `Debug.getMemoryInfo()` total PSS before/after/peak (sampled every 100 ms on a thread). Also report system `ActivityManager.MemoryInfo.availMem` delta as a proxy for dma-buf/rpcmem DSP buffers, which may not appear in RSS [U] |
| All | **Unit actually used** | llama.cpp: per-device op counts from the scheduler (`GGML_HEXAGON_VERBOSE`-style supports-op dump, aggregated). ORT: session profiling node placement / EPContext node present. LiteRT: accelerator report. Show "NPU 100% / partial n%" |
| All | **Thermal / power state** | `PowerManager.getCurrentThermalStatus()` before/after; record the power mode (llama.cpp/GenieX default `burst`) |
| LLM | **TTFT** | Fixed prompt = real system prompt + a 300-word ru/en sample transcript + context (~1,000 tokens). Time from `generate` to the first token |
| LLM | **Prefill tok/s** | n_prompt / prefill time |
| LLM | **Decode tok/s** | 128 generated tokens, greedy, fixed seed, excluding the first token |
| LLM | **End-to-end cleanup latency** | TTFT + time to EOS on the fixed sample (the number the user feels) |
| ASR | **RTF** | Processing time / audio duration on a bundled 30 s clip (ru + en + code-switch), mel included |
| ASR | **First-partial latency** | Streaming/chunked models: speech start → first text |
| ASR | **Sanity WER/CER** | Against the bundled reference text, to catch a broken NPU numerical path (fp16 overflow, bad quantization) |
| OCR | **ms per screenshot** | Detector + all recognizer crops on a bundled 2400×1080 screenshot; boxes found |

Protocol: one warm-up run, then 3 measured runs, reporting the median and min/max. Results are stored per `(model, artifact, unit, firmware build fingerprint)` and used to pick each model's default unit.

---

## 9. What can be validated without a Fold7

| Check | Where | Possible? |
|---|---|---|
| Builds (llama.cpp Hexagon via Docker toolchain, AARs, NDK r28+) | Build server (Docker available, user in `docker` group; NDK 30 installed) | Yes |
| GGUF conversion/quantization of base and fine-tuned LLMs; tokenizer and chat-template correctness; quality eval of Q4_0 vs Q8_0 vs F16 | Build server CPU (llama.cpp x86) | Yes |
| ONNX graphs for ASR/OCR: golden outputs, WER on a ru/en set, CPU fallback correctness | Build server (ORT CPU, sherpa-onnx) | Yes |
| QNN numerics of our compiled graphs | QAIRT **x86 HTP emulator** (`lib/x86_64-linux-clang`, `libQnnHtp.so` + `qnn-net-run`); slow, functional only [V] ([ExecuTorch QNN docs](https://docs.pytorch.org/executorch/stable/backends-qualcomm.html)) | Yes (functional) |
| Compile to SM8750 context binaries; NPU latency, peak memory, op placement, on-device outputs **on real Galaxy S25 (same SoC)** | Qualcomm AI Hub Workbench (`Samsung Galaxy S25 (Family)`; the 8 Elite QRD was removed 2026-09-28 in favor of S25) [V] | Yes, needs a Qualcomm ID + API token |
| llama.cpp-Hexagon and our full APK on a real Snapdragon 8 Elite Android device | Qualcomm Device Cloud interactive session ("choose Snapdragon 8 Elite… install the demo APK") [V] ([GenieX FAQ](https://geniex.aihub.qualcomm.com/en/resources/faq.md), [QDC FAQ: free minutes](https://qdc.qualcomm.com/support/faq)) | Yes (Qualcomm reference device, not Samsung firmware) |
| Our APK on a real Samsung Galaxy with One UI 9 (Samsung DSP lib path, Samsung SELinux) | Samsung Remote Test Lab with ADB via Remote Debug Bridge [V]; Fold8/Flip8/S26 listed [V]; **Fold7 / S25 availability [U]** ([Samsung RTL post](https://developer.samsung.com/sdp/news/en/2026/07/29/july-2026-samsung-dev-insight)) | Likely; book an S25 or Fold7 |
| UI, accessibility bubble, download manager, fallback ladder logic | x86_64 emulator (NPU absent → must fall back cleanly; needs x86_64 CPU builds of ORT/llama.cpp or ARM image) | Yes |
| **Cannot be validated without a Fold7 (or at least a retail SM8750 Samsung unit):** | | |
| Sustained performance and thermals in the Fold7 chassis (thinner than S25), throttling during long dictations | — | No |
| True cold-load times from Fold7 UFS and LMK behavior with 12 GB under One UI 9 and real foreground apps | — | No |
| Simultaneous QNN PD + ggml-htp PD on the cDSP while the mic FGS, accessibility overlay and screenshot run | — | No (RTL partially) |
| Android 17 `android.hardware.npu` enforcement on Samsung's build when targeting API 37 | — | No (RTL partially) |
| Battery cost per dictation | — | No |

---

## 10. Risks (ranked)

1. **NPU access on a retail Fold7 with One UI 9 is not demonstrated.** Evidence comes from the S25 family (AI Hub farm, GenieX docs, one developer report on Android 16). Samsung-specific DSP lib paths and Android 17's NPU feature flag are known gotchas. Mitigation: a day-one CPU/GPU fallback, plus an early RTL/QDC smoke test.
2. **QAIRT version coupling** between context binaries and the runtime; LiteRT-QNN and ORT-QNN in one process will break. Mitigation: one pinned QAIRT per process, with compile versions ≤ runtime.
3. **The llama.cpp Hexagon backend is "experimental"** with fast-moving internals. Mitigation: pin a tag, keep GenieX as Plan B, and use the same GGUF on GPU/CPU.
4. **Qwen3-ASR on the NPU** is likely not to work on Qualcomm (Google's sample marks it CPU-only), and the SM8750 file is 1.9 GB. Mitigation: ship it as CPU/GPU via LiteRT-LM and keep NPU experimental.
5. **GigaAM/T-one need our own NPU compiles.** The NPU may not beat CPU for tiny streaming T-one.
6. **Memory with 12 GB:** Parakeet float (~1.2–2.3 GB) + LLM (0.7–1.3 GB weights + KV) + OCR, while other apps are open. Mitigation: unload ASR before LLM generate and use separate killable processes.
7. **Two concurrent cDSP sessions** (ASR QNN + LLM ggml-htp) may fail or contend [U]. Mitigation: serialize.
8. **Licensing:**
   - The QTI AI Stack License export-control clause (Russia/Belarus etc.).
   - Hexagon SDK terms for redistributing `libggml-htp-v79.so` [U].
   - LFM `lfm1.0` license terms for the owner's use [U].
   - Parakeet CC-BY-4.0 requires attribution in the app.
9. **Hosting:** Qualcomm AI Hub assets live on S3, not Hugging Face. The brief says models download from HF, so we must mirror them (license permitting) to the owner's HF account.
10. **Decode on the NPU is not faster than CPU** for most of our LLMs. A strict "NPU-only" policy would make Qwen3.5 slower end-to-end for short prompts.

---

## 11. Sources (primary, fetched 2026-11-18)

- Qualcomm press release (Fold7 chip, globally): https://www.qualcomm.com/news/releases/2025/07/the-most-powerful-snapdragon-mobile-platform-fuels-samsung-galax
- Qualcomm AI Hub Workbench release notes: https://workbench.aihub.qualcomm.com/docs/hub/release_notes.html
- AI Hub model perf/assets (v0.66.0): https://github.com/qualcomm/ai-hub-models/tree/v0.66.0/src/qai_hub_models/models (lfm2_5_1_2b_instruct, lfm3_1_7b, qwen3_5_0_8b, qwen3_5_2b, qwen3_1_7b, whisper_*, parakeet_tdt, easyocr, zipformer)
- Qualcomm HF org (model cards + `release_assets.json`): https://huggingface.co/qualcomm
- Maven: https://repo1.maven.org/maven2/com/qualcomm/qti/ (qnn-runtime 2.52.0, qnn-litert-delegate 2.52.0, onnxruntime-android-qnn 2.7.0, geniex-android 0.10.0); https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/ (1.32.0); https://dl.google.com/android/maven2/com/google/ai/edge/litert/group-index.xml; https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/group-index.xml
- QTI AI Stack License (inside `qnn-runtime-2.52.0.aar`, referenced by POM): https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf
- GenieX: https://github.com/qualcomm/GenieX, https://github.com/qualcomm/GenieX/blob/main/notes/run.md, https://geniex.aihub.qualcomm.com/en/run/android/quickstart.md, https://geniex.aihub.qualcomm.com/en/models/supported.md
- llama.cpp Hexagon: https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md, …/developer.md, https://github.com/ggml-org/llama.cpp/tree/master/ggml/src/ggml-hexagon
- whisper.cpp README: https://github.com/ggml-org/whisper.cpp
- ONNX Runtime QNN plugin EP: https://github.com/onnxruntime/onnxruntime-qnn and https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- LiteRT NPU: https://developers.google.com/edge/litert/next/npu, https://developers.google.com/edge/litert/next/qualcomm, https://developers.google.com/edge/litert/next/litert_lm_npu
- litert-community: https://huggingface.co/litert-community/Qwen3-ASR-0.6B, https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3, https://huggingface.co/litert-community/whisper-tiny
- LiteRT ASR sample: https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition
- ExecuTorch: https://github.com/pytorch/executorch/releases/tag/v1.6.0, https://docs.pytorch.org/executorch/stable/backends-qualcomm.html, https://github.com/pytorch/executorch/blob/main/examples/qualcomm/oss_scripts/llama/README.md
- sherpa-onnx QNN: https://k2-fsa.github.io/sherpa/onnx/qnn/index.html, QAIRT download URL pattern: https://k2-fsa.github.io/sherpa/onnx/qnn/download-qnn.html
- MNN: https://github.com/alibaba/MNN (README backend table, `source/backend/hexagon`)
- MLC-LLM: https://github.com/mlc-ai/mlc-llm
- Liquid LEAP deprecation: https://github.com/Liquid4All/leap-sdk, https://docs.liquid.ai/deployment/on-device/llama-cpp/migrating-from-leap-sdk
- Android 17 changes (NPU feature flag): https://developer.android.com/about/versions/17/summary
- Galaxy S25 Ultra NPU bring-up report: https://dev.to/jdshah/what-i-learned-getting-an-npu-to-actually-initialize-six-silent-failures-oa1
- QNN Java tutorial (`uses-native-library libcdsprpc.so`): https://docs.qualcomm.com/bundle/publicresource/80-63442-10/topics/tutorial_java_application.md
- Samsung Neural SDK status: https://developer.samsung.com/neural; Samsung RTL: https://developer.samsung.com/sdp/news/en/2026/07/29/july-2026-samsung-dev-insight
- QDC FAQ: https://qdc.qualcomm.com/support/faq
