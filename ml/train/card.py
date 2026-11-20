#!/usr/bin/env python3
"""Model card (README.md) + Model-hub catalog entry for an exported fine-tune.

  python -m ml.train.card --run OUTPUT_DIR --name murmur-cleanup-qwen3.5-0.8b --repo OWNER/REPO \
      --eval OUTPUT_DIR/eval/Q4_0/report.json [--ref-eval OUTPUT_DIR/eval/BF16/report.json] \
      [--stock-eval EVAL/stock-q4/report.json]

Reads RUN/gguf/manifest.json (from ml.train.export) and RUN/train_info.json; writes
RUN/gguf/README.md and RUN/gguf/catalog_entry.json (shape of docs/research/llm-catalog.json
"models[]" entries, plus a prompt_profile). Quantisation loss is copied from measured reports only.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ml.eval import compare, contract, gates
from ml.eval.run_eval import table

LICENSES = {
    "qwen3.5": {"id": "apache-2.0", "commercial": "yes", "card": "apache-2.0",
                "notice": "Derived from Qwen3.5 (Apache-2.0). Modified by fine-tuning; see LICENSE."},
    "lfm3": {"id": "lfm-open-1.0 (derivative)", "commercial": "only while the licensee's annual revenue < US$10M",
             "card": "other", "license_name": "lfm1.0",
             "notice": "Derivative of LiquidAI/LFM3-1.7B under the LFM Open License v1.0 (copy in LICENSE). "
                       "Modified by fine-tuning. Commercial use is licensed only below US$10M annual revenue."},
}
TEMPLATE_IDS = {"qwen3.5": "qwen3.5-chatml", "lfm3": "lfm3-chatml"}


def load(p):
    return json.loads(Path(p).read_text()) if p and Path(p).exists() else None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--repo", required=True, help="HF repo the GGUF is uploaded to, e.g. owner/murmur-cleanup-qwen3.5-0.8b-gguf")
    ap.add_argument("--revision", default=None, help="HF commit sha after upload (filled by upload_hf.py)")
    ap.add_argument("--eval", required=True, help="report.json of the shipped quantised file")
    ap.add_argument("--ref-eval", help="report.json of the BF16 GGUF (quantisation loss)")
    ap.add_argument("--stock-eval", help="report.json of the stock model with the default prompt")
    ap.add_argument("--qloss-json", help="ml.eval.compare --json output (BF16 vs Q4_0 on the same engine); "
                                         "default: compare --ref-eval with --eval")
    ap.add_argument("--quant", default="Q4_0")
    a = ap.parse_args(argv)
    run = Path(a.run)
    man = load(run / "gguf/manifest.json")
    info = load(run / "train_info.json") or {}
    rcfg = load(run / "config.resolved.json") or {}
    sysname = contract.render._versioned((rcfg.get("prompt") or {}).get("system") or "finetuned.v2")
    rep = load(a.eval)
    tpl = man["template"]
    lic = LICENSES[tpl]
    f = next(x for x in man["files"] if x["quant"] == a.quant and not x.get("deleted"))
    ref, stock = load(a.ref_eval), load(a.stock_eval)
    qloss = load(a.qloss_json) or (compare.compare(ref, rep) if ref else None)
    vs_stock = compare.compare(stock, rep) if stock else None
    gs, gr = (compare.guarded_view(stock), compare.guarded_view(rep)) if stock else (None, None)
    vs_stock_guarded = compare.compare(gs, gr) if gs and gr else None
    o = rep["summary"]["overall"]
    if contract.render._versioned(rep["info"].get("system") or "") != sysname:
        print(f"WARNING: {a.eval} was measured with system {rep['info'].get('system')!r}, the run trained with "
              f"{sysname!r}: the card's numbers do not describe the shipped prompt", file=__import__("sys").stderr)

    entry = {
        "id": a.name, "family": "finetune", "display_name": a.name.replace("-", " "), "role": "finetune",
        "status": "candidate" if rep["gates"]["verdict"] != "PASS" else "available",
        "base_repo": info.get("base_model"), "params": info.get("params_total"),
        "languages": ["ru", "en"], "license": {"id": lic["id"], "commercial": lic["commercial"]},
        "context_length": 4096, "template_id": TEMPLATE_IDS[tpl], "runtime_format": "gguf",
        "min_llama_cpp": man.get("llama_cpp_build"),
        "prompt_profile": {"system": sysname, "system_file": f"prompts/system/{sysname}.md",
                           "preferences_block": "preferences_block.v1", "thinking": "off",
                           "decoding": "greedy, no repetition/presence penalty",
                           "max_new_tokens_formula": "min(1024, 2*transcript_tokens + 64)"},
        "download_files": [{"repo": a.repo, "repo_sha": a.revision, "path": f["path"], "bytes": f["bytes"],
                            "sha256": f["sha256"], "quant": f["quant"] + (" (imatrix)" if f.get("imatrix") else ""),
                            "default": True, "backends": ["cpu", "gpu", "npu"] if f["quant"] == "Q4_0" else ["cpu", "gpu"]}],
        "eval": {"set": rep["info"]["data"], "n": o["n"], "verdict": rep["gates"]["verdict"], "em": o["em"],
                 "nwer": o["nwer"], "noop_exact": o["noop_exact"], "answer_rate": o["answer_rate"],
                 "injection_success": o["injection_success"], "entity_recall": o["entity_recall"],
                 "guarded_verdict": (rep.get("guarded") or {}).get("gates", {}).get("verdict"),
                 "pass_guarded_by_set": {k: v.get("pass") for k, v in
                                         (((rep.get("guarded") or {}).get("summary") or {}).get("by_set") or {}).items()},
                 "vs_best_stock_pass_guarded": {k: v.get("delta_pass_guarded_vs_best") for k, v in
                                                ((rep.get("baseline") or {}).get("sets") or {}).items()}},
        "training": {"method": info.get("method"), "steps": info.get("steps"), "run_name": info.get("run_name")},
    }
    (run / "gguf/catalog_entry.json").write_text(json.dumps(entry, indent=1, ensure_ascii=False))

    front = ["---", f"license: {lic['card']}"] + ([f"license_name: {lic['license_name']}", "license_link: LICENSE"]
                                                  if "license_name" in lic else []) + [
        f"base_model: {info.get('base_model')}", "base_model_relation: finetune", "library_name: gguf",
        "language: [ru, en]", "pipeline_tag: text-generation", "tags: [gguf, llama.cpp, dictation, murmur]", "---"]
    md = front + [
        f"# {a.name}", "",
        "On-device dictation post-processor for the Murmur Android app: turns a raw ru/en speech-to-text "
        "transcript plus screen context into the text the speaker meant to type. Not a chat model.", "",
        f"* Base: `{info.get('base_model')}`; method: **{info.get('method')}**; steps: {info.get('steps')}",
        f"* File: `{f['path']}` ({f['bytes']:,} bytes, sha256 `{f['sha256']}`), llama.cpp `{man.get('llama_cpp_build')}`",
        f"* Prompt: system = `prompts/system/{sysname}.md` (+ optional preferences block), user = `Style:` line + "
        "`<context>` + `<transcript>` exactly as `prompts/render.py` builds it; thinking disabled "
        f"(`{'<think>' + chr(92) + 'n' + chr(92) + 'n</think>' + chr(92) + 'n' + chr(92) + 'n' if tpl == 'qwen3.5' else '<think></think>'}`); "
        "greedy decoding; stop on `<|im_end|>`.",
        f"* {lic['notice']}", "",
        f"## Evaluation ({rep['info']['backend']}, {Path(rep['info']['model']).name}, n={o['n']})", "",
        table(rep["summary"]), "", gates.to_markdown(rep["gates"]), ""]
    if qloss:
        md += ["## Quantisation loss (measured: BF16 GGUF vs this file, same eval set)", "", compare.to_markdown(qloss), ""]
    if rep.get("guarded"):
        md += ["## After the app's output guard (prompts/guard.py)", "", table(rep["guarded"]["summary"]), "",
               gates.to_markdown(rep["guarded"]["gates"]), ""]
    if vs_stock:
        sp = stock["info"].get("system")
        md += [f"## Versus the stock model with the shipped stock prompt (`{sp}`), raw outputs", "",
               compare.to_markdown(vs_stock), ""]
    if vs_stock_guarded:
        md += [f"## Versus the stock model (`{stock['info'].get('system')}`), both after prompts/guard.py", "",
               compare.to_markdown(vs_stock_guarded), ""]
    if rep.get("baseline"):
        from ml.eval import baseline
        md += [baseline.to_markdown(rep["baseline"], label=a.name), ""]
    md += ["## Limitations", "", "* Host-side latency in the tables is not phone latency; use the app's Benchmark.",
           "* Evaluated on synthetic + hand-written sets; real-dictation (E5) results are listed only when measured."]
    (run / "gguf/README.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(json.dumps(entry, indent=1, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
