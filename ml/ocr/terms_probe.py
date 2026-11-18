"""How well does OCR read the probe's hard terms on rendered screens? (task step 5)

    python -m ml.ocr.terms_probe specs  OUT_SPECS.jsonl [--reps 2]   # term-carrying render specs
    python -m ml.screens.build_ctx --batch OUT_SPECS.jsonl OUT_CTX.jsonl --workers 4 --threads 2
    python -m ml.ocr.terms_probe report OUT_SPECS.jsonl OUT_CTX.jsonl  # -> .cache/ml/ocr/measure/terms.json,
                                                                      #    ml/ocr/ocr_hard_terms.json
Each term (written_forms[0] of ml/roundtrip/probe/terms.jsonl, read-only) is placed in a carrier message,
4-5 terms per screen, `reps` times across templates, devices, themes and font scales.
"""
from __future__ import annotations

import argparse
import json
import random
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

from . import metrics as M
from .config import REPO

TERMS = REPO / "ml" / "roundtrip" / "probe" / "terms.jsonl"
OUT = REPO / ".cache" / "ml" / "ocr" / "measure"
CARRIERS = {
    "ru": ["Посмотри {t}, пожалуйста", "Обнови {t} до вечера", "Опять проблема с {t} на проде", "Я поставил {t}, всё ок",
           "Кто знает, как настроить {t}?", "В пятницу обсудим {t} на созвоне"],
    "en": ["Can you check {t} today?", "We moved to {t} last week", "{t} is failing again", "Let's try {t} first",
           "Is {t} worth it?", "Ping me when {t} is done"],
}
TEMPLATES = [("messenger_a", "messaging"), ("messenger_b", "messaging"), ("work_chat", "work_chat"),
             ("email_thread", "email"), ("issue_tracker", "work_chat"), ("browser_pr", "code"), ("ai_chat", "default"),
             ("notes", "notes")]
FONT_SCALES = [0.85, 0.9, 1.0, 1.0, 1.1, 1.15, 1.3]


def load_terms() -> list[str]:
    out = []
    for line in TERMS.read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line)
            out.append((d.get("written_forms") or [d["term"]])[0])
    return list(dict.fromkeys(out))


def make_specs(reps: int, seed: int = 7) -> list[dict]:
    from ml.screens.specs import from_teacher
    terms = load_terms()
    specs = []
    for rep in range(reps):
        r = random.Random(f"terms|{seed}|{rep}")
        order = terms[:]
        r.shuffle(order)
        i = 0
        while i < len(order):
            k = r.randint(4, 5)
            chunk = order[i:i + k]
            i += k
            lang = r.choice(["ru", "ru", "en"])
            msgs = []
            for j, t in enumerate(chunk):
                msgs.append({"author": r.choice(["Тимур Галиев", "Priya Raghunathan", "Марина Ефимова", "Nikolai Petrov"]),
                             "is_me": r.random() < 0.4, "text": r.choice(CARRIERS[lang]).format(t=t)})
            tpl, cat = r.choice(TEMPLATES)
            n = len(specs)
            spec = from_teacher({"title": r.choice(["Бэкенд", "infra", "Тимур Галиев", "Release 3.2"]), "messages": msgs,
                                 "snippets": [], "before_cursor": ""},
                                {"package": "x", "label": "X", "category": cat}, "message", lang, seed=50_000 + n,
                                template=tpl, font_scale=r.choice(FONT_SCALES), keyboard=r.random() < 0.5)
            spec["bubble"] = False
            spec["filler"] = False
            spec["overlay"] = "none"
            spec["id"] = f"terms-{rep}-{n:04d}"
            spec["terms"] = chunk
            specs.append(spec)
    return specs


def _fold_diacritics(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s) if not unicodedata.combining(c))


TWINS = set("aceopxyABCEHKMOPTXkmnurb") | set("асеорхуАВСЕНКМОРТХкмпигьт")


def classify(term: str, got: str) -> str:
    """Coarse error mode of a misread term (got = best-matching OCR substring)."""
    if not got:
        return "missing"
    if got.replace(" ", "") == term.replace(" ", ""):
        return "space"
    if got.casefold() == term.casefold():
        return "case"
    if _fold_diacritics(got) == _fold_diacritics(term) or "ß" in term:
        return "diacritics"
    _, ed = M.semiglobal(term, got)
    kinds = set()
    for a, b in ed:
        if a and b and (a in TWINS or b in TWINS) and (M.is_mixed_script(a + b)):
            kinds.add("homoglyph")
        elif {a, b} & set("0Oo1lI|") and a and b:
            kinds.add("digit_letter")
        elif (a and not a.isalnum()) or (b and not b.isalnum()):
            kinds.add("punct_symbol")
        else:
            kinds.add("letter")
    if len(kinds) == 1:
        return kinds.pop()
    return "mixed:" + "+".join(sorted(kinds))


def report(specs_path: str, ctx_path: str) -> dict:
    specs = {json.loads(x)["id"]: json.loads(x) for x in Path(specs_path).read_text(encoding="utf-8").splitlines() if x.strip()}
    per_term = defaultdict(list)
    screens = []
    for line in Path(ctx_path).read_text(encoding="utf-8").splitlines():
        d = json.loads(line)
        if "error" in d:
            continue
        spec = specs[d["id"]]
        lines = (d["ctx"].get("screen") or {}).get("lines", [])
        screens.append(M.compare(d["visible"], lines, d["exclude_rects"]))
        vis_items = [v["text"] for v in d["visible"] if v["role"] not in M.IGNORED_ROLES]
        vis_joined = " ".join(vis_items)
        ocr_texts = [ln["text"] for ln in lines]
        for t in spec["terms"]:
            on_one_line = any(t in v for v in vis_items)
            if t not in vis_joined and not on_one_line:
                # scrolled off / wrapped mid-term in the render itself
                per_term[t].append({"visible": False, "wrapped": any(t.split()[0] in v for v in vis_items) if t.split() else False})
                continue
            exact = any(t in o for o in ocr_texts)
            best, bd = "", len(t)
            for o in ocr_texts:
                dist, _ = M.semiglobal(t, o)
                if dist < bd:
                    bd, best = dist, o
            got = ""
            if best:
                # recover the matched substring for classification: shortest window with the best distance
                n = len(t)
                cands = [best[i:i + L] for L in range(max(1, n - 3), n + 4) for i in range(0, max(1, len(best) - L + 1))]
                got = min(cands, key=lambda s: (M.lev(t, s), abs(len(s) - n))) if cands else ""
            per_term[t].append({"visible": True, "single_line": on_one_line, "exact": exact, "dist": bd,
                                "got": "" if exact else got, "mode": None if exact else classify(t, got if bd < len(t) else "")})
    agg = M.aggregate(screens)
    obs = [o for v in per_term.values() for o in v if o["visible"]]
    single = [o for o in obs if o["single_line"]]
    modes = Counter(o["mode"] for o in obs if not o["exact"])
    hard = {}
    for t, v in per_term.items():
        seen = [o for o in v if o["visible"]]
        fails = [o for o in seen if not o["exact"]]
        if fails:
            hard[t] = {"seen": len(seen), "failed": len(fails), "ocr": sorted({o["got"] for o in fails}),
                       "modes": sorted({o["mode"] for o in fails})}
    terms_seen = [t for t, v in per_term.items() if any(o["visible"] for o in v)]
    rep = {
        "screens": len(screens), "terms_total": len(per_term), "terms_seen": len(terms_seen),
        "observations": len(obs), "not_visible_observations": sum(1 for v in per_term.values() for o in v if not o["visible"]),
        "exact_recall_obs": sum(o["exact"] for o in obs) / max(1, len(obs)),
        "exact_recall_single_line_obs": sum(o["exact"] for o in single) / max(1, len(single)),
        "wrapped_obs": len(obs) - len(single),
        "terms_always_exact": sum(1 for t in terms_seen if all(o["exact"] for o in per_term[t] if o["visible"])),
        "terms_never_exact": sum(1 for t in terms_seen if not any(o["exact"] for o in per_term[t] if o["visible"])),
        "error_modes": dict(modes.most_common()),
        "screen_profile": {k: agg[k] for k in ("cer", "content_cer", "item_recall_exact", "missed_share",
                                               "non_content_line_share", "mixed_script_token_share", "line_roles",
                                               "cer_by_role", "top_confusions")},
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "terms.json").write_text(json.dumps(rep, ensure_ascii=False, indent=1))
    hard_doc = {"source": "ml/roundtrip/probe/terms.jsonl", "method": "ml/ocr/terms_probe.py (docs/ml/OCR_CONTEXT.md)",
                "note": "terms OCR did not read exactly in at least one rendering; 'ocr' = what it read instead",
                "terms": dict(sorted(hard.items(), key=lambda kv: (-kv[1]["failed"] / kv[1]["seen"], kv[0])))}
    (REPO / "ml" / "ocr" / "ocr_hard_terms.json").write_text(json.dumps(hard_doc, ensure_ascii=False, indent=1) + "\n",
                                                             encoding="utf-8")
    return rep


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m ml.ocr.terms_probe")
    ap.add_argument("what", choices=["specs", "report"])
    ap.add_argument("a")
    ap.add_argument("b", nargs="?")
    ap.add_argument("--reps", type=int, default=2)
    x = ap.parse_args(argv)
    if x.what == "specs":
        specs = make_specs(x.reps)
        Path(x.a).write_text("".join(json.dumps(s, ensure_ascii=False) + "\n" for s in specs), encoding="utf-8")
        print(len(specs), "specs")
    else:
        rep = report(x.a, x.b)
        print(json.dumps({k: v for k, v in rep.items() if k != "screen_profile"}, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
