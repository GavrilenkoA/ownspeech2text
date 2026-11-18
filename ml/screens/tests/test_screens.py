import hashlib
import shutil

import pytest

from ml.screens.render import Renderer, _merge, build_html
from ml.screens.samples import S, sample_spec
from ml.screens.specs import from_teacher
from ml.screens.templates import TEMPLATES, normalise_spec

needs_chrome = pytest.mark.skipif(shutil.which("node") is None, reason="node not available")


def test_every_template_builds_html_on_both_devices():
    for t in TEMPLATES:
        for dev in ("fold7_cover", "fold7_inner"):
            for kb in (True, False):
                spec = from_teacher(S["group_mixed"], {"package": "x", "label": "X", "category": "messaging"}, "message",
                                    "ru", seed=1, template=t, device=dev, keyboard=kb)
                html, d = build_html(spec)
                assert "<body>" in html and d["w"] in (1080, 1968)
                assert ('data-exclude="keyboard"' in html) == kb


def test_spec_validation():
    with pytest.raises(ValueError):
        normalise_spec({"template": "nope"})
    with pytest.raises(ValueError):
        normalise_spec({"template": "notes", "font_scale": 3})


def test_from_teacher_keeps_all_content_and_is_deterministic():
    sc = S["group_mixed"]
    a = from_teacher(sc, {"package": "org.telegram.messenger", "label": "Telegram", "category": "messaging"}, "message", "mixed", seed=5)
    b = from_teacher(sc, {"package": "org.telegram.messenger", "label": "Telegram", "category": "messaging"}, "message", "mixed", seed=5)
    assert a == b
    assert [m["text"] for m in a["messages"]] == [m["text"] for m in sc["messages"]]
    assert a["snippets"] == sc["snippets"] and a["title"] == sc["title"]
    assert build_html(a)[0] == build_html(b)[0]


def test_merge_fragments():
    out = _merge([{"role": "message", "text": "Hello", "box": [0, 0, 50, 20]},
                  {"role": "message", "text": "world", "box": [55, 0, 100, 20]},
                  {"role": "time", "text": "12:00", "box": [101, 0, 140, 20]}])
    assert [o["text"] for o in out] == ["Hello world", "12:00"]


@needs_chrome
def test_render_visible_and_deterministic(tmp_path):
    r = Renderer(tmp_path / "w")
    spec = sample_spec(1, 0, "chat_ru")
    spec.update(device="fold7_cover", keyboard=True)
    a = r.render(spec)
    b = r.render(spec)
    r.close()
    assert a["size"] == [1080, 2520]
    assert hashlib.sha256(a["png"]).hexdigest() == hashlib.sha256(b["png"]).hexdigest()
    assert a["visible"] == b["visible"]
    roles = {v["role"] for v in a["visible"]}
    assert {"title", "message", "keyboard", "status"} <= roles
    kb = [x for x, k in zip(a["exclude_rects"], a["exclude_kinds"]) if k == "keyboard"]
    assert kb and kb[0][3] > 2000 and kb[0][2] == 1080
    for v in a["visible"]:
        x0, y0, x1, y1 = v["box"]
        assert 0 <= x0 < x1 <= 1080 and 0 <= y0 < y1 <= 2520


@needs_chrome
def test_scrolled_off_text_is_not_visible(tmp_path):
    r = Renderer(tmp_path / "w")
    long = [{"author": "A", "is_me": i % 2 == 0, "text": f"Сообщение номер {i} " + "текст " * 30} for i in range(4)]
    spec = from_teacher({"title": "A", "messages": long, "snippets": [], "before_cursor": ""},
                        {"package": "org.telegram.messenger", "label": "Telegram", "category": "messaging"}, "message", "ru",
                        seed=3, template="messenger_a", device="fold7_cover", keyboard=True)
    res = r.render(spec)
    r.close()
    text = " ".join(v["text"] for v in res["visible"] if v["role"] == "message")
    assert "номер 3" in text and "номер 0" not in text
