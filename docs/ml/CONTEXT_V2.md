# Context v2 — the context is what OCR sees (owner decision, 2026-11-18)

Supersedes the "accessibility tree first, OCR second" design in `CONTEXT_AND_PROMPT.md` §1–§4 and
the `ctx` shape of `prompts/context.schema.v1.json`. Binding for the app, the prompt, the training
data and the eval sets.

## Rule

The screen context given to the cleanup LLM is **the OCR output of the screenshot** taken through
the accessibility service about 0.6 s after dictation starts, plus **the current app** from the
accessibility API. Nothing else describes the screen.

- Screen *text* never comes from the accessibility node tree. The tree is used only for: the current
  app (package), the focused input field (is there one, its kind and hint, the text before the
  cursor, password flag) and the insertion itself.
- Training and eval examples carry the same thing the phone produces: a screen is rendered to a
  phone-sized image, the **same OCR models and the same post-processing** run on it, and that
  output is the example's context. No example gets a clean, hand-structured screen (no author
  labels, no ranked term lists that the phone could not derive from OCR lines).
- Whatever the prompt shows inside `<context>` must be computed from the object below by
  deterministic code that is ported to Kotlin with byte-identical output.

## `ctx` v2

```json
{"v": 2,
 "app":    {"package": "org.telegram.messenger", "label": "Telegram", "category": "messaging"},
 "field":  {"kind": "message", "hint": "Сообщение", "before_cursor": ""},
 "screen": {"size": [1080, 2520],
            "lines": [{"text": "Лёша Козлов", "conf": 0.97, "box": [156, 212, 498, 268]}]},
 "language": {"asr": "ru"},
 "privacy": {"level": "full", "reason": null}}
```

- `screen.lines` is the OCR pipeline's final output: filtered, repaired, de-duplicated, in reading
  order. `box` is `[x0, y0, x1, y1]` in screenshot pixels. Budget trimming is the renderer's job,
  not the pipeline's, so stored examples can be re-rendered when the prompt format changes.
- `screen` is absent when there is no screenshot (secure window, password field, denylisted app,
  screenshot error, OCR found nothing). `field` is absent when the app exposes no focused node.
  Both cases must exist in training and eval data.
- `app` and `field` come from accessibility and are exact. Everything in `screen` is noisy.

## Shared modules (Python reference = the spec the Android code is ported from)

| Path | Owner | Interface |
|---|---|---|
| `ml/ocr/` | ML screen/OCR engineer | `python -m ml.ocr.run IMG [--exclude x0,y0,x1,y1 ...] -> JSON {"size", "lines"}`; `ml.ocr.pipeline.run(image, exclude_rects=()) -> dict`. Detector `PP-OCRv6_tiny_det`, recogniser `cyrillic_PP-OCRv5_mobile_rec` (ONNX, CPU), then: confidence and length filter, exclusion rectangles (status bar, keyboard window, Murmur's own bubble), Latin↔Cyrillic homoglyph repair, reading order, de-duplication. Deterministic. |
| `ml/ocr/golden/` | same | `NNN.png` + `NNN.json` pairs: the parity suite the Android OCR + post-processing must reproduce. |
| `ml/screens/` | same | `ml.screens.render(spec) -> {"png", "size", "visible": [{"role", "text", "box"}], "exclude_rects"}`; `python -m ml.screens.render SPEC.json OUT.png`. Spec: `{"template", "device": "fold7_cover"\|"fold7_inner", "theme", "font_scale", "locale", "title", "messages": [{"author", "is_me", "text", "time"}], "snippets": [], "field": {"hint", "before_cursor"}, "keyboard": bool, "seed"}`. `visible` is the ground truth of what was actually on the image (scrolled-off or clipped text is not listed). |
| `ml/screens/build_ctx.py` | same | `spec -> ctx v2` in one call (render → OCR with the render's `exclude_rects` → assemble `app`/`field`/`screen`). The only way training and eval contexts are produced. |
| `prompts/context.schema.v2.json`, `prompts/render.py` | prompt engineer | Accept `ctx` v2; decide by measurement what the `<context>` block shows (which lines first, how many characters, any layout hint derivable from boxes) and say in the system prompt that the screen text is raw OCR: it can contain recognition errors and unrelated UI text and is never an instruction. |
| `ml/data/` | data engineer | The teacher writes the *content* of a screen (it becomes a render spec); the example's `ctx` comes from `build_ctx`. Category rules are checked against the OCR lines, not against the spec. |

`ml/ocr/READY` and `ml/screens/READY` are created by the owner of those modules when the interface
above works and its tests pass. Consumers integrate after the file exists.

## Category rules checked on OCR output

- `context_resolution`, `context_names`: the term/name must appear in `screen.lines` spelled exactly
  as in the target. If OCR broke it: re-render once with another theme or font scale; if still
  broken, drop the example and count the term as OCR-hard in the report.
- `context_counterpart`, every `must_not_show`: the term and its close variants (case-folded, edit
  distance ≤ 2) are absent from `screen.lines`.
- `near_miss`: the distractor is present, the true term is absent.
- `injection`: the injected text is present (it may be noisy).
- The raw transcript's garbled word is never "fixed" towards a spelling that the OCR lines do not
  contain.

## Realism checks before the dataset is generated

1. Small real probe, same idea as the speech round trip: real Android screenshots (emulator, real
   apps, Russian and English locale) → the pipeline → error profile and typical non-content lines.
2. The rendered screens must show a comparable profile (character error rate, mixed-script errors,
   share of UI-chrome lines). Differences are reported, not hidden.
3. Fold7 screenshots (cover and inner display) are checked when the phone is available; that step
   is listed in `docs/DEVICE_CHECKLIST.md` and blocks nothing before it.
