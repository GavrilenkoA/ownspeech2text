# OCR context pipeline and synthetic screens (phase 2, 2026-11-18)

Implements the `ml/ocr` and `ml/screens` rows of `CONTEXT_V2.md` ("context = what OCR sees").
Porting spec: `ml/ocr/README.md`. Screen renderer: `ml/screens/README.md`. All numbers below were measured
on this server (x86_64, AVX-512, shared with other jobs: load average 25-40 on 16 cores during every run),
not on the phone.

## Decisions

1. **Models**: `PP-OCRv6_tiny_det` + `cyrillic_PP-OCRv5_mobile_rec` (ONNX, Apache-2.0), onnxruntime called
   directly (no RapidOCR). Dictionary read from the model's `inference.yml`; exported as
   `ml/ocr/golden/rec_keys.txt`. The phase-1 `keys.txt` was wrong (`''` instead of `'`).
2. **Detector input: longest side 960** (cover 1080x2520 -> 416x960, inner 1968x2184 -> 864x960), no tiling.
   CER is flat from 960 to native size (table below); 960 is the cheapest. Re-check on Fold7 screenshots (DEVICE).
3. **Axis-aligned DB post-processing** (connected components + bbox score + closed-form unclip): no Clipper,
   no rotated boxes; ports in ~60 lines of Kotlin. Thresholds from the model's yml: 0.2 / 0.4 / unclip 1.4.
4. **Recogniser**: height 48, own padded-width bucket per line (320 ... 3200), batch 1 on CPU (batch 8 costs 28 %
   more CPU per line on x86; outputs identical on the golden suite, so the phone may batch).
5. **Post-processing**: conf >= 0.5, >= 2 non-space chars, exclusion rects (>= 50 % of the box), homoglyph
   repair (rules in README §6.3, extended after the term and emulator probes), geometric then textual
   de-duplication, reading order with two-pane detection for the inner display.
6. **Determinism**: same image -> byte-identical JSON across runs and thread counts 1/2/4 (checked); the
   renderer gives byte-identical PNG + `visible` for the same spec (unit test).
7. **Renderer**: HTML/CSS templates, `playwright-core` 1.65.1 + system Chrome (no browser download), 17 templates
   in 12 families, both displays, light/dark, font scale 0.85-1.3, ru/en UI and keyboard layouts, emoji,
   avatars, read marks, date separators, badges, link previews, pinned bars, transient overlays. Generic
   look-alikes; fonts Roboto, Noto Sans, Roboto Mono, JetBrains Mono, Noto Color Emoji (OFL/Apache).
   Display density ASSUMED (no `docs/research/verify/platform.md`): 2.63 px/dp cover (411x959 dp),
   2.67 px/dp inner (738x819 dp); pixel sizes from the spec sheets.
8. **`build_ctx`** is the only producer of ctx v2: render -> OCR with the render's exclusion rects -> app/field/
   screen. `privacy != full` or a password field -> no `screen`; `field: null` -> no `field`.

## Numbers

### Detector input size (48 rendered screens, 24 per display, 4 threads, server under load)

| config | display | CER % | content CER % | items exact % | det ms | total ms |
|---|---|---|---|---|---|---|
| limit 960 | cover | 2.97 | 2.21 | 80.3 | 136 | 1206 |
| limit 960 | inner | 2.96 | 1.68 | 77.4 | 311 | 1652 |
| limit 1280 | cover | 2.89 | 2.23 | 81.2 | 208 | 1592 |
| limit 1280 | inner | 2.86 | 1.50 | 77.7 | 452 | 2326 |
| limit 1600 | cover | 2.73 | 1.65 | 79.6 | 332 | 1796 |
| limit 1600 | inner | 2.85 | 1.66 | 77.6 | 700 | 3006 |
| limit 2048 | cover | 2.51 | 1.61 | 81.7 | 618 | 2607 |
| limit 2048 | inner | 2.77 | 1.60 | 77.9 | 1481 | 4239 |
| native | cover | 2.51 | 1.58 | 81.4 | 1110 | 4013 |
| native | inner | 2.75 | 1.60 | 77.6 | 1856 | 5518 |
| native, 1024-row tiles | cover | 2.63 | 1.65 | 80.5 | 931 | 2676 |
| native, 1024-row tiles | inner | 2.87 | 1.62 | 77.1 | 1908 | 4231 |

(`.cache/ml/ocr/measure/detlimit.json`; measured before the final homoglyph rules; relative comparison only.)
The recogniser dominates the cost (~35 ms CPU per 48x320 line, ~200 ms per 48x960 line, x86, 1 thread).

### Rendered screens (300 sample specs, final pipeline, `ctx300c.profile.json`)

| | all | cover | inner |
|---|---|---|---|
| CER (all visible text) | 2.38 % | 2.02 % | 2.80 % |
| content CER (title, messages, authors, snippets, field) | 1.78 % | 1.25 % | 2.57 % |
| visible items read exactly | 82.4 % | | |
| visible items missed (mostly 1-char chrome, avatars) | 7.2 % | | |
| OCR lines per screen | 18.0 | | |
| non-content OCR lines (chrome, time, filler, hint, avatar...) | 60.9 % | 52.5 % | 69.7 % |
| OCR lines with no ground truth under them (icons read as text) | 3.4 % | | |
| mixed-script tokens | 0.49 % | | |

### Throughput (task step 4)

`build_ctx --batch` 300 specs, `taskset -c 0-7`, 8 workers x 1 onnxruntime thread, each worker its own Chrome:
**167.5 s wall -> 3,000 screens in ~28 min** on 8 shared cores (load average ~40 on the machine), 0 errors;
CPU 682 s user+sys = 2.3 core-s per screen -> ~14 min on 8 dedicated cores. Median per screen: render 0.97 s,
OCR 3.0 s. (First attempt, 4 workers x 2 threads, det 1280, batch 8: 393 s -> 65 min; the changes in
decisions 2 and 4 made the difference.)

### Hard terms (task step 5; `ml/roundtrip/probe/terms.jsonl`, 725 terms)

Each term in a carrier message (ru/en), 4-5 per screen, twice, across 8 templates, both displays, both
themes, font scales 0.85-1.3 (326 screens, no filler, no overlays): 1,389 visible observations (61 more
scrolled off), 9 wrapped across lines.

- exact recall per observation **92.6 %** (93.2 % when the term is on one visual line);
- **649 / 725** terms read exactly every time, **30** never; **76** terms failed at least once ->
  `ml/ocr/ocr_hard_terms.json` (what OCR read instead, error modes);
- error modes of the misses: other letter 46, digit/letter (`l`/`I`/`1`, `O`/`0`) 14, letter + punctuation 13,
  punctuation only (`--` -> `-`, `–`) 9, Latin/Cyrillic homoglyph 6, missing 4, case 3, mixed 4;
- typical: `AI` -> `Al`, `CI` -> `Cl`, `curl`/`logits`/`journalctl` (l/I/i), `--force` -> `-force`,
  all-twin Latin words inside Russian sentences read as Cyrillic (`Canary` -> `Сапагу`), `ß` -> `B`,
  `ø` -> `o` (both not in the dictionary), `Ł`, `№eMo`;
- screen profile of these screens: CER 2.6 %, content CER 2.2 %, non-content lines 59.6 %.

Fixed on the way (homoglyph rules): `HAProxy`, `API`, `A100`, `x86_64`, `p99`, `C#`, `promрt`, `RNN-T`,
`llama.cpp`, `SIM-карты` (exact recall 88.0 % -> 92.6 %, never-exact terms 53 -> 30).

### Real screenshots (task step 6, emulator)

Android 16 (API 36, google_apis x86_64) emulator `murmur-ocr-probe`, console port 5580, 1080x2520 @ 420 dpi and
1968x2184 via `wm size`, real apps (Settings pages, Contacts, Google Messages with received SMS, Calendar,
Files, Chrome on two Wikipedia pages, launcher), ground truth = `uiautomator dump`. 64 screenshots: Russian
locale 32, English 32 (the first English round died when the zygote restarted for the locale switch; it was
redone on a fresh AVD that boots in en-US). AVD deleted afterwards. `ml/ocr/emu_probe.sh`,
`.cache/ml/ocr/real/emu*/report.json`.

| | real ru | real en | rendered |
|---|---|---|---|
| CER on items OCR found (dist < 50 % of length) | 0.82 % | 0.79 % | 1.78 % (content) |
| those items read exactly | 91.3 % | 94.2 % | 82.4 % (all items) |
| OCR lines per screen | 14.9 | 13.3 | 18.0 |
| label-like lines (<= 2 words and <= 18 chars, or a clock/counter) | 47 % | 52 % | 60 % |
| mixed-script tokens | 1.1 % | 0.1 % | 0.5 % |
| median OCR ms (4 threads) | 1134 | 1012 | — |

The uiautomator dump is taken ~1 s after the screenshot, so 6 + 5 screens whose content changed in between
(loading screens, dialogs) are excluded by the "dist < 50 %" filter; whole-set numbers in the reports.

What the real screens showed and the renders now copy: transient UI (heads-up notifications, snackbars,
permission dialogs, translate/cookie banners), phone numbers as chat titles, unread separators.
What still differs (reported, not hidden):
- the renders are **harder** character-wise (denser technical text, smaller font scales, mixed scripts):
  the real error profile is a subset of the rendered one, so training data errs on the noisy side;
- renders have **more** short UI lines (60 % vs 47-52 %), mostly the inner-display two-pane lists and
  per-message timestamps; acceptable for teaching the model to ignore chrome, but the share can be tuned
  (`filler: false`, keyboard / bubble / overlay switches);
- real-only failure modes: a search icon read as `Q` glued to the next word (`QПоиск`), `Gooдle`,
  `Dispray & toucn`, typographic punctuation normalised (`‑` -> `-`, `’` -> `'`, `…` -> `...`), truncated UI
  text (`Упорядочи..`) where accessibility has the full string;
- real UI fonts (Google Sans / SamsungOne) are not available under open licences; renders use Roboto / Noto Sans;
- the soft keyboard never came up in the emulator rounds (first AVD had `hw.keyboard=yes`; in the second the
  scripted tap landed on Messages' welcome screen), so the IME exclusion rect is verified only on renders;
  `real_probe.window_rects` reads it from `dumpsys window` and the DEVICE probe has a keyboard-up shot.

## Limits

- Python reference vs Android: bit-exact parity is not expected on another CPU/NPU; acceptance criteria in
  `ml/ocr/README.md` ("Parity with Android"). The post-processing alone must match exactly (`stages.rec` ->
  `ocr`).
- Not in the dictionary: `ß`, `ø`, `Ł` and other non-ru/en letters, emoji (dropped or read as junk).
- `l`/`I`, `0`/`O`, `--`/`-` confusions are inherent to the UI fonts; homoglyph repair cannot fix them.
- All-twin Latin words in Russian sentences (`Canary`, `Bun`) stay Cyrillic: flipping on context alone would
  break Russian prepositions between English terms (`по`, `с`, `и`), which are far more frequent.
- Reading order: the two-pane gutter test can split a chat whose bubbles are all short on the inner display
  (needs >= 3 lines per side over >= 40 % of the height with nothing crossing); not seen in the 300-screen set.
- Densities, status-bar height, keyboard height and the real Fold7 fonts are assumptions until the DEVICE check.

## Re-check on the Fold7 (DEVICE)

`ml/ocr/device_probe.sh [SERIAL]`: cover display then (after unfolding) inner display; opens Settings,
Display, Contacts, Messages, Calendar, Samsung Notes, My Files, Chrome on two pages, then the owner's own
messenger with the keyboard up and a mail thread; screenshot + `uiautomator dump` + `wm size/density` +
window frames for each; runs the pipeline and writes `.cache/ml/ocr/real/fold7/report.json` and
`metrics.txt`. Expected: ~24 screenshots, real display metrics (to replace the assumed densities in
`ml/screens/chrome.py` DEVICES), CER on found items <= ~1 % as on the emulator, label-like share ~50 %;
re-run `make -C ml/ocr measure` only if the inner-display CER is worse than the cover's by more than 1 point.

## Files

`ml/ocr/`: `pipeline.py`, `postprocess.py`, `config.py`, `run.py` (CLI), `metrics.py`, `measure.py`,
`golden.py` + `golden/` (26 PNG+JSON pairs, 7.2 MB, `rec_keys.txt`), `terms_probe.py`, `ocr_hard_terms.json`,
`real_probe.py`, `emu_probe.sh`, `device_probe.sh`, `fetch_assets.sh`, `requirements.txt`, `Makefile`, `tests/`.
`ml/screens/`: `render.py`, `driver.js`, `package.json`, `base.py`, `chrome.py`, `filler.py`, `families/`,
`templates.py`, `specs.py` (teacher screen -> spec), `build_ctx.py`, `samples.py`, `sheet.py`, `tests/`.
