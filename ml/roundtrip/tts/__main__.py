"""TTS CLI: list systems/voices, or synthesise one sentence per voice for listening checks.

  python -m ml.roundtrip.tts list [--systems cpu]
  tools/heavy .cache/ml/roundtrip/venv/bin/python -m ml.roundtrip.tts say --systems supertonic3 \
      --lang mixed --text "Задеплой в Kubernetes" --out /tmp/tts [--voices sid0,sid3]
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from ..asr.audio import SAMPLE_RATE, write_audio
from .base import TtsUnavailable
from .registry import get_tts, resolve_systems


def cmd_list(args: argparse.Namespace) -> int:
    for system in resolve_systems(args.systems):
        drv = get_tts(system)
        voices = drv.voices()
        print(f"{system} ({'GPU' if drv.gpu else 'CPU'}): {len(voices)} voices")
        for v in voices:
            print(f"  {v.voice:32s} {'+'.join(sorted(v.languages)):6s} latin_in_ru={v.latin_in_ru:8s} {v.licence}")
    return 0


def cmd_say(args: argparse.Namespace) -> int:
    out = Path(args.out)
    for system in resolve_systems(args.systems):
        drv = get_tts(system, args.threads)
        voices = [v for v in drv.voices() if v.speaks(args.lang)]
        if args.voices:
            want = set(args.voices.split(","))
            voices = [v for v in voices if v.voice in want]
        for v in voices:
            t0 = time.perf_counter()
            try:
                x = drv.synthesize(args.text, v.voice, args.lang, seed=args.seed)
            except TtsUnavailable as exc:
                print(json.dumps({"system": system, "error": str(exc)}), flush=True)
                break
            wall = time.perf_counter() - t0
            path = out / f"{system}__{v.voice.replace('#', '_')}.wav"
            write_audio(path, x)
            dur = len(x) / SAMPLE_RATE
            print(json.dumps({"system": system, "voice": v.voice, "audio_s": round(dur, 2),
                              "wall_s": round(wall, 2), "path": str(path)}), flush=True)
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m ml.roundtrip.tts", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    ls = sub.add_parser("list", help="list systems and voices")
    ls.add_argument("--systems", default="all")
    ls.set_defaults(fn=cmd_list)
    s = sub.add_parser("say", help="synthesise one sentence with every matching voice")
    s.add_argument("--systems", default="cpu")
    s.add_argument("--voices", default="", help="comma list of voice ids (default: all that speak --lang)")
    s.add_argument("--lang", choices=("ru", "en", "mixed"), required=True)
    s.add_argument("--text", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--threads", type=int, default=2)
    s.add_argument("--seed", type=int, default=0)
    s.set_defaults(fn=cmd_say)
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
