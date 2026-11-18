"""Convert public datasets into the shared data contract, with strict filters.

  python -m ml.data.public.convert --out .cache/ml/data/public_out [--only sotto,handy]

Writes <out>/<source>.jsonl (kept records), <out>/<source>.rejects.jsonl
(sampled rejects with reasons, for review) and <out>/stats.json.
GenSEC and the Russian enrichment set also feed confusion tables for the
simulated ASR channel: <out>/../derived/confusions_{en,ru}.json.
"""
from __future__ import annotations

import argparse
import collections
import functools
import json
import re
import sys
from pathlib import Path

from ml.common import text as T
from ml.common.schema import validate_record, write_jsonl
from ml.common.util import bucket_split, h, unit
from ml.data.public.filters import category_of, pair_reasons
from ml.data.public.sources import CACHE, SOURCES, local

EXCLUDED_AAWAAZ = {"meeting_notes", "shopping_lists", "recipe_cooking"}
AAWAAZ_CTX = {"email_professional": ("email", "email_body", "Gmail"), "technical_code": ("work_chat", "message", "Slack")}


def minimal_ctx(lang: str, category: str = "default", kind: str = "generic", label: str = "",
                privacy: str = "none") -> dict:
    return {"v": 1, "app": {"package": "", "label": label, "category": category}, "field": {"kind": kind},
            "language": {"asr": "ru" if lang == "ru" else "en"}, "privacy": {"level": privacy}}


# ------------------------------------------------------------------ loaders: yield dict(raw_id, inp, out, ...)
def _pq(path, cols=None):
    import pyarrow.parquet as pq
    return pq.read_table(path, columns=cols).to_pylist()


def load_sotto():
    for split in ("train", "val"):
        for i, r in enumerate(_pq(local("sotto", f"{split}.parquet"))):
            yield dict(raw_id=f"{split}:{i}", inp=r["input"] or "", out=r["output"] or "", lang="en")


def load_aawaaz():
    for f in sorted((CACHE / "aawaaz").glob("*.jsonl")):
        cat = f.stem
        for i, line in enumerate(open(f, encoding="utf-8")):
            r = json.loads(line)
            app = AAWAAZ_CTX.get(cat)
            yield dict(raw_id=f"{cat}:{i}", inp=r["input"], out=r["output"], lang="en", subset=cat,
                       excluded="excluded_category" if cat in EXCLUDED_AAWAAZ else None,
                       ctx=minimal_ctx("en", app[0], app[1], app[2], "app_only") if app else None)


def _chat_file(path, tag):
    for i, line in enumerate(open(path, encoding="utf-8")):
        m = json.loads(line)["messages"]
        u = next(x["content"] for x in m if x["role"] == "user")
        a = next(x["content"] for x in m if x["role"] == "assistant")
        yield i, u, a


def load_handy():
    for i, u, a in _chat_file(local("handy", "handy_editor.jsonl"), "train"):
        yield dict(raw_id=f"train:{i}", inp=u, out=a, lang="en")
    for i, u, a in _chat_file(local("handy", "handy_eval.jsonl"), "eval"):
        yield dict(raw_id=f"eval:{i}", inp=u, out=a, lang="en", force_split="test")


def load_hasan():
    for s, sp in (("train", None), ("validation", "dev"), ("test", "test")):
        for i, u, a in _chat_file(local("hasan", f"{s}.jsonl"), s):
            yield dict(raw_id=f"{s}:{i}", inp=u, out=a, lang="en", force_split=sp)


def load_speako():
    for line in open(local("speako", "examples.jsonl"), encoding="utf-8"):
        r = json.loads(line)
        yield dict(raw_id=r["id"], inp=r["input"], out=r["expected"], lang="en", subset=r.get("category"),
                   force_split="test")


def load_gpb11():
    for i, r in enumerate(_pq(local("gpb11", "train.parquet"))):
        yield dict(raw_id=str(i), inp=r["input"] or "", out=r["output"] or "", lang="ru", excluded="no_licence")


LOADERS = {"sotto": load_sotto, "aawaaz": load_aawaaz, "handy": load_handy, "hasan": load_hasan,
           "speako": load_speako, "gpb11": load_gpb11}


# ------------------------------------------------------------------ generic pair pipeline
def skeleton(target: str) -> str:
    """Template key: names (capitalised non-initial words) and numbers masked.
    Rows sharing a skeleton are variants of one generator template."""
    ws = T.words(target)
    out = []
    for i, w in enumerate(ws):
        if re.search(r"\d", w) or T._en_value(w.lower()) or w.lower() in T.RU_NUM:
            out.append("#")
        elif i > 0 and w[:1].isupper():
            out.append("N")
        else:
            out.append(T.norm_word(w))
    return " ".join(out)


def convert_pairs(src: str, rows, reject_sample: int = 300, template_cap: int = 25):
    info = SOURCES[src]
    stats = collections.Counter()
    first_reason = collections.Counter()
    by_input: dict[str, list] = collections.defaultdict(list)
    rows = list(rows)
    stats["rows"] = len(rows)
    for r in rows:
        by_input[" ".join(T.norm_words(r["inp"]))].append(r)
    kept, rejects = [], []
    skel_count: collections.Counter = collections.Counter()
    for key, group in by_input.items():
        outs = {" ".join(T.norm_words(g["out"])) + "|" + g["out"].strip() for g in group}
        for gi, r in enumerate(group):
            reasons = []
            if r.get("excluded"):
                reasons.append(r["excluded"])
            if len(outs) > 1:
                reasons.append("conflicting_targets")
            elif gi > 0:
                reasons.append("duplicate_input")
            reasons += pair_reasons(r["inp"], r["out"], r["lang"])
            if not reasons:
                sk = skeleton(r["out"])
                skel_count[sk] += 1
                if skel_count[sk] > template_cap:
                    reasons.append("template_cap")
            for x in set(reasons):
                stats["reason:" + x] += 1
            if reasons:
                first_reason[reasons[0]] += 1
                if unit("rej", src, r["raw_id"]) < reject_sample / max(1, len(rows)):
                    rejects.append({"source": src, "raw_id": r["raw_id"], "reasons": reasons,
                                    "input": r["inp"][:600], "output": r["out"][:600]})
                continue
            lang = r["lang"]
            out = r["out"].strip()
            cat = category_of(r["inp"], out, lang)
            ctx = r.get("ctx") or minimal_ctx(lang)
            split = r.get("force_split") or bucket_split("pub:" + " ".join(T.norm_words(out)), dev=0.02, test=0.02)
            rec = {"id": f"pub-{src}-{h(src, r['raw_id'])[:12]}", "lang": lang, "category": cat,
                   "source": f"public:{info['repo']}", "split": split, "ctx": ctx,
                   "transcript": r["inp"].strip(), "target": out,
                   "meta": {"licence": info["licence"], "licence_class": info["licence_class"],
                            "raw_id": r["raw_id"], "subset": r.get("subset"), "filters": "public.v1"}}
            errs = validate_record(rec, check_render=False)
            if errs:
                stats["reason:schema"] += 1
                first_reason["schema"] += 1
                continue
            kept.append(rec)
    stats["kept"] = len(kept)
    for rec in kept:
        stats["kept_cat:" + rec["category"]] += 1
        stats["kept_split:" + rec["split"]] += 1
    return kept, rejects, stats, first_reason


# ------------------------------------------------------------------ Russian enrichment (real GigaAM errors)
@functools.lru_cache(maxsize=1)
def _cyr_product_forms() -> frozenset:
    from ml.data.synth.lexicon import TECH
    forms = set()
    for k, vs in TECH.items():
        if k[:1].isupper():
            for v in vs:
                if " " not in v:
                    stem = T.norm_word(v)
                    forms |= {stem, stem + "е", stem + "а", stem + "у", stem + "ом", stem + "ы", stem + "ах"}
    return frozenset(forms)

def convert_enrich(reject_sample: int = 300, rulib_keep: float = 0.25, noop_cap: float = 0.3):
    info = SOURCES["enrich"]
    rows = _pq(local("enrich", "train.parquet"),
               ["source_id", "domain", "tier", "clean_text", "ref", "gigaam_text", "duration_s"])
    stats = collections.Counter(rows=len(rows))
    first_reason = collections.Counter()
    kept, rejects, noops = [], [], []
    conf = collections.Counter()
    for r in rows:
        reasons = []
        clean = (r["clean_text"] or "").strip()
        giga = (r["gigaam_text"] or "").strip() if isinstance(r["gigaam_text"], str) else ""
        ref = (r["ref"] or "").strip()
        if r["tier"] == "C":
            reasons.append("tier_c_llm_arbitrated")
        if not giga:
            reasons.append("no_gigaam_hypothesis")
        if r["domain"] == "rulibrispeech" and unit("rulib", r["source_id"]) >= rulib_keep:
            reasons.append("rulibrispeech_subsampled")
        tc, tr, tg = T.nwer_tokens(clean, "ru"), T.nwer_tokens(ref, "ru"), T.nwer_tokens(giga, "ru")
        if len(tc) < 2:
            reasons.append("too_short")
        if tc != tr:
            reasons.append("ref_disagrees_with_clean")
        if not reasons:
            w = T.wer(tc, tg)
            if w > 0.35:
                reasons.append("asr_too_noisy")
            # recoverable from text alone: substitutions that sound alike, removed fillers.
            # A word the ASR dropped or invented cannot be fixed without audio -> drop.
            for op, i, j in T.align(tc, tg):
                if op == "del":
                    reasons.append("asr_deleted_word")
                    break
                if op == "ins" and tg[j] not in T.HESITATION:
                    reasons.append("asr_inserted_word")
                    break
                if op == "sub" and T.phon_sim(tc[i], tg[j]) < 0.5:
                    reasons.append("unrecoverable_substitution")
                    break
            if T.spelled_numbers_over(clean, 10, "ru"):
                reasons.append("number_words_in_target")
            if not re.match(r"[«\"(]?[А-ЯЁA-Z0-9]", clean) or not re.search(r"[.!?…»\"]$", clean):
                reasons.append("target_unformatted")
            if T.guess_lang(clean) != "ru":
                reasons.append("not_russian")
            # rule 7 of the prompt: product names in Latin spelling ("Telegram", not "телеграм")
            if set(T.norm_words(clean)) & _cyr_product_forms():
                reasons.append("product_name_in_cyrillic")
        for x in set(reasons):
            stats["reason:" + x] += 1
        if reasons:
            first_reason[reasons[0]] += 1
            if unit("rej", r["source_id"]) < reject_sample / len(rows):
                rejects.append({"source": "enrich", "raw_id": r["source_id"], "reasons": reasons, "ref": ref,
                                "gigaam": giga, "clean": clean})
            continue
        # confusion mining (only on accepted, 3-way-confirmed rows)
        for op, i, j in T.align(tc, tg):
            if op == "sub":
                conf[(tc[i], tg[j])] += 1
        lower_style = unit("style", r["source_id"]) < 0.5
        transcript = giga
        if lower_style:
            transcript = " ".join(re.findall(r"[\w-]+", giga.lower()))
        cat = "no_op" if transcript == clean else "asr_correction"
        rec = {"id": f"pub-enrich-{h('enrich', r['source_id'])[:12]}", "lang": "ru", "category": cat,
               "source": f"public:{info['repo']}", "split": bucket_split("enrich:" + r["source_id"], 0.02, 0.02),
               "ctx": minimal_ctx("ru"), "transcript": transcript, "target": clean,
               "meta": {"licence": info["licence"], "licence_class": info["licence_class"], "raw_id": r["source_id"],
                        "subset": r["domain"], "tier": r["tier"], "asr": "gigaam(v2 per card)",
                        "input_style": "lower_nopunct" if lower_style else "asr_native", "filters": "enrich.v1"}}
        (noops if cat == "no_op" else kept).append(rec)
    cap = int(noop_cap * len(kept) / (1 - noop_cap))
    noops.sort(key=lambda x: x["id"])
    stats["reason:noop_cap"] = max(0, len(noops) - cap)
    first_reason["noop_cap"] = max(0, len(noops) - cap)
    kept += noops[:cap]
    stats["kept"] = len(kept)
    for rec in kept:
        stats["kept_cat:" + rec["category"]] += 1
        stats["kept_split:" + rec["split"]] += 1
        stats["kept_domain:" + rec["meta"]["subset"]] += 1
    return kept, rejects, stats, first_reason, conf


# ------------------------------------------------------------------ GenSEC: English confusion mining only
def mine_gensec(max_rows: int | None = None):
    rows = _pq(local("gensec", "train.parquet"), ["hypothesis", "transcription", "source"])
    stats = collections.Counter(rows=len(rows))
    conf = collections.Counter()
    for r in rows[:max_rows]:
        ref = T.norm_words(r["transcription"] or "")
        hyp = T.norm_words((r["hypothesis"] or [""])[0])
        if not ref or ref == hyp:
            stats["identical_top1"] += 1
            continue
        if r["source"] in ("train_swbd", "train_coraal", "train_chime4"):
            stats["skipped_noisy_domain"] += 1
            continue
        if T.wer(ref, hyp) > 0.3:
            stats["too_noisy"] += 1
            continue
        stats["mined_rows"] += 1
        for op, i, j in T.align(ref, hyp):
            if op == "sub" and T.phon_sim(ref[i], hyp[j]) >= 0.5:
                conf[(ref[i], hyp[j])] += 1
    stats["kept"] = 0
    stats["reason:targets_lowercase_unpunctuated(all rows)"] = len(rows)
    return stats, conf


# ------------------------------------------------------------------ KU Leuven: eval-only (non-commercial)
_GIGA_PUNCT = {"<COMMA>": ",", "<PERIOD>": ".", "<QUESTIONMARK>": "?", "<EXCLAMATIONPOINT>": "!"}


def convert_kul(reject_sample: int = 100):
    info = SOURCES["kul"]
    rows = _pq(local("kul", "train_s.parquet"),
               ["id", "text", "hypothesis", "title", "description", "tags", "corrections", "source_model"])
    stats = collections.Counter(rows=len(rows))
    first_reason = collections.Counter()
    kept, rejects = [], []
    for r in rows:
        reasons = []
        try:
            corr = json.loads(r["corrections"] or "[]")
        except json.JSONDecodeError:
            corr = []
        hits = [c for c in corr if c.get("context_hits")]
        if not hits:
            reasons.append("no_context_hit")
        t = r["text"] or ""
        for k, v in _GIGA_PUNCT.items():
            t = t.replace(" " + k, v).replace(k, v)
        t = re.sub(r"<[A-Z]+>", "", t).strip().lower()
        t = re.sub(r"(^|[.!?]\s+)([a-z])", lambda m: m.group(1) + m.group(2).upper(), t)
        if len(T.words(t)) < 4:
            reasons.append("too_short")
        for x in set(reasons):
            stats["reason:" + x] += 1
        if reasons:
            first_reason[reasons[0]] += 1
            continue
        tags = [x.strip('"') for x in (r["tags"] or [])][:30]
        ctx = {"v": 1, "app": {"package": "", "label": "YouTube", "category": "default"},
               "field": {"kind": "generic"},
               "conversation": {"kind": "page", "title": (r["title"] or "")[:120]},
               "terms": [{"text": x[:60], "score": 1.0, "src": "a11y"} for x in tags],
               "screen_snippets": [(r["description"] or "")[:300]] if r["description"] else [],
               "language": {"asr": "en"}, "privacy": {"level": "full"}}
        kept.append({"id": f"pub-kul-{h('kul', r['id'])[:12]}", "lang": "en", "category": "context_asr_correction",
                     "source": f"public:{info['repo']}", "split": "test", "ctx": ctx,
                     "transcript": r["hypothesis"] or "", "target": t,
                     "meta": {"licence": info["licence"], "licence_class": info["licence_class"], "raw_id": r["id"],
                              "asr": r["source_model"], "eval_only": True, "target_normalized": True,
                              "note": "reference casing lost upstream; score with N-WER / entity recall only"}})
    stats["kept"] = len(kept)
    stats["kept_split:test"] = len(kept)
    return kept, rejects, stats, first_reason


def _save_conf(conf: collections.Counter, path: Path, min_count: int = 2, top: int = 5000):
    path.parent.mkdir(parents=True, exist_ok=True)
    items = [{"ref": a, "hyp": b, "n": n} for (a, b), n in conf.most_common() if n >= min_count][:top]
    path.write_text(json.dumps(items, ensure_ascii=False, indent=0), encoding="utf-8")
    return len(items)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(CACHE / "public_out"))
    ap.add_argument("--only", default="sotto,aawaaz,handy,hasan,speako,gpb11,enrich,kul,gensec")
    a = ap.parse_args(argv)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    derived = out.parent / "derived"
    all_stats = {}
    statf = out / "stats.json"
    if statf.exists():
        all_stats = json.loads(statf.read_text())
    for src in a.only.split(","):
        print(f"[convert] {src} ...", file=sys.stderr, flush=True)
        if src == "enrich":
            kept, rej, st, fr, conf = convert_enrich()
            st["confusions_ru"] = _save_conf(conf, derived / "confusions_ru.json")
        elif src == "gensec":
            st, conf = mine_gensec()
            st["confusions_en"] = _save_conf(conf, derived / "confusions_en.json")
            kept, rej, fr = [], [], collections.Counter()
        elif src == "kul":
            kept, rej, st, fr = convert_kul()
        else:
            kept, rej, st, fr = convert_pairs(src, LOADERS[src]())
        write_jsonl(out / f"{src}.jsonl", kept)
        write_jsonl(out / f"{src}.rejects.jsonl", rej, key_order=False)
        all_stats[src] = {"licence": SOURCES[src]["licence"], **dict(st),
                          "first_reason": dict(fr.most_common())}
        print(f"[convert] {src}: rows={st['rows']} kept={st['kept']}", file=sys.stderr, flush=True)
    statf.write_text(json.dumps(all_stats, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
