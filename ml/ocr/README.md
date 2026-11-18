# ml/ocr — reference OCR context pipeline (porting spec)

The Python here is the specification the Android implementation is ported from (CONTEXT_V2). Every
constant lives in `config.py` (`PIPELINE_VERSION = ocr.v1`); the golden suite `golden/` pins the behaviour.
Decisions and measurements: `docs/ml/OCR_CONTEXT.md`.

```
python -m ml.ocr.run IMG [--exclude x0,y0,x1,y1 ...] [--detail]   -> {"size": [w, h], "lines": [{"text","conf","box"}]}
from ml.ocr.pipeline import run; run(image, exclude_rects=())    -> same dict
make -C ml/ocr venv assets test golden golden-check measure
```

Setup: `make -C ml/ocr venv assets` (uv venv `.cache/ml/ocr/venv` from `requirements.txt`; models + fonts by
`fetch_assets.sh`, sha256-pinned). The shared `.cache/ml/venv` also has every runtime dependency (no pytest).

## Models (ONNX, Apache-2.0, PaddlePaddle on Hugging Face)

| stage | repo / file | input | output |
|---|---|---|---|
| detector | `PaddlePaddle/PP-OCRv6_tiny_det_onnx/inference.onnx` (1.8 MB, sha256 `193bab7a…`) | `x` float32 [1,3,H,W], H,W multiples of 32 | prob map [1,1,H,W] |
| recogniser | `PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx/inference.onnx` (8.0 MB, sha256 `5371ee1d…`) | `x` float32 [N,3,48,W] | softmax [N, W/8, 852] |
| dictionary | `PostProcess.character_dict` of that repo's `inference.yml`, exported as `golden/rec_keys.txt` (850 lines, UTF-8, sha256 `db40aa52…`) | | class 0 = CTC blank, 1..850 = dictionary, 851 = space |

Read the dictionary with a YAML parser or use `golden/rec_keys.txt`; a naive line copy of the YAML gets
`''` instead of `'` (the phase-1 `keys.txt` had exactly that bug).

onnxruntime CPU, `intra_op_num_threads` = 4 (any value gives identical output), `inter_op` 1, sequential,
graph optimisation ALL. Output is byte-identical across runs and thread counts (1/2/4 checked).

## 1. Input

The screenshot as RGB8 (alpha dropped, no colour management), W x H as captured. Channel order for both
networks is **BGR** (PaddleOCR convention).

## 2. Detector pre-processing

1. Scale `r = det_limit / max(W, H)` if `max(W, H) > det_limit`, else `r = 1` (never upscale). `det_limit = 1600`
   (see OCR_CONTEXT.md for the sweep). `det_limit = 0` means native size.
2. Target size per side: `max(32, floor(side * r / 32 + 0.5) * 32)` (round half up to a multiple of 32).
   Cover 1080x2520 -> 704x1600; inner 1968x2184 -> 1440x1600 (native would be 1088x2528 / 1984x2176).
3. Resize: bilinear, half-pixel centres (`src = (dst + 0.5) * scale - 0.5`, clamped to the edge), computed in
   float32 on 0..255 values (OpenCV `INTER_LINEAR` on a float32 image). Not area averaging, not uint8 fixed point.
4. Normalise per channel in B, G, R order: `(v / 255 - mean) / std`, mean `(0.485, 0.456, 0.406)`,
   std `(0.229, 0.224, 0.225)` (index 0 applies to B). Layout NCHW.
5. Tiling (`det_tile`, off by default): full-width horizontal tiles of `det_tile` rows with `det_tile_overlap`
   rows of overlap; each tile owns its rows up to the middle of each overlap.

## 3. DB post-processing (axis-aligned)

1. Binary map: `prob > 0.2` (strict).
2. 8-connected components of the binary map (any labelling algorithm; only the component set matters).
3. For each component with bounding box x, y, w, h (pixels `[x, x+w) x [y, y+h)` in map coordinates):
   - drop if `min(w, h) < 3`;
   - score = mean of `prob` over the bounding box (all pixels, not just the component); drop if `< 0.4`;
   - unclip: `d = w * h * 1.4 / (2 * (w + h))`; box `[x - d, y - d, x + w + d, y + h + d]`;
     drop if its shorter side `< 5`;
   - clamp to `[0, Wmap] x [0, Hmap]`, scale by `W / Wmap`, `H / Hmap`, round half up (`floor(v + 0.5)`),
     clamp to the image; drop if width or height `< 2`.
4. Sort boxes by `(y0, x0, y1, x1)`.

This is PaddleOCR's DB post-processing with `score_mode=fast` and the box reduced to its axis-aligned
rectangle (no rotated boxes: UI text is horizontal; for a rectangle, polygon offsetting by `d` and taking the
minimum-area rectangle equals growing each side by `d`, so no Clipper library is needed).

## 4. Exclusion rectangles

A box is dropped before recognition when `intersection(box, rect) >= 0.5 * area(box)` for any exclusion
rectangle (status bar, IME window incl. suggestion strip, Murmur's own bubble/pill — from window bounds).

## 5. Recogniser

1. Crop the box from the original RGB image (no rotation, no perspective warp), convert to BGR.
2. `rw = ceil(48 * w / h)`; padded width `bw` = the smallest bucket `>= rw` of
   `(320, 480, 640, 800, 960, 1280, 1600, 1920, 2560, 3200)`; if `rw > 3200`, `rw = bw = 3200` (squeezed).
3. Resize the crop to `rw x 48` (same bilinear as §2), normalise `(v / 255 - 0.5) / 0.5`, place at the left of a
   zero tensor `[3, 48, bw]` (zero = mid-grey after normalisation; right padding).
4. Batching: group by `bw`, batches of up to 8 in box order. The padded width per line does not depend on the
   batch (the model has global attention over width, so padding changes outputs: never pad a line to the
   batch's widest line, pad to its own bucket).
5. Greedy CTC: argmax per time step (first index on ties), merge consecutive repeats, drop class 0;
   `conf` = mean of the max probability over the kept steps (0 if nothing kept). Class 851 is a space.

## 6. Post-processing (`postprocess.py`, plain lists)

1. Text: Unicode NFC, collapse whitespace runs to one space, strip.
2. Filter: drop if `conf < 0.5` or fewer than 2 non-space characters.
3. Homoglyph repair per space-separated token (`repair_homoglyphs`):
   - strong twins (identical shapes) `a c e o p x y A B C E H K M O P T X` <-> `а с е о р х у А В С Е Н К М О Р Т Х`;
     weak twins Latin `k n u r b m t` -> `к п и г ь т т`, Cyrillic `к п и г ь т і ј ѕ ԁ` -> `k n u r b m i j s d`;
   - evidence per script: 1 per letter of that script with no twin, 0.25 per weak twin, 0 per strong twin;
   - a token holding both scripts becomes the script with more evidence (ties: unchanged);
   - a token with no evidence (only strong twins, e.g. `Bce`, `c`) takes the script of the decisive tokens
     among the 2 before and 2 after it on the same line, only when they all agree; otherwise unchanged.
4. Geometric de-duplication: visit lines by `(-conf, y0, x0)`; drop a line whose IoU with a kept line is
   `>= 0.5` or that lies `>= 80 %` inside a kept line; restore box order.
5. Reading order:
   - two-pane check only when `W / H >= 0.75` (inner display): scan `x` from `0.25 W` to `0.6 W` in steps of 4 px;
     `x` is a gutter when at least 3 lines lie fully left and 3 fully right, the band where both sides have
     content spans `>= 0.4 H`, no line crosses `x` inside that band, and both sides keep `>= 3` lines inside the
     band. The widest free gap wins. Order: lines above the band (and lines crossing the gutter at the band's
     top), then the left pane, the right pane, then lines below the band;
   - inside each region: rows by `(y0, x0)`: a line joins the first row whose first line overlaps it vertically by
     `>= 0.5 * min(heights)` and none of whose lines it intersects; rows in creation order, lines in a row by `x0`.
6. Textual de-duplication: keep the first line (in reading order) of each case-folded, space-collapsed text.
7. Output `conf` rounded to 3 decimals (Python `round`, half-even); `box` integers `[x0, y0, x1, y1]`.

## Parity with Android

`golden/NNN.png` + `NNN.json` (26 pairs): inputs (`exclude_rects`), intermediate stages (`det_input`,
`det_boxes` with scores, `rec` lines before post-processing) and the final `ocr`. Python reproduces them
byte for byte (`make -C ml/ocr golden-check`). An Android port on another CPU/NPU will not be bit-exact
(float kernels differ). Acceptance: same number of lines, boxes within 2 px, texts identical for >= 98 % of
lines and CER <= 0.5 % on the rest, conf within 0.02; and the post-processing alone (feed `stages.rec` into
the Kotlin post-processing) must reproduce `ocr` exactly.
