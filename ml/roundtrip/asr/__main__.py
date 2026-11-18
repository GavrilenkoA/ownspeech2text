"""ASR driver CLI: list registered models, or verify that each one loads and transcribes.

  python -m ml.roundtrip.asr list
  tools/heavy .cache/ml/roundtrip/venv/bin/python -m ml.roundtrip.asr verify \
      --ru clip_ru.wav --en clip_en.wav [--models all] [--json report.json]
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from .audio import as_samples, duration_s
from .base import AsrUnavailable
from .registry import all_specs, get_driver, resolve_models


def cmd_list(_: argparse.Namespace) -> int:
    for s in all_specs():
        langs = "+".join(sorted(s.languages))
        status = s.notes or "ok"
        print(f"{s.name:50s} {s.extra['kind']:20s} {langs:6s} catalog={s.catalog_id or '-'}  {status}")
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    clips = {k: v for k, v in (("ru", args.ru), ("en", args.en)) if v}
    if not clips:
        print("verify needs --ru and/or --en clips", file=sys.stderr)
        return 2
    audio = {k: as_samples(Path(v)) for k, v in clips.items()}
    report = []
    failures = 0
    for name in resolve_models(args.models):
        drv = get_driver(name, args.threads)
        row = {"asr_model": name, "languages": sorted(drv.spec.languages)}
        t0 = time.perf_counter()
        try:
            drv.load()
            row["load_s"] = round(time.perf_counter() - t0, 2)
            for lang, x in audio.items():
                if not drv.spec.accepts(lang):
                    continue
                t1 = time.perf_counter()
                text = drv.transcribe(x, lang)
                wall = time.perf_counter() - t1
                row[lang] = {"hypothesis": text, "wall_s": round(wall, 3),
                             "rtfx": round(duration_s(x) / max(wall, 1e-9), 2)}
            row["status"] = "ok"
        except AsrUnavailable as exc:
            row["status"] = f"unavailable: {exc}"
            failures += 1
        finally:
            drv.unload()
        print(json.dumps(row, ensure_ascii=False), flush=True)
        report.append(row)
    if args.json:
        Path(args.json).write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return 1 if failures and args.strict else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m ml.roundtrip.asr", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="print registered models").set_defaults(fn=cmd_list)
    v = sub.add_parser("verify", help="load every selected model and transcribe the clips")
    v.add_argument("--models", default="all", help="'all' or comma list of names/substrings")
    v.add_argument("--ru", help="Russian clip (wav/flac)")
    v.add_argument("--en", help="English clip (wav/flac)")
    v.add_argument("--threads", type=int, default=4)
    v.add_argument("--json", help="write the report here")
    v.add_argument("--strict", action="store_true", help="exit 1 if any model is unavailable")
    v.set_defaults(fn=cmd_verify)
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
