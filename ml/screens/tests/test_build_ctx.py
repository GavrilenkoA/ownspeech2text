import shutil

import pytest

from ml.ocr.config import DEFAULT, MODEL_DIR
from ml.screens.build_ctx import build_ctx_detailed
from ml.screens.samples import sample_spec

pytestmark = pytest.mark.skipif(shutil.which("node") is None or not (MODEL_DIR / DEFAULT.det_model).exists(),
                                reason="needs node + OCR models")


def test_ctx_v2_shape_and_privacy():
    spec = sample_spec(2, 0, "chat_en")
    spec.update(device="fold7_cover", keyboard=True)
    d = build_ctx_detailed(spec)
    ctx = d["ctx"]
    assert ctx["v"] == 2 and set(ctx) >= {"app", "field", "screen", "language", "privacy"}
    assert ctx["screen"]["size"] == [1080, 2520]
    texts = [ln["text"] for ln in ctx["screen"]["lines"]]
    assert any("Terraform" in t for t in texts)
    kb = [x for x, k in zip(d["exclude_rects"], d["exclude_kinds"]) if k == "keyboard"][0]
    assert all(ln["box"][1] < kb[1] for ln in ctx["screen"]["lines"])   # nothing from the keyboard
    spec2 = dict(spec, privacy="app_only", field=None)
    ctx2 = build_ctx_detailed(spec2)["ctx"]
    assert "screen" not in ctx2 and "field" not in ctx2 and ctx2["privacy"]["reason"] == "app_only"
