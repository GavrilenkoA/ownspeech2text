"""Pipeline tests on a synthetic image drawn with PIL (no browser needed) and on the golden suite if present."""
import json
from pathlib import Path

import numpy as np
import pytest
from PIL import Image, ImageDraw, ImageFont

from ml.ocr import pipeline as P
from ml.ocr.config import DEFAULT, MODEL_DIR
from ml.ocr.run import dumps

FONT = MODEL_DIR.parent / "screens" / "fonts" / "Roboto[wdth,wght].ttf"
GOLDEN = Path(__file__).resolve().parents[1] / "golden"
needs_models = pytest.mark.skipif(not (MODEL_DIR / DEFAULT.det_model).exists(), reason="OCR models not present")


def _img():
    im = Image.new("RGB", (1080, 900), "white")
    d = ImageDraw.Draw(im)
    f = ImageFont.truetype(str(FONT), 44)
    d.text((40, 60), "Привет! Скинь ссылку на Grafana", fill="black", font=f)
    d.text((40, 200), "Встреча перенесена на 15:30", fill="black", font=f)
    d.text((40, 340), "kube-prometheus prod-eu-2", fill="black", font=f)
    d.text((40, 700), "Клавиатура", fill="black", font=f)
    return im


def test_det_size():
    assert P.det_size(1080, 2520, DEFAULT.with_(det_limit=960)) == (416, 960)
    assert P.det_size(1080, 2520, DEFAULT.with_(det_limit=0)) == (1088, 2528)
    assert P.det_size(100, 50, DEFAULT.with_(det_limit=960)) == (96, 64)


def test_rec_width():
    assert P.rec_width(100, 48, DEFAULT) == (100, 320)
    assert P.rec_width(1000, 48, DEFAULT) == (1000, 1280)
    assert P.rec_width(10000, 48, DEFAULT) == (3200, 3200)


def test_ctc_decode():
    chars = ["", "a", "b", " "]
    p = np.zeros((6, 4), np.float32)
    for t, k in enumerate([1, 1, 0, 1, 2, 3]):
        p[t, k] = 0.9
    text, conf = P.ctc_decode(p, chars)
    assert text == "aab "
    assert abs(conf - 0.9) < 1e-6


@needs_models
def test_pipeline_reads_and_excludes():
    im = _img()
    out = P.run(im, exclude_rects=[(0, 650, 1080, 900)])
    texts = [ln["text"] for ln in out["lines"]]
    assert out["size"] == [1080, 900]
    assert texts[0] == "Привет! Скинь ссылку на Grafana"
    assert "Встреча перенесена на 15:30" in texts
    assert "kube-prometheus prod-eu-2" in texts
    assert not any("Клавиатура" in t for t in texts)
    for ln in out["lines"]:
        x0, y0, x1, y1 = ln["box"]
        assert 0 <= x0 < x1 <= 1080 and 0 <= y0 < y1 <= 900 and 0.5 <= ln["conf"] <= 1.0


@needs_models
def test_deterministic():
    im = _img()
    assert dumps(P.run(im)) == dumps(P.run(im))


@needs_models
@pytest.mark.skipif(not list(GOLDEN.glob("*.png")), reason="no golden suite yet")
def test_golden_suite():
    pngs = sorted(GOLDEN.glob("*.png"))
    assert pngs
    for png in pngs[:6]:     # the full suite runs with `make -C ml/ocr golden-check`
        want = json.loads(png.with_suffix(".json").read_text(encoding="utf-8"))
        got = P.run(png, want["exclude_rects"])
        assert dumps(got) == dumps(want["ocr"]), png.name


@needs_models
def test_exported_dictionary_matches_model():
    assert (GOLDEN / "rec_keys.txt").read_text(encoding="utf-8").split("\n")[:-1] == P.load_keys(DEFAULT)
    assert P.load_keys(DEFAULT)[6] == "'"
