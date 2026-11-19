#!/usr/bin/env python3
"""Write the ctx v2 eval sets: prompts/eval/sets/{dev,test}.v2.jsonl + {dev,test}.v2.specs.jsonl, the v2
few-shot files and prompts/eval/smoke_cases.v2.jsonl, and verify every case's screen terms.

  python3 -m prompts.eval.cases.build_v2 [--fixture]     (from the repo root; make -C prompts sets)

ctx producer: ml.screens.build_ctx (real render + real OCR) when ml/screens/READY exists, else the fixture
prompts/eval/ocr_fixture.py (meta.ctx_source records which). Verification (CONTEXT_V2 category rules): each
meta.check.screen_terms string must be in the OCR lines (joined with spaces, so a term wrapped over two lines
counts) AND in the rendered <context> under the given ctx2 options; failures are listed and exit code is 1.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "prompts"))
sys.path.insert(0, str(ROOT))
import render  # noqa: E402

from . import dev_v1, shots_v3, shots_v4, test_v1, to_v2  # noqa: E402
from .fingerprint import producer_fingerprint  # noqa: E402

PRODUCER = producer_fingerprint()

SETS = ROOT / "prompts" / "eval" / "sets"


def _fold(s: str) -> str:
    return " ".join(s.replace("ё", "е").replace("Ё", "Е").casefold().split())


def verify(c: dict, ctx2: dict | None = None) -> list[str]:
    errs = []
    lines = " ".join(l["text"] for l in (c["ctx"].get("screen") or {}).get("lines") or [])
    rendered = render.render_context(c["ctx"], ctx2)
    for t in c["meta"]["check"].get("screen_terms", []):
        if _fold(t) not in _fold(lines):
            errs.append(f"{c['id']}: {t!r} not in OCR lines")
        elif _fold(t) not in _fold(rendered):
            errs.append(f"{c['id']}: {t!r} in OCR lines but trimmed from <context> ({ctx2 or 'default'})")
    return errs


def smoke_v1_to_case(s: dict) -> dict:
    """Smoke format -> case-like dict good enough for to_v2.spec_for."""
    keep = s.get("must") or []
    return {"id": s["id"], "category": s.get("cat", ""), "transcript": s["transcript"], "target": s.get("ref", ""),
            "ctx": s["ctx"], "meta": {"check": {"keep": keep, "forbid": s.get("must_not") or []}}}


def _real_batch(specs: list[dict], tag: str, workers: int, threads: int) -> list[dict]:
    from ml.screens.build_ctx import batch
    out = ROOT / ".cache/ml/prompt/v2real" / f"{tag}.jsonl"
    out.parent.mkdir(parents=True, exist_ok=True)
    print(json.dumps(batch(specs, str(out), workers=workers, threads=threads)), file=sys.stderr)
    return [json.loads(l) for l in out.read_text(encoding="utf-8").splitlines() if l.strip()]


def _attach(item: dict, res: dict, src: str) -> None:
    """Result of the producer -> item["c2"] (case with ctx v2)."""
    if "error" in res:
        raise RuntimeError(f"{item['key']}: {res['error']}")
    ctx = to_v2.privacy_fix(res["ctx"], item["spec"])
    fb = [v["box"] for v in res.get("visible") or [] if v.get("role") == "field"]
    if "field" in ctx and fb and "box" not in ctx["field"]:
        # fallback only: build_ctx emits field.box (focused node bounds, as getBoundsInScreen) since its 2026-11-19 update
        ctx["field"]["box"] = [min(b[0] for b in fb), min(b[1] for b in fb), max(b[2] for b in fb), max(b[3] for b in fb)]
    if not ctx["field"].get("hint") if "field" in ctx else False:
        ctx["field"].pop("hint", None)
    if not (ctx.get("field") or {}).get("before_cursor") and "field" in ctx:
        ctx["field"].pop("before_cursor", None)
    ctx["language"] = dict((item["case"]["ctx"].get("language") or ctx.get("language") or {}))
    c2 = {**item["case"], "ctx": ctx, "meta": {**item["case"]["meta"], "check": dict(item["case"]["meta"]["check"]),
                                                "ctx_source": src}}
    st = to_v2.screen_terms(item["case"])
    if st:
        c2["meta"]["check"]["screen_terms"] = st
    if res.get("meta"):
        c2["meta"]["render"] = {k: res["meta"][k] for k in ("template", "device", "theme", "font_scale", "keyboard", "ocr", "ocr_src",
                                                         "screens", "screens_src", "chrome", "det_limit")
                                if k in res["meta"]}
        c2["meta"]["render"]["producer"] = PRODUCER
    item["c2"] = c2


DENSE_CATS = {"context_names", "injection", "translit_terms", "code_switch", "continuation", "no_answer", "tone"}


def build_dense(a) -> int:
    cases = [c for c in dev_v1.CASES if c["category"] in DENSE_CATS or to_v2.screen_terms(c)]
    items = [{"group": "dense", "key": f"dense:{c['id']}", "case": c, "filler": True} for c in cases]
    for it in items:
        it["spec"] = to_v2.spec_for(it["case"], True, pools=True, force_dense=True)
    rspecs = [{**to_v2.screens_spec(it["spec"], it["case"]), "id": it["key"]} for it in items]
    for it, rs, res in zip(items, rspecs, _real_batch(rspecs, "dense", a.workers, a.threads)):
        it["rspec"] = rs
        _attach(it, res, "ml.screens.build_ctx (dense)")
    errs = [e for it in items for e in verify(it["c2"])]
    rows = [it["c2"] for it in items]
    (SETS / "dev.v2dense.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    (SETS / "dev.v2dense.specs.jsonl").write_text("".join(
        json.dumps({"id": it["case"]["id"], "spec": it["rspec"]}, ensure_ascii=False) + "\n" for it in items), encoding="utf-8")
    n = [sum(len(l["text"]) + 1 for l in (r["ctx"].get("screen") or {}).get("lines", [])) for r in rows]
    print(f"sets/dev.v2dense.jsonl: {len(rows)} cases; OCR chars median {sorted(n)[len(n) // 2]}, max {max(n)}, "
          f"> 800: {sum(x > 800 for x in n)}, > 1400: {sum(x > 1400 for x in n)}")
    for e in errs:
        print(e, file=sys.stderr)
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixture", action="store_true", help="use the fixture even if ml/screens/READY exists")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--dense", action="store_true",
                    help="only write sets/dev.v2dense.jsonl: context-relevant dev cases on dense screens (inner display, "
                         "keyboard hidden, template filler + this module's history pools) for the budget measurement")
    a = ap.parse_args(argv)
    if a.dense:
        return build_dense(a)
    real = not a.fixture and (ROOT / "ml" / "screens" / "READY").exists()
    src = "ml.screens.build_ctx" if real else "fixture:prompts/eval/ocr_fixture.py"
    sfx = "" if real else ".fx"  # fixture outputs: sets/*.v2fx.jsonl, shots/*.fx.jsonl, smoke_cases.v2fx.jsonl
    eval_tr = {c["transcript"] for c in dev_v1.CASES + test_v1.CASES}
    shot_files = {"v2ctx.v3": shots_v3.CASES, "default.v4": shots_v4.CASES,
                  "default.v4s": [c for c in shots_v4.CASES if c["id"] in shots_v4.SHORT]}
    items: list[dict] = []
    for name, cases in (("dev", dev_v1.CASES), ("test", test_v1.CASES)):
        items += [{"group": name, "key": f"{name}:{c['id']}", "case": c, "filler": True} for c in cases]
    for fname, cases in shot_files.items():
        items += [{"group": "shots:" + fname, "key": f"{fname}:{c['id']}", "case": c, "filler": False} for c in cases]
    smoke = [json.loads(l) for l in (ROOT / "prompts/eval/smoke_cases.v1.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    for sm in smoke:
        c = smoke_v1_to_case({**sm, "ctx": {"v": 1, **sm["ctx"]}})
        c["lang"] = sm.get("lang", "ru")
        items.append({"group": "smoke", "key": f"smoke:{sm['id']}", "case": c, "filler": True, "smoke": sm})
    errs: list[str] = []
    for it in items:
        it["spec"] = to_v2.spec_for(it["case"], it["filler"], pools=not real)
        if it["group"].startswith("shots") and it["case"]["transcript"] in eval_tr:
            errs.append(f"shot {it['key']} transcript is in dev/test")
    if real:
        rspecs = [{**to_v2.screens_spec(it["spec"], it["case"]), "id": it["key"]} for it in items]
        for it, rs, res in zip(items, rspecs, _real_batch(rspecs, "all", a.workers, a.threads)):
            it["rspec"] = rs
            _attach(it, res, src)
        # CONTEXT_V2: a term OCR broke -> re-render once with another theme / font scale; still broken -> OCR-hard
        bad = [it for it in items if verify(it["c2"])]
        if bad:
            retry = [{**it["rspec"], "theme": "dark" if it["rspec"]["theme"] == "light" else "light", "font_scale": 1.15}
                     for it in bad]
            for it, rs, res in zip(bad, retry, _real_batch(retry, "retry", a.workers, a.threads)):
                old = it["c2"]
                _attach(it, res, src)
                if verify(it["c2"]):
                    it["c2"] = old
                    it["c2"]["meta"]["check"]["ocr_hard"] = True
                else:
                    it["rspec"] = rs
                    it["c2"]["meta"]["rerendered"] = True
    else:
        sys.path.insert(0, str(ROOT / "prompts" / "eval"))
        import ocr_fixture
        for it in items:
            _attach(it, {"ctx": ocr_fixture.build_ctx(it["spec"])}, src)
    for it in items:
        if it["group"] in ("dev", "test"):
            it["c2"]["meta"]["supersedes"] = f"{it['group']}.v1.jsonl"
        e = verify(it["c2"])
        if it["c2"]["meta"]["check"].get("ocr_hard"):
            e = [x + " [ocr_hard, kept]" for x in e]
        errs += e
        if it["group"] in ("dev", "test") and it["c2"]["meta"]["check"].get("screen_terms"):
            for opt in ({"budget": 800}, {"order": "reading", "budget": 800}):
                errs += [x + " [info]" for x in verify(it["c2"], opt)]
    tag = "v2" if real else "v2fx"
    for name in ("dev", "test"):
        g = [it for it in items if it["group"] == name]
        rows = [it["c2"] for it in g]
        (SETS / f"{name}.{tag}.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
        (SETS / f"{name}.{tag}.specs.jsonl").write_text("".join(
            json.dumps({"id": it["case"]["id"], "spec": it.get("rspec") or it["spec"]}, ensure_ascii=False) + "\n" for it in g),
            encoding="utf-8")
        n_scr = sum(1 for r in rows if r["ctx"].get("screen"))
        n_terms = sum(1 for r in rows if r["meta"]["check"].get("screen_terms"))
        n_hard = sum(1 for r in rows if r["meta"]["check"].get("ocr_hard"))
        print(f"sets/{name}.{tag}.jsonl: {len(rows)} cases, {n_scr} with screen, {n_terms} depend on screen terms "
              f"({n_hard} OCR-hard); ctx from {src}")
    for fname in shot_files:
        rows = [it["c2"] for it in items if it["group"] == "shots:" + fname]
        (ROOT / "prompts" / "shots" / f"{fname}{sfx}.jsonl").write_text(
            "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    sm_rows = [{**it["smoke"], "ctx": it["c2"]["ctx"], "spec": it.get("rspec") or it["spec"], "ctx_source": src}
               for it in items if it["group"] == "smoke"]
    (ROOT / f"prompts/eval/smoke_cases.{tag}.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in sm_rows), encoding="utf-8")
    print(f"smoke_cases.{tag}.jsonl: {len(sm_rows)} cases; shots{sfx}: " + ", ".join(
        f"{k} {sum(1 for it in items if it['group'] == 'shots:' + k)}" for k in shot_files))
    hard = [e for e in errs if not e.endswith("[info]") and not e.endswith("kept]")]
    for e in errs:
        print(e, file=sys.stderr)
    return 1 if hard else 0


if __name__ == "__main__":
    raise SystemExit(main())
