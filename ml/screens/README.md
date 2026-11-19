# ml/screens — synthetic phone screens and ctx v2

Implements the `ml/screens` rows of `docs/ml/CONTEXT_V2.md`. Decisions and measurements: `docs/ml/OCR_CONTEXT.md`.

```python
from ml.screens.specs import from_teacher          # teacher screen -> render spec
from ml.screens.build_ctx import build_ctx          # spec -> ctx v2 (render -> OCR -> app/field/screen)
from ml.screens import render                       # spec -> {"png", "size", "visible", "exclude_rects", ...}

spec = from_teacher(screen, app={"package": ..., "label": ..., "category": ...}, field_kind="message",
                    lang="ru", seed=123)            # template/device/theme/font scale/keyboard from the seed
ctx = build_ctx(spec)
```

CLI (repo root, venv `.cache/ml/ocr/venv`, `PYTHONPATH=.` or `-m` from the root):

    python -m ml.screens.render SPEC.json OUT.png                  # + OUT.json (visible, exclude_rects)
    python -m ml.screens.render --batch SPECS.jsonl OUTDIR [--html]
    python -m ml.screens.build_ctx SPEC.json                        # ctx v2
    tools/heavy python -m ml.screens.build_ctx --batch SPECS.jsonl OUT.jsonl --workers 4 --threads 2 [--png-dir D]
    python -m ml.screens.build_ctx --check FILE.jsonl [--loose]     # exit 1 if any context is stale (below)

## Spec

CONTEXT_V2 fields: `template, device (fold7_cover|fold7_inner), theme (light|dark), font_scale (0.7-1.6),
locale (ru|en: UI chrome language), title, messages [{author, is_me, text, time?}], snippets [str],
field {kind?, hint, before_cursor} | null, keyboard, seed`.
Extras: `app` {package, label, category}, `lang` (ru|en|mixed -> ctx.language.asr), `privacy` (full | other ->
no screen), `avoid` (strings filler must never contain), `keyboard_layout` (ru|en), `bubble` (bool, default 50 %),
`overlay` (none|headsup|snackbar|dialog|banner, default ~25 % random: transient UI seen on real screenshots),
`clock`, `filler` (false = no filler content), `font` (roboto|noto), `accent`.

What each template does with the teacher content (nothing is dropped from the spec; what does not fit is
scrolled off and absent from `visible`):

| template | look-alike | title | messages | snippets | field |
|---|---|---|---|---|---|
| messenger_a / messenger_b | Telegram / WhatsApp-like chat (inner: chat list + chat) | chat name | bubbles, authors in groups | pinned bar / link preview | message box |
| work_chat | Slack-like channel (inner: sidebar) | channel | feed rows | quotes / attachments | message box |
| email_inbox | mail list (inner: list + thread) | subject of newest | rows (sender, preview) | — | search box |
| email_thread | open thread (inner: list + thread) | subject | messages (last expanded) | quoted blocks | quick reply |
| email_compose | compose / reply | subject (Re:) | last one quoted | below body | body, or To: for kind=address |
| notes | Keep-like editor (inner: notes list + editor) | note title | note text after the cursor | tags / checklist | note body |
| doc_editor | Docs-like page | heading | paragraphs, authored ones as comments | bullet list | current paragraph |
| browser_article | news article | headline | paragraphs | "read more" links | comment box or URL bar (kind search/address) |
| browser_search | results page | query | results (author = site) | "people also ask" | query box |
| browser_pr | code-review page | PR title | comments | check lines | comment box |
| browser_docs | docs page (inner: nav) | page title | sections (author = heading) | code blocks | docs search |
| terminal | terminal emulator + extra keys | session tab | is_me = commands, others = output | output lines | prompt line |
| code_editor | mobile code editor | file name | code lines | comments | current line |
| issue_tracker | issue page | issue title | description + comments | labels | comment box |
| ai_chat | assistant chat (inner: history) | conversation name | user / assistant turns | suggestion chips | prompt box |
| calendar_tasks | agenda + tasks | date | events (author = organiser, time) | tasks | new task |
| social_feed | post + comments | post title | post + comments | replies | comment box |

## ctx.field.box (focused-field bounds)

Every template marks the element that stands for the focused editable node with `data-focus`; its bounds,
clipped to the visible area, in screenshot pixels, are `render(...)["field_box"]` and `ctx.field.box` — what the
phone gets from `AccessibilityNodeInfo.getBoundsInScreen`. prompts/render.py anchors "nearest to the field"
on it and drops the OCR lines inside it (the field's own text is given exactly as `before_cursor`/`hint`).
No field -> no box; two marked fields in one render is an error.

| templates | box = | evidence |
|---|---|---|
| all input pills (messengers, work chat, comment / reply / search / URL / prompt / new-task boxes) | text area between the icon buttons, full pill height (`data-focus="y-parent"`) | emulator, Google Messages compose EditText `[316,2310][796,2436]` on 1080x2520: icons outside, pill height |
| email_compose | the To: value, or the body text block (quote and snippets are separate views below) | assumed |
| notes | the note body: typed text + the rest of the note (one EditText); title, checklist, chips outside | assumed |
| doc_editor | the document view (the whole page canvas) | assumed |
| terminal, code_editor | the terminal / editor view (all its text is inside the box) | assumed |

The assumed rows are checked by `ml/ocr/device_probe.sh` (report.json `aggregate.focused_fields`). With a
big box (terminal, code editor, doc) render.py drops every OCR line of that view: phone parity, but the
screen text of such contexts is then only what lies outside the editor.

## Versions and stale contexts

Every `build_ctx` meta carries `ocr` (`PIPELINE_VERSION`, hand-bumped), `ocr_src` (hash of ml/ocr config.py,
pipeline.py, postprocess.py), `screens` (`RENDERER_VERSION`, hand-bumped), `screens_src` (hash of the renderer's
.py/.js/.json sources except tests, samples.py, sheet.py, specs.py), `chrome` (Chrome build) and `det_limit`.
`ml.screens.VERSION` = `screens.v2+src:<hash>`. `stale(meta, strict=True)` lists what differs from the current
pipeline (loose: hand-bumped versions and det_limit only); `--check` applies it to build_ctx output
(`meta`), prompt eval sets (`meta.render`) and ml/data records (`meta.screen.render`). Consumers that store
contexts should copy the whole meta (minus `ms`) and refuse stale records before an eval or a training run.
History: screens.v1/ocr.v1 = everything built on 2026-11-18 (no field.box; det_limit 1600 or 960; homoglyph
rules changed in place); screens.v2/ocr.v2 = 2026-11-19.

`visible` roles: content = `title, message, author, snippet, field`; non-content = `time, date, chrome, status,
keyboard, hint, avatar, badge, filler, murmur`. `exclude_rects` (+ `exclude_kinds`): `status_bar`, `keyboard`
(IME window incl. suggestion strip), `murmur_bubble` — what the phone knows from window bounds.

## Rendering

`driver.js` (Node, `playwright-core` 1.65.1 pinned in `package.json`, installed under `.cache/ml/screens/node_modules`)
drives the system Chrome (`/usr/local/bin/google-chrome`, override `MURMUR_CHROME`); never `playwright install`.
CSS px = dp; device scale 2.628 (cover, 411x959 dp) and 2.667 (inner, 738x819 dp) — ASSUMED densities.
Visible text = per-glyph test against every clipping ancestor, the viewport and occlusion (`elementFromPoint`).
Fonts (open licences, `.cache/ml/screens/fonts`, `ml/ocr/fetch_assets.sh`): Roboto, Noto Sans (OFL), Roboto Mono,
JetBrains Mono (OFL/Apache), Noto Color Emoji (OFL). Generic look-alikes: no logos, brand names only as app labels in ctx.
Deterministic: same spec -> byte-identical PNG and identical `visible` (tested).

Throughput: `build_ctx --batch` with 8 workers x 1 thread renders + recognises 300 screens in 167 s on 8 shared
cores (3,000 in ~28 min). Visual review: `python -m ml.screens.sheet OUT.png A.png B.png ...`.
Data engineers: integrate through `specs.from_teacher(...)` + `build_ctx`; check category rules on
`ctx.screen.lines`; terms OCR cannot read exactly are listed in `ml/ocr/ocr_hard_terms.json`.

Tests: `.cache/ml/ocr/venv/bin/python -m pytest -q ml/screens/tests`
