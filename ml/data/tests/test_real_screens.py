"""Real render + OCR path (ml.screens + ml.ocr). Skipped unless both READY files exist and the OCR venv's
packages are importable (run with .cache/ml/ocr/venv/bin/python -m pytest ml/data/tests)."""
import pytest

from ml.data.synth import build as B
from ml.data.synth import sampler as S
from ml.data.synth import screen as SCR

pytest.importorskip("numpy")
pytest.importorskip("onnxruntime")
if not (SCR.SCREENS_READY.exists() and SCR.OCR_READY.exists()):
    pytest.skip("ml/screens or ml/ocr not READY", allow_module_level=True)


def test_context_resolution_and_counterpart_on_real_ocr():
    be = SCR.Backend("real")
    sc = next(s for s in S.plan(300, 0, 0, seed=4) if s["category"] == "context_resolution" and s["counterpart"]
              and not s["screen_absent"] and s["lang"] == "ru")
    term = sc["spoken_terms"][0]["text"]
    content = {"title": "Команда бэкенда", "messages": [
        {"author": "Оля Смирнова", "is_me": False, "text": f"Кто вчера обновлял {term} на стенде?"},
        {"author": "Оля Смирнова", "is_me": False, "text": "Обед в два, не забудь"}], "snippets": [], "before_cursor": ""}
    try:
        ctx, meta = B.make_ctx(sc, content, be, f"Я обновлял {term}.")
    except B.OcrReject as e:  # an OCR-hard term is a legitimate outcome; the rule machinery must say so
        assert e.rule == "term_not_readable" and e.term == term
        return
    assert ctx["v"] == 2 and SCR.on_screen(ctx, term) and meta["render"]["ocr"]
    cp, cmeta = B.counterpart_ctx(sc, meta["spec"], term, be)
    assert not SCR.close_on_screen(cp, term)
    assert cmeta["spec"]["template"] == meta["spec"]["template"]
