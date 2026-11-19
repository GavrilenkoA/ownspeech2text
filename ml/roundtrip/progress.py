"""One-screen progress of a ``run_roundtrip --plan`` run: done/total jobs, rows, errors, rate, ETA, state.

  .cache/ml/roundtrip/venv/bin/python -m ml.roundtrip.progress --out .cache/ml/roundtrip/out/full --run full

Reads only files (plan, rows, errors log, run log, DONE/STOP markers); safe to run while the run goes on.
Add ``--detail`` for the per model / language / TTS / channel split of the rows written so far.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import time
from collections import Counter
from pathlib import Path
from typing import Optional, Sequence

from .full_plan import accepts


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _fmt_s(s: float) -> str:
    s = max(0, int(s))
    return f"{s // 3600}h{(s % 3600) // 60:02d}m"


def _runner_cap_h(pid: Optional[int]) -> Optional[float]:
    """``--max-wall-h`` of the live runner (the python child of the wrapper shell in runner.pid), or None."""
    if not pid:
        return None
    try:
        for d in Path("/proc").iterdir():
            if not d.name.isdigit():
                continue
            try:
                stat = (d / "stat").read_text()
                if int(stat.rsplit(")", 1)[1].split()[1]) != pid:
                    continue
                argv = (d / "cmdline").read_bytes().split(b"\0")
            except (OSError, ValueError, IndexError):
                continue
            if b"ml.roundtrip.run_roundtrip" in argv and b"--max-wall-h" in argv:
                return float(argv[argv.index(b"--max-wall-h") + 1])
    except OSError:
        pass
    return None


def _work_line(out: Path, rows: list, start: Optional[dict], pid: Optional[int], now: float, n_jobs: int,
               running: bool) -> None:
    """Progress weighted by the plan's expected worker-seconds per row of each model. Jobs are not equal:
    a whisper-medium row costs ~20x a GigaAM row, and the cheap models run up to --asr-lookahead waves ahead,
    so the jobs/h rate and a jobs-based ETA overstate progress while the heavy models lag."""
    summ = out / "plan.summary.json"
    if not summ.exists() or not rows:
        return
    est = json.loads(summ.read_text(encoding="utf-8")).get("estimate", {})
    ws, nr = est.get("asr_worker_s") or {}, est.get("rows") or {}
    per = {m: ws[m] / nr[m] for m in ws if nr.get(m)}
    total = sum(ws.values())
    if not total:
        return
    done_w = sum(per.get(r["asr_model"], 0.0) for r in rows)
    msg = f"work      {100 * done_w / total:.1f}% of the plan's expected ASR worker-s done ({len(rows)} rows)"
    if start and running:
        before = start["jobs_total"] - start["jobs_pending"]
        new_w = sum(per.get(r["asr_model"], 0.0) for r in rows[before:])  # rows are appended in order
        el = now - start["t"]
        if new_w > 0 and el > 600:
            rate = new_w / el  # planned worker-s per wall-s since the last start
            eta = (total - done_w) / rate
            msg += f"; at the rate since start: ETA {_fmt_s(eta)} -> ~{time.strftime('%a %H:%M', time.localtime(now + eta))}"
            cap = _runner_cap_h(pid)
            if cap:
                t_cap = start["t"] + cap * 3600
                frac = min(1.0, (done_w + rate * max(0.0, t_cap - now)) / total)
                msg += (f"\ncap       --max-wall-h {cap:g}: no new shard after {time.strftime('%a %H:%M', time.localtime(t_cap))}"
                        f" -> ~{100 * frac:.0f}% of the work, ~{frac * n_jobs:.0f} jobs at the plan's mix"
                        f" (more rows: cheap models run ahead)")
    print(msg)


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", type=Path, default=Path(".cache/ml/roundtrip/out/full"))
    ap.add_argument("--run", default="full")
    ap.add_argument("--plan", type=Path, default=None, help="default: <out>/plan.jsonl")
    ap.add_argument("--detail", action="store_true")
    ap.add_argument("--complete-waves", type=Path, default=None, metavar="OUT_JSONL",
                    help="write only the rows of waves in which every planned job has a row (a balanced sample of a "
                         "partial run: the cheap models run up to --asr-lookahead waves ahead of the heavy ones)")
    a = ap.parse_args(argv)
    plan_path = a.plan or a.out / "plan.jsonl"
    rows_path = a.out / "roundtrip" / f"{a.run}.jsonl"
    err_path = a.out / "roundtrip" / f"{a.run}.errors.jsonl"
    probes = {}
    for f in ("seed.jsonl", "gen.jsonl"):
        p = Path(__file__).resolve().parent / "probe" / f
        for line in p.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                probes[r["id"]] = r["lang"]

    jobs = set()
    wave_of = {}
    clips = 0
    audio_have = 0
    for line in plan_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        c = json.loads(line)
        clips += 1
        lang = probes[c["probe_id"]]
        v = c["tts_voice"].replace("#", "_").replace("/", "_")
        if (a.out / "audio" / c["tts_system"] / v / f"{c['probe_id']}.{c['channel']}.flac").exists():
            audio_have += 1
        wave_of[(c["probe_id"], c["tts_system"], c["tts_voice"], c["channel"])] = int(c.get("wave", 0))
        for m in c["asr"]:
            if accepts(m, lang):
                jobs.add((c["probe_id"], c["tts_system"], c["tts_voice"], c["channel"], m))

    rows = []
    torn = 0
    if rows_path.exists():
        for line in rows_path.read_text(encoding="utf-8").splitlines():
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                torn += 1
    keys = [(r["probe_id"], r["tts_system"], r["tts_voice"], r["channel"], r["asr_model"]) for r in rows]
    done = jobs & set(keys)
    dups = len(keys) - len(set(keys))
    outside = len(set(keys) - jobs)

    start = None
    errors = []
    if err_path.exists():
        for line in err_path.read_text(encoding="utf-8").splitlines():
            e = json.loads(line)
            if e.get("event") == "start":
                start, errors = e, []
            else:
                errors.append(e)

    done_marker = a.out / "DONE"
    stop_marker = a.out / "STOP"
    pid_file = a.out / "runner.pid"
    pid = int(pid_file.read_text().split()[0]) if pid_file.exists() else None
    if done_marker.exists():
        state = "ENDED " + done_marker.read_text().strip()
    elif pid and _pid_alive(pid):
        state = f"RUNNING (pid {pid})" + (" - STOP requested" if stop_marker.exists() else "")
    else:
        state = "NOT RUNNING (no DONE marker: interrupted? rerun ml/roundtrip/full_run.sh start)"

    now = time.time()
    print(f"state     {state}")
    print(f"audio     {audio_have}/{clips} planned clips synthesised")
    pct = 100 * len(done) / max(1, len(jobs))
    print(f"jobs      {len(done)}/{len(jobs)} done ({pct:.1f}%)   rows in file {len(rows)}"
          f"   duplicates {dups}   rows outside plan {outside}   torn lines {torn}")
    print(f"errors    {len(errors)} since the last start" + (f": {json.dumps(errors[-1], ensure_ascii=False)[:200]}"
                                                         if errors else ""))
    if start:
        el = now - start["t"]
        new = len(done) - (start["jobs_total"] - start["jobs_pending"])
        line = f"rate      {new} jobs in {_fmt_s(el)} since start"
        print(line)
    log = a.out / "run.log"
    if log.exists():
        lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
        # only the current invocation's lines (each start appends "=== start ...")
        starts = [i for i, x in enumerate(lines) if x.startswith("=== start")]
        lines = lines[starts[-1]:] if starts else lines
        last = [x for x in lines if re.match(r"\[(tts|asr)\] ", x)]
        if last:
            print(f"runner    {last[-1]}   (log updated {_fmt_s(now - log.stat().st_mtime)} ago)")
        asr = [m for m in (re.match(r"\[asr\] (\d+)/(\d+)\s+([\d.]+)s elapsed", x) for x in lines) if m]
        if asr and not done_marker.exists():
            n, tot, el = int(asr[-1].group(1)), int(asr[-1].group(2)), float(asr[-1].group(3))
            if n and el:
                eta = el / n * (tot - n)
                fin = time.strftime("%a %H:%M", time.localtime(now - (now - log.stat().st_mtime) + eta))
                print(f"asr rate  {n / el * 3600:.0f} jobs/h over the ASR stage ({n} in {_fmt_s(el)}); "
                      f"ETA {_fmt_s(eta)} -> ~{fin} (before the --max-wall-h cap)")
    _work_line(a.out, rows, start, pid, now, len(jobs), state.startswith("RUNNING"))
    if a.complete_waves is not None:
        need, have = Counter(wave_of[j[:4]] for j in jobs), Counter(wave_of[k[:4]] for k in done)
        full = sorted(w for w in need if have[w] == need[w])
        seen = set()
        with open(a.complete_waves, "w", encoding="utf-8") as fh:
            n = 0
            for r, k in zip(rows, keys):
                if k in jobs and k not in seen and wave_of[k[:4]] in full:
                    seen.add(k)
                    fh.write(json.dumps(r, ensure_ascii=False) + "\n")
                    n += 1
        print(f"complete  {len(full)} of {len(need)} waves complete ({full[:1] and f'{full[0]}..{full[-1]}' or 'none'}"
              f"{'' if full == list(range(len(full))) else ', not contiguous'}): {n} rows -> {a.complete_waves}")
    if a.detail and rows:
        for name, key in (("model", "asr_model"), ("lang", "lang"), ("tts", "tts_system"), ("channel", "channel"),
                          ("tts_input", "tts_input")):
            print(f"{name:9s} " + ", ".join(f"{k}={v}" for k, v in sorted(Counter(r[key] for r in rows).items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
