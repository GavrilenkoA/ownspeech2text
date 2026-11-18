"""Every constant of the reference OCR pipeline, in one place (README.md is the porting spec for these).

Changing a value here changes the golden files: re-run `make -C ml/ocr golden` and bump PIPELINE_VERSION.
"""
from __future__ import annotations

import os
from functools import lru_cache
from dataclasses import dataclass, field, replace
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MODEL_DIR = Path(os.environ.get("MURMUR_OCR_MODELS", REPO / ".cache" / "ml" / "ocr"))

# History: ocr.v1 = 2026-11-18 (det_limit 1600, later 960; homoglyph rules changed in place: contexts labelled
# ocr.v1 are NOT reproducible). ocr.v2 = 2026-11-19 (the constants below). Bump on ANY change that can change
# the output; SOURCE_FILES' hash (source_hash()) catches the bumps that were forgotten.
PIPELINE_VERSION = "ocr.v2"
SOURCE_FILES = ("config.py", "pipeline.py", "postprocess.py")   # everything that decides the output


def src_hash(root: Path, rels) -> str:
    """sha1 over (relative path, bytes) of the given files, 12 hex chars. Shared by ml/screens."""
    import hashlib
    h = hashlib.sha1()
    for rel in sorted(rels):
        h.update(str(rel).encode())
        h.update(b"\0")
        h.update((root / rel).read_bytes())
    return h.hexdigest()[:12]


@lru_cache(maxsize=1)
def source_hash() -> str:
    return src_hash(Path(__file__).resolve().parent, SOURCE_FILES)

# Latin letters that are visually identical to a Cyrillic letter ("strong" twins) and Latin letters that the
# recogniser confuses with a Cyrillic letter in UI fonts ("weak" twins). Values are the Cyrillic twin.
LAT2CYR_STRONG = dict(zip("aceopxyABCEHKMOPTX", "асеорхуАВСЕНКМОРТХ"))
LAT2CYR_WEAK = dict(zip("knurbmti", "кпигьтти"))
CYR2LAT_STRONG = {v: k for k, v in LAT2CYR_STRONG.items()}
CYR2LAT_WEAK = dict(zip("кпигьт", "knurbm"))
# Cyrillic letters that do not occur in Russian (the recogniser's dictionary covers Ukrainian, Serbian, ...):
# on this user's screens they are always a misread Latin letter, so they count as Latin evidence.
NON_RU_TO_LAT = dict(zip("іїєјѕԁІЇЄЈЅӀӏ", "iieјsdIIEJSll")) | {"ј": "j"}
WEAK_WEIGHT = 0.25   # evidence of a weak twin for its own script (strong twins: 0, other letters: 1)


@dataclass(frozen=True)
class OcrConfig:
    # --- models (PaddlePaddle ONNX exports, Apache-2.0)
    det_model: str = "PP-OCRv6_tiny_det_onnx/inference.onnx"
    rec_model: str = "cyrillic_PP-OCRv5_mobile_rec_onnx/inference.onnx"
    rec_dict: str = "cyrillic_PP-OCRv5_mobile_rec_onnx/inference.yml"   # PostProcess.character_dict (850 entries)
    # --- runtime
    threads: int = int(os.environ.get("MURMUR_OCR_THREADS", "4"))
    # --- detector pre-processing
    det_limit: int = 960            # longest side after resize (0 = native size); see README "detector input size"
    det_multiple: int = 32           # each side rounded to a multiple of this (round half up, min 32)
    det_tile: int = 0                # 0 = no tiling; else tile height in resized-image pixels (tiles are full width)
    det_tile_overlap: int = 96       # overlap of consecutive tiles in resized-image pixels
    det_mean: tuple = (0.485, 0.456, 0.406)   # applied to B, G, R channels in this order (PaddleOCR convention)
    det_std: tuple = (0.229, 0.224, 0.225)
    # --- detector post-processing (DB)
    db_thresh: float = 0.2           # binarisation threshold on the probability map
    db_box_thresh: float = 0.4       # mean probability inside the component's bounding box
    db_unclip_ratio: float = 1.4     # expand distance d = w*h*ratio / (2*(w+h)) in map pixels
    db_min_side: float = 3.0         # drop components with a bbox side < this (map pixels) before unclip
    db_min_side_unclipped: float = 5.0
    # --- recogniser
    rec_height: int = 48
    rec_buckets: tuple = (320, 480, 640, 800, 960, 1280, 1600, 1920, 2560, 3200)   # padded input widths
    rec_batch: int = 1                # CPU: batching is slower per line for this model; outputs are batch-independent
    # --- post-processing
    min_conf: float = 0.5            # mean CTC max-probability of the kept characters
    min_chars: int = 2               # non-space characters
    exclude_min_overlap: float = 0.5  # drop a box when this share of its area lies inside one exclusion rect
    dedup_iou: float = 0.5           # geometric duplicates (tiling seams)
    row_overlap: float = 0.5         # boxes share a row when vertical overlap >= this * min(height)
    pane_min_aspect: float = 0.75    # two-pane detection only when width / height >= this (inner display)
    pane_gutter_range: tuple = (0.25, 0.6)   # candidate gutter x as a share of the width
    pane_min_lines: int = 3          # each pane must hold at least this many lines
    pane_min_span: float = 0.4       # ... and its lines must span at least this share of the height
    screen_script_ratio: float = 2.0   # screen-level fallback script needs this evidence ratio
    conf_digits: int = 3             # conf is rounded to this many decimals in the output
    extra: dict = field(default_factory=dict)

    def path(self, rel: str) -> Path:
        return MODEL_DIR / rel

    def with_(self, **kw) -> "OcrConfig":
        return replace(self, **kw)


DEFAULT = OcrConfig()
