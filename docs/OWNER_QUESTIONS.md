# Questions for the owner

Collected from the work-package reports at integration. Each item: the question in plain words, what the architect
recommends, and whether it blocks anything. Answer in any order; "go with the recommendation" is a valid answer.

Updated: wave-1 integration pass 1 (2026-11-19; WP-01, 05, 06, 07, 08, 09, 11, 12, 13 merged).

## Models and ML

1. **Where can the app download the fine-tuned cleanup models?** (WP-13) The three fine-tune entries
   (`murmur-cleanup-lfm3-1.7b`, `-qwen3.5-0.8b`, `-qwen3.5-2b`) stay "in training" until their GGUF files are in a public
   Hugging Face repo (the ML team's repos are private, and the app has no token).
   *Recommendation:* one public HF repo owned by you (Hermes holds the token) with pinned revisions; the same repo can
   mirror the two GitHub-only assets (sherpa NPU binaries, Silero VAD) so that downloads come from Hugging Face only, as
   D9 says (ADR-016).
   **Blocks:** WP-13 Deliver 8 (fine-tunes downloadable in the hub). Nothing else.
2. **Do you want Whisper tiny/base on the NPU?** (WP-13) Sherpa has SM8750 binaries for them; the catalog lists
   Whisper small/medium (NPU) and large-v3-turbo (CPU) today.
   *Recommendation:* no. Their Russian accuracy is weak and the hub already covers the range.
   Blocks nothing.
3. **May the OCR reference store its confidence values unrounded?** (WP-07) `ml/ocr` goldens keep the recogniser
   confidence with 4 digits, so 36 of 578 values cannot be reproduced exactly. The Kotlin port is byte-identical
   everywhere else (26/26 pages).
   *Recommendation:* yes: the ML owner stores 6+ digits in `golden.py` and regenerates the goldens (minutes of work).
   **Blocks:** only WP-07's acceptance item 1 as worded (exact JSON equality from `stages.rec`); parity itself is proven.

## Product behaviour

4. **Should a successful Retry count again on the Dashboard?** (WP-11) Retry now updates the failed History entry in
   place. The numbers row for that dictation was already written when it failed, so a retry writes no second one: the
   words of a successful retry do not appear in "Today, words" and the recording time is not counted twice.
   *Recommendation:* keep it (one dictation = one row; rows stay append-only). The alternative is updating the old
   numbers row, which costs a schema change.
   Blocks nothing (WP-17 shows whatever the rows hold).

## Design and accessibility

5. **With "Remove animations" on, may the 150 ms cross-fades become instant?** (WP-01) Compose scales every animation
   by the system animator scale, so DESIGN 3.5's reduced-motion fades disappear when the user turns animations off.
   *Recommendation:* yes, follow the system (that is what the setting asks for); keeping the fades needs a custom clock.
   Blocks nothing.
6. **Darken two app-tile colours?** (WP-01) White letters on `#B9861E` (3.23:1) and `#3C7EBF` (4.26:1) are below the
   4.5:1 text contrast. The tiles are treated as decorative today (3:1 graphic contrast passes).
   *Recommendation:* the design owner darkens both in `docs/design/tokens.json` so the letters pass as text.
   Blocks nothing.

## Decided by the architect in this pass (say if you disagree)

- Emulator timing criteria (ASR as well as LLM) run with the host CPU exposed (`MURMUR_EMU_CPU_HOST=1`); the shared
  AVDs stay at 3 GB (ADR-076, from WP-06).
- A secure window (banking apps, FLAG_SECURE) sends the cleanup model only the app name, no field text (ADR-070).
- A unit whose newest benchmark looks wrong is not chosen, even if an older run was fine (ADR-071).
- Retry runs visibly through the bubble and inserts into the focused field (ADR-064).

## Still open from architecture v2 (unchanged, listed for completeness)

- **Licences** (blocks shipping any NPU library; DC8 APKs stay local until then): Hexagon SDK Community Edition
  redistribution of `libggml-htp-v79.so`; the `qnn-runtime` licence (export clause). See `docs/DEVICE_CHECKLIST.md`.
- **"NPU for all models"** is not met in v1 (VAD and T-one stay CPU; OCR and GigaAM NPU after DC4): sign-off needed
  (ADR-059).
- Metrics kept 90 days independent of history retention (ADR-035).
- Long dictations: cleanup runs once over the whole transcript (D4); on LFM3 NPU the longest cleaned dictation is
  ≈ 3.7 min, chunked cleanup would remove the limit (ADR-037).
- Toolchain owner: `tools/gw` could export `ANDROID_HOME` itself (ADR-052).
