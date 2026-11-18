"""ctx.field.box: the training contexts must carry the focused-field bounds the phone gets from
AccessibilityNodeInfo.getBoundsInScreen, so that prompts/render.py orders and drops OCR lines the same way."""
import importlib.util
import shutil
from pathlib import Path

import pytest

from ml.ocr.config import DEFAULT, MODEL_DIR
from ml.screens.render import Renderer
from ml.screens.samples import S
from ml.screens.specs import from_teacher
from ml.screens.templates import TEMPLATES

REPO = Path(__file__).resolve().parents[3]
needs_chrome = pytest.mark.skipif(shutil.which("node") is None, reason="node not available")
needs_ocr = pytest.mark.skipif(shutil.which("node") is None or not (MODEL_DIR / DEFAULT.det_model).exists(),
                               reason="needs node + OCR models")
APP = {"package": "x", "label": "X", "category": "messaging"}
BC, HINT = "Проверка поля сорок два", "Подсказка поля"


def _inside(b, f):
    cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    return f[0] <= cx <= f[2] and f[1] <= cy <= f[3]


def _prompt_render():
    spec = importlib.util.spec_from_file_location("murmur_prompt_render_t", REPO / "prompts" / "render.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@needs_chrome
def test_every_template_reports_one_field_box_that_holds_the_field_text():
    r = Renderer()
    shown = covered = 0
    try:
        for t in sorted(TEMPLATES):
            for dev in ("fold7_cover", "fold7_inner"):
                for bc in (BC, ""):
                    spec = from_teacher(S["chat_ru"], APP, "message", "ru", seed=3, template=t, device=dev, keyboard=True)
                    spec["field"] = {"kind": "message", "hint": HINT, "before_cursor": bc}
                    res = r.render(spec)
                    fb = res["field_box"]
                    assert fb and fb[2] > fb[0] and fb[3] > fb[1], (t, dev)
                    w, h = res["size"]
                    assert 0 <= fb[0] and 0 <= fb[1] and fb[2] <= w and fb[3] <= h
                    own = [v for v in res["visible"] if (v["role"] == "field") or (v["role"] == "hint" and v["text"] == HINT)]
                    assert all(_inside(v["box"], fb) for v in own), (t, dev, bc, fb, own)
                    if bc:   # a transient overlay (snackbar, heads-up) may cover the field: rare, not never
                        seen = any(v["role"] == "field" for v in own)
                        shown, covered = shown + seen, covered + (not seen)
                spec["field"] = None
                assert r.render(spec)["field_box"] is None, (t, dev)
    finally:
        r.close()
    assert covered <= 0.15 * (shown + covered), (shown, covered)


@needs_ocr
def test_rendered_context_equals_phone_equivalent_box():
    """The phone's EditText bounds differ from the template's by a few pixels (padding, rounding, pill
    border): the <context> rendered from build_ctx's box must not change under such a shift, and must not
    repeat the field's own text as screen text."""
    from ml.screens.build_ctx import build_ctx_detailed
    pr = _prompt_render()
    checked = 0
    for t, content, dev in (("messenger_a", "chat_ru", "fold7_cover"), ("messenger_b", "chat_en", "fold7_inner"),
                            ("work_chat", "group_mixed", "fold7_cover"), ("issue_tracker", "issue_ru", "fold7_cover")):
        spec = from_teacher(S[content], APP, "message", "ru", seed=11, template=t, device=dev, keyboard=True)
        spec["field"] = {"kind": "message", "hint": "", "before_cursor": BC}
        ctx = build_ctx_detailed(spec)["ctx"]
        fb = ctx["field"]["box"]
        assert any(_inside(ln["box"], fb) for ln in ctx["screen"]["lines"]), t   # OCR read the field's own text
        a = pr.render_context_v2(ctx)
        for d in ((-12, -6, 12, 6), (8, 4, -8, -4), (0, -10, 0, -10)):
            phone = {**ctx, "field": {**ctx["field"], "box": [v + dv for v, dv in zip(fb, d)]}}
            assert pr.render_context_v2(phone) == a, (t, d)
        nobox = {**ctx, "field": {k: v for k, v in ctx["field"].items() if k != "box"}}
        assert "Проверка поля" in pr.render_context_v2(nobox).split("Screen text", 1)[-1], t   # the drift this prevents
        screen_part = a.split("Screen text", 1)[1] if "Screen text" in a else ""
        assert "Проверка поля" not in screen_part, t
        checked += 1
    assert checked == 4


@needs_ocr
def test_meta_versions_and_stale():
    from ml.screens import VERSION
    from ml.screens.build_ctx import build_ctx_detailed, stale, versions
    spec = from_teacher(S["chat_en"], APP, "message", "en", seed=2, template="messenger_b")
    m = build_ctx_detailed(spec)["meta"]
    v = versions()
    assert {k: m[k] for k in v} == v and stale(m) == [] and VERSION.startswith(v["screens"] + "+src:")
    old = dict(m, ocr="ocr.v1", det_limit=1600)
    assert len(stale(old, strict=False)) == 2
    assert stale(dict(m, screens_src="000000000000"), strict=False) == [] and stale(dict(m, screens_src="0")) != []
    assert stale({"template": "x", "seed": 1, "ocr": "ocr.v1"}) != []   # pre-v2 records carry no hashes
