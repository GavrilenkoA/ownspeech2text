#!/usr/bin/env python3
"""Render a Murmur request with prompts/render.py and split it the way the app does: the cacheable prefix
(render.cache_prefix(profile, template, preferences): chat header + system prompt + few-shot turns +
"<|im_start|>user\\n") and the per-dictation suffix (Style line + <context> + <transcript> + assistant opener with
thinking off). prefix + suffix is byte-identical to render.render_prompt (checked).

  make_prompt.py --template qwen3.5|qwen3|lfm3|lfm2.5 [--profile default.v1]
                 [--input prompts/examples/ctxv2_chat_ru.input.json] --out-prefix P.txt --out-suffix S.txt
  make_prompt.py --assets DIR [--check]      instrumented-test prompts of :infer:llm (see ASSET_CASES)

Used by tools/device/llm_npu_smoke.sh (stock prefix, DC2) and to generate
infer/llm/src/androidTest/assets/prompts/ (--check fails when they drift from prompts/).
"""
import argparse
import importlib.util
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
EXAMPLES = REPO / "prompts" / "examples"
ASSET_TEMPLATES = ("qwen3.5", "lfm3")
CATALOG = REPO / "core/catalog/src/main/resources/catalog/catalog.json"


def stock_profile() -> str:
    """llm.promptProfile of the stock catalog LLMs (one value for all of them, ADR-085); the assets follow it so that
    LlmServiceTest finds prefix.<catalog profile>.<template>.txt."""
    cat = json.loads(CATALOG.read_text(encoding="utf-8"))
    profiles = {m["llm"]["promptProfile"] for m in cat["models"] if m["kind"] == "llm" and m["family"] != "murmur-cleanup"}
    if len(profiles) != 1:
        raise SystemExit(f"stock catalog LLMs use several prompt profiles: {sorted(profiles)}")
    return profiles.pop()


ASSET_PROFILE = stock_profile()
# (asset case name, example input, transcript override). "emoji" checks the UTF-8 byte path through JNI (D11).
ASSET_CASES = (
    ("ctxv2_chat_ru", "ctxv2_chat_ru.input.json", None),
    ("ctxv2_mail_mixed", "ctxv2_mail_mixed.input.json", None),
    ("emoji", "ctxv2_chat_ru.input.json", "да тимур уже проверил договор 👍 акт сверки скину до пятницы 🙏"),
)


def load_render():
    spec = importlib.util.spec_from_file_location("murmur_render", REPO / "prompts" / "render.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def split(r, profile: str, template: str, ctx: dict, transcript: str, preferences: str | None) -> tuple[str, str]:
    full = r.render_prompt(profile, ctx, transcript, template, preferences)
    prefix = r.cache_prefix(profile, template, preferences)
    if not full.startswith(prefix):
        raise SystemExit(f"render_prompt does not start with cache_prefix ({profile}, {template})")
    return prefix, full[len(prefix):]


def assets(r, out: Path, check: bool) -> int:
    files: dict[str, str] = {}
    for tpl in ASSET_TEMPLATES:
        prefix = None
        for name, inp, transcript in ASSET_CASES:
            g = json.loads((EXAMPLES / inp).read_text(encoding="utf-8"))
            p, s = split(r, ASSET_PROFILE, tpl, g["context"], transcript or g["transcript"], g.get("preferences"))
            prefix = prefix or p
            if p != prefix:
                raise SystemExit("asset cases must share one prefix")
            files[f"suffix.{name}.{tpl}.txt"] = s
        files[f"prefix.{ASSET_PROFILE}.{tpl}.txt"] = prefix
    drift = []
    for name, text in sorted(files.items()):
        path = out / name
        if check:
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                drift.append(name)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
    if check:
        extra = sorted(p.name for p in out.glob("*.txt") if p.name not in files) if out.is_dir() else []
        if drift or extra:
            print(f"drift in {out}: {drift + extra} (regenerate: make_prompt.py --assets {out})")
            return 1
        print(f"{len(files)} prompt assets match prompts/")
        return 0
    print(f"wrote {len(files)} prompt assets to {out}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--template", choices=["qwen3.5", "qwen3", "lfm3", "lfm2.5"])
    ap.add_argument("--profile", default=ASSET_PROFILE)
    ap.add_argument("--input", default=str(EXAMPLES / "ctxv2_chat_ru.input.json"))
    ap.add_argument("--out-prefix")
    ap.add_argument("--out-suffix")
    ap.add_argument("--assets")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    r = load_render()
    if a.assets:
        return assets(r, Path(a.assets), a.check)
    if not (a.template and a.out_prefix and a.out_suffix):
        ap.error("--template, --out-prefix and --out-suffix are required (or --assets DIR)")
    g = json.loads(Path(a.input).read_text(encoding="utf-8"))
    prefix, suffix = split(r, a.profile, a.template, g["context"], g["transcript"], g.get("preferences"))
    Path(a.out_prefix).write_text(prefix, encoding="utf-8")
    Path(a.out_suffix).write_text(suffix, encoding="utf-8")
    print(json.dumps({"template": a.template, "profile": a.profile, "prefix_chars": len(prefix), "suffix_chars": len(suffix)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
