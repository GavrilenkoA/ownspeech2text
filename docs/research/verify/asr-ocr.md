# ASR + OCR runtime spike (asr-ocr)

Date 2026-11-18. Scratch: `.cache/spikes/asr-ocr/` (paths below are relative to it unless they start with `docs/`, `tools/`, `native/`).
Owned outputs: this file, `docs/research/asr-catalog.json` (edited in place, §9), `native/sherpa/build-android.sh`,
`tools/device/asr_npu_smoke.sh`.

Status words: **VERIFIED** = I ran it (or inspected its saved output) and quote the result. **REFUTED** = I ran it and it
contradicts the claim. **UNVERIFIABLE-HERE** = something this server lacks is needed (named). **UNVERIFIED-ON-DEVICE** =
only the Fold7 can prove it; `tools/device/asr_npu_smoke.sh` measures it.

Host caveat: every speed number below comes from a shared x86_64 server (16 threads) whose load average was 20-60 from other
tenants during these runs. Use the numbers to compare models, never as phone numbers. The emulator numbers come from an API-36
x86_64 AVD (4 vCPU, SwiftShader) on the same loaded host.

---

## 1. Decisions

| # | Decision | Status |
|---|---|---|
| A1 | **ASR runtime = sherpa-onnx v2.0.1, built from source with `SHERPA_ONNX_ENABLE_QNN=ON` and linked against the Maven ONNX Runtime `com.microsoft.onnxruntime:onnxruntime-android:1.32.0`** (`native/sherpa/build-android.sh`, `ORT_AAR=…`). One `libonnxruntime.so` per APK, shared with OCR. The official `sherpa-onnx-2.0.1.aar` cannot be used for NPU: it has no QNN code. | Build VERIFIED for arm64-v8a (QNN) and x86_64 (CPU). x86_64 decodes correctly on the emulator. QNN path on HTP: UNVERIFIED-ON-DEVICE |
| A2 | **NPU = sherpa-onnx's own QNN code with the prebuilt SM8750 context binaries** (Parakeet v3, Nemotron 3.5, Whisper tiny…medium). **ORT + the QNN plugin EP** (`com.qualcomm.qti:onnxruntime-android-qnn:2.7.0`) is kept only for graphs that sherpa cannot run on QNN: a later GigaAM NPU port, and the OCR NPU option. This resolves the npu-runtime.md vs asr-models.md conflict in favour of sherpa-onnx for ASR. ORT QNN EP + AI Hub artifacts would need our own TDT/RNN-T decoding loops, features and a compile of a 2.27 GB universal DLC per QAIRT version. | VERIFIED (artifacts, source, builds). Speed: UNVERIFIED-ON-DEVICE |
| A3 | **One QNN stack per APK: `com.qualcomm.qti:qnn-runtime:2.52.0`** (`libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so`; leave out `libQnnHtpPrepare.so`, 89.6 MB, unless on-device compile is needed). It loads sherpa's QAIRT-2.40 binaries (newer runtime, older binary), and its own 2.52 binaries. A 2.40 runtime **rejects** a 2.52 binary (error 5000). | VERIFIED on the x86 HTP simulator (§5.2). On the real HTP: UNVERIFIED-ON-DEVICE |
| A4 | **Process layout: `:asr` = sherpa-onnx + ORT 1.32 + Silero VAD + OCR (ORT CPU) + QNN 2.52; `:llm` = llama.cpp/ggml-hexagon on its own** (same as the LLM spike's D7). Never load QNN and ggml-hexagon's FastRPC skel into one process. Never load two QNN versions into one process. | Two QNN versions in one x86 process work (§5.2), but that test does not cover the DSP side. The separation is cheap, and killing `:asr` is the only way to release the HTP context |
| A5 | **Default ASR model for a ru+en user: Parakeet TDT 0.6B v3**, NPU binary with a 20 s window, fed by VAD segments. CPU fallback: the same weights as int8 ONNX. Russian-only option: **GigaAM v3 e2e RNN-T (CPU int8)**. Nemotron 3.5 streaming is an optional live preview only, never the text of record. | Accuracy VERIFIED on host CPU (§3). NPU accuracy and latency UNVERIFIED-ON-DEVICE |
| A6 | **Segmentation: cut only in pauses** (Silero VAD v5, 32 ms frames; cut after ≥0.5 s silence, or ≥0.15 s once the segment is ≥14 s; 0.3 s pre-roll and post-roll; hard limit 19.5 s). Decode each segment when it closes. At stop, decode only the open tail. Do **not** re-decode the whole utterance: whole-clip decoding was worse in every test (§6). | VERIFIED on host (5 one-minute clips × 3 batch models) |
| A7 | **OCR runtime = ONNX Runtime 1.32 default CPU EP (MLAS), not XNNPACK, not NPU** for `PP-OCRv6_tiny_det` + `cyrillic_PP-OCRv5_mobile_rec`. Detector input limited to **960 px on the long side**. Recogniser at **batch 1**. On Android the XNNPACK EP **segfaults** on the recogniser and is not faster on the detector. NPU (fp16 context binaries, fixed shapes) is a labelled option (§7.6). | VERIFIED: host + Android x86_64 emulator, output parity with Python exact. arm64 speed: UNVERIFIED-ON-DEVICE |

## 2. Engine list (what the app ships / downloads)

Pack sizes are server sizes (HEAD-checked, §9). Params come from HF safetensors totals (HF API) or are counted from ONNX initializers.

| Engine (catalog id) | Runtime / compute | Artifact | Size | Licence | Params | Langs | Window | Role |
|---|---|---|---|---|---|---|---|---|
| `parakeet-tdt-0.6b-v3@qnn-sm8750-20s` | sherpa OfflineRecognizer, provider=qnn / **NPU** | GH `k2-fsa/sherpa-onnx@asr-models-qnn-binary-2`, `…-parakeet-tdt-0.6b-v3-20s-transducer.tar.bz2` sha256 `5ea894cf…95ec46` (re-hashed) | 446 MB | CC-BY-4.0 | 627,057,286 | 25 EU incl. ru, uk | fixed 20.00 s per call | **default** |
| `parakeet-tdt-0.6b-v3@onnx-int8` | sherpa, CPU | HF `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` @ed5d745 | 671 MB | CC-BY-4.0 | same | same | any (VAD segments) | CPU fallback, emulator |
| `gigaam-v3-e2e-rnnt@onnx-int8` | sherpa, CPU | HF `csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16` @a6039be | 232 MB | MIT | 222.5 M | ru | segments ≤20 s | Russian-only option |
| `nemotron-3.5-asr-streaming-0.6b@qnn-sm8750-560ms` | sherpa OnlineRecognizer, provider=qnn / **NPU** | GH `…@asr-models-qnn-binary-3`, `…-nemotron-3.5-asr-streaming-0.6b-560ms.tar.bz2` sha256 `a5af6d03…bbd51` (re-hashed) | 443 MB | OpenMDW-1.1 | 637,997,088 | 40 locales | streaming, 560 ms chunks | optional live preview |
| `whisper-small@qnn-sm8750` | sherpa whisper, provider=qnn / **NPU** | GH `…@asr-models-qnn-binary-2`, `…-whisper-small.tar.bz2` sha256 `a38ccc45…79aeb` (re-hashed) | 507 MB | Apache-2.0 (HF meta) | 241,734,912 | 99 | 30 s (mel [1,3000,80]) | optional |
| `whisper-small@onnx-int8` (added) | sherpa, CPU | HF `csukuangfj/sherpa-onnx-whisper-small` @8f3c18b | 375 MB | Apache-2.0 | same | 99 | 30 s, rest discarded | CPU fallback of the above |
| `whisper-large-v3-turbo@qaihub-sm8750-w8a16` | QNN context binary from Qualcomm AI Hub; **no runner** in sherpa | AI Hub S3 zip, QAIRT **2.50.0.260828** (metadata.json) | 824 MB | MIT | 808,878,080 | 99 | 30 s | not shipped (needs a custom runner) |
| `whisper-large-v3-turbo@onnx-int8` | sherpa, CPU | HF `csukuangfj/sherpa-onnx-whisper-turbo` | 1,037 MB | MIT | same | 99 | 30 s | not recommended (host RTF 1.2) |
| `t-one@onnx` | sherpa OnlineRecognizer, CPU | HF `csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08` | 144 MB | Apache-2.0 | 71,697,827 | ru, 8 kHz | streaming | optional (lowercase, no punctuation) |
| `qwen3-asr-0.6b@onnx-int8` | sherpa, CPU | HF `csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` | 987 MB | Apache-2.0 | 938,008,576 | 30 + 22 dialects | autoregressive | not recommended |
| `silero-vad@onnx-v5` (added) | ORT CPU (or sherpa Vad) | GH `…@asr-models/silero_vad_v5.onnx` sha256 `6b99cbfd…74396` | 2.3 MB | MIT | — | — | 512-sample frames | segmenter |
| OCR det `PP-OCRv6_tiny_det` | ORT 1.32 CPU EP | HF `PaddlePaddle/PP-OCRv6_tiny_det_onnx` @2ba1506, `inference.onnx` sha256 `193bab7a…dafb19f8` (= local file) | 1.8 MB | Apache-2.0 | — | — | dynamic, limit 960 | screen context |
| OCR rec `cyrillic_PP-OCRv5_mobile_rec` | ORT 1.32 CPU EP | HF `PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx` @2cef881, `inference.onnx` sha256 `5371ee1d…e172e5` (= local file) | 8.0 MB | Apache-2.0 | — | Cyrillic + Latin, 850 chars | H=48, W dynamic ≤3200 | screen context |

Runtime libraries (Maven Central, checked 2026-11-18 21:58 via `maven-metadata.xml`): `com.microsoft.onnxruntime:onnxruntime-android`
latest **1.32.0**. `com.qualcomm.qti:qnn-runtime` latest **2.52.0** (libQnnHtp build `v2.52.0.261104115025`; skels V68…V85;
POM licence "Qualcomm AI Hub Model License"). `com.qualcomm.qti:onnxruntime-android-qnn` latest **2.7.0** (only
`libonnxruntime_providers_qnn.so`, a plugin EP; NEEDED = liblog/libm/libdl/libc only; exports `CreateEpFactories`; built for "QAIRT 2.44+").
The old bridge EP `com.microsoft.onnxruntime:onnxruntime-android-qnn` stops at 1.29.0. Do not use it. PyPI `onnxruntime-qnn` is at 2.8.0 (host AOT compile).

## 3. Host proof: every ASR family on CPU through sherpa-onnx (same C++ core as the Android JNI)

`scripts/asr_eval.py` via `tools/heavy`, one model at a time. sherpa-onnx 2.0.1 Python + ORT CPU, 4 threads. Set: 12 FLEURS-dev
utterances per language + 1 pack clip each (`eval/short.json`). WER/CER after lowercasing and stripping punctuation (numbers are not
normalised, so models that spell numbers out are penalised). Peak RSS = `ru_maxrss` of the process. Logs: `logs/eval_<model>_<mode>.{json,log}`,
summary `logs/host_eval_summary.json`. Language mode: auto (Whisper and Nemotron "forced" runs gave the same WER).

| Model (pack) | ru WER / CER % | en WER / CER % | RTF ru / en (host) | load s | peak RSS MB | Android path |
|---|---|---|---|---|---|---|
| Parakeet TDT 0.6B v3 int8 | **7.41 / 1.97** | **7.61 / 5.25** | 0.087 / 0.091 | 3.1 | 1302 | NPU (sherpa SM8750 20 s) + CPU fallback |
| GigaAM v3 e2e RNN-T int8 | **4.17 / 0.63** | 71.3 / 36.1 | 0.032 / 0.030 | 0.8 | 414 | CPU (NPU port possible, §5.4) |
| GigaAM v3 e2e CTC int8 | 5.09 / 1.65 | 70.9 / 31.0 | 0.051 / 0.056 | 1.2 | 401 | CPU; NPU compile VERIFIED (§5.4) |
| T-one (streaming) | 13.43 / 2.52 | 100 / 100 | 0.065 / 0.056 | 1.9 | 558 | CPU only (§5.4) |
| Nemotron 3.5 streaming 560 ms int8 | 13.89 / 5.50 | 12.80 / 8.70 | 0.39 / 0.61 | 1.6 | 977 | NPU (sherpa SM8750) + CPU |
| Whisper small int8 | 12.50 / 5.03 | **4.84** / 3.37 | 0.58 / 0.43 | 1.7 | 1202 (3016 with 2 recognisers) | NPU (sherpa SM8750) + CPU |
| Whisper large-v3-turbo int8 | 6.02 / 3.14 | 5.88 / 3.30 | **1.19** / 0.78 | 5.9 | 2067 | CPU too slow; NPU only via AI Hub binary + custom runner |
| Qwen3-ASR 0.6B int8 | 19.91 / 6.92 | 6.92 / 4.95 | 0.81 / 0.49 | 13.7 | 2390 | CPU (sherpa) only. LiteRT NPU claim UNVERIFIABLE-HERE |

Example (`ru_00`, ref "Основной религией в Молдавии является православное христианство."): Parakeet, GigaAM and Whisper-turbo are
exact. Nemotron, Whisper-small and Qwen3 each make one inflection error. T-one gives "основной религии в молдавии является
православное христианство" (lowercase, no punctuation). On `en_00` GigaAM produces garbage ("The main Locko Bear is number wine…") and
T-one transliterates into Cyrillic. Nemotron in auto mode turned the Russian `ru_qwen` clip into Polish ("Barsuch żybusi w
Kiwskom Zoparkie…"). With `--language=ru` it gives Russian ("Берсук жибу си в Киевском зоопарке…").

**Default for a Russian+English user = Parakeet v3**, because:
- It is the only model in the top two for both languages (GigaAM is better in Russian but cannot do English; Whisper-turbo is slightly better but 13x slower on CPU and has no sherpa NPU binary).
- With VAD segments it follows ru↔en switches between phrases: mixed 60 s clip 4.5 % WER, against 33 % for Nemotron in one stream (§6).
- It outputs punctuation and casing.
- A ready SM8750 binary exists (§5), and the same weights run on the CPU and the emulator.

Android x86_64 emulator, CPU (VERIFIED, `logs/e3/emu_sherpa_offline.log`): `build-ort132/out/x86_64` (sherpa 2.0.1 + ORT
1.32.0) `sherpa-onnx-offline` gives exactly the host text for GigaAM (2 ru clips, RTF 0.42) and Parakeet (ru + 2 en clips, RTF 0.65).
The emulator was emulator-5584 (a sibling agent's AVD, idle at the time). I used my own directory `/data/local/tmp/murmur-asr-ocr` and deleted it
afterwards. My own emulator instance did not come up in four attempts (missing kvm group, AVD lock, then qemu exiting during boot,
 once with a captured segfault, exit 139; `logs/e3/emu55*.log`).

## 4. sherpa-onnx facts

| Claim | Result | Evidence |
|---|---|---|
| Current release | **v2.0.1** VERIFIED: PyPI latest 2.0.1 (uploaded 2026-10-27); `git ls-remote --tags` newest `v2.0.1`; tag commit `af2d8d81` dated 2026-10-26 22:57 +0800 | `curl pypi.org/pypi/sherpa-onnx/json`; `src/sherpa-onnx` `git log -1` |
| Official Android AAR includes QNN | **REFUTED**. `sherpa-onnx-2.0.1.aar` → `libsherpa-onnx-jni.so` contains "Please rebuild sherpa-onnx with -DSHERPA_ONNX_ENABLE_QNN=ON if you want to use qnn" and no `QnnInterface_getProviders`. The custom build has `QnnInterface_getProviders` and `ParakeetTdtModelQnn`. Release v2.0.1 assets contain no QNN AAR | `strings` (§ inventory command), `assets_v2.0.1.txt` |
| QNN SDK needed for the build | Headers only: `qnn-include-2.40.0.251030.tar.bz2` from the public `asr-models-qnn` GitHub release. No account. QNN libraries are `dlopen`ed at run time and come from Maven `qnn-runtime` | `native/sherpa/build-android.sh`, `logs/build-android.log` (`SHERPA_ONNX_ENABLE_QNN: ON`) |
| Single ORT | `build-ort132/out/{arm64-v8a,x86_64}/lib/libonnxruntime.so` byte-identical to the Maven 1.32.0 AAR (`cmp`); `libsherpa-onnx-jni.so` NEEDED `libonnxruntime.so` | `cmp …` → `SAME_AS_MAVEN_1.32.0`, `X86_SAME_AS_MAVEN_1.32.0` |
| SM8750 binaries | Releases `asr-models-qnn-binary-2` and `-3`: Parakeet TDT 0.6B v3 (3/5/8/10/13/15/18/20/23/25/28/30 s), v2, tdt_ctc-110m, Nemotron 3.5 streaming (80/160/320/560/1120 ms), Nemotron Speech EN, Whisper tiny/base/small/medium (+ .en, distil), Moonshine. **None for GigaAM, T-one or Qwen3-ASR** | `assets_asr-models-qnn-binary-{2,3}.txt` |
| QAIRT that compiled them | **v2.40.0.251030114326_189385** (all binaries read: Parakeet 20 s, Nemotron 560 ms, Whisper small); `coreApi 2.30.0`, `backendApi 5.40.0`, `ctxBlobVersion 3.3.3`, `socVersion` empty | `tools/qnn_ctx_info` (QnnSystemContext_getBinaryInfo) → `logs/qnn_ctx_info.txt` |
| Parakeet window | Encoder input `x uq16 [1,2000,128]` → `encoder_out [1,250,1024]`. Source (`qnn/offline-parakeet-tdt-model-qnn.cc:56-68`): input longer than `max_num_frames` is **truncated** with a warning, shorter input is **zero-padded** to the window (same NPU cost for 1 s or 20 s). Decoder `y i32 [1,1]`, h/c f16 `[2,640,1]`; joiner `log_probs [1,1,1,8198]`. Files: encoder 622,759,936 B, decoder 23,715,840, joiner 6,537,216 | same |
| Nemotron 560 ms | Encoder `x_65_56 uq16 [1,65,128]` per chunk + caches `[1,56,1024,24]`, `[1,1024,8,24]`, `prompt_index i32` (language), `encoder_out [1,1024,7]`; joiner `logits [1,1,1,13088]`. Encoder 609,431,552 B | same |
| Whisper small | Encoder `small_mel f32 [1,3000,80]` (30 s); decoder self-attention KV `f32 [1,768,448]` × 12 layers; `offline-whisper-model-qnn.cc:91` truncates longer input | same |
| CLI flags for QNN | `--provider=qnn --transducer.qnn-backend-lib --transducer.qnn-system-lib --transducer.qnn-context-binary=enc,dec,joiner`; online CLI `--language` | `qnn-config.cc:18-33`, `offline-transducer-model-config.cc:52`, `sherpa-onnx.cc:91` |
| VAD | sherpa 2.0.1 `VoiceActivityDetector` accepts `silero_vad.onnx` (metadata: "silero-vad v4 exported to onnx by k2-fsa"), `silero_vad_v5.onnx` and onnx-community v5: 6 segments each on a 60 s clip, the two v5 graphs identical | scratchpad `e3/vadtest.py` |

## 5. QNN coupling, two stacks, process layout

### 5.1 Who needs which QAIRT

| Artifact | Built with | Runtime that can load it |
|---|---|---|
| sherpa SM8750 binaries (Parakeet, Nemotron, Whisper) | QAIRT 2.40.0.251030 | 2.40, 2.52 (VERIFIED on host simulator) |
| AI Hub Whisper-large-v3-turbo w8a16 | QAIRT 2.50.0.260828 (`metadata.json` read through HTTP range requests) | ≥ 2.50 (by the rule below) |
| Our host compiles (OCR det/rec, GigaAM CTC) | QAIRT 2.52 via `onnxruntime-qnn` 2.8.0 (x86 `libQnnHtp.so` `v2.52.0.261104115025`) | 2.52 |

### 5.2 Version coupling, measured (VERIFIED on the x86_64 HTP simulator, `logs/e3/qnn_version_matrix.txt`)

```
runtime=240 bin=joiner.bin (2.40):     contextCreateFromBinary 0       -> OK
runtime=240 bin=tiny_ctx_qnn.bin (2.52): contextCreateFromBinary 5000  -> rejected
runtime=252 bin=joiner.bin (2.40):     contextCreateFromBinary 0, graphRetrieve 0 -> OK
runtime=252 bin=tiny_ctx_qnn.bin (2.52): 0 -> OK
```
So a newer runtime loads older binaries, and an older runtime refuses newer ones with an explicit error, not silently. This
**partly REFUTES** npu-runtime.md "a version mismatch fails silently / pair exactly matching libs". **Two QNN versions in one
process** (`tools/qnn_two_stacks`, dlopen RTLD_LOCAL 2.40 + 2.52, each creates a backend and a context): `both_ok=1`
(`logs/e3/qnn_two_stacks.txt`). This proves only the CPU-side libraries. On a phone, each `libQnnHtp` opens a FastRPC session to the
cDSP and loads its own `libQnnHtpV79Skel.so`, and that is UNVERIFIED-ON-DEVICE. Hence A3/A4: one QNN version (2.52) in `:asr`, no
QNN in `:llm`.

### 5.3 Process layout

- `:asr` (killable): AudioRecord → Silero VAD (ORT CPU) → sherpa-onnx (QNN HTP for Parakeet; CPU for GigaAM/fallback) +
  OCR (ORT CPU, once per dictation, at bubble tap). Libraries: `libsherpa-onnx-jni.so`, `libonnxruntime.so` (1.32.0),
  `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so` (2.52.0), optionally
  `libonnxruntime_providers_qnn.so` (2.7.0) if the OCR/GigaAM NPU option ever ships. `ADSP_LIBRARY_PATH` must point at a real
  directory (extractNativeLibs / `useLegacyPackaging`, as the LLM spike found for its skel).
- `:llm`: llama.cpp + ggml-hexagon (its own `libggml-htp-v79.so` skel over FastRPC). It receives the transcript and the OCR lines over binder.
- Why OCR goes into `:asr` and not the UI process: ORT is already mapped there. The ASR decode runs on the NPU, so the CPU is free for
  OCR while the user speaks. The UI process stays free of native ML.

### 5.4 Families without a sherpa NPU binary

- **GigaAM v3**: the e2e CTC fp32 ONNX (`istupakov/gigaam-v3-onnx` `v3_e2e_ctc.onnx`), fixed to `[1,64,2000]` (20 s), compiles
  **100 % onto the HTP** with ORT QNN EP 2.8.0 for SM8750 (CPU fallback disabled): 1 EPContext node, 466 MB fp16 context, 185 s
  compile (VERIFIED, `asrqnn/gigaam_ctc_20s.json`). The app would still need the GigaAM front end (64-bin mel) and CTC decoding.
  Not run on the simulator; accuracy and latency UNVERIFIED-ON-DEVICE. RNN-T not tried. Realistic path: CPU now (RTF 0.03 host),
  NPU port later if the phone's CPU numbers disappoint.
- **T-one**: the 300 ms streaming graph with fixed state cannot compile strictly: with fallback it becomes 19 HTP partitions plus CPU
  `BatchNormalization` (VERIFIED, `asrqnn/t_one_300ms.json`). The model is 72 M parameters and fast on CPU. CPU only.
- **Qwen3-ASR 0.6B**: sherpa CPU works, but it is the weakest on Russian here and the heaviest. The LiteRT NPU artifact in the catalog
  (`qwen3-asr-0.6b@litert-npu-sm8750`) exists (HEAD 200) but was not run: LiteRT NPU needs the device (UNVERIFIABLE-HERE).
- **Whisper-large-v3-turbo**: the AI Hub w8a16 SM8750 binary exists (I/O read from its metadata: encoder `input_features uint16
  [1,128,3000]`, decoder `input_ids i32 [1,1]` + cross-KV `uint8 [20,1,64,1500]`). sherpa's whisper QNN runner expects sherpa's own
  export (`small_mel f32 [1,3000,80]` etc.), so turbo needs a custom runner. Not worth it while Parakeet is the default.

## 6. Streaming vs segmentation ("can we split audio without losing information?")

Prototype `scripts/seg_stream.py`: real-time simulation of the segmenter. Silero VAD v5 (`onnx-community/silero-vad`, ORT, 512-sample
frames, thr 0.5 / neg 0.35), cuts and decoding as in A6, "stop" tapped 0.3 s after the last word. It is compared with one whole-clip decode
and with the reference. Native streaming (Nemotron, T-one) is fed in 160 ms chunks. Clips (`eval/long.json`, built by
`scripts/make_eval.py`): 50-61 s concatenations of FLEURS dev utterances (ru, en, alternating ru/en), with 0.6-2 s pauses ("pauses")
or with no inserted pause ("nopause"), each utterance levelled to RMS 0.05.

| Model / mode | ru pauses | en pauses | ru nopause | en nopause | mixed ru/en | latency to final after stop (host CPU, load ~28) |
|---|---|---|---|---|---|---|
| Parakeet, pause cuts | **3.45** (whole 8.62) | **10.62** (15.93) | **4.31** (8.62) | **10.62** (23.01) | **4.50** (whole **85.59**) | 0.97 s / 1.67 s (8.4 s tail) |
| GigaAM RNN-T, pause cuts | **2.59** (7.76) | n/a (ru model) | 3.45 (3.45) | n/a | 34.2 (57.7) | 0.90 s / 1.43 s |
| Whisper small, pause cuts | 6.90 (88.79) | 13.27 (53.1) | 12.07 (71.55) | 9.73 (53.1) | 7.21 (86.49) | 3-13 s at load 40-60 (RTF 0.4-0.6) |
| Nemotron, native streaming | 8.62 | 13.27 | 12.07 | 10.62 | 33.33 | 0.33 s |
| T-one, native streaming | 12.07 | 100 | 13.79 | 100 | 56.76 | 0.10 s |
| Parakeet, **forced 8 s cuts** (stress, no pause cuts) | 18.10 | 53.10 | 14.66 | — | 19.82 | — |
| GigaAM, forced 8 s cuts, text merge / time merge | 29.31 / **7.76** | — | 12.93 / 6.90 | — | — | — |

WER %, whole-clip decode in brackets. Files: `logs/seg_*.json`, `logs/segforced8_*.json`, latencies `logs/e3/seglat_*.json`.

Answers:
- **Cutting in pauses loses nothing.** Segmented WER is at or below the whole-clip WER in every run. Russian runs have zero deletions
  and zero insertions. I inspected every segment joint: the words on both sides of each cut are intact (e.g. "применив слезоточивый
  газ | по некоторым сообщениям"). The few en deletions are recognition errors ("Bishkek" → "bishkest", "1760" → "seventeen sixty"),
  not boundary losses. In all 15 pause-cut runs (3 models × 5 clips) no forced cut was needed: with the 0.15 s soft-silence rule,
  segments were 0.8-15 s.
- **Whole-clip decoding is worse, not better.** Parakeet over a 58 s ru/en clip locks into one language (85.6 % WER). Whisper
  silently drops everything after 30 s (sherpa warns "Only waves less than 30 seconds are supported…"). Do not re-decode at stop.
- **Forced cuts in running speech do lose or duplicate words.** In the 8 s stress test, Parakeet's text merge deleted up to 49 words and
  GigaAM's text merge duplicated 30 words. GigaAM's timestamp merge restored whole-clip quality (7.76). Parakeet's timestamp merge
  did not (53 % on en, open item: probably TDT token timestamps vs. the 1 s overlap). Keep forced cuts as a last resort at 19.5 s and
  prefer the lowest-VAD frame in the last 2 s. On the phone, the 20 s NPU window makes them rare.
- **Streaming in one stream does not follow code-switching**: Nemotron 33 % on the mixed clip (29 deletions), T-one Russian only. A
  segmented batch model sees each phrase separately and gets 4.5 %.
- **Latency to final text** = decode of the open tail only. On the NPU, Parakeet costs one 20 s-window encoder call whatever the tail
  length (UNVERIFIED-ON-DEVICE; smoke script measures it). On host CPU it is 0.9-1.7 s for an 8.4 s tail.

## 7. OCR (CONTEXT_V2: the LLM's screen context is the OCR output)

### 7.1 Runtime decision and proof

- ORT Android 1.32.0 default CPU EP runs both graphs on the x86_64 emulator with **output parity against Python ORT**
  (`ortbench/ort_bench.cc`, built for x86_64 and arm64 with NDK r30 against the AAR. Real screenshot tensors from
  `scripts/ortbench_inputs.py`; `logs/e3/emu_ortbench_parity.jsonl`): detector max |Δ| 4e-5…7e-5, binary map at 0.2 identical
  (agreement 1.0) at 960x864, 1280x1152 and native 2176x1984. Recogniser max |Δ| 2.4e-6 / 4.4e-6, argmax agreement 1.0 (48x320, 48x640).
- XNNPACK EP: the detector runs (same parity) but is **not faster** (960x864: 530 ms vs 453 ms CPU EP; native: 2223 vs 1790 ms). The
  recogniser **segfaults** inside `libonnxruntime.so` on session run (threads 1 and 4; tombstone in `logcat -b crash`). Do not use XNNPACK.
  arm64 behaviour UNVERIFIED-ON-DEVICE (smoke script runs it).
- Emulator CPU EP latency (4 vCPU, loaded host): det 960x864 **453 ms** median, 1280x1152 685 ms, 2176x1984 1790 ms; rec **88 ms**
  per 48x320 line, 140 ms per 48x640 (`logs/e3/emu_ortbench.jsonl`).
- Host CPU, full pipeline in Python (`scripts/ocr_eval.py`, 4 threads, batch 1): per page at limit 960, det 56-193 ms + rec 270-1060 ms
  for 14-20 lines, at load 25-40. Peak RSS 1.2 GB for the Python process (OpenCV + both sessions + a native-resolution detector run).
- Batch 1 beats padded batches on CPU: 6x48x320 = 390 ms CPU vs 35 ms per single 48x320 (`recbench2.py`, scratchpad). Recogniser cost is
  roughly linear in width (16 ms @160 → 241 ms @1280, 1 thread).

### 7.2 Accuracy on phone-sized screenshots (`ocr/results/*.json`, summary `scripts/ocr_summary.py`)

Pages: 24 synthetic third-party-app screens (chat, mail, article, work tracker; ru / en / mixed ru-en), rendered with Chrome at the
Fold7 cover (1079x2520) and inner (1969x2184) resolutions, ground truth from DOM line boxes (`scripts/ocr_pages.py`). Plus 6 small-text
stress pages (8-14 CSS px = 21-37 device px, `scripts/ocr_small_pages.py`). The 12 Murmur prototype screens were also rendered, but their DOM
ground truth is invalid (text hidden behind modal sheets, CSS `text-transform: uppercase`; checked visually on
`proto081_en_inner.png`). They are used for latency only.

| Detector limit (DB params from the model yml: 0.2 / 0.4 / 1.4) | ru CER cover / inner | en CER | mixed CER raw → after homoglyph repair | look-alike swaps (of look-alike chars) in mixed |
|---|---|---|---|---|
| 960 | 0.29 / 0.46 % | 0.11 / 0.06 % | 2.57 / 3.37 → **0.54 / 0.65 %** | 6.7 / 8.9 % |
| 1280 | 0.46 / 0.40 % | 0.06 / 0.06 % | 3.65 / 3.31 → 1.02 / 0.71 % | 8.7 / 8.5 % |
| native (≈PaddleX pipeline) | 0.35 / 0.34 % | 0.11 / 0.06 % | 2.87 / 3.49 → 0.78 / 0.47 % | 7.2 / 9.6 % |
| band tiling 640 px at native res | 0.46 / 0.34 % | 0.06 / 0.00 % | 2.63 / 3.55 → 0.48 / 0.41 % | 7.1 / 10.0 % |

PaddleX DB parameters (0.3 / 0.6 / 1.5) give the same accuracy within noise (`cpu_dbpaddlex.json`). In pure ru or pure en pages there
are **zero** Latin/Cyrillic swaps. In mixed pages 6-10 % of the look-alike letters (А В Е К М Н О Р С Т Х а е о р с у х and Latin
twins) come out in the wrong script, and that is most of the mixed-page error. The per-token majority-script repair in
`ocr_eval.py:repair()` removes ~80 % of it.

**Small text and the 960 limit at 1968x2184 (task 7b)**: small-text pages, inner display, CER by CSS font size at limit 960:
8 px 0.4 %, 9 px 1.8 %, 10 px 1.3 %, 11 px 2.3 %, 12 px 1.2 %, 14 px 1.3 % (all 1.41 %). Native resolution: 1.90 % overall.
Band tiling: 1.62 %. **No line was missed at any limit** (`logs/e3/ocr_small_by_font.txt`). A 960 limit does not lose small UI text
down to 8 CSS px. It costs ~4x less detector time than native (emulator 453 vs 1790 ms). Keep 960. Tiling is only useful for a
fixed-shape NPU detector.

### 7.3 Exact pre/post-processing for the Android port (what `ocr_eval.py` does; must equal `ml/ocr` when it lands)

Detector `PP-OCRv6_tiny_det`:
1. Input = screenshot as **BGR** uint8 (Android `ARGB_8888` → drop A, order B,G,R). The model was trained on BGR (`DecodeImage img_mode: BGR`).
2. Resize: `r = min(1, 960 / max(h, w))`, `nh = max(32, round(h*r/32)*32)`, `nw = max(32, round(w*r/32)*32)` (independent rounding),
   bilinear (`cv2.resize` default). Cover 2520x1080 → 960x416; inner 2184x1968 → 960x864.
3. `x = (pix/255 − [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]` applied **to B, G, R in that order** (Paddle quirk); HWC→CHW; float32 `[1,3,nh,nw]`.
4. Output prob map `[1,1,nh,nw]`. Binary = prob > **0.2** (model yml; PaddleX pipeline uses 0.3).
5. `findContours(RETR_LIST, CHAIN_APPROX_SIMPLE)`, at most 3000. For each: `minAreaRect`, drop if short side < 3; score = mean prob
   inside the contour polygon (Paddle default "fast" = inside the box polygon; pick one and keep it identical on both sides), drop
   if < **0.4** (PaddleX 0.6).
6. Unclip: `d = area(box) * 1.4 / perimeter(box)` (PaddleX 1.5), `pyclipper` offset `JT_ROUND`, `minAreaRect` again, drop if short side < 5.
7. Scale points by `(w/nw, h/nh)`, clip to the image, order TL, TR, BR, BL (min x+y, min y−x, max x+y, max y−x).
8. Reading order: sort by (y, x) of the TL point, then PaddleOCR's pass: neighbours with |Δy| < 10 px and x out of order are swapped
   (`ocr_eval.py` uses the approximation `(round(y/10), x)`; use the exact Paddle rule in both implementations).

Recogniser `cyrillic_PP-OCRv5_mobile_rec`:
1. Crop: perspective warp of the 4 points to `w = max(|TL-TR|, |BR-BL|)`, `h = max(|TL-BL|, |TR-BR|)`, `BORDER_REPLICATE` (Paddle
   uses bicubic, `ocr_eval.py` bilinear; pick one). If `h / w ≥ 1.5`, rotate 90°.
2. Resize to height **48**, width `ceil(48 * w / h)` (cap 3200 = the export's max dynamic width). Pad with zeros on the right to
   `max(320, that width)` (Paddle `RecResizeImg [3,48,320]` + `max_wh_ratio`). `x = (pix/255 − 0.5) / 0.5`, BGR, CHW.
3. **Batch 1** on CPU (§7.1). Output `[1, W/8, 852]` (852 = blank + 850 dictionary chars + space).
4. CTC greedy: argmax per step, collapse repeats, drop index 0. Index k → `dict[k-1]`, index 851 → `' '`. Confidence = mean of
   the max probabilities of the kept steps.
5. Dictionary: `inference.yml → PostProcess.character_dict` (850 entries, identical to `keys.txt`; first `! " # $ %`).

### 7.4 What this means for `ml/ocr` (read-only for me)

`ml/ocr/READY` did not exist during this spike (`ls ml/ocr` → no such directory), so I could not compare. Parity material for the ML
team: `ortbench/data/*.in.f32 / *.ref.f32` (preprocessed tensors and reference outputs), `ocr/pages` + `ocr/small` (screenshots + DOM
truth), `ocr/results/*.json` (per-line hyp/ref, timings), `scripts/ocr_eval.py` (the full pipeline above).

### 7.5 OCR on the NPU (option, not the plan)

VERIFIED on host (`ocrqnn/result.json`, `scripts/ocr_qnn.py`): with fixed shapes, both graphs compile **100 % onto the HTP**
(CPU fallback disabled) with ORT QNN EP 2.8.0 / QAIRT 2.52 for SM8750, as **float (fp16) without quantisation**: det 960x416
(1.7 MB context), det 960x960, rec 48x320 (4.8 MB), rec 6x48x640. On the x86 HTP simulator, outputs vs ORT CPU: det max |Δ| 0.074 / 0.13
(mean 1e-4 / 3e-4), rec max |Δ| 0.013 / 0.027 with **argmax agreement 1.0**. What the app would have to do: one context per
detector shape (cover 960x416, inner 960x864, plus rotation variants), recogniser width buckets (e.g. 320/640/1280, pad to the bucket),
ship 2.52-compatible binaries or compile on device with `libQnnHtpPrepare.so` (+89.6 MB), and share the HTP with Parakeet. Accuracy
risk is small (fp16, no quantisation). The gain is bounded by one detector call plus ~20 short recogniser calls per dictation. Decide
after `tools/device/asr_npu_smoke.sh` prints CPU vs QNN times on the phone. **No NPU OCR claim is made here.**

## 8. Verification of phase-1 ASR/OCR claims

| Claim (doc) | Status | Evidence |
|---|---|---|
| sherpa-onnx v2.0.1 is current (asr-models §1.2) | VERIFIED | §4 |
| sherpa ships SM8750 QNN context binaries for Parakeet v3, Nemotron 3.5, Nemotron EN, Whisper tiny-medium (asr-models §1.2) | VERIFIED | release asset lists; 3 tarballs downloaded and re-hashed = catalog sha256 (`logs/e3/sm8750_sha256.txt`) |
| "Prebuilt QNN models only for SM8850 per docs" (npu-runtime §2 table, sherpa row) | **REFUTED** | SM8750 binaries in `asr-models-qnn-binary-2/3` |
| sherpa QNN builds need the QNN SDK + `SHERPA_ONNX_ENABLE_QNN=ON` (npu-runtime) | VERIFIED. Headers are public (sherpa GitHub release), no Qualcomm account | §4 |
| Official AAR usable for NPU (implicit in asr-models "one runtime: sherpa-onnx") | **REFUTED** for the AAR, VERIFIED for a source build | §4 |
| "Which QAIRT built the binaries is not documented" (asr-models §5) | Now known: **2.40.0.251030114326** | §4 |
| model.bin tied to the QNN SDK version it was built with (asr-models §5) | Partly: a **newer** runtime (2.52) loads 2.40 binaries; an older one refuses newer binaries (error 5000) | §5.2 (simulator) |
| "Pair the runtime with exactly matching QNN libs (a version mismatch fails silently)" (npu-runtime) | **REFUTED** on the simulator (backward compatible; mismatch gives an explicit error) | §5.2 |
| QNN EP is a Qualcomm plugin, Maven `com.qualcomm.qti:onnxruntime-android-qnn` (npu-runtime) | VERIFIED: 2.7.0 latest on Maven (pip 2.8.0); plugin with no libonnxruntime NEEDED | §2 |
| `qnn-runtime:2.52.0` on Maven Central (npu-runtime) | VERIFIED; V79 stub+skel present | §2 |
| ASR Plan A = ORT + QNN EP with AI Hub artifacts (npu-runtime §1.3) | **Rejected** in favour of sherpa (A2). Not refuted technically | §1 |
| Default ru+en = Nemotron 3.5 streaming (asr-models §1.3) | **REFUTED by measurement**: Nemotron ru 13.9 / en 12.8 % WER vs Parakeet 7.4 / 7.6; mixed ru/en stream 33 % vs 4.5 %; auto-LID turned Russian into Polish | §3, §6 |
| Nemotron "final within about 0.1 s of the stop tap" [I] | Host CPU **0.33 s** (includes the 0.8 s tail padding). NPU UNVERIFIED-ON-DEVICE | §6 |
| Parakeet 20 s QNN window truncates longer input (asr-models §3) | VERIFIED in source; shorter input is padded to the window | §4 |
| At stop, re-decode the whole utterance if ≤ 20 s (asr-models §1.6) | **Not adopted**: whole-clip decoding was worse in every long-clip test; a ≤20 s re-decode was not separately measured | §6 |
| Forced cut with overlap + timestamp merge keeps all words (asr-models §1.6) | **Partly REFUTED**: GigaAM time-merge = whole-clip quality; Parakeet forced cuts lost up to 49 words in the stress test | §6 |
| Qwen3-ASR weaker on Russian (asr-models §1.7) | VERIFIED (19.9 % WER, 2.4 GB RSS, 13.7 s load on host) | §3 |
| T-one: lowercase Cyrillic, no punctuation, 8 kHz (asr-models) | VERIFIED (output; sherpa resampler log "output_sample_rate: 8000") | `logs/eval_t-one_auto.log` |
| GigaAM v3 best Russian, no NPU build (asr-models §1.5) | VERIFIED (4.17 % WER); NPU compile of the CTC variant is feasible | §3, §5.4 |
| Whisper-turbo NPU only as an AI Hub zip, needs a custom runner (asr-models §1.7) | VERIFIED (QAIRT 2.50, I/O differs from sherpa's whisper runner) | §5.4 |
| `silero_vad.onnx` (643,854 B) is the VAD to bundle; HF v5 sherpa compatibility UNVERIFIED (asr-models §5) | The sherpa asset is **v4** (ONNX metadata). sherpa 2.0.1 accepts both v4 and v5 → **VERIFIED**; bundle v5 | §4 |
| OCR = EasyOCR on ORT+QNN (npu-runtime §1.4) | **Superseded** by the owner (CONTEXT_V2: PP-OCR). PP-OCR runs on ORT CPU (A7) | §7 |
| OCR `w8a8` on NPU (npu-runtime) | Not needed: fp16 compiles 100 % on HTP for PP-OCR | §7.5 |

## 9. `docs/research/asr-catalog.json` edits

Backup of the phase-1 file: `logs/e3/asr-catalog.before.json`. Applied by `scripts/edit_catalog.py` (reviewed; two unverified
sentences removed before running), then two notes added after the VAD test. Full log: `logs/e3/catalog_edits.txt`. Summary:
- `defaults`: ru+en and en → `parakeet-tdt-0.6b-v3@qnn-sm8750-20s`; `livePreview_optional` → Nemotron 3.5 560 ms; `vad` →
  `silero-vad@onnx-v5`; `qnnRuntime` → `com.qualcomm.qti:qnn-runtime:2.52.0`; ru-only quality stays GigaAM RNN-T; CPU fallbacks kept.
- `fieldNotes.hostEval` and `fieldNotes.qnnBinary` added; `verifiedBy` → this file.
- Nemotron 3.5 QNN: params 637,997,088; runtime string (3 context binaries, custom build needed); `qnnBinary` (QAIRT 2.40, I/O, file
  sizes); tier recommended → optional; `defaultFor` [] ; new notes; `hostEval`.
- Parakeet v3 QNN 20 s: params 627,057,286; runtime; `maxAudio` (fixed [1,2000,128], pad/truncate); `qnnBinary`; `defaultFor` [ru+en, en]; notes.
- Whisper small QNN: params 241,734,912; runtime; `qnnBinary`; `hostEval`. Whisper medium QNN: params 763,857,920. Nemotron EN: 618,084,865.
- Whisper-large-v3-turbo AI Hub: params 808,878,080; `qnnBinary.qairt` 2.50.0.260828221209 + I/O.
- CPU entries Parakeet, Nemotron, GigaAM RNN-T / CTC (params 222.5 M / 220.9 M from ONNX initializers), T-one (71,697,827), Qwen3-ASR
  (938,008,576; "0.6B" is the LM part), Whisper turbo: `hostEval` + measured notes.
- Added `whisper-small@onnx-int8` (the files the host eval used; sha256 = local files) and auxiliary `silero-vad@onnx-v5`.
- `silero-vad@onnx` marked superseded (it is v4); `silero-vad@onnx-hf` notes: sherpa compatibility VERIFIED.
- Every URL (51) HEAD-checked after the edit: 51 × HTTP 200, `Content-Length`/`X-Linked-Size` = catalog bytes (`logs/e3/headcheck-after.jsonl`).

## 10. Phone checks (`tools/device/asr_npu_smoke.sh`)

It prepares on the host (downloads: QNN 2.40 libs, `qnn-runtime-2.52.0.aar`, `onnxruntime-android-qnn-2.7.0.aar`, Parakeet 20 s + Nemotron
560 ms SM8750 binaries, Parakeet/Nemotron/GigaAM int8 packs, 3 clips), pushes to `/data/local/tmp/murmur-asr`, runs, prints JSONL +
a markdown table, and deletes the device directory.
- ASR: Parakeet CPU vs NPU (QNN 2.40 and 2.52 libs, cold + warm), Nemotron CPU vs NPU (ru/en), GigaAM CPU. Reports create time, decode time,
  RTF, peak RSS (VmHWM), text, errors.
- OCR (`ort_bench` arm64): det 960x416 / 960x864 / native, rec 48x320 / 48x640 on CPU EP and XNNPACK EP. QNN with the host-compiled 2.52
  context binaries (strict, no CPU fallback) and an on-device JIT compile. Outputs are pulled and compared with the host reference.
- Verified here: `bash -n`; the result parsers on real emulator outputs. Not run end-to-end (no phone).

## 11. Open items / not done

- Parakeet timestamp merge after a forced cut deleted words (§6). It matters only for forced cuts. Fix before relying on forced cuts.
- The ≤20 s whole-utterance re-decode was not measured on its own.
- OCR reading order: exact PaddleOCR `sorted_boxes` is not implemented in `ocr_eval.py` (approximation used).
- `qnn-runtime` licence terms: the POM names the "Qualcomm AI Hub Model License". npu-runtime.md quotes an "AI Stack License" that allows
  object-code redistribution and names Russia in its export clause. I could not extract the PDF text here; it needs the owner's read.
- My own emulator instance would not boot from this session (four attempts, three ports; qemu exit / segfault 139). Sibling instances run. Not investigated further.

## 12. Reproduce

```
B=.cache/spikes/asr-ocr
tools/heavy $B/venv/bin/python $B/scripts/asr_eval.py parakeet-v3 out.json          # one model, all clips (see run_all_evals.sh)
ASR_THREADS=4 tools/heavy $B/venv/bin/python -I $B/scripts/seg_stream.py parakeet-v3 out.json long_ru_pauses
OCR_THREADS=4 OCR_BS=1 tools/heavy $B/venv/bin/python -I $B/scripts/ocr_eval.py $B/ocr/pages out.json 960 0 -1
ORT_AAR=$B/dl/onnxruntime-android-1.32.0.aar tools/heavy native/sherpa/build-android.sh all $B/build-ort132
clang++ --target=aarch64-linux-android26 -O2 -std=c++17 -I$B/build-ort132/ort-aar/headers $B/ortbench/ort_bench.cc \
  -L$B/build-ort132/ort-aar/jni/arm64-v8a -lonnxruntime -static-libstdc++ -o ort_bench   # NDK r30 toolchain
$B/venv-qnn/bin/python -I $B/scripts/ocr_qnn.py $B/ocrqnn                            # host AOT compile for SM8750 + simulator parity
```
Deleted to save disk (regeneration in `.cache/progress/spike-asr-ocr.md`): the sherpa build tree, host ASR QNN compiles,
extracted SM8750 binaries, the GigaAM fp32 export, the Qwen3-ASR and Whisper-turbo packs, FLEURS source audio. Scratch is 4.1 GB.
