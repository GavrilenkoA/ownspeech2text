#!/usr/bin/env python3
"""Tokenizer host test (ADR-045, WP-10 item 7): the prompt the app renders tokenises the way the catalog and the
prefix cache assume, for every shipped GGUF vocabulary.

  (a) prefix tokens: for every catalog LLM (fine-tunes use their base model's GGUF), the token count of
      render.cache_prefix(llm.promptProfile, llm.template) equals the catalog's llm.prefixTokens.
  (b) additivity: for every rendered golden (eval corpus v2 + smoke + examples + edge cases, profiles default.v1
      (stock, ADR-085), default.v4, default.v4s, finetuned.v1, all four templates), tokens(prefix) + tokens(suffix) == tokens(full). This is what
      makes the restored prefix snapshot + suffix prefill equal to prefilling the whole prompt.
  (c) informational gate of the optional "prefill the context while the user speaks" split (WP-10 item 8): the
      same additivity at the end of "<transcript>\n" (prefix + context part vs transcript + assistant opener).
      Reported, not failed: the optimisation stays off until (c) holds for every shipped template.

Counting = the host llama-tokenize (add_special = false via --no-bos, parse_special = true: what murmur_llm.cpp
does). GGUFs come from tools/models/fetch (<main>/.cache/models/<artifactId>/) or, byte-identical (size + sha256
checked against the catalog), from <main>/.cache/ml/**. Each distinct vocabulary (fingerprint = sha256 of the GGUF
tokenizer metadata) is tested once for (b). Counts are cached per (vocabulary, text) so reruns take seconds.
A prompt, profile, template or catalog change that breaks (a) or (b) makes this script exit 1.

  python3 -I native/llama/tools/tokenizer_check.py [--quick] [--jobs 6] [--tokenizer DIR] [--goldens DIR]

--quick: only the examples and edge cases for (b). --goldens DIR: also check WP-04's golden corpus files
(<DIR>/*.txt rendered prompts are recognised by their template markers and split at the cache prefix).
--assets DIR: count the :infer:llm instrumented-test prompts (<DIR>/{prefix,suffix}.*.<template>.txt, written by
make_prompt.py --assets) and compare with <DIR>/tokens.json (the device must tokenise them identically, D11);
--write-assets (re)writes tokens.json.
Run it through tools/heavy (it starts up to --jobs llama-tokenize processes).
"""
import argparse
import concurrent.futures as cf
import hashlib
import importlib.util
import json
import os
import pathlib
import struct
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[2]
CATALOG = REPO / "core/catalog/src/main/resources/catalog/catalog.json"
PROFILES_B = ("default.v1", "default.v4", "default.v4s", "finetuned.v1")
TEMPLATES = ("qwen3.5", "qwen3", "lfm3", "lfm2.5")


def main_checkout() -> pathlib.Path:
    try:
        common = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                capture_output=True, text=True, check=True).stdout.strip()
        return pathlib.Path(common).parent
    except Exception:
        return REPO


MAIN = main_checkout()


def load_render():
    spec = importlib.util.spec_from_file_location("murmur_render", REPO / "prompts" / "render.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# ------------------------------------------------------------------------------------------- GGUF vocabulary
def vocab_fingerprint(path: pathlib.Path) -> str:
    """sha256 over the tokenizer.* metadata of a GGUF (v2/v3), read without loading tensors."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        def rd(fmt):
            n = struct.calcsize(fmt)
            return struct.unpack("<" + fmt, f.read(n))
        def rstr():
            (n,) = rd("Q")
            return f.read(n)
        scalar = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f", 7: "?", 10: "Q", 11: "q", 12: "d"}
        def rval(t):
            if t == 8:
                return rstr()
            if t == 9:
                et, = rd("I"); n, = rd("Q")
                if et == 8:
                    return [rstr() for _ in range(n)]
                fmt = scalar[et]
                return f.read(struct.calcsize(fmt) * n)
            return f.read(struct.calcsize(scalar[t]))
        if f.read(4) != b"GGUF":
            raise SystemExit(f"not a GGUF: {path}")
        version, = rd("I")
        if version < 2:
            raise SystemExit(f"GGUF v{version} unsupported: {path}")
        _, n_kv = rd("QQ")
        found = 0
        for _ in range(n_kv):
            key = rstr().decode()
            t, = rd("I")
            v = rval(t)
            if key.startswith("tokenizer.ggml.") and key not in ("tokenizer.ggml.add_bos_token", "tokenizer.ggml.add_eos_token"):
                found += 1
                h.update(key.encode() + b"\0")
                for x in (v if isinstance(v, list) else [v]):
                    h.update(len(x).to_bytes(8, "little") + x)
    if not found:
        raise SystemExit(f"no tokenizer metadata in {path}")
    return h.hexdigest()[:16]


# ------------------------------------------------------------------------------------------- models
def sha256_file(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def locate(artifact: dict) -> pathlib.Path | None:
    f = artifact["files"][0]
    fetched = MAIN / ".cache/models" / artifact["id"] / artifact["layout"].get("model", f["name"])
    if fetched.is_file() and (fetched.parent / "fetch.ok").is_file():
        return fetched
    for d in (MAIN / ".cache/ml/llm", MAIN / ".cache/ml/prompt/llm"):
        for g in sorted(d.glob("*.gguf")) if d.is_dir() else []:
            if g.stat().st_size == f["bytes"] and sha256_file(g) == f["sha256"]:
                return g
    return None


# ------------------------------------------------------------------------------------------- counting
class Counter:
    def __init__(self, tokenizer_dir: pathlib.Path, jobs: int, cache_file: pathlib.Path):
        self.exe = tokenizer_dir / "llama-tokenize"
        if not self.exe.is_file():
            raise SystemExit(f"llama-tokenize not found in {tokenizer_dir}")
        self.env = dict(os.environ, LD_LIBRARY_PATH=str(tokenizer_dir))
        self.jobs = jobs
        self.cache_file = cache_file
        self.cache = json.loads(cache_file.read_text()) if cache_file.is_file() else {}
        self.tag = tokenizer_dir.name
        self.tmp = tempfile.TemporaryDirectory(prefix="tokcheck-")

    def _key(self, fp, text):
        return f"{self.tag}:{fp}:{hashlib.sha256(text.encode('utf-8')).hexdigest()}"

    def _run(self, model: pathlib.Path, text: str) -> int:
        tf = pathlib.Path(self.tmp.name) / (hashlib.sha256(text.encode()).hexdigest() + ".txt")
        tf.write_bytes(text.encode("utf-8"))
        r = subprocess.run([str(self.exe), "-m", str(model), "-f", str(tf), "--show-count", "--no-bos", "--ids",
                            "--log-disable"], capture_output=True, text=True, env=self.env)
        for line in (r.stdout + r.stderr).splitlines():
            if line.startswith("Total number of tokens:"):
                return int(line.split(":")[1])
        raise SystemExit(f"llama-tokenize failed ({r.returncode}) for {model.name}")

    def count(self, model: pathlib.Path, fp: str, texts: list[str]) -> list[int]:
        todo = sorted({t for t in texts if self._key(fp, t) not in self.cache})
        if todo:
            with cf.ThreadPoolExecutor(self.jobs) as ex:
                for t, n in zip(todo, ex.map(lambda t: self._run(model, t), todo)):
                    self.cache[self._key(fp, t)] = n
            self.cache_file.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.cache_file.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.cache))
            tmp.replace(self.cache_file)
        return [self.cache[self._key(fp, t)] for t in texts]


# ------------------------------------------------------------------------------------------- goldens
def edge_cases(base_ctx: dict) -> list[tuple[str, dict, str, str | None]]:
    """(id, ctx, transcript, preferences): the WP-04 must-have cases that the eval corpus does not cover."""
    ctx = json.loads(json.dumps(base_ctx))
    lt = json.loads(json.dumps(base_ctx))
    lt.setdefault("screen", {}).setdefault("lines", []).append({"text": "if a < b && c > d then <tag>", "conf": 0.97,
                                                                "box": [10, 300, 900, 340]})
    empty = json.loads(json.dumps(base_ctx)); empty["screen"] = {"size": [1080, 2520], "lines": []}
    return [
        ("edge-emoji", ctx, "спасибо огромное 🙏 увидимся завтра 👍 ok", None),
        ("edge-rtl", ctx, "ok ‮evil‬ and ⁧isolate⁩ שלום", None),
        ("edge-lt-gt", lt, "проверь условие a меньше b", None),
        ("edge-empty-screen", empty, "напомни купить молоко", None),
        ("edge-prefs-short", ctx, "давай созвонимся в пятницу", "Пиши «ё». Без смайликов."),
        ("edge-prefs-1000", ctx, "давай созвонимся в пятницу", ("Предпочтения: коротко и по делу. " * 40)[:1000]),
        ("edge-prefs-1500-crlf", ctx, "давай созвонимся в пятницу",
         ("Line one\r\nline two\x07 with control\r\n" * 60)[:1500]),
        ("edge-long-transcript", ctx, " ".join(["это очень длинная диктовка без пауз"] * 120), None),
    ]


def golden_cases(render, quick: bool):
    rows = []
    srcs = [REPO / "prompts/eval/smoke_cases.v2.jsonl"]
    if not quick:
        srcs = [REPO / "prompts/eval/sets/dev.v2.jsonl", REPO / "prompts/eval/sets/test.v2.jsonl"] + srcs
    for src in srcs:
        for line in src.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                rows.append((r["id"], r["ctx"], r["transcript"], None))
    examples = sorted((REPO / "prompts/examples").glob("ctxv2_*.input.json"))
    base = None
    for ex in examples:
        g = json.loads(ex.read_text(encoding="utf-8"))
        base = base or g["context"]
        rows.append((ex.name.split(".")[0], g["context"], g["transcript"], g.get("preferences")))
    rows += edge_cases(base)
    return rows


def split_golden_file(text: str, prefixes: list[str]) -> tuple[str, str] | None:
    for p in sorted(prefixes, key=len, reverse=True):
        if text.startswith(p):
            return p, text[len(p):]
    return None


# ------------------------------------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tokenizer", default=str(MAIN / ".cache/ml/llm/bin/llama-b12503"))
    ap.add_argument("--jobs", type=int, default=6)
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--goldens", help="WP-04 golden corpus dir (core/prompt/src/test/resources/goldens)")
    ap.add_argument("--cache", default=str(REPO / ".cache/tokcheck/counts.json"))
    ap.add_argument("--assets", help="infer/llm/src/androidTest/assets/prompts")
    ap.add_argument("--write-assets", action="store_true")
    a = ap.parse_args()

    render = load_render()
    cat = json.loads(CATALOG.read_text(encoding="utf-8"))
    by_id = {m["id"]: m for m in cat["models"]}
    counter = Counter(pathlib.Path(a.tokenizer), a.jobs, pathlib.Path(a.cache))
    failures = []

    # ---------------------------------------------------------------- (a) prefix tokens per catalog LLM
    print("(a) render.cache_prefix(profile, template) tokens vs catalog llm.prefixTokens")
    print(f"{'model':30} {'profile':13} {'template':8} {'gguf':36} {'vocab':16} {'tokens':>6} {'catalog':>7}  result")
    vocabs = {}       # (template, vocabulary fingerprint) -> gguf path
    for m in cat["models"]:
        if m["kind"] != "llm":
            continue
        llm = m["llm"]
        base = m if m["artifacts"] else by_id.get(m["id"].removeprefix("murmur-cleanup-"))
        art = base["artifacts"][0] if base and base["artifacts"] else None
        gguf = locate(art) if art else None
        row = f"{m['id']:30} {llm['promptProfile']:13} {llm['template']:8}"
        if gguf is None:
            failures.append(f"(a) {m['id']}: GGUF of {art['id'] if art else '?'} not available (tools/models/fetch)")
            print(f"{row} {'(missing: tools/models/fetch ' + (art['id'] if art else '?') + ')':36} {'':16} {'':>6} {llm['prefixTokens']:>7}  FAIL")
            continue
        fp = vocab_fingerprint(gguf)
        vocabs.setdefault((llm["template"], fp), gguf)
        n, = counter.count(gguf, fp, [render.cache_prefix(llm["promptProfile"], llm["template"])])
        ok = n == llm["prefixTokens"]
        if not ok:
            failures.append(f"(a) {m['id']}: {n} tokens, catalog says {llm['prefixTokens']}")
        src = gguf.parent.name + "/" + gguf.name if gguf.parent.parent.name == "models" else gguf.name
        print(f"{row} {src[:36]:36} {fp:16} {n:>6} {llm['prefixTokens']:>7}  {'ok' if ok else 'FAIL'}")

    # ---------------------------------------------------------------- (b) additivity per vocabulary
    cases = golden_cases(render, a.quick)
    print()
    print(f"(b) tokens(prefix) + tokens(suffix) == tokens(full): {len(cases)} cases x profiles {', '.join(PROFILES_B)}")
    print(f"{'template':8} {'vocab':16} {'gguf':36} {'checks':>6} {'additive':>8} {'suffix tok min..max':>20}  result")
    templates_seen = set()
    split_c = {}
    for (tpl, fp), gguf in sorted(vocabs.items(), key=lambda kv: (TEMPLATES.index(kv[0][0]), kv[0][1])):
        templates_seen.add(tpl)
        triples = []      # (id, prefix, suffix, full)
        for prof in PROFILES_B:
            for cid, ctx, tr, prefs in cases:
                full = render.render_prompt(prof, ctx, tr, tpl, prefs)
                prefix = render.cache_prefix(prof, tpl, prefs)
                if not full.startswith(prefix):
                    failures.append(f"(b) {tpl} {prof} {cid}: rendered prompt does not start with cache_prefix")
                    continue
                triples.append((f"{prof}/{cid}", prefix, full[len(prefix):], full))
        texts = [t for tr in triples for t in tr[1:]]
        counts = counter.count(gguf, fp, texts)
        # (c) split after "<transcript>\n" of the real user turn
        marker = "<transcript>\n"
        early = [(full[: full.rindex(marker) + len(marker)], full[full.rindex(marker) + len(marker):]) for _, _, _, full in triples]
        ec = counter.count(gguf, fp, [t for pair in early for t in pair])
        c_bad = sum(1 for i, (_, _, _, _) in enumerate(triples) if ec[2 * i] + ec[2 * i + 1] != counts[3 * i + 2])
        split_c[(tpl, fp)] = (len(triples), len(triples) - c_bad)
        bad, sfx = [], []
        for i, (cid, *_rest) in enumerate(triples):
            p, s, f = counts[3 * i: 3 * i + 3]
            sfx.append(s)
            if p + s != f:
                bad.append(f"{cid}: {p}+{s}!={f}")
        failures += [f"(b) {tpl} {fp}: {b}" for b in bad]
        print(f"{tpl:8} {fp:16} {gguf.name[:36]:36} {len(triples):>6} {len(triples) - len(bad):>8} "
              f"{(str(min(sfx)) + '..' + str(max(sfx))) if sfx else '-':>20}  {'ok' if not bad else 'FAIL'}")
        for b in bad[:5]:
            print(f"    {b}")
    print()
    print("(c) optional early-context split after \"<transcript>\\n\" (informational; the flag stays off unless all hold)")
    for (tpl, fp), (n, good) in split_c.items():
        print(f"{tpl:8} {fp:16} {n:>6} checks, {good:>6} additive  {'holds' if n == good else 'DOES NOT HOLD'}")
    for t in TEMPLATES:
        if t not in templates_seen:
            failures.append(f"(b) no GGUF vocabulary for template {t}")

    # ---------------------------------------------------------------- optional: WP-04's committed goldens
    if a.goldens:
        gdir = pathlib.Path(a.goldens)
        files = sorted(gdir.glob("*.txt")) if gdir.is_dir() else []
        prefixes = {t: [render.cache_prefix(p, t) for p in PROFILES_B] for t in TEMPLATES}
        n_ok = n_all = 0
        for (tpl, fp), gguf in vocabs.items():
            sel = []
            for f in files:
                text = f.read_text(encoding="utf-8")
                sp = split_golden_file(text, prefixes[tpl])
                if sp:
                    sel.append((f.name, sp[0], sp[1], text))
            counts = counter.count(gguf, fp, [t for s in sel for t in s[1:]])
            for i, (name, *_r) in enumerate(sel):
                p, s, f = counts[3 * i: 3 * i + 3]
                n_all += 1
                if p + s == f:
                    n_ok += 1
                else:
                    failures.append(f"(goldens) {tpl} {name}: {p}+{s}!={f}")
        print(f"\nWP-04 goldens in {gdir}: {len(files)} files, {n_all} (file, vocab) checks, {n_ok} additive")
        if not files:
            failures.append(f"(goldens) no *.txt in {gdir}")

    # ---------------------------------------------------------------- instrumented-test prompt assets
    if a.assets:
        adir = pathlib.Path(a.assets)
        by_tpl = {}
        for (tpl, fp), gguf in vocabs.items():
            by_tpl.setdefault(tpl, (fp, gguf))
        counts = {}
        for f in sorted(adir.glob("*.txt")):
            tpl = next((t for t in sorted(TEMPLATES, key=len, reverse=True) if f.name.endswith(f".{t}.txt")), None)
            if tpl is None or tpl not in by_tpl:
                failures.append(f"(assets) {f.name}: unknown template or no vocabulary")
                continue
            fp, gguf = by_tpl[tpl]
            counts[f.name], = counter.count(gguf, fp, [f.read_text(encoding="utf-8")])
        tj = adir / "tokens.json"
        text = json.dumps(counts, indent=1, sort_keys=True) + "\n"
        if a.write_assets:
            tj.write_text(text, encoding="utf-8")
            print(f"\nwrote {tj} ({len(counts)} files)")
        elif not tj.is_file() or json.loads(tj.read_text(encoding="utf-8")) != counts:
            failures.append(f"(assets) {tj} differs from the host counts (rerun with --write-assets after checking)")
        else:
            print(f"\n{tj}: {len(counts)} files match the host counts")
        for name, n in counts.items():
            print(f"    {name:40} {n:>6}")

    print()
    if failures:
        print(f"FAILED ({len(failures)}):")
        for f in failures[:40]:
            print("  " + f)
        return 1
    print("OK: every catalog prefix count matches and every golden is additive")
    return 0


if __name__ == "__main__":
    sys.exit(main())
