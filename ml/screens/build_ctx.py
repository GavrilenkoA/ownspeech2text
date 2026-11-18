"""spec -> ctx v2 (docs/ml/CONTEXT_V2.md): render -> OCR with the render's exclude_rects -> app/field/screen.
The only way training and eval contexts are produced.

    from ml.screens.build_ctx import build_ctx, build_ctx_detailed
    ctx = build_ctx(spec)

    python -m ml.screens.build_ctx SPEC.json                      # ctx v2 on stdout
    python -m ml.screens.build_ctx --batch SPECS.jsonl OUT.jsonl [--workers 4] [--png-dir DIR]
      OUT.jsonl: one {"id", "ctx", "visible", "exclude_rects", "meta"} per spec, in input order.

Spec extras beyond CONTEXT_V2's render spec (all optional): "app" {package, label, category} (default: the
template's look-alike app), "field": null = no focused node (ctx.field absent), field.kind, "lang"
("ru"|"en"|"mixed" -> language.asr), "privacy" ("full" | anything else -> no screen, reason set),
"avoid" (strings filler must not contain). Use ml.screens.specs.from_teacher() to make specs.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

from ml.ocr import pipeline as ocr
from ml.ocr.config import DEFAULT as OCR_DEFAULT, PIPELINE_VERSION

from .render import Renderer
from .templates import TEMPLATE_APPS, normalise_spec

_R: Renderer | None = None


def _renderer() -> Renderer:
    global _R
    if _R is None:
        _R = Renderer()
    return _R


def app_of(spec: dict) -> dict:
    if spec.get("app"):
        a = spec["app"]
        return {"package": a.get("package", ""), "label": a.get("label", ""), "category": a.get("category", "default")}
    pkg, label, cat, _, _ = TEMPLATE_APPS[spec["template"]]
    return {"package": pkg, "label": label, "category": cat}


def field_of(spec: dict) -> dict | None:
    f = spec.get("field")
    if f is None:
        return None
    kind = f.get("kind") or TEMPLATE_APPS[spec["template"]][3]
    out = {"kind": kind, "hint": f.get("hint") or "", "before_cursor": f.get("before_cursor") or ""}
    if f.get("password"):
        out["password"] = True
    return out


def build_ctx_detailed(spec: dict, renderer: Renderer | None = None, ocr_cfg=OCR_DEFAULT, png_out=None) -> dict:
    spec = normalise_spec(spec)
    t0 = time.perf_counter()
    res = (renderer or _renderer()).render(spec, png_out)
    t1 = time.perf_counter()
    privacy = spec.get("privacy", "full")
    field = field_of(spec)
    screen, reason = None, None
    if privacy != "full":
        reason = spec.get("privacy_reason") or privacy
    elif field and field.get("password"):
        reason = "password_field"
    else:
        o = ocr.run(res["png"], res["exclude_rects"], ocr_cfg)
        if o["lines"]:
            screen = o
    t2 = time.perf_counter()
    lang = spec.get("lang", "ru")
    ctx = {"v": 2, "app": app_of(spec)}
    if field:
        ctx["field"] = field
    if screen:
        ctx["screen"] = {"size": screen["size"], "lines": screen["lines"]}
    ctx["language"] = {"asr": "en" if lang == "en" else "ru"}
    ctx["privacy"] = {"level": privacy, "reason": reason}
    meta = {"template": spec["template"], "device": spec["device"], "theme": spec["theme"], "font_scale": spec["font_scale"],
            "locale": spec["locale"], "keyboard": bool(spec.get("keyboard")) and spec.get("field") is not None,
            "seed": spec["seed"], "ocr": PIPELINE_VERSION, "det_limit": ocr_cfg.det_limit,
            "ms": {"render": round((t1 - t0) * 1e3), "ocr": round((t2 - t1) * 1e3)}}
    return {"ctx": ctx, "visible": res["visible"], "exclude_rects": res["exclude_rects"],
            "exclude_kinds": res["exclude_kinds"], "meta": meta, "png": res["png"]}


def build_ctx(spec: dict) -> dict:
    """CONTEXT_V2 interface: spec -> ctx v2."""
    return build_ctx_detailed(spec)["ctx"]


# ----------------------------------------------------------------------------- batch

_W: dict = {}


def _init(threads: int, det_limit: int):
    _W["r"] = Renderer()
    _W["cfg"] = OCR_DEFAULT.with_(threads=threads, det_limit=det_limit)


def _one(args):
    i, spec, png_dir = args
    sid = spec.get("id") or f"{i:06d}"
    png = Path(png_dir) / f"{sid}.png" if png_dir else None
    try:
        d = build_ctx_detailed(spec, _W["r"], _W["cfg"], png)
        d.pop("png")
        return i, {"id": sid, **d}
    except Exception as ex:   # one bad spec must not kill a batch
        return i, {"id": sid, "error": f"{type(ex).__name__}: {ex}"}


def batch(specs: list[dict], out_path: str, workers: int = 4, threads: int = 2, png_dir: str | None = None,
          det_limit: int = OCR_DEFAULT.det_limit) -> dict:
    import multiprocessing as mp
    if png_dir:
        Path(png_dir).mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    res: dict[int, dict] = {}
    jobs = [(i, s, png_dir) for i, s in enumerate(specs)]
    with mp.get_context("spawn").Pool(workers, initializer=_init, initargs=(threads, det_limit)) as pool:
        for i, d in pool.imap_unordered(_one, jobs, chunksize=1):
            res[i] = d
    with open(out_path, "w", encoding="utf-8") as f:
        for i in range(len(specs)):
            f.write(json.dumps(res[i], ensure_ascii=False) + "\n")
    errs = sum(1 for d in res.values() if "error" in d)
    return {"n": len(specs), "errors": errs, "wall_s": round(time.time() - t0, 1), "workers": workers, "threads": threads}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m ml.screens.build_ctx")
    ap.add_argument("spec")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--batch", action="store_true")
    ap.add_argument("--workers", type=int, default=int(os.environ.get("MURMUR_SCREENS_WORKERS", "4")))
    ap.add_argument("--threads", type=int, default=2, help="onnxruntime threads per worker")
    ap.add_argument("--png-dir")
    ap.add_argument("--det-limit", type=int, default=OCR_DEFAULT.det_limit)
    a = ap.parse_args(argv)
    if a.batch:
        specs = [json.loads(x) for x in Path(a.spec).read_text(encoding="utf-8").splitlines() if x.strip()]
        print(json.dumps(batch(specs, a.out, a.workers, a.threads, a.png_dir, a.det_limit)))
    else:
        spec = json.loads(Path(a.spec).read_text(encoding="utf-8"))
        d = build_ctx_detailed(spec, png_out=a.out)
        sys.stdout.write(json.dumps(d["ctx"], ensure_ascii=False, indent=1) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
