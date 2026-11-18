#!/usr/bin/env python3
"""Split a rendered Murmur prompt (prompts/render.py, v1) into the cacheable prefix and the per-dictation suffix.

prefix = everything up to and including the first "<|im_start|>user\\n" (BOS + system turn; it depends only on
         the system prompt + user preferences, so the app snapshots it once per model load)
suffix = style line + <context> + <transcript> + "<|im_end|>\\n" + assistant generation prefix (thinking off)

  make_prompt.py --template lfm3|qwen3.5 [--system default|finetuned] [--input prompts/examples/golden_en.input.json]
                 --out-prefix P.txt --out-suffix S.txt
"""
import argparse
import importlib.util
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]


def load_render():
    spec = importlib.util.spec_from_file_location("murmur_render", REPO / "prompts" / "render.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--template", required=True, choices=["lfm3", "qwen3.5"])
    ap.add_argument("--system", default="default")
    ap.add_argument("--input", default=str(REPO / "prompts" / "examples" / "golden_en.input.json"))
    ap.add_argument("--out-prefix", required=True)
    ap.add_argument("--out-suffix", required=True)
    a = ap.parse_args()
    r = load_render()
    g = json.loads(Path(a.input).read_text(encoding="utf-8"))
    full = r.chat(r.system_prompt(a.system, g.get("preferences")), r.user_message(g["context"], g["transcript"]), a.template)
    marker = "<|im_start|>user\n"
    cut = full.index(marker) + len(marker)
    Path(a.out_prefix).write_text(full[:cut], encoding="utf-8")
    Path(a.out_suffix).write_text(full[cut:], encoding="utf-8")
    print(json.dumps({"template": a.template, "prefix_chars": cut, "suffix_chars": len(full) - cut}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
