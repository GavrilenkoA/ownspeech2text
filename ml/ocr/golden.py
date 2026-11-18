"""Golden parity suite for the Android OCR port: ml/ocr/golden/NNN.png + NNN.json.

    python -m ml.ocr.golden make     # render the suite and write the expected outputs (needs Chrome)
    python -m ml.ocr.golden check    # re-run the Python reference on every PNG, byte-compare `ocr`

NNN.json = {"pipeline", "spec", "exclude_rects", "exclude_kinds", "field_box", "visible", (inputs / ground truth;
                                                       field_box = the focused field, ctx.field.box)
            "stages": {"det_input": [w, h], "det_boxes": [[x0,y0,x1,y1,score]],          (after DB post-processing,
                       "rec": [{"box", "text", "conf"}]},                                 before exclusion / after recognition)
            "ocr": {"size", "lines"}}                                                     (the final output)
PNGs are re-encoded losslessly (optimize=True) to keep the suite small.
"""
from __future__ import annotations

import io
import json
import sys
from pathlib import Path

from . import pipeline as P
from .config import DEFAULT, PIPELINE_VERSION
from .run import dumps

GOLDEN = Path(__file__).resolve().parent / "golden"

# (sample content, template, device, theme, font_scale, keyboard)
CASES = [
    ("chat_ru", "messenger_a", "fold7_cover", "light", 1.0, True),
    ("chat_ru", "messenger_a", "fold7_inner", "dark", 1.0, True),
    ("chat_en", "messenger_b", "fold7_cover", "dark", 1.15, True),
    ("chat_en", "messenger_b", "fold7_inner", "light", 0.9, False),
    ("group_mixed", "messenger_a", "fold7_cover", "dark", 0.85, True),
    ("group_mixed", "work_chat", "fold7_inner", "light", 1.0, True),
    ("work_en", "work_chat", "fold7_cover", "dark", 1.3, False),
    ("mail_en", "email_inbox", "fold7_cover", "light", 1.0, False),
    ("mail_en", "email_inbox", "fold7_inner", "dark", 1.0, True),
    ("mail_en", "email_thread", "fold7_cover", "dark", 1.1, True),
    ("mail_ru", "email_compose", "fold7_cover", "light", 1.0, True),
    ("mail_ru", "email_thread", "fold7_inner", "light", 1.15, True),
    ("note_ru", "notes", "fold7_cover", "light", 1.3, True),
    ("note_ru", "notes", "fold7_inner", "dark", 1.0, False),
    ("doc_en", "doc_editor", "fold7_inner", "light", 1.0, True),
    ("article_ru", "browser_article", "fold7_cover", "dark", 1.0, False),
    ("search_en", "browser_search", "fold7_cover", "light", 0.9, True),
    ("pr_en", "browser_pr", "fold7_inner", "dark", 1.0, True),
    ("docs_en", "browser_docs", "fold7_inner", "light", 0.85, False),
    ("term", "terminal", "fold7_cover", "dark", 1.0, True),
    ("term", "terminal", "fold7_inner", "light", 1.15, False),
    ("code", "code_editor", "fold7_cover", "dark", 1.0, True),
    ("issue_ru", "issue_tracker", "fold7_cover", "light", 1.1, True),
    ("ai_ru", "ai_chat", "fold7_inner", "dark", 1.0, True),
    ("cal_en", "calendar_tasks", "fold7_cover", "light", 1.0, True),
    ("social_ru", "social_feed", "fold7_inner", "light", 1.3, True),
]


def expected(png, exclude_rects, cfg=DEFAULT) -> dict:
    rgb = P.load_rgb(png)
    h, w = rgb.shape[:2]
    m = P.models(cfg)
    x, tw, th = P.det_input(rgb, cfg)
    dets = P.db_boxes(P.prob_map(m, x, cfg), w, h, cfg)
    boxes = [b for b, _ in dets if not P.pp.excluded(b, [tuple(r) for r in exclude_rects], cfg.exclude_min_overlap)]
    recs = P.recognise(m, rgb, boxes, cfg)
    return {"stages": {"det_input": [tw, th], "det_boxes": [b + [s] for b, s in dets],
                       "rec": [{"box": b, "text": t, "conf": round(c, 4)} for b, (t, c) in zip(boxes, recs)]},
            "ocr": P.run(rgb, exclude_rects, cfg)}


def make() -> None:
    from PIL import Image
    from ml.screens.render import Renderer
    from ml.screens.samples import APPS, S
    from ml.screens.specs import from_teacher
    GOLDEN.mkdir(exist_ok=True)
    for old in list(GOLDEN.glob("[0-9][0-9][0-9].*")):
        old.unlink()
    r = Renderer()
    total = 0
    for i, (key, tpl, dev, theme, fs, kb) in enumerate(CASES, 1):
        pkg, label, cat, kind, lang = APPS[key]
        spec = from_teacher(S[key], {"package": pkg, "label": label, "category": cat}, kind, lang, seed=1000 + i,
                            template=tpl, device=dev, theme=theme, font_scale=fs, keyboard=kb)
        spec["bubble"] = i % 3 == 0
        res = r.render(spec)
        buf = io.BytesIO()
        Image.open(io.BytesIO(res["png"])).save(buf, "PNG", optimize=True)
        png = GOLDEN / f"{i:03d}.png"
        png.write_bytes(buf.getvalue())
        exp = expected(png, res["exclude_rects"])
        doc = {"pipeline": PIPELINE_VERSION, "spec": spec, "exclude_rects": res["exclude_rects"],
               "exclude_kinds": res["exclude_kinds"], "field_box": res["field_box"], "visible": res["visible"], **exp}
        png.with_suffix(".json").write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        total += png.stat().st_size + png.with_suffix(".json").stat().st_size
        print(f"{png.name} {tpl} {dev} {theme} fs={fs} kb={kb} lines={len(exp['ocr']['lines'])}", flush=True)
    r.close()
    print(f"{len(CASES)} pairs, {total / 1e6:.1f} MB")


def check() -> int:
    bad = 0
    pngs = sorted(GOLDEN.glob("[0-9][0-9][0-9].png"))
    for png in pngs:
        want = json.loads(png.with_suffix(".json").read_text(encoding="utf-8"))
        got = expected(png, want["exclude_rects"])
        ok = dumps(got["ocr"]) == dumps(want["ocr"]) and dumps(got["stages"]) == dumps(want["stages"])
        bad += not ok
        print(png.name, "ok" if ok else "MISMATCH", flush=True)
    print(f"{len(pngs) - bad}/{len(pngs)} identical")
    return 1 if bad else 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    sys.exit(make() if cmd == "make" else check())
