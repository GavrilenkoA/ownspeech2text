"""Measurements for docs/ml/OCR_CONTEXT.md.

    python -m ml.ocr.measure detlimit [--n 48] [--threads 4]      # detector input size / tiling sweep
    python -m ml.ocr.measure profile SPECS.jsonl [--threads 4]     # CER profile of rendered specs (one config)
Writes JSON reports to .cache/ml/ocr/measure/.
"""
from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path

from . import metrics as M
from . import pipeline as P
from .config import DEFAULT, REPO

OUT = REPO / ".cache" / "ml" / "ocr" / "measure"


def render_set(n: int, seed: int, out_dir: Path) -> list[dict]:
    """n sample screens, half cover half inner, cached on disk (png + visible + exclude_rects)."""
    from ml.screens.render import Renderer
    from ml.screens.samples import sample_spec
    out_dir.mkdir(parents=True, exist_ok=True)
    idx = out_dir / "index.json"
    if idx.exists():
        items = json.loads(idx.read_text())
        if len(items) >= n:
            return items[:n]
    r = Renderer()
    items = []
    for i in range(n):
        spec = sample_spec(i, seed)
        spec["device"] = "fold7_cover" if i % 2 == 0 else "fold7_inner"
        png = out_dir / f"{spec['id']}.png"
        res = r.render(spec, png)
        items.append({"id": spec["id"], "png": str(png), "device": spec["device"], "visible": res["visible"],
                      "exclude_rects": res["exclude_rects"], "template": spec["template"]})
    r.close()
    idx.write_text(json.dumps(items, ensure_ascii=False))
    return items


def evaluate(items: list[dict], cfg) -> dict:
    by_dev: dict[str, list] = {}
    lat: dict[str, list] = {}
    for it in items:
        d = P.run_detailed(it["png"], it["exclude_rects"], cfg)
        by_dev.setdefault(it["device"], []).append(M.compare(it["visible"], d["result"]["lines"], it["exclude_rects"]))
        lat.setdefault(it["device"], []).append(d["ms"])
    out = {}
    for dev in by_dev:
        a = M.aggregate(by_dev[dev])
        ms = lat[dev]
        out[dev] = {k: a[k] for k in ("screens", "cer", "content_cer", "item_recall_exact", "missed_share",
                                     "non_content_line_share", "mixed_script_token_share")}
        out[dev]["det_ms_median"] = round(statistics.median(m["det"] for m in ms))
        out[dev]["rec_ms_median"] = round(statistics.median(m["rec"] for m in ms))
        out[dev]["total_ms_median"] = round(statistics.median(m["total"] for m in ms))
        out[dev]["total_ms_p90"] = round(sorted(m["total"] for m in ms)[int(0.9 * (len(ms) - 1))])
    return out


def detlimit(n: int, threads: int) -> dict:
    items = render_set(n, 11, OUT / "screens")
    base = DEFAULT.with_(threads=threads)
    configs = {"limit_960": base.with_(det_limit=960), "limit_1280": base.with_(det_limit=1280),
               "limit_1600": base.with_(det_limit=1600), "limit_2048": base.with_(det_limit=2048),
               "native": base.with_(det_limit=0), "native_tile1024": base.with_(det_limit=0, det_tile=1024)}
    P.run(items[0]["png"], (), base)   # warm-up
    rep = {}
    for name, cfg in configs.items():
        t = time.time()
        rep[name] = evaluate(items, cfg)
        print(name, round(time.time() - t), "s", json.dumps(rep[name]), flush=True)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "detlimit.json").write_text(json.dumps({"threads": threads, "n": n, "results": rep}, indent=1))
    return rep


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m ml.ocr.measure")
    ap.add_argument("what", choices=["detlimit"])
    ap.add_argument("--n", type=int, default=48)
    ap.add_argument("--threads", type=int, default=4)
    a = ap.parse_args(argv)
    if a.what == "detlimit":
        detlimit(a.n, a.threads)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
