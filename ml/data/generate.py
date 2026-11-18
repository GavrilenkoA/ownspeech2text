"""ONE command for the PLAN_V2 stage-C synthetic set: plan -> teacher (resumable) -> build -> filter -> dedup -> split.

  tools/with-teacher .cache/ml/data/venv/bin/python -m ml.data.generate --out .cache/ml/data/synth_v2 \\
      --n 3000 --dev 300 --test 300 [--mined ml/roundtrip/out/mined] [--max-requests 700]

  python -m ml.data.generate --teacher stub --out /tmp/x --n 300 --dev 30 --test 30     # offline plumbing test

Writes OUT/{train,dev,test}.jsonl (shared contract), OUT/scenarios.jsonl, OUT/teacher/*.jsonl (resumable
shards: re-running the same command continues), OUT/rejects.jsonl (every rejected example with reasons),
OUT/stats.json and OUT/REPORT.md. Each (split, category) keeps exactly the target count of
sampler.CATEGORY_TARGETS when enough examples survive (shortfalls are reported, never padded).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

from ml.common.render_bridge import REPO
from ml.common.schema import write_jsonl
from ml.common.util import stable_id
from ml.data.synth import build as B
from ml.data.synth import filters as F
from ml.data.synth import sampler as S
from ml.data.synth import screen as SCR
from ml.data.synth import teacher as TC
from ml.data.synth.channel import Channel
from ml.data.synth.teacher_prompts import BATCH_SCHEMA, GEN_VERSION, gen_request

TONES = S.TONES


def make_jobs(scs: list[dict], batch: int, prefix: str = "gen") -> list[dict]:
    """Batches of scenarios with the same language (one shared prompt per request)."""
    jobs = []
    by_lang = defaultdict(list)
    for sc in scs:
        by_lang[sc["lang"]].append(sc)
    for lang in sorted(by_lang):
        lst = by_lang[lang]
        for i in range(0, len(lst), batch):
            chunk = lst[i:i + batch]
            ins, inp = gen_request(chunk, TONES)
            # the id hashes the exact request text: a changed scenario or prompt is never answered from an old shard
            jobs.append({"id": stable_id(prefix, GEN_VERSION, ins, inp), "instructions": ins, "input": inp,
                         "schema": BATCH_SCHEMA, "name": "examples", "sids": [s["sid"] for s in chunk], "gen": GEN_VERSION})
    return jobs


def stratified_limit(scs: list[dict], k: int) -> list[dict]:
    """First k scenarios taken round-robin over (category, split), so a small test run covers every category."""
    groups = defaultdict(list)
    for sc in scs:
        groups[(sc["category"], sc["split"])].append(sc)
    keys = sorted(groups, key=lambda g: (g[1] != "train", g[0]))
    out, i = [], 0
    while len(out) < k and any(groups.values()):
        g = keys[i % len(keys)]
        if groups[g]:
            out.append(groups[g].pop(0))
        i += 1
        if i > 10 * (k + len(keys)):
            break
    return out


def examples_from(results: dict, jobs: list[dict]) -> tuple[dict, Counter]:
    ex, bad = {}, Counter()
    for j in jobs:
        r = results.get(j["id"])
        if not r:
            bad["job_failed"] += len(j["sids"])
            continue
        got = {e["sid"]: e for e in r["result"]["examples"] if e.get("sid") in j["sids"]}
        for sid in j["sids"]:
            if sid in got:
                ex[sid] = got[sid] | {"_teacher": r.get("model", ""), "_gen": r.get("gen") or "gen.v2"}
            else:
                bad["sid_missing_in_batch"] += 1
    return ex, bad


def run(a) -> dict:
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    plan = S.plan(a.n, a.dev, a.test, a.seed, overgen=a.overgen)
    tgt_counts = S.targets(a.n, a.dev, a.test)
    with open(out / "scenarios.jsonl", "w", encoding="utf-8") as f:
        for sc in plan:
            f.write(json.dumps(sc, ensure_ascii=False) + "\n")
    teach = [sc for sc in plan if sc["category"] not in S.CODE_ONLY]
    if a.limit:
        teach = stratified_limit(teach, a.limit)
        keep_sids = {sc["sid"] for sc in teach}
    channel = Channel(a.mined)  # fail fast on a bad --mined dir (workers build their own)
    teacher = TC.make_teacher(a.teacher, max_requests=a.max_requests)
    jobs = make_jobs(teach, a.batch)
    st_t = TC.run_sharded(jobs, teacher, out / "teacher", "gen", shard_size=a.shard_size,
                          concurrency=a.concurrency, max_retries=a.max_retries)
    ex, bad = examples_from(TC.load_results(out / "teacher", "gen"), jobs)
    # one top-up pass for scenarios a batch dropped or a failed job lost
    missing = [sc for sc in teach if sc["sid"] not in ex]
    st_top = None
    if missing and not st_t.get("quota_stop") and a.topup:
        tjobs = make_jobs(missing, a.batch, prefix="topup")
        st_top = TC.run_sharded(tjobs, teacher, out / "teacher", "topup", shard_size=a.shard_size,
                                concurrency=a.concurrency, max_retries=a.max_retries)
        ex2, _ = examples_from(TC.load_results(out / "teacher", "topup"), tjobs)
        ex.update(ex2)
    # build: render + OCR + noise + filters (parallel workers; each owns its OCR session)
    recs, rejects, reasons = [], [], Counter()
    ocr_drops: dict = {"by_category": Counter(), "by_term": Counter(), "by_rule": Counter()}
    by_sid = {sc["sid"]: sc for sc in plan}
    todo = []
    for sc in plan:
        if a.limit and sc["category"] not in S.CODE_ONLY and sc["sid"] not in keep_sids:
            continue
        if sc["category"] == "context_counterpart":
            continue
        if sc["category"] != "noise_only" and sc["sid"] not in ex:
            reasons["no_teacher_example"] += 1
            continue
        todo.append((sc, ex.get(sc["sid"])))
    backend_info, timing = _backend_probe(a.screens)
    if backend_info["backend"] == "fixture" and not a.limit and a.n >= 1000 and not a.allow_fixture:
        raise SystemExit("refusing a full-size run with the fixture screen backend (no OCR): wait for "
                         "ml/screens/READY + ml/ocr/READY or pass --allow-fixture")
    tb = time.time()
    results = _build_all(todo, a, teacher.name)
    build_wall = time.time() - tb
    for sc, (built, err, tm) in zip([t[0] for t in todo], results):
        for k, v in tm.items():
            timing[k] = timing.get(k, 0.0) + v
        if err:
            reasons["build:" + err["reason"]] += 1
            if err["reason"].startswith("ocr:"):
                ocr_drops["by_category"][sc["category"]] += 1
                ocr_drops["by_rule"][err["reason"][4:]] += 1
                if err.get("term"):
                    ocr_drops["by_term"][err["term"]] += 1
            rejects.append({"sid": sc["sid"], "category": sc["category"], "reasons": ["build:" + err["reason"]],
                            "term": err.get("term"), "example": ex.get(sc["sid"])})
            continue
        for rec in built:
            if "_reject" in rec:
                reasons["build:" + rec["_reject"]] += 1
                if rec["_reject"].startswith("ocr:"):
                    ocr_drops["by_category"][rec["category"]] += 1
                    ocr_drops["by_rule"][rec["_reject"][4:]] += 1
                    if rec.get("term"):
                        ocr_drops["by_term"][rec["term"]] += 1
                rejects.append({"sid": sc["sid"], "category": rec["category"], "reasons": ["build:" + rec["_reject"]],
                                "term": rec.get("term")})
                continue
            why = F.check(rec)
            if why:
                for w in why:
                    reasons["filter:" + w] += 1
                rejects.append({"sid": sc["sid"], "category": rec["category"], "reasons": why, "record": rec})
            else:
                recs.append(rec)
    if a.limit:  # noise_only is code-made: keep it proportional in a limited run
        n_no = max(1, round(len(recs) * S.CATEGORY_TARGETS["noise_only"] / 3000))
        no = [r for r in recs if r["category"] == "noise_only"][:n_no]
        recs = [r for r in recs if r["category"] != "noise_only"] + no
    recs, dups = F.dedup(recs)
    for rid, why, other in dups:
        reasons["dedup:" + why] += 1
    # exact counts per (split, category): first survivors in plan order
    final, short = [], {}
    by_cell = defaultdict(list)
    for r in recs:
        by_cell[(r["split"], r["category"])].append(r)
    for split, cc in tgt_counts.items():
        for cat, c in cc.items():
            cell = sorted(by_cell.get((split, cat), []), key=lambda r: by_sid[r["meta"]["scenario"]]["index"])
            if not a.limit and len(cell) < c:
                short[f"{split}/{cat}"] = {"target": c, "got": len(cell)}
            final += cell if a.limit else cell[:c]
    # a counterpart is useless without its context-resolution parent (and vice versa it stays useful alone)
    ids = {r["id"] for r in final}
    final = [r for r in final if r["category"] != "context_counterpart" or r["meta"]["counterpart_of"] in ids]
    leak = F.leakage(final)
    for split in ("train", "dev", "test"):
        write_jsonl(out / f"{split}.jsonl", [r for r in final if r["split"] == split])
    write_jsonl(out / "rejects.jsonl", rejects, key_order=False)
    stats = {
        "args": {k: v for k, v in vars(a).items()}, "teacher": getattr(getattr(teacher, "cfg", None), "public", lambda: {"name": "stub"})(),
        "noise_source": channel.source, "scenarios_planned": len(plan), "teacher_scenarios": len(teach),
        "teacher_jobs": len(jobs), "teacher_run": st_t, "topup_run": st_top, "teacher_examples": len(ex),
        "batch_problems": dict(bad), "built_and_filtered_kept": len(recs) + len(dups), "after_dedup": len(recs),
        "final": len(final), "reasons": dict(reasons.most_common()), "shortfall": short, "leakage": leak,
        "final_by_split": dict(Counter(r["split"] for r in final)),
        "final_by_category": dict(Counter(r["category"] for r in final).most_common()),
        "final_by_lang": dict(Counter(r["lang"] for r in final)),
        "final_by_asr": dict(Counter(r["meta"].get("asr_model", "-") for r in final).most_common()),
        "final_by_app": dict(Counter(r["ctx"]["app"]["category"] for r in final).most_common()),
        "final_with_screen": sum(1 for r in final if r["ctx"].get("screen")),
        "final_without_screen": dict(Counter(r["ctx"].get("privacy", {}).get("reason") or "-" for r in final
                                             if not r["ctx"].get("screen"))),
        "final_without_field": sum(1 for r in final if "field" not in r["ctx"]),
        "final_by_device": dict(Counter(r["meta"].get("screen", {}).get("spec", {}).get("device", "-") for r in final)),
        "final_rerendered": sum(1 for r in final if r["meta"].get("screen", {}).get("variant") == 1),
        "screens": backend_info,
        "ocr_drops": {k: dict(v.most_common()) for k, v in ocr_drops.items()},
        "timing": {"teacher_wall_s": st_t.get("wall_s", 0) + ((st_top or {}).get("wall_s") or 0),
                   "build_wall_s": round(build_wall, 1), "workers": a.workers,
                   **{k: round(v, 1) for k, v in timing.items()}},
        "wall_s": round(time.time() - t0, 1),
    }
    (out / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "REPORT.md").write_text(report(stats), encoding="utf-8")
    return stats


# ------------------------------------------------------------------ parallel build
_W: dict = {}


def _winit(mined, screens):
    import os
    for v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "MURMUR_OCR_THREADS"):
        os.environ.setdefault(v, "1")
    _W["channel"] = Channel(mined)
    _W["backend"] = SCR.Backend(screens)


def _build_one(job):
    sc, e, teacher_name = job
    ch, be = _W["channel"], _W["backend"]
    t0, c0, r0, o0 = be.timing["build_ctx_s"], be.timing["calls"], be.timing["render_s"], be.timing["ocr_s"]
    t = time.time()
    try:
        if sc["category"] == "noise_only":
            built = B.build_noise_only(sc, ch, be)
        else:
            built = B.build(sc, e, ch, e.get("_teacher", teacher_name), be, gen=e.get("_gen"))
        err = None
    except B.Reject as rj:
        built, err = [], {"reason": str(rj), "term": getattr(rj, "term", None)}
    tot = time.time() - t
    ocr = be.timing["build_ctx_s"] - t0
    return built, err, {"screen_build_ctx_s": ocr, "screen_calls": be.timing["calls"] - c0, "code_s": tot - ocr,
                        "render_s": be.timing["render_s"] - r0, "ocr_s": be.timing["ocr_s"] - o0}


def _build_all(todo, a, teacher_name):
    jobs = [(sc, e, teacher_name) for sc, e in todo]
    t0, out = time.time(), []

    def tick():
        if len(out) % 100 == 0 or len(out) == len(jobs):
            print(f"[build] {len(out)}/{len(jobs)} scenarios {time.time() - t0:.0f}s", file=sys.stderr, flush=True)
    if a.workers <= 1:
        _winit(a.mined, a.screens)
        for j in jobs:
            out.append(_build_one(j))
            tick()
        return out
    import multiprocessing as mp
    with mp.get_context("spawn").Pool(a.workers, initializer=_winit, initargs=(a.mined, a.screens)) as pool:
        for res in pool.imap(_build_one, jobs, chunksize=2):  # ordered, streamed for progress
            out.append(res)
            tick()
    return out


def _backend_probe(kind):
    be = SCR.Backend(kind)
    info = dict(be.version) | {"renderer_v2": SCR.renderer_shows_screen(),
                               "ocr_hard_terms": len(SCR.ocr_hard_terms())}
    return info, {}


def report(st: dict) -> str:
    t = st["teacher_run"]
    lines = ["# Synthetic set (PLAN_V2 stage C) — run report", "",
             f"Teacher: {st['teacher']}; noise: `{st['noise_source']}`.", "",
             f"Scenarios planned {st['scenarios_planned']}, sent to the teacher {st['teacher_scenarios']} in "
             f"{st['teacher_jobs']} batches; teacher examples received {st['teacher_examples']}; "
             f"kept after build+filters {st['built_and_filtered_kept']}, after dedup {st['after_dedup']}, final {st['final']}.",
             "", f"Teacher run: {json.dumps(t, ensure_ascii=False)}", "", "## Final counts", "",
             "| split | n |", "|---|---:|"] + [f"| {k} | {v} |" for k, v in st["final_by_split"].items()] + [
             "", "| category | n |", "|---|---:|"] + [f"| {k} | {v} |" for k, v in st["final_by_category"].items()] + [
             "", "| lang | n |", "|---|---:|"] + [f"| {k} | {v} |" for k, v in st["final_by_lang"].items()] + [
             "", "## Why examples were dropped", "", "| reason | n |", "|---|---:|"] + [
             f"| {k} | {v} |" for k, v in st["reasons"].items()] + [
             "", f"Shortfall vs targets: {json.dumps(st['shortfall'], ensure_ascii=False) or 'none'}",
             "", f"Leakage check: {json.dumps(st['leakage'], ensure_ascii=False)}", "",
             "## Screens (CONTEXT_V2)", "", f"Backend: `{json.dumps(st['screens'], ensure_ascii=False)}`", "",
             f"With screen {st['final_with_screen']}, without screen {json.dumps(st['final_without_screen'])}, "
             f"without field {st['final_without_field']}, re-rendered once {st['final_rerendered']}, "
             f"devices {json.dumps(st['final_by_device'])}.", "",
             f"OCR-rule drops: {json.dumps(st['ocr_drops'], ensure_ascii=False)}", "",
             f"Timing: {json.dumps(st['timing'])}", ""]
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--n", type=int, default=3000, help="training examples")
    ap.add_argument("--dev", type=int, default=300)
    ap.add_argument("--test", type=int, default=300)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--teacher", default="responses", choices=["responses", "chat", "stub"])
    ap.add_argument("--mined", default=None, help="mined error tables dir (ml/roundtrip); default builtin simulator")
    ap.add_argument("--batch", type=int, default=6, help="scenarios per teacher request")
    ap.add_argument("--overgen", type=float, default=1.15, help="scenario over-generation to absorb filter losses")
    ap.add_argument("--concurrency", type=int, default=None)
    ap.add_argument("--max-requests", type=int, default=0, help="hard cap on HTTP requests (0 = none)")
    ap.add_argument("--max-retries", type=int, default=5)
    ap.add_argument("--shard-size", type=int, default=25, help="teacher jobs per resumable shard")
    ap.add_argument("--limit", type=int, default=0, help="only K teacher scenarios, stratified (test runs)")
    ap.add_argument("--no-topup", dest="topup", action="store_false")
    ap.add_argument("--screens", default="auto", choices=["auto", "real", "fixture"],
                    help="ctx source: real = ml.screens.build_ctx (render + OCR); auto = real when READY files exist")
    ap.add_argument("--allow-fixture", action="store_true", help="allow a full-size run on the fixture backend")
    ap.add_argument("--workers", type=int, default=4, help="build processes (render + OCR); <= 6 on shared CPUs")
    a = ap.parse_args(argv)
    if a.concurrency is None:
        a.concurrency = TC.TeacherConfig().concurrency if a.teacher != "stub" else 4
    st = run(a)
    print(json.dumps({k: st[k] for k in ("final", "final_by_split", "teacher_run", "shortfall", "leakage", "wall_s")},
                     ensure_ascii=False, indent=1))
    print(f"report: {Path(a.out) / 'REPORT.md'}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
