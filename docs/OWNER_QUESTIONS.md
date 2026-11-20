# Questions for the owner

Collected from the work-package reports at integration. Each item: the question in plain words, what the architect
recommends, and whether it blocks anything. Answer in any order; "go with the recommendation" is a valid answer.

Updated: integration pass 8 (2026-11-20; WP-21 merged: the NPU versions of screen reading and GigaAM, prepared and
switched off). Pass 8 added three open questions (17-19, under "NPU ports") and two architect decisions under
"Pass 8". Before: integration pass 7 (2026-11-20; wave 3 of WP-20 merged: the app runs on the real engines end to end, the
debug test modes and the insertion timing fix). Pass 7 added no open question from the packages; question 16 (ML data,
publishing) moved here from a note at the end of the file, and three architect decisions are under "Pass 7".
Before: integration pass 6 (2026-11-20; wave 2 merged: the app shell, onboarding, History, Dashboard, Models and
Settings screens, WP-15..19). Pass 6 added four open questions (12-15, design and catalog; none blocks a build or a
test) and the architect decisions under "Pass 6". Before: wave-1 integration pass 5 (WP-01..14 and WP-20's wave-1 part
merged, plus the prompt decision default.v1). Pass 5 added one open prompt question (11) and three architect decisions (last section). Pass 4 (WP-10) added
no open question: its two owner items were decided or done there.

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
12. **Should the catalog say how many dialects a speech model knows?** (WP-18, catalog owner) The Models frames show
   "+28 · 22 dialects" on a language badge and "30 languages and 22 Chinese dialects" in the model facts. The catalog
   has only a list of language codes, so the app shows "+28" and "30 languages".
   *Recommendation:* skip it for v1 (it only matters for Chinese dialects, which Murmur does not target); if you want
   it, the catalog gets an optional `dialects` count per model in the next schema version and WP-18's badge reads it.
   Blocks nothing (two strings in the hub).

16. **May we publish models trained on the synthetic cleanup set?** (ML data, 2026-11-20) The synthetic training set
   (about 3,600 records) was written by the teacher model behind the local proxy, and its terms for training models
   that we then publish are not confirmed (`docs/ml/DATA_CARD.md` section 2a).
   *Recommendation:* until you confirm, the dataset and the fine-tuned models stay private (nothing uploaded publicly;
   a private HF repo at most). Training itself goes ahead.
   **Blocks:** only publishing the dataset or the fine-tuned models (and with it question 1's public download source).
   Nothing in the app or the training run.

## NPU ports (WP-21)

17. **Where should the compiled NPU files for screen reading and GigaAM be hosted?** (WP-21) Five files, about 530 MB
   in total (screen-reading detector 10.7 MB and recogniser 52.5 MB, GigaAM 466.5 MB plus two small files), compiled
   on this server for the Fold7's chip. The catalog lists them with their checksums, but at a placeholder address
   (`owner-hosting-pending/npu-contexts`), so no build can download them and the app never offers them.
   *Recommendation:* upload them to the same Hugging Face repo you own for question 1 (Hermes holds the token), at a
   fixed revision `qairt-2.52.0-r1`, from `.cache/spikes/wp-21/ctx` exactly as built (the compile is not bit-for-bit
   repeatable, so keep that folder until then). Then one constant in `core/catalog/tools/build_catalog.py` changes and
   the catalog is rebuilt; the rebuild checks every file's size and checksum. Compiling on the phone instead would add
   about 90 MB to the app and is not built.
   **Blocks:** downloading the NPU versions inside the app, which only matters if question 19 adopts one. The phone
   check DC4 does not need hosting (its script pushes the files over USB).
18. **Licence of Qualcomm's NPU libraries.** (WP-21, ADR-005/006/048) The ONNX Runtime QNN plugin
   (`com.qualcomm.qti:onnxruntime-android-qnn` 2.7.0) says MIT in its package description, but it only works together
   with Qualcomm's QNN runtime (`com.qualcomm.qti:qnn-runtime` 2.52.0), whose licence text (export clause) nobody has
   read yet. This is the same open licence item as in "Still open from architecture v2" below, now covering one more
   library.
   *Recommendation:* read the QNN runtime terms (or have a lawyer read them) before any build with the NPU libraries
   leaves your own phone; until then those builds (`-Pmurmur.qnn=true`) stay local, and the release build contains no
   QNN library at all (checked by `tools/e2e/check_release_apk.sh --control` at every integration).
   **Blocks:** shipping any NPU speech or screen-reading library to anyone else. Nothing on your own phone.
19. **Switch on NPU screen reading or NPU GigaAM after the phone check?** (WP-21, ADR-059) On the server's NPU
   simulator, screen reading on the NPU does not meet the accuracy bar: with only the detector on the NPU, 15 of 26 test
   pages are off (mostly text boxes shifted by 3-7 pixels; 3 pages merge or shift lines); with both stages on the NPU,
   17 of 26 pages are off and 92.4 % of lines are identical (the bar is 98 %). The recogniser alone keeps 577 of 578
   lines but its confidence drifts by 0.026 (the bar is 0.02). GigaAM on the NPU is within 1 point of the CPU word error
   rate (5.8 % vs 6.3 % on the same 20 s windows; the unpadded CPU model gets 4.9 %).
   *Recommendation:* keep both off (they are off now). Run DC4 on the phone; switch a part on only if its DC4 row meets
   the accuracy bar and is clearly faster. For screen reading that probably means "stay on CPU". A "recogniser only on
   the NPU" path with the small confidence drift is acceptable only if you (and the ML owner, for the OCR tolerance) say so.
   **Blocks:** nothing now. Needs DC4 on the phone.

## Product behaviour

4. **Should a successful Retry count again on the Dashboard?** (WP-11) Retry now updates the failed History entry in
   place. The numbers row for that dictation was already written when it failed, so a retry writes no second one: the
   words of a successful retry do not appear in "Today, words" and the recording time is not counted twice.
   *Recommendation:* keep it (one dictation = one row; rows stay append-only). The alternative is updating the old
   numbers row, which costs a schema change.
   Blocks nothing (WP-17 shows whatever the rows hold).

5. **What should Settings show for the cleanup prompt while a fine-tuned model is active and you have an edit for the
   standard prompt?** (WP-04, WP-19) Fine-tunes keep the prompt they were trained on, so your edit is not used with
   them and their editor is read-only. The brief's rule labels this case "Your edit was made for an older prompt" and
   offers Reset; Reset would delete an edit that works again as soon as you switch back to a standard model.
   *Recommendation:* on a fine-tune show the read-only shipped prompt with "Your edit is kept for the standard prompt"
   and no Reset; keep "made for an older prompt" + Reset for the standard prompts only. WP-19 is told to build the
   recommendation unless you decide otherwise.
   Blocks nothing (copy and one button).

## Design and accessibility

6. **With "Remove animations" on, may the 150 ms cross-fades become instant?** (WP-01) Compose scales every animation
   by the system animator scale, so DESIGN 3.5's reduced-motion fades disappear when the user turns animations off.
   *Recommendation:* yes, follow the system (that is what the setting asks for); keeping the fades needs a custom clock.
   Blocks nothing.
7. **Darken two app-tile colours?** (WP-01) White letters on `#B9861E` (3.23:1) and `#3C7EBF` (4.26:1) are below the
   4.5:1 text contrast. The tiles are treated as decorative today (3:1 graphic contrast passes).
   *Recommendation:* the design owner darkens both in `docs/design/tokens.json` so the letters pass as text.
   Blocks nothing.
8. **How should a working pill look while it is dragged?** (WP-03, design owner) Dragging Listening, Transcribing or
   Polishing now lifts the pill itself (slightly larger, with a shadow, growing toward the edge it was docked to); it
   cannot be hidden, only moved. DESIGN 5.3 shows a drag only for the idle circle. Screenshots:
   `.cache/spikes/wp-03/shots/b-drag-listen--cover.png`, `b-drag-listen--inner.png`, `overlay-drag-listening.png`
   (in the integration tree, taken with the bundled fonts).
   *Recommendation:* keep it (the user sees what they are holding; no jump to a circle and back).
   Blocks nothing (the current look ships unless you say otherwise).
9. **Copy for two bubble errors that are not in the copy deck.** (WP-03, design owner) Proposed today:
   transcription failed = "Couldn't transcribe that" / "Не удалось распознать" (with Open entry); any other failure
   (preflight and the rest) = "Dictation didn't work" / "Диктовка не удалась".
   *Recommendation:* accept both, or send replacements (the pill is at most 280 dp wide).
   Blocks nothing (strings only).

13. **Re-render the design frames with the font the app ships?** (WP-17, design owner) The frames were drawn in a
   browser with Google Fonts' Onest; the app bundles `onest_variable.ttf`, whose letters are a hair wider (one banner
   line measures 613.3 px against the frame's 612 px). So a few lines wrap one word earlier on the phone, e.g. the
   Dashboard's "bubble is off" banner ("…after an update / or a crash"). Every screen is otherwise within a few percent
   of its frame, and the screenshot tests allow for this.
   *Recommendation:* re-render `docs/design/screens/*.png` with the bundled font file (exact wraps, tighter test
   budgets); otherwise accept the one-word differences.
   Blocks nothing.
14. **Please review the screen pieces that have no frame.** (WP-16, WP-17, WP-18, design owner) Built from the design
   system, waiting for your look:
   History: the "history off" state, the multi-select bar, the "Original:" prefix, the error state, "Recording not
   available", the snackbar after deleting several entries, the stacked action bar at very large text, the list
   snackbar for a failed Retry/Re-polish ("Telegram: Couldn't re-polish. The text is unchanged.") and the
   Final/Original/Changes control that scrolls sideways with a fading edge at 200 % text
   (`/home/private/projects/murmur/.cache/spikes/wp-16/`);
   Dashboard: the error state, the screen with real fixture data and the typing-speed sheet
   (`/home/private/projects/murmur/.cache/spikes/wp-17/final2/{cover,inner}/`);
   Models: the download of a pinned unit or of the CPU version while it runs, and the loading skeletons shown until the
   phone's capabilities are known (`/home/private/projects/murmur/.cache/spikes/wp-18/`, `no-frame/`).
   *Recommendation:* review when convenient; what you see ships unless you send changes.
   Blocks nothing.
15. **The Dashboard frames' sample numbers do not add up.** (WP-17, design owner) "9 % of words changed" next to
   "286 filler words removed, 54 fixes" cannot both hold for the frame's word count. The app computes both from the
   same rows, so its numbers always agree.
   *Recommendation:* fix the sample in the frames when they are re-rendered (question 13). Blocks nothing.

## Prompt

10. **Which file and schema should hold the texts of the named tones (Casual / Neutral / Formal / Verbatim)?** (WP-04,
   ADR-020) The app looks for `named.<tone>.text` in `prompts/tones/tones.v1.json`, the place `prompts/render.py`
   `load_tones` reads; no texts exist yet, so a chosen tone falls back to the app category's default and the Settings
   preview says so. A test fails as soon as `prompts/tones` gains a file or a section, so a new file cannot be missed.
   *Recommendation:* the prompt owner adds the four texts as `named.<tone>.text` in `tones.v1.json` (no new file); if a
   `tones.v2.json` is preferred, say so and the port follows.
   **Blocks:** only the named tones themselves (WP-19's tone picker shows "preview" until then). Nothing else.

11. **Will you write a fresh set of test dictations before the next prompt change?** (prompt work, PROMPT_EVAL.md
   section R) The stock cleanup prompt is now default.v1 (ADR-085): on the held-out test set it ties default.v4 at a
   third of the prompt length and answers fewer dictated questions. That test set has now been used for three ship
   decisions, and the results of v1, v4 and the tested v6 are within noise of each other, so it can no longer tell a
   better prompt from a lucky one. default.v1 also predates the screen-text context (it does not say the screen text is
   raw OCR); the tested fix (v6) missed its rule by one answered question and was not shipped.
   *Recommendation:* after the first phone pass (DC8), write about 60 short dictations from your own use (Russian,
   English, mixed, with the screen they were made on) as the next held-out set; until then no prompt change ships.
   **Blocks:** any future change of the stock prompt. Nothing in the app (the shipped prompt is fixed and tested).

## Decided by the architect in this pass (say if you disagree)

- Emulator timing criteria (ASR as well as LLM) run with the host CPU exposed (`MURMUR_EMU_CPU_HOST=1`); the shared
  AVDs stay at 3 GB (ADR-076, from WP-06).
- A secure window (banking apps, FLAG_SECURE) sends the cleanup model only the app name, no field text (ADR-070).
- A unit whose newest benchmark looks wrong is not chosen, even if an older run was fine (ADR-071).
- Retry runs visibly through the bubble and inserts into the focused field (ADR-064).
- OCR benchmark on the phone: over the two bundled screenshots (43 lines) one line may differ by a few characters
  (a rounding difference on the NPU) and the result still counts as valid; a second differing line, or a line that is
  wrong, marks it "Results look wrong" (ADR-080, from WP-14). The OCR parity test itself stays strict (ADR-077).
- The notification's "Start dictation" goes through the status notification's own action handler (one receiver), which
  also closes the notification shade before the target app is read (ADR-079).
- If you switch apps in the 0.6 s before the screenshot, no screen text is used for that dictation (the screenshot
  never shows another app; ADR-079).

- With "Show the bubble: Always" and no field focused, tapping the edge tab opens the dictation button for 4 s and it
  then folds back, whether "Tuck in" is on or off; with TalkBack on it stays open (ADR-081, from WP-03 review).
- A bubble that is listening, transcribing or polishing can be moved but not dragged onto "hide for 1 hour"
  (dropping it there used to start the hour silently while the dictation went on; ADR-081).

- When the phone runs short of memory while loading the cleanup model, Android usually kills the model process instead
  of refusing the memory (WP-10). Murmur now treats such a kill (the system records it as "low memory") like any other
  out-of-memory: the dictation falls back or inserts without cleanup, and the model is tried again on the next
  dictation. Before, one such moment disabled the model on that unit until the next app update. Any other crash
  during a load still disables the unit (ADR-083). Blocks nothing; the phone check is in the device checklist.
- The catalog with the re-measured prompt sizes (WP-10) was regenerated with network access and re-verified against
  Hugging Face / GitHub at integration (catalog 2026-11-19.1); nothing is left to do (ADR-083).

Pass 6 (wave 2, the screens):
- "Tone per app" now really changes the cleanup instructions once a tone has its own text (question 10): the app's own
  choice wins; "All other apps" applies to apps that have no category of their own (messengers, email and so on keep
  their category's style). History records the tone that was used. Re-polish and "Test" in the prompt editor keep the
  category style (they do not know the app's category) (ADR-088). Blocks nothing.
- In the prompt editor, only the model's own control markers count as forbidden markup; mentioning `<context>` or
  `<transcript>` in your instructions is allowed (the shipped prompt does it). "Test" never sends a prompt that fails
  these checks (ADR-087). Blocks nothing.
- The normal debug build (the one for the phone) only stores real benchmark results; the made-up results used for
  screenshots exist only in the test-data mode (ADR-087). Blocks nothing.
- If you delete a dictation in History and leave the screen before the 6 s Undo runs out, the delete is final at once
  (ADR-087, from WP-16). Blocks nothing.
- A bubble error that points to the model hub ("Open Models") opens the hub on the right list: Speech for a speech
  model problem, Cleanup for a cleanup model problem (ADR-087). Blocks nothing.

Pass 7 (wave 3, the finished app):
- When Murmur inserts text and the field does not show it, it tries the next way of inserting for at most 1.5 s in
  total, so it never inserts the same text twice and never inserts after the bubble already said "Copied". If the app
  accepted the text but the field cannot be read back, Murmur leaves the text on the clipboard ("Copied") instead of
  trying again (ADR-090). Blocks nothing; checked on the phone in DC10.
- The first time the app starts after an install or an update, it starts the two model processes for a few seconds to
  check what the phone's NPU and CPU can run, instead of doing that during your first dictation (ADR-090). Blocks nothing.
- In the debug build, the Settings switch "Use fake engines" takes effect the next time the app starts, and it does not
  exist in the release build (ADR-090). Blocks nothing.

Pass 8 (WP-21, the NPU ports):
- The NPU versions of screen reading and GigaAM are in the catalog but cannot be chosen or downloaded in any normal
  build: the hub shows them as "Not supported in this version" (the build does not contain the NPU library), and
  "Auto" never picks them (ADR-092). Blocks nothing.
- If the app's first check of the phone's NPU and CPU fails (for example right after a reboot), the app no longer stays
  on the CPU until the next restart: it checks again 5 minutes later, the next time it needs the answer (ADR-092).
  Blocks nothing; the check's duration is recorded in DC8.

## Still open from architecture v2 (unchanged, listed for completeness)

- **Licences** (blocks shipping any NPU library; DC8 APKs stay local until then): Hexagon SDK Community Edition
  redistribution of `libggml-htp-v79.so`; the `qnn-runtime` licence (export clause), see question 18. See `docs/DEVICE_CHECKLIST.md`.
- **"NPU for all models"** is not met in v1 (VAD and T-one stay CPU; OCR and GigaAM NPU after DC4): sign-off needed
  (ADR-059).
- Metrics kept 90 days independent of history retention (ADR-035).
- Long dictations: cleanup runs once over the whole transcript (D4); on LFM3 NPU the longest cleaned dictation is
  ≈ 3.7 min, chunked cleanup would remove the limit (ADR-037).
- Toolchain owner: `tools/gw` could export `ANDROID_HOME` itself (ADR-052).

Pass 5 (prompt decision and WP-20):
- The stock cleanup prompt is default.v1 for every stock model (ADR-085, by the rule written before the comparison
  runs). The model's context window shrinks to 7,168 tokens and the first dictation after a prompt edit waits about a
  third as long for the prompt to be read in. default.v4 stays in the app. A prompt edit you made under default.v4 shows
  "Your edit was made for an older prompt" with Reset. Blocks nothing.
- The speech and cleanup processes no longer shut down in the middle of a dictation: the 60 s "idle" timer now starts
  only when a dictation ends (ADR-086, found by WP-20's end-to-end run). Blocks nothing.
- If the speech model process is killed while the model is still loading (on the emulator, a SIGKILL), that model is
  marked as failed on that unit, as for any crash during a load (ADR-066). The way back is the model hub: running the
  model's benchmark re-tests a failed unit and a good result clears the mark (ADR-080); WP-18 shows "Try again" on a
  failed unit for that (ADR-086). Blocks nothing.
