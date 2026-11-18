# ASR model catalog and transcription strategy

Owner: ML (speech) · Researched 2026-11-18 · Machine-readable companion: [`asr-catalog.json`](./asr-catalog.json)

Every fact below comes from a page or API response fetched on 2026-11-18, and its source is linked next to it.
**[V]** marks a verified fact. **[I]** marks my own inference or estimate. **UNVERIFIED** means I looked and could not confirm it.

---

## 1. Decisions / Recommendation

1. **"Samsung NPU" on the Fold7 means the Qualcomm Hexagon NPU.** The Fold7 runs the Snapdragon 8 Elite for Galaxy (SM8750) [V] ([GSMArena](https://m.gsmarena.com/samsung_galaxy_z_fold7-13826.php), [Notebookcheck](https://www.notebookcheck.net/Samsung-Galaxy-Z-Fold7.1078581.0.html)). Every NPU artifact in this document is therefore a Qualcomm QNN/QAIRT artifact built for SM8750.
2. **Use one ASR runtime: sherpa-onnx (v2.0.1, 2026-10-26).** It is the only runtime that ships all of the following [V] ([releases](https://github.com/k2-fsa/sherpa-onnx/releases)):
   - pre-compiled **SM8750 QNN context binaries** for Parakeet TDT 0.6B v3, Nemotron 3.5 ASR Streaming, Nemotron Speech Streaming EN, and Whisper tiny/base/small/medium;
   - CPU ONNX packs for GigaAM v3, T-one, Qwen3-ASR 0.6B and Whisper turbo;
   - Silero VAD;
   - a Kotlin API that has a `QnnConfig` ([QnnConfig.kt](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/QnnConfig.kt)).

   LiteRT (Google) is the second-best option. It is kept only as an experimental path for Qwen3-ASR on the NPU.
3. **Default model for a ru+en user: Nemotron 3.5 ASR Streaming 0.6B, NPU build, 560 ms chunks.**
   - It is a true cache-aware streaming RNN-T: each audio frame is processed once, so nothing is lost.
   - Russian is a "transcription-ready" locale. Output has punctuation and casing. The licence is OpenMDW-1.1 (permissive) [V] ([card](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b)).
   - The SM8750 binary exists [V].
   - It answers the owner's question in the cleanest way: text appears while the user speaks and is final within about 0.1 s of the stop tap [I].
4. **Default for English-only users, and the accuracy alternative for ru+en: Parakeet TDT 0.6B v3, NPU build, 20 s window, run as VAD-segmented batch.**
   - It is the most accurate model with an NPU build. Published WER: en LibriSpeech clean/other 1.93/3.59, ru FLEURS 5.51 [V] ([card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)).
   - Nemotron 3.5 publishes ru FLEURS 9.60 at 560 ms [V], but the two cards use different normalisation (see §4.4).
   - The hub should offer both models. The Benchmark button decides on the owner's own voice.
5. **Russian-only quality option: GigaAM v3 e2e RNN-T (CPU int8, MIT).** It produces punctuated, normalised Russian and has the best published Russian WER. No NPU build exists, so it runs on the CPU. Porting it to the NPU is a later task (§8).
6. **Answer to the owner's open question (brief, step 6):** yes, we can transcribe while the user speaks without losing information.
   - **Streaming models** (Nemotron, T-one) consume every frame as it arrives.
   - **Batch models** (Parakeet, GigaAM, Whisper, Qwen3-ASR) get a VAD-segmented incremental pipeline. A segment is cut only inside a pause. When there is no pause, a forced cut is made with overlap and a timestamp merge.
   - When the user taps stop, re-decode the whole utterance once if it fits the model window (≤ 20 s for Parakeet). On the NPU this costs about 0.1 s [I], and it removes any boundary errors.
   - The full raw audio is always kept, so a final pass is always possible. Details are in §6.
7. **What not to offer by default:**
   - Qwen3-ASR as the default. Its published Russian is weaker (FLEURS ru 9.91 for 0.6B, 5.99 for 1.7B). It is an autoregressive LLM. The 1.7B build needs 2.4–4.1 GB of RAM on a phone [V].
   - T-one as the default. It outputs lowercase Cyrillic only, with no punctuation and no Latin letters [V].
   - Whisper large-v3-turbo on the NPU. It exists only as a Qualcomm AI Hub zip and needs a custom runner.

   All three remain in the hub as optional or experimental entries.

---

## 2. Model families: what is current on 2026-11-18

| Family (owner's name) | Current releases found | Release date | Status for Murmur |
|---|---|---|---|
| Whisper | `openai/whisper-{tiny,base,small,medium}[.en]`, `large-v3`, `large-v3-turbo`. No newer OpenAI checkpoint on HF ([org listing](https://huggingface.co/openai)). Distilled: `distil-whisper/distil-large-v3.5` (English) | turbo 2024-10-01; distil-v3.5 2024-12 | Offered: small/medium on the NPU, turbo on the CPU, distil-v3.5 as an English option |
| Parakeet ("parquet") | `nvidia/parakeet-tdt-0.6b-v3` (25 EU languages), `parakeet-tdt-0.6b-v2` (en), `parakeet-unified-en-0.6b` (2026-04-07, en, offline+streaming). Third-party post-trains of v3: `moondream/parakeet-ultra`, `moondream/parakeet-redux` (2026-09) | v3 2025-08-04 | v3 is the core model; Ultra is optional |
| → Parakeet's streaming successor | `nvidia/nemotron-3.5-asr-streaming-0.6b` (**40 locales incl. ru**, 2026-06-04), `nvidia/nemotron-speech-streaming-en-0.6b` (en) | 2026-06 / 2025-12 | **Default** |
| GigaAM (Sber) | `ai-sage/GigaAM-v3` (branches `ctc`, `rnnt`, `e2e_ctc`, `e2e_rnnt`, `ssl`); **new:** `ai-sage/GigaAM-Multilingual` (220M/600M char-CTC; ru, kk, ky, uz, en; 2026-07-14) | v3 2025-11-19 | v3 e2e offered; Multilingual noted but not offered (§3.3) |
| T-one (T-Bank) | `t-tech/T-one` only. No newer release; GitHub has no tagged releases; last push 2025-12-30 ([repo](https://github.com/voicekit-team/T-one)) | 2025-07-14 | Optional |
| Qwen3-ASR | `Qwen/Qwen3-ASR-0.6B`, `Qwen/Qwen3-ASR-1.7B`, `Qwen3-ForcedAligner-0.6B`; `-hf` Transformers-native copies of all three (2026-06-26) ([GitHub news](https://github.com/QwenLM/Qwen3-ASR)) | 2026-01-29 | Optional / experimental |

Repo listings came from `https://huggingface.co/api/models?author=<org>` for nvidia, ai-sage, t-tech, Qwen and openai [V].

---

## 3. Per-model facts

### 3.1 Summary table

WER is in %, lower is better. Numbers from different sources use different text normalisation and **must not be compared across rows**; see §4.4.

| Model | Params | Langs | Licence | Architecture | Streaming | P&C | Max audio / long-form | en WER (source) | ru WER (source) |
|---|---|---|---|---|---|---|---|---|---|
| Nemotron 3.5 ASR Streaming 0.6B | 600M | 40 locales (32 usable without fine-tuning), auto-LID | OpenMDW-1.1 | Cache-aware FastConformer + RNN-T + language prompt | **Yes**, chunks 80/160/320/560/1120 ms | Yes | Unbounded (streaming) | FLEURS 7.99 @560 ms, 7.91 @1.12 s | FLEURS 9.60 @560 ms, 9.17 @1.12 s; 10.57 auto-LID @560 ms ([card](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b)) |
| Nemotron Speech Streaming EN 0.6B | 600M | en | NVIDIA Open Model Licence | same, English | Yes | Yes | Unbounded | LS clean 2.32 / other 4.84 @1.12 s ([card](https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b)) | — |
| Parakeet TDT 0.6B v3 | 627M (AI Hub: enc 609.5M + dec 12.2M + joint 5.3M) | 25 EU (bg hr cs da nl en et fi fr de el hu it lv lt mt pl pt ro sk sl es sv **ru uk**), auto-LID | CC-BY-4.0 | FastConformer + TDT | No (buffered streaming only in NeMo) | Yes, plus word timestamps | 24 min full attention on A100, 3 h local attention (upstream) | LS 1.93 / 3.59; Open ASR mean 6.32; FLEURS 4.85 | FLEURS 5.51; CoVoST 3.00 ([card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)) |
| Parakeet Ultra (Moondream) | same as v3 | same as v3 | CC-BY-4.0 | same as v3, post-trained | No | Yes | — | LS 1.41 / 2.98; Open ASR 7-set 5.80 | FLEURS 5.21 (v3 = 5.91 in the same table) ([card](https://huggingface.co/moondream/parakeet-ultra)) |
| GigaAM v3 e2e_rnnt | 220–240M (card gives a range) | ru | MIT | Conformer (HuBERT-CTC pretrain) + RNN-T | No | **Yes**, plus normalisation | **25 s per `transcribe()` call**; long-form needs a VAD ([GitHub](https://github.com/salute-developers/GigaAM)) | not published | avg 11.2 over 10 sets; CV19 3.0; Golos farfield 5.5 (P&C stripped) ([evaluation.md](https://github.com/salute-developers/GigaAM/blob/main/evaluation.md)) |
| GigaAM v3 e2e_ctc | 220–240M | ru | MIT | Conformer + CTC | No | Yes | 25 s | — | avg 12.0 |
| GigaAM v3 rnnt (non-e2e) | 220–240M | ru | MIT | Conformer + RNN-T | No | **No** (34-token lowercase Cyrillic vocabulary [V]) | 25 s | — | avg 8.3 |
| GigaAM Multilingual large_ctc | 600M | ru, kk, ky, uz; "moderate" en | MIT | Conformer + char CTC | No | No (lowercase Latin+Cyrillic chars) | — | FLEURS 9.4, CV 21.5 | FLEURS 3.0, CV 5.1 ([card](https://huggingface.co/ai-sage/GigaAM-Multilingual)) |
| T-one | 71M | ru | Apache-2.0 | Streaming Conformer + CTC, 300 ms chunks, **8 kHz** input | **Yes** | **No** (vocabulary = 33 Cyrillic letters + space [V], [config](https://huggingface.co/t-tech/T-one/blob/main/config.json)) | Unbounded | — | CV19 5.32; call-centre 8.63 ([card](https://huggingface.co/t-tech/T-one)) |
| Qwen3-ASR 0.6B | "0.6B" (bf16 safetensors 1,876,091,704 B) | 30 languages + 22 Chinese dialects, auto-LID | Apache-2.0 | Audio-LLM (audio encoder + Qwen3 decoder) | "Streaming" = re-decode growing audio (§6.2); vLLM only upstream | Yes | 1,200 s per call (`MAX_ASR_INPUT_SECONDS`) with low-energy chunk split ([utils.py](https://github.com/QwenLM/Qwen3-ASR/blob/main/qwen_asr/inference/utils.py)) | LS 2.11 / 4.55; FLEURS-en 4.39 | FLEURS 9.91; CV 14.07; MLC-SLM 19.24 ([arXiv 2601.21337 Table A.2](https://arxiv.org/abs/2601.21337)) |
| Qwen3-ASR 1.7B | 2.04B total (LiteRT card) | same | Apache-2.0 | Audio-LLM | same | Yes | same | LS 1.63 / 3.38; FLEURS-en 3.35 | FLEURS 5.99; CV 8.28; MLC-SLM 15.17 |
| Whisper large-v3-turbo | 809M | 99 | MIT | Encoder-decoder (4 decoder layers) | No | Yes | 30 s window; sequential or chunked long-form ([card](https://huggingface.co/openai/whisper-large-v3-turbo)) | not on the card (large-v3: LS 1.51 / 3.97 per the [Qwen3-ASR card](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)) | **UNVERIFIED** for turbo. large-v3 FLEURS-ru 3.1, CV-ru 9.1 per [GigaAM eval](https://github.com/salute-developers/GigaAM/blob/main/evaluation.md) |
| Whisper small / medium | 244M / 769M | 99 | Apache-2.0 (HF metadata) | Encoder-decoder | No | Yes | 30 s | UNVERIFIED (not captured) | UNVERIFIED |
| Distil-Whisper large-v3.5 | 756M | en | MIT | Encoder-decoder, 2 decoder layers | No | Yes | 30 s | Open ASR mean 7.21; LS 2.37 / 5.04 ([card](https://huggingface.co/distil-whisper/distil-large-v3.5)) | — |

**Gated or licence-restricted:** none of the offered repos is gated (`gated: false` in the HF API for every repo above) [V].
- **CC-BY-4.0 (Parakeet):** requires attribution. Add an "Open-source models" screen in Settings.
- **NVIDIA Open Model Licence (Nemotron EN, parakeet-unified-en):** commercial use is allowed per the cards, but it is a custom licence. The owner should read it.
- **OpenMDW-1.1:** permissive. You must keep the licence and origin notices when redistributing. Your rights terminate if you sue over the model ([licence text](https://openmdw.ai/license/1-1/)).
- **Gated dependency to avoid:** GigaAM's upstream long-form path depends on the **gated** `pyannote/segmentation-3.0`. We use Silero VAD instead, which avoids it.
- **Silero VAD:** MIT ([repo](https://github.com/snakers4/silero-vad)).

### 3.2 Per-family notes (what the implementer must know)

**Nemotron 3.5 ASR (NVIDIA)**
- Select the language per stream: `en`, `ru`, or `auto`. In `auto` mode the model appends a language tag after the final punctuation; sherpa-onnx strips it ([sherpa docs](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/nemo/nemotron-streaming.rst); the [HF pack README](https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11) says: "Use per-stream language strings such as "en", "ja", or "auto"").
- Each chunk size is a **separate export**: 80, 160, 320, 560 and 1120 ms. Larger chunks give better WER (ru: 10.84 @80 ms → 9.17 @1.12 s) [V].
- Latency is set by `att_context_size` [56, R] with R ∈ {0, 1, 3, 6, 13}, in 80 ms frames [V].
- Mixed ru/en inside one utterance: **UNVERIFIED**. Test both `ru` and `auto` on code-switched phrases in the Benchmark screen.

**Parakeet TDT 0.6B v3**
- It is not a streaming model. The LiteRT and sherpa-onnx builds use fixed windows: LiteRT uses 5 s with overlap; sherpa QNN offers 3–30 s binaries. If the input is longer than the QNN window it is **truncated**, and if shorter it is padded ([sherpa QNN docs](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/qnn/run-executables-on-your-phone-binary.rst)) [V]. The VAD segmenter must therefore never emit a segment longer than the window.
- Language is detected automatically. There is no way to force it.
- A shared Latin+Cyrillic vocabulary means mixed ru/en is at least representable [I].

**GigaAM v3**
- Only the e2e variants punctuate. The e2e vocabularies contain Latin letters, digits and «», so it writes "2025" rather than "две тысячи двадцать пять" [V] (vocab inspected: [istupakov/gigaam-v3-onnx](https://huggingface.co/istupakov/gigaam-v3-onnx)). Russian is still the only training language.
- The 25 s limit per call is upstream guidance [V]. Use VAD segments of ≤ 20 s.
- **GigaAM Multilingual** (2026-07) has no punctuation and only "moderate" English (FLEURS-en 9.4–12.2). It does not beat v3 e2e for dictation, so it is not offered [I].

**T-one**
- Input is 8 kHz [V], so the pipeline must resample from 16 kHz. Whether sherpa-onnx resamples automatically for this model is UNVERIFIED; resample in-app to be safe.
- The output has no Latin letters, digits or punctuation. Every English word in a ru+en dictation will be lost or transliterated [I]. The LLM must restore all formatting.
- The upstream accuracy uses a 5.46 GB KenLM (`kenlm.bin`) [V]. The KenLM is not offered: too big for the phone.

**Qwen3-ASR**
- The upstream "streaming" re-feeds **all audio accumulated so far** on every 2 s chunk. It uses the previous text minus the last `unfixed_token_num = 5` tokens as a prefix ([qwen3_asr.py](https://github.com/QwenLM/Qwen3-ASR/blob/main/qwen_asr/inference/qwen3_asr.py)) [V]. That is O(n²) and only usable for short utterances [I].
- Published streaming-mode WER is worse than offline (0.6B LibriSpeech/FLEURS-en/FLEURS-zh avg 3.48 offline → 4.40 streaming) [V].
- The decoder is autoregressive: final latency grows with text length.

**Whisper**
- Fixed 30 s receptive field [V]. Short inputs are padded to 30 s, so cost per call is constant [V for the QNN exports: "Input resolution: 128x3000 (30 seconds audio)"].
- Hallucinates on silence or noise. Never feed it non-speech; gate it with the VAD [I, well known].
- The language is chosen per window. Code-switching inside one window is weak [I].

---

## 4. On-device formats: exact artifacts (all confirmed via HF API / GitHub API on 2026-11-18)

The JSON has full sha256 values, pinned HF revisions and URLs. Sizes are in bytes.

### 4.1 NPU (SM8750 = Snapdragon 8 Elite for Galaxy)

| Model | Artifact | Where | Bytes |
|---|---|---|---|
| Nemotron 3.5 560 ms | `sherpa-onnx-qnn-SM8750-binary-nemotron-3.5-asr-streaming-0.6b-560ms.tar.bz2` | GitHub release `k2-fsa/sherpa-onnx@asr-models-qnn-binary-3` | 442,651,414 |
| — other chunk sizes | `…-{80ms,160ms,320ms,1120ms}.tar.bz2` | same release | ≈442 M each |
| — SoC-independent build (`libmodel.so`) | `sherpa-onnx-qnn-nemotron-3.5-asr-streaming-0.6b-560s-android-aarch64.tar.bz2` | `asr-models-qnn-3` | 440,285,052 |
| Parakeet TDT 0.6B v3, 20 s window | `sherpa-onnx-qnn-SM8750-binary-parakeet-tdt-0.6b-v3-20s-transducer.tar.bz2` | `asr-models-qnn-binary-2` | 446,220,152 |
| — other windows | 3, 5, 8, 10, 13, 15, 18, 23, 25, 28, 30 s | same | 437.6–449.5 M |
| — SoC-independent build | `sherpa-onnx-qnn-parakeet-tdt-0.6b-v3-20s-android-aarch64.tar.bz2` | `asr-models-qnn-2` | 438,119,184 |
| Nemotron Speech EN 560 ms | `sherpa-onnx-qnn-SM8750-binary-nemotron-speech-streaming-en-0.6b-560ms.tar.bz2` | `asr-models-qnn-binary-2` | 424,330,705 |
| Whisper small / medium | `sherpa-onnx-qnn-SM8750-binary-whisper-small.tar.bz2` / `…-whisper-medium.tar.bz2` | `asr-models-qnn-binary-2` | 507,088,655 / 1,477,526,214 |
| Whisper large-v3-turbo w8a16 | `whisper_large_v3_turbo_quantized-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_for_galaxy.zip` | Qualcomm AI Hub S3 (linked from [qualcomm/Whisper-Large-V3-Turbo-Quantized](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized)) | 823,682,281 (HTTP HEAD) |
| Parakeet v3 (Qualcomm export) | `parakeet_tdt-qnn_dlc-float.zip` (universal DLC, not SoC-specific) | Qualcomm AI Hub S3 ([qualcomm/Parakeet-TDT-0.6B-v3](https://huggingface.co/qualcomm/Parakeet-TDT-0.6B-v3)) | 2,266,727,995 |
| Parakeet v3 (LiteRT NPU) | `parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8750.tflite` | HF `litert-community/parakeet-tdt-0.6b-v3` | 1,274,777,312 |
| Qwen3-ASR 0.6B (LiteRT NPU) | `qwen3_asr_0.6b_5s_f32_Qualcomm_SM8750.tflite` | HF `litert-community/Qwen3-ASR-0.6B` | 1,896,782,144 |

**Not available as an NPU build anywhere I could find:** GigaAM (any version), T-one, Parakeet Ultra, Qwen3-ASR 1.7B, Whisper turbo as a sherpa package.
- sherpa-onnx's QNN code covers offline parakeet-tdt/ctc, whisper, moonshine, sense-voice, zipformer, paraformer, and online NeMo-transducer/zipformer ([csrc/qnn](https://github.com/k2-fsa/sherpa-onnx/tree/master/sherpa-onnx/csrc/qnn)) [V].
- `andrewsab/gigaam-v3-e2e-rnnt-ov-npu` exists, but it is OpenVINO (Intel), so it is not relevant.

**`model.bin` vs `libmodel.so`** [V] ([comparison](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/qnn/qnn_model_comparison.rst)):
- `model.bin` loads fast, but it is tied to one SoC **and to the QNN SDK version it was built with**.
- `libmodel.so` works on any SoC and any SDK version, but the first run is slow because the context is generated on the device.

Which QAIRT version built the `asr-models-qnn-binary-2/3` binaries is not documented. The older `asr-models-qnn` release ships `qnn-libs-2.40.0.251030`. The LLM agent's Qualcomm AI Hub artifacts use **QAIRT 2.50**. Since one process can load only one `libQnnHtp.so`, **this version mismatch is a real risk** (§9).

### 4.2 CPU (sherpa-onnx + ONNX Runtime), all on Hugging Face

| Model | HF repo | Files (bytes) |
|---|---|---|
| Parakeet TDT 0.6B v3 int8 | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` | encoder.int8.onnx 652,282,294; decoder.int8.onnx 11,845,274; joiner.int8.onnx 6,355,277; tokens.txt 93,939 |
| Parakeet Ultra int8 | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8` | encoder 652,282,345; decoder 11,845,274; joiner 6,355,277; tokens 93,939 |
| Nemotron 3.5 560 ms int8 | `csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11` | encoder 657,601,403; decoder 14,978,075; joiner 9,504,438; tokens 131,440 (other chunk sizes in sibling repos `…-{80,160,320,1120}ms-int8-2026-06-11`) |
| GigaAM v3 e2e RNN-T | `csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16` | encoder.int8.onnx 224,570,820; decoder.onnx 4,600,132; joiner.onnx 2,712,896; tokens.txt 13,354 |
| GigaAM v3 e2e CTC | `csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16` | model.int8.onnx 224,893,661; tokens.txt 2,007 |
| T-one | `csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08` | model.onnx 144,193,702; tokens.txt 202 |
| Qwen3-ASR 0.6B int8 | `csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` | conv_frontend.onnx 44,148,281; encoder.int8.onnx 182,491,662; decoder.int8.onnx 755,914,231; tokenizer/{vocab.json 2,776,833, merges.txt 1,671,853, tokenizer_config.json 12,487} |
| Whisper large-v3-turbo int8 | `csukuangfj/sherpa-onnx-whisper-turbo` | turbo-encoder.int8.onnx 674,716,297; turbo-decoder.int8.onnx 361,080,764; turbo-tokens.txt 816,730 |
| Distil-Whisper large-v3.5 int8 | `csukuangfj/sherpa-onnx-whisper-distil-large-v3.5` | encoder.int8 668,147,593; decoder.int8 314,871,757; tokens 816,730 |
| Silero VAD | GitHub `k2-fsa/sherpa-onnx@asr-models` `silero_vad.onnx` 643,854 (bundle in the APK). HF alternative: `onnx-community/silero-vad` `onnx/model.onnx` 2,243,022 (sherpa compatibility UNVERIFIED) | |

**Other formats that exist, not chosen:**
- GGUF for [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp): `handy-computer/*-gguf` covers Parakeet, GigaAM, Qwen3-ASR, Whisper and Nemotron 3.5. It has no Android build and no NPU backend; its releases are Linux, macOS and Windows only.
- NVIDIA's own `nemotron-3.5-asr-streaming-0.6b.q8_0.gguf` (742,090,464 B) for [NeMo-Speech.cpp](https://github.com/NVIDIA/NeMo-Speech.cpp). Its v0.3.0 releases have no Android target.
- `ggerganov/whisper.cpp` GGML: CPU/GPU only.
- LiteRT `.litertlm` bundles for MediaPipe `AudioTranscriber` (Parakeet v3 int8 614,588,752 B). This is a viable second stack.

### 4.3 Reported mobile speed and RAM (primary sources only)

| Model / build | Device | Reported | Source |
|---|---|---|---|
| Parakeet v3, QNN_DLC float, 13.2 s window (`MAX_TIME_STEPS=1320`) | Snapdragon 8 Elite for Galaxy NPU | encoder **38.0 ms**; decoder step 0.237 ms; joint step 0.224 ms; encoder peak memory 1–948 MB | [qualcomm/Parakeet-TDT-0.6B-v3](https://huggingface.co/qualcomm/Parakeet-TDT-0.6B-v3), [model.py](https://github.com/qualcomm/ai-hub-models/blob/v0.66.0/src/qai_hub_models/models/parakeet_tdt/model.py) |
| Whisper large-v3-turbo w8a16 | same | encoder 150.7 ms per 30 s window; decoder 3.54 ms/token; max 200 tokens | [card](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized) |
| Whisper medium w8a16 / small w8a16 | same | enc 97.4 ms, dec 13.4 ms/tok / enc 34.4 ms, dec 4.7 ms/tok | [medium](https://huggingface.co/qualcomm/Whisper-Medium-Quantized), [small](https://huggingface.co/qualcomm/Whisper-Small-Quantized) |
| Parakeet v3 LiteRT INT8 | Galaxy S24 Ultra **CPU** (8 Gen 3) | RTF 0.064–0.104; peak RAM 765–779 MB; GPU slower than CPU | [litert-community card](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3) |
| Qwen3-ASR 1.7B LiteRT-LM, 15 s file | Galaxy S26 | 6.4 s clip in 1.40–1.51 s (GPU), 1.55 s (CPU); decode 23.5–26 tok/s; peak 2.41 GB (GPU) / 4.10 GB (CPU) | [card](https://huggingface.co/litert-community/Qwen3-ASR-1.7B) |
| SenseVoice QNN binary (reference for sherpa QNN overhead) | SM8850 | RTF 0.009 for 5.6 s audio; recognizer created in 0.535 s | [sherpa log](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/qnn/code/sennse-voice-8850-5-seconds-binary.txt) |

No primary source gives Fold7/SM8750 numbers for the **sherpa-onnx** QNN Nemotron, Parakeet or Whisper binaries, or for GigaAM, T-one or Qwen3-0.6B on any recent Snapdragon. These must be measured by the Benchmark button. **No device is attached to the build server, so every speed and RAM figure for Murmur itself is unvalidated until a Fold7 run.**

### 4.4 Why the WER columns disagree

The same language and test set give different numbers on different cards:
- Parakeet v3 FLEURS-ru is 5.51 on NVIDIA's card and 5.91 on Moondream's table.
- Nemotron's own card warns that its normalisation inflates its numbers.

Comparisons inside one table (e.g. Moondream v3 vs Ultra, or GigaAM's 10-set table) are valid. Comparisons across cards are not. **Action:** the Benchmark button should report WER on a fixed ru+en clip set, with one normaliser, for every model (§7.3). If the owner wants an apples-to-apples offline comparison, it can run on the build server's CPU or a Hermes GPU (§10).

---

## 5. RAM and footprint plan (12 GB device)

[I] These are estimates from file sizes and the published peaks above.

| Model | Download | Expected resident during dictation |
|---|---|---|
| Nemotron 3.5 QNN | 443 MB | ≈0.5–1 GB (context binary + HTP buffers) |
| Parakeet v3 QNN | 446 MB | ≈0.5–1 GB (AI Hub encoder peak up to 948 MB for float DLC) |
| Parakeet v3 CPU int8 | 671 MB | ≈0.8 GB (LiteRT int8 equivalent: 765–779 MB) |
| GigaAM v3 e2e CPU int8 | 232 MB | ≈0.3–0.5 GB |
| T-one CPU | 144 MB | ≈0.2–0.3 GB |
| Qwen3-ASR 0.6B CPU int8 | 987 MB | ≈1.2–1.5 GB |
| Qwen3-ASR 1.7B LiteRT GPU | 2.70 GB | 2.4–4.1 GB [V, S26] |

**Load policy (brief: no 24/7 residency):**
- Load the ASR at bubble-tap. Unload it after the final text plus a short grace period (e.g. 30 s), to cover back-to-back dictations.
- The QNN context load time on SM8750 must be measured. The sherpa SM8850 log shows 0.5–1.2 s to create a recognizer [V].
- Audio captured before the model is ready must be buffered and fed once it loads. This is mandatory: the user starts talking immediately.

---

## 6. Transcribing while the user is still speaking (answer to brief step 6)

### 6.1 Requirements restated

- The final text must contain every word. No audio can be dropped at segment boundaries.
- Final text should be ready as close as possible to the stop tap, so the LLM can start immediately.
- Live partial text is a nice-to-have for the bubble UI. It is not required for correctness.
- The stop tap (not silence) ends dictation. This makes the problem much easier than voice-agent endpointing: we never have to guess when the user is done.

### 6.2 The three approaches compared

| | A. True streaming (cache-aware RNN-T / streaming CTC) | B. VAD-segmented incremental batch | C. Whisper-style re-decoding window + agreement policy |
|---|---|---|---|
| Models | Nemotron 3.5, Nemotron EN, T-one | Parakeet, GigaAM, Qwen3-ASR, Whisper | Whisper, Qwen3-ASR (upstream "streaming") |
| How | Feed 80–1120 ms chunks; the encoder keeps caches; every frame processed once [V] | Silero VAD splits audio at pauses; each closed segment is transcribed once and committed | Re-transcribe a growing buffer every 1–2 s; emit only the prefix that 2 consecutive runs agree on (LocalAgreement-2, [whisper_streaming](https://github.com/ufal/whisper_streaming): 3.3 s latency on long-form [V]); trim the buffer at confirmed sentence ends |
| Information loss | None by construction | None **if** cuts happen only in silence or use overlap (below) | None if the buffer is trimmed only at confirmed boundaries; risk of duplicated or dropped words at trims |
| Accuracy | Slightly below offline (Nemotron ru 9.17–10.84 by chunk size) | Offline accuracy inside each segment; small loss at forced cuts | Offline accuracy, but unstable partials |
| Compute | Linear, minimal | Linear (each sample encoded once, plus about 10 % overlap on forced cuts) | Quadratic in buffer length; 2–5× more compute [I]; Qwen3-ASR re-feeds all audio every 2 s [V] |
| Partials | Native, stable, low lag (≈ chunk size) | Final per segment (appears after each pause); optional interim decode of the open segment | Native but flickering; lag 1–3 s |
| Fit with the NPU | Good: fixed-shape chunk graph | Good: fixed-window graph (pad to 20 s) | Poor: repeated 30 s encoder calls |

**Verdict:** use A where the model supports it and B for everything else. C is not worth implementing: B plus the final re-decode (§6.4) gives the same "no loss" guarantee at a fraction of the compute. UFAL itself now marks whisper_streaming as superseded by [SimulStreaming](https://github.com/ufal/SimulStreaming) [V].

### 6.3 Approach B in detail (the segmenter)

**VAD.**
- Silero VAD through the sherpa-onnx `Vad` API, at 16 kHz with 512-sample (32 ms) windows [V] ([utils_vad.py v6.2.3](https://github.com/snakers4/silero-vad/blob/v6.2.3/src/silero_vad/utils_vad.py); upstream claims < 1 ms per chunk on one CPU thread [V]).
- Run it on the CPU. It is tiny and must not compete with the ASR for the NPU.
- Upstream defaults [V]: threshold 0.5, neg_threshold = threshold − 0.15, min_speech 250 ms, min_silence 100 ms, speech_pad 30 ms.
- Murmur starting values [I, tune with the Benchmark set]:

| Parameter | Value | Why |
|---|---|---|
| `threshold` / `neg_threshold` | 0.5 / 0.35 | upstream default; hysteresis avoids chattering |
| `minSilenceToCutMs` | 500 | dictation has short intra-sentence pauses; 100 ms cuts mid-phrase and hurts context |
| `preRollMs` | 300 | keep audio before speech onset: plosives and soft onsets are often below threshold |
| `postRollMs` | 300 | keep trailing fricatives and word ends |
| `minSegmentMs` | 400 | shorter → merge into the next segment, never drop it |
| `softMaxSegmentSec` | 0.7 × model window (14 s for a 20 s Parakeet binary; 17 s for GigaAM's 25 s) | start accepting shorter pauses (≥ 150 ms) as cut points |
| `hardMaxSegmentSec` | window − 0.5 s (19.5 s Parakeet-20s; 20 s GigaAM; 29 s Whisper) | QNN inputs are truncated beyond the window [V], so never exceed it |

**Cutting rules (never lose audio):**
1. The audio ring buffer keeps everything since the bubble tap. Segments are views into it, not copies taken off a stream. Nothing is ever discarded until the dictation is saved to history (brief step 8 needs the audio anyway).
2. **Normal cut.** When the VAD reports silence ≥ `minSilenceToCutMs`, cut at the midpoint of the silence. The segment is [start − preRoll, cut], and the next segment starts at the cut. Because the cut is inside silence, nothing is split. Using the midpoint gives both sides their roll.
3. **Soft limit reached** (segment ≥ softMax and still speaking). Lower the silence requirement to 150 ms, and cut at the first such pause.
4. **No pause at all** (segment reaches hardMax). This is a forced cut:
   - Pick the 32 ms frame with the **lowest VAD probability, ties broken by lowest RMS energy**, within the last 2.0 s before hardMax. Cut there.
   - Transcribe segment N up to cut + 1.0 s. Start segment N+1 at cut − 1.0 s. That is a 2 s overlap in total.
   - **Merge** with model timestamps. Parakeet TDT and GigaAM RNN-T/CTC return token timestamps in sherpa-onnx [I, per the NeMo/sherpa result structs]. Keep words of N whose centre is < cut, and words of N+1 whose centre is ≥ cut. Dedupe the 1–2 words nearest the cut by normalised string match.
   - If a model gives no timestamps (Whisper under QNN, Qwen3-ASR), merge by longest common word suffix/prefix over the overlap text.
5. **Code-switch boundary.** No special rule. Parakeet v3 detects language per segment automatically. Nemotron gets a fixed or `auto` prompt per stream.
6. **Stop tap.** Close the open segment immediately (no need to wait for silence) with postRoll = whatever audio exists, and transcribe it.

**Final re-decode (accuracy safety net):**
- If total speech ≤ hardMax (most dictations are shorter than 20 s [I]), transcribe the whole utterance once more as a single segment, and use that result as the final text.
- The incremental segments then served only for live preview and as the fallback if the final pass fails.
- On the Parakeet NPU this costs about one encoder call (≈40–60 ms for a 13–20 s window [V for 13.2 s]) plus decoding.
- If speech > hardMax, keep the committed segments. Optionally re-decode only the forced-cut overlaps.

**Interim text for the open segment (optional UI polish).**
- On NPU models, every 1.0 s re-decode the open segment (≤ 20 s, padded window, fixed cost) and show it in grey as `Partial`.
- Throttle this on the CPU (every 2 s, or not at all for Whisper/Qwen on CPU).

### 6.4 Recommended strategy per family and expected latency

"Latency" means stop tap → final ASR text available to the LLM. Estimates are [I]: device timings published for SM8750 (§4.3) plus a QNN call overhead of 5–20 ms per call, which is my assumption. Every figure must be confirmed on the Fold7.

| Model | Strategy | Partials while speaking | Est. stop → final text |
|---|---|---|---|
| Nemotron 3.5 / EN, NPU, 560 ms | A: OnlineRecognizer; on stop, feed about 1 chunk of zero padding, call `inputFinished()`, decode remaining | Yes, lag ≈ 0.6–1.2 s | **≈ 50–150 ms** |
| Nemotron 3.5, CPU int8 | A | Yes | ≈ 150–400 ms |
| T-one, CPU | A (300 ms chunks, 8 kHz) | Yes | ≈ 50–150 ms (71M model) |
| Parakeet v3, NPU 20 s | B + final re-decode if ≤ 19.5 s | Per segment after pauses; optional 1 s interim | **≈ 100–250 ms** |
| Parakeet v3 / Ultra, CPU int8 | B; final re-decode only if ≤ 8 s of speech | Per segment | tail segment × RTF 0.06–0.10 → ≈ 0.3–1.0 s for a 5–10 s tail |
| GigaAM v3 e2e, CPU int8 | B (hardMax 20 s); final re-decode if ≤ 8 s | Per segment | ≈ 0.2–0.6 s (UNVERIFIED; 220M encoder) |
| Whisper small/medium, NPU | B (hardMax 29 s); no interim | Per segment | 35–100 ms encoder + 5–13 ms × tokens → ≈ 0.3–1.0 s |
| Whisper turbo, CPU int8 | B | Per segment | ≈ 1.5–4 s (padded 30 s encoder on CPU) — slow |
| Qwen3-ASR 0.6B, CPU int8 | B (hardMax 20 s); no interim | Per segment | ≈ 1–2.5 s |
| Qwen3-ASR 1.7B, LiteRT GPU | B (15 s windows) | Per segment | ≈ 1.4–2.8 s per 6–11 s tail [V, S26] |

### 6.5 Concurrency with the LLM and OCR (must coordinate with the LLM agent)

- The LLM plan prefills screen context **on the NPU during dictation** ([llm-models.md §6](./llm-models.md)). Streaming ASR on the same HTP will then time-share with it. Expected effect: partial-text lag grows. Final latency should be unaffected, because prefill finishes before the stop tap [I].
- Order of priority on the HTP: ASR chunk > LLM prefill > OCR. If the HTP is saturated, partial updates are throttled. No audio is dropped, because the ring buffer decouples capture from inference.
- All QNN users in the process must share **one QAIRT runtime version** (§4.1, §9).

---

## 7. Transcriber interface (every model fits behind it)

### 7.1 Kotlin contract

```kotlin
/** Static facts from asr-catalog.json; the UI and the pipeline read only this. */
data class AsrCapabilities(
    val streaming: Boolean,               // true = feeds chunks natively (Nemotron, T-one)
    val maxWindowSec: Float?,             // null = unbounded; else segmenter hardMax = window - 0.5
    val inputSampleRate: Int,             // 16000, or 8000 for T-one; pipeline resamples
    val punctuation: Boolean,
    val casing: Boolean,
    val wordTimestamps: Boolean,          // enables timestamp merge on forced cuts
    val languages: List<String>,
    val languageMode: LanguageMode,       // AUTO_ONLY (Parakeet), HINT_OR_AUTO (Nemotron, Whisper, Qwen), FIXED (GigaAM, T-one)
    val accelerator: Accelerator,         // NPU, GPU, CPU
)

interface AsrEngine : AutoCloseable {
    val modelId: String
    val capabilities: AsrCapabilities
    suspend fun load(opts: LoadOptions)        // accelerator preference, cache dir; may take ~1 s
    fun newSession(opts: SessionOptions): AsrSession   // language hint, enableInterim
    suspend fun transcribe(pcm: FloatArray, sampleRate: Int, opts: SessionOptions): Transcript // one-shot (benchmark, final re-decode)
}

interface AsrSession {
    /** Called from the capture thread with 16 kHz mono float PCM; must never block (copies into ring buffer). */
    fun accept(pcm: FloatArray)
    val events: kotlinx.coroutines.flow.Flow<AsrEvent>
    /** Stop tap: flushes, runs final re-decode if applicable, returns the complete text. */
    suspend fun finish(): Transcript
    fun cancel()
}

sealed interface AsrEvent {
    /** Replaceable hypothesis for the not-yet-committed tail. stablePrefixLen = chars that will not change. */
    data class Partial(val text: String, val stablePrefixLen: Int) : AsrEvent
    /** Immutable, appended in order. startMs/endMs relative to the bubble tap. */
    data class Committed(val segmentIndex: Int, val text: String, val startMs: Long, val endMs: Long,
                         val words: List<Word>?, val language: String?) : AsrEvent
    data class Stats(val audioMs: Long, val computeMs: Long) : AsrEvent   // for Dashboard/Benchmark
    data class Failure(val error: Throwable, val recoverable: Boolean) : AsrEvent
}

data class Transcript(val text: String, val segments: List<AsrEvent.Committed>, val language: String?,
                      val finalPass: Boolean /* true if whole-utterance re-decode produced `text` */)
```

### 7.2 Two adapters implement `AsrSession`

- **`StreamingSession`** wraps sherpa-onnx `OnlineRecognizer` plus `OnlineStream`.
  - Disable sherpa's endpointing, or use it only to emit `Committed` at long pauses. The stop tap is the real end.
  - `Partial` = current result.
  - `finish()` = feed tail padding, call `inputFinished()`, then decode until not ready.
- **`SegmentingSession`** wraps sherpa-onnx `Vad` plus `OfflineRecognizer`, using the §6.3 rules.
  - It runs segments through a single-threaded inference queue, so segments are committed in order.
  - It does the timestamp/overlap merge and the final re-decode.
  - `maxWindowSec` comes from capabilities.
- The pipeline picks the adapter from `capabilities.streaming`. Nothing else in the app knows which model is loaded.
- The audio ring buffer, the resampler and the "buffer audio until the model is loaded" logic live **outside** the engine. They are shared by both adapters and by the history saver.

### 7.3 Benchmark button (ASR)

- Ship 10 short clips in the APK, about 60 s in total: 4 ru, 3 en, 3 mixed ru/en, read by a neutral voice, CC-licensed or recorded by the owner. Optionally add the owner's own recordings later.
- Report:
  - model load time;
  - RTF;
  - stop → final latency (simulated by feeding clips in real time);
  - peak PSS (from `Debug.getMemoryInfo`);
  - WER/CER against references, with one normaliser (lowercase, strip punctuation, numerals as-is).
- For streaming models, also report median partial lag.

---

## 8. Work items this research implies

1. **Toolchain:** sherpa-onnx v2.0.1 AAR plus a QNN-enabled build of its JNI libs, with `libQnnHtp.so`, `libQnnSystem.so` and the V79 stub/skel.
   - The stock AAR names show no QNN variant (`sherpa-onnx-2.0.1.aar`, `-rknn.aar`, `-static-link-onnxruntime.aar`) [V], so a source build per the [sherpa QNN Android guide](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/qnn/build-android-demo.rst) is required.
   - Hexagon V79 for SM8750 is UNVERIFIED. The guide lists V68–V81 libs without naming the SoC mapping.
2. **Hosting:** the brief requires downloads from Hugging Face, but the QNN binaries are GitHub-release assets.
   - Option (a): mirror them to an owner-controlled HF repo, e.g. `<owner>/murmur-asr-qnn-sm8750`. This needs the owner's HF token (via orchestrator → Hermes). Keep the upstream licence files and the CC-BY / OpenMDW notices.
   - Option (b): allow `github.com/k2-fsa/sherpa-onnx/releases` as a second trusted source. The catalog JSON already carries `source` per file.
3. **QAIRT alignment:** one QNN SDK version for ASR, LLM and OCR. Either confirm that the sherpa binaries load under QAIRT 2.50 on the Fold7, or regenerate SM8750 context binaries from the `libmodel.so` packages with the app's QAIRT version (sherpa provides the export flow). The fallback is to ship the `libmodel.so` variants and cache the generated context on first run.
4. **GigaAM on the NPU (stretch goal):**
   - Export `v3_e2e_ctc` first. CTC has no decoder loop, so it is a single static graph of [1, 64 mel, ≈2,500 frames] for 25 s. The config gives 64 mel features and a hop of 160 at 16 kHz [V] ([v3_e2e_ctc.yaml](https://huggingface.co/istupakov/gigaam-v3-onnx/blob/main/v3_e2e_ctc.yaml)).
   - Compile it for SM8750 with QAIRT, using the same recipe sherpa uses for `parakeet-ctc` QNN.
   - No GPU is needed; this runs on the x86 build server. Expect a 2–4-day spike [I].

---

## 9. Risks

- **QNN version and HTP contention.** The binaries' QAIRT version is unknown and may conflict with the LLM's QAIRT 2.50 stack. A failure means CPU fallback for ASR, which is slower but works (CPU fallbacks for all defaults are in the catalog).
- **No device validation possible here.** Every speed, RAM, load-time and NPU-compatibility claim for Murmur is unvalidated until the app runs on a Fold7.
- **Russian accuracy of the streaming default.** Nemotron 3.5's published ru WER (9.2–10.8) is clearly worse than Parakeet v3 (5.5) and GigaAM. The normalisation differs, but the gap may be real. If the Benchmark confirms it, switch the ru+en default to Parakeet v3 NPU with approach B. The interface makes this a config change.
- **ru/en code-switching.** No source publishes code-switched WER for any of these models. It may be poor across the board. The LLM post-processing then carries more weight.
- **Licences:** the NVIDIA Open Model Licence for the English Nemotron needs owner review. CC-BY requires an attribution UI. Mirroring weights to HF must keep the notices.
- **Hosting rule:** the GitHub-hosted QNN binaries break the "download from HF" rule unless they are mirrored.

---

## 10. Requests for the orchestrator (not contacted directly)

1. **HF token (Hermes):** needed only if the owner chooses to mirror the sherpa-onnx SM8750 QNN binaries (≈0.45 GB each, ≈1.4 GB for the default set) to an owner HF repo. No token is needed for any download in the catalog; all repos are public and ungated.
2. **GPU (Hermes, optional):** a one-hour 5090/H100 pod to run an apples-to-apples WER comparison on FLEURS-ru/en, Golos and a small code-switched set, with one normaliser, for Parakeet v3/Ultra, Nemotron 3.5 (560 ms), GigaAM v3 e2e, Qwen3-ASR 0.6B/1.7B and Whisper turbo. It would settle the default before any device time. It can also run on the build server CPU (16 cores) more slowly, so this is optional.

---

## 11. Unverified items (do not build on them without checking)

- QAIRT/QNN SDK version used to build the `asr-models-qnn-binary-2/3` SM8750 context binaries.
- Whether sherpa-onnx QNN binaries load under QAIRT 2.50.
- That SM8750 uses the Hexagon V79 stub/skel libraries.
- sherpa-onnx per-chunk NPU timings for Nemotron, and all Fold7 timings.
- Whisper large-v3-turbo Russian WER from a primary source. Whisper small/medium WER.
- ru/en code-switching quality of every model.
- Whether sherpa-onnx resamples internally for T-one's 8 kHz input.
- Whether the sherpa-onnx Vad accepts `onnx-community/silero-vad` `model.onnx`, and which Silero version the sherpa `silero_vad.onnx` asset is.
- Whether a Parakeet Ultra QNN binary can be produced with sherpa's v3 export unchanged.
- GigaAM v3 exact parameter count (the card gives 220–240M).

## 12. Sources (all fetched 2026-11-18)

- HF API `https://huggingface.co/api/models/<repo>?blobs=true` for every repo named in §4. Raw responses were used to generate `asr-catalog.json`.
- Model cards: [Nemotron 3.5](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) · [Nemotron EN](https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b) · [Parakeet v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) · [parakeet-unified-en](https://huggingface.co/nvidia/parakeet-unified-en-0.6b) · [Parakeet Ultra](https://huggingface.co/moondream/parakeet-ultra) · [Parakeet Redux](https://huggingface.co/moondream/parakeet-redux) · [GigaAM v3](https://huggingface.co/ai-sage/GigaAM-v3) · [GigaAM Multilingual](https://huggingface.co/ai-sage/GigaAM-Multilingual) · [T-one](https://huggingface.co/t-tech/T-one) · [Qwen3-ASR-0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B) · [Whisper turbo](https://huggingface.co/openai/whisper-large-v3-turbo) · [distil-large-v3.5](https://huggingface.co/distil-whisper/distil-large-v3.5)
- On-device builds: [litert-community/parakeet-tdt-0.6b-v3](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3) · [litert-community/Qwen3-ASR-0.6B](https://huggingface.co/litert-community/Qwen3-ASR-0.6B) · [litert-community/Qwen3-ASR-1.7B](https://huggingface.co/litert-community/Qwen3-ASR-1.7B) · [qualcomm/Parakeet-TDT-0.6B-v3](https://huggingface.co/qualcomm/Parakeet-TDT-0.6B-v3) · [qualcomm/Whisper-Large-V3-Turbo-Quantized](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized) · sherpa-onnx releases [asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models), [asr-models-qnn-2](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models-qnn-2), [asr-models-qnn-3](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models-qnn-3), [asr-models-qnn-binary-2](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models-qnn-binary-2), [asr-models-qnn-binary-3](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models-qnn-binary-3), [v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1)
- Code and docs: [Qwen3-ASR inference](https://github.com/QwenLM/Qwen3-ASR/tree/main/qwen_asr/inference) · [Qwen3-ASR tech report arXiv 2601.21337](https://arxiv.org/abs/2601.21337) · [GigaAM GitHub](https://github.com/salute-developers/GigaAM) · [sherpa QNN docs](https://github.com/k2-fsa/sherpa/tree/master/docs/source/onnx/qnn) · [Silero VAD v6.2.3](https://github.com/snakers4/silero-vad/releases/tag/v6.2.3) · [whisper_streaming](https://github.com/ufal/whisper_streaming) · [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) · [NeMo-Speech.cpp](https://github.com/NVIDIA/NeMo-Speech.cpp) · [OpenMDW-1.1](https://openmdw.ai/license/1-1/)
- Device: [GSMArena Fold7](https://m.gsmarena.com/samsung_galaxy_z_fold7-13826.php), [TechRadar Fold7 specs](https://www.techradar.com/phones/samsung-galaxy-phones/samsung-galaxy-z-fold-7-specs)
