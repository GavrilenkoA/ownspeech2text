#!/usr/bin/env python3
"""Scale the probe set with the teacher LLM.

Each request asks for one *batch* of slots planned by ``plan.Planner``
(language, domain, length, required lexicon terms). The prompt text lives in
``gen_prompt.md``. Every returned sentence is checked the same way as the seed
set (schema, script mix, length, lexicon terms, tts_text) and against all
existing sentences for exact / near / template duplicates; survivors are
appended to ``--out``.

Resumable: ``--n`` is the total wanted in ``--out``. Re-running continues from
the accepted sentences in ``--out`` and the batch counter in ``<out>.state.json``;
rejected candidates and their reasons go to ``<out>.rejects`` (JSON lines).
The plan of a batch depends only on ``--seed``, the batch number and the
accepted sentences, so a resumed run plans exactly what an uninterrupted one would.

The teacher is configured only through the environment (see teacher.py):
  tools/with-teacher .cache/ml/roundtrip/venv/bin/python ml/roundtrip/probe/gen_probe.py --n 200
Without it the script exits with a clear message; --dry-run prints the first
request instead and needs no teacher.
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import os
import random
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from ml.roundtrip.probe.dedup import DedupIndex
from ml.roundtrip.probe.lexicon import TermMatcher, load_lexicon, surface_occurs
from ml.roundtrip.probe.plan import DEFAULT_LANG_SHARE, Planner, Slot, Usage
from ml.roundtrip.probe.prompt import PromptSet, load_prompt
from ml.roundtrip.probe.respell import respell
from ml.roundtrip.probe.schema import LANGS, ProbeRecord, check_record, dumps, load_records
from ml.roundtrip.probe.teacher import (
    Message,
    TeacherClient,
    TeacherConfigError,
    TeacherError,
    config_from_env,
)
from ml.roundtrip.probe.numstyle import normalise_apostrophes
from ml.roundtrip.probe.spoken import has_number_token, transfer_spoken_numbers
from ml.roundtrip.probe.textutil import collapse_ws, has_cyrillic, has_latin, nfc, word_count

HERE = Path(__file__).resolve().parent
DEFAULT_OUT = HERE / "gen.jsonl"
MAX_WORKERS = 2  # shared teacher quota: at most 2 concurrent requests
_FENCE_RE = re.compile(r"^```(?:json)?\s*\n(.*)\n```\s*$", re.DOTALL)
_BATCH_ID_RE = re.compile(r"^gen-[a-z]+-b(\d+)-s\d+$")


class ReplyError(ValueError):
    """The teacher's reply cannot be used at all (triggers one repair turn)."""


# ------------------------------------------------------------------ parsing
@dataclass(frozen=True)
class Candidate:
    slot: int
    text: str
    tts_text: str | None
    names: tuple[str, ...]


def parse_reply(raw: str, n_slots: int) -> dict[int, Candidate]:
    """Parse ``{"sentences": [...]}``; raise ReplyError when nothing is usable.

    Items with a bad shape or an unknown slot are skipped; the first item for a
    slot wins.
    """
    s = raw.strip()
    m = _FENCE_RE.match(s)
    if m:
        s = m.group(1).strip()
    try:
        obj = json.loads(s)
    except json.JSONDecodeError as exc:
        raise ReplyError(f"the reply is not valid JSON ({exc.msg} at char {exc.pos})") from None
    if not isinstance(obj, dict) or not isinstance(obj.get("sentences"), list):
        raise ReplyError('the reply must be an object with a "sentences" array')
    out: dict[int, Candidate] = {}
    for item in obj["sentences"]:
        if not isinstance(item, dict):
            continue
        slot, text, tts, names = item.get("slot"), item.get("text"), item.get("tts_text"), item.get("names", [])
        if isinstance(slot, bool) or not isinstance(slot, int) or not 0 <= slot < n_slots or slot in out:
            continue
        if not isinstance(text, str) or not text.strip():
            continue
        if not isinstance(tts, str) or not tts.strip():
            tts = None
        if not isinstance(names, list):
            names = []
        out[slot] = Candidate(slot, text, tts, tuple(n.strip() for n in names if isinstance(n, str) and n.strip()))
    if not out:
        raise ReplyError(f"no usable items; expected one item per slot 0..{n_slots - 1} with an integer slot and text")
    return out


def slots_json(slots: list[Slot]) -> str:
    """JSON array of slots, one slot per line (compact but readable)."""
    return "[\n" + ",\n".join(json.dumps(s.to_prompt(), ensure_ascii=False) for s in slots) + "\n]"


# --------------------------------------------------------------- acceptance
@dataclass
class Verdict:
    record: ProbeRecord | None
    reasons: list[str] = field(default_factory=list)


def build_candidate_record(slot: Slot, cand: Candidate, matcher: TermMatcher) -> Verdict:
    """Turn a teacher sentence into a probe record, or explain why not."""
    text = collapse_ws(normalise_apostrophes(nfc(cand.text)))
    reasons: list[str] = []
    matches = matcher.find(text)
    terms: list[dict[str, str]] = []
    seen: set[str] = set()
    found_entries = {m.entry.term for m in matches}
    for m in matches:
        if m.surface not in seen:
            terms.append({"term": m.surface, "category": m.entry.category})
            seen.add(m.surface)
    missing = [t.term for t in slot.terms if t.term not in found_entries]
    if missing:
        reasons.append(f"required term(s) missing: {missing}")
    spans = [(m.start, m.end) for m in matches]
    for name in cand.names:
        name = nfc(name)
        if name in seen or not surface_occurs(name, text):
            continue
        idx = text.find(name)
        if any(not (idx + len(name) <= s or idx >= e) for s, e in spans):
            continue
        terms.append({"term": name, "category": "person"})
        seen.add(name)
    terms.sort(key=lambda t: text.find(t["term"]))

    tts: str | None = None
    rs = respell(text, slot.lang, matcher)
    teacher_tts = collapse_ws(normalise_apostrophes(nfc(cand.tts_text))) if cand.tts_text else None
    if teacher_tts and not (_script_ok(teacher_tts, slot.lang) and _similar_length(teacher_tts, text)):
        teacher_tts = None
    if slot.lang == "mixed" and rs.leftovers:
        if teacher_tts:
            tts = teacher_tts
        else:
            reasons.append(f"no usable tts_text: Latin tokens {list(rs.leftovers)} are not in the lexicon "
                           "and the teacher's tts_text is missing or unusable")
    elif slot.lang != "ru":
        tts = rs.tts_text
    if has_number_token(text) and teacher_tts and (tts is None or has_number_token(tts)):
        # the lexicon respelling keeps digits: take the spoken numbers (declined) from the teacher's hint
        spoken = transfer_spoken_numbers(tts or text, teacher_tts, slot.lang)
        tts = spoken if spoken != text else tts
    rec: ProbeRecord = {"id": slot.rid, "lang": slot.lang, "domain": slot.domain, "text": text,
                        "terms": terms, "tts_text": tts}  # type: ignore[typeddict-item]
    reasons.extend(check_record(rec, matcher))
    return Verdict(None if reasons else rec, reasons)


def _script_ok(tts: str, lang: str) -> bool:
    return not has_cyrillic(tts) if lang == "en" else not has_latin(tts)


def _similar_length(a: str, b: str) -> bool:
    wa, wb = word_count(a), word_count(b)
    return abs(wa - wb) <= max(4, int(0.5 * wb))


# ---------------------------------------------------------------- the run
def drop_partial_last_line(path: Path) -> None:
    """Truncate a half-written last line left by an interrupted run."""
    if not path.exists():
        return
    data = path.read_bytes()
    if data and not data.endswith(b"\n"):
        path.write_bytes(data[: data.rfind(b"\n") + 1])


@dataclass
class RunState:
    seed: int
    next_batch: int = 0
    requests: int = 0
    accepted: int = 0
    rejected: int = 0
    prompt_version: str = ""

    @classmethod
    def load(cls, path: Path, seed: int) -> "RunState":
        if not path.exists():
            return cls(seed=seed)
        d = json.loads(path.read_text(encoding="utf-8"))
        return cls(**{k: d[k] for k in ("seed", "next_batch", "requests", "accepted", "rejected", "prompt_version") if k in d})

    def save(self, path: Path) -> None:
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(json.dumps(self.__dict__, indent=1) + "\n", encoding="utf-8")
        os.replace(tmp, path)


@dataclass
class BatchResult:
    batch: int
    slots: list[Slot]
    candidates: dict[int, Candidate]
    error: str | None
    requests: int


def ask_teacher(client: TeacherClient, prompts: PromptSet, slots: list[Slot], examples: str,
                temperature: float | None, repairs: int = 1) -> BatchResult:
    """One request (plus up to ``repairs`` repair turns) for a planned batch."""
    client = dataclasses.replace(client, requests_sent=0)  # per-call counter, safe with 2 workers
    before = 0
    user = prompts.render_user(len(slots), slots_json(slots),
                               examples)
    messages = [Message("user", user)]
    batch = slots[0].batch
    for attempt in range(repairs + 1):
        try:
            raw = client.complete(prompts.system, messages, temperature)
        except TeacherError as exc:
            return BatchResult(batch, slots, {}, str(exc), client.requests_sent - before)
        try:
            return BatchResult(batch, slots, parse_reply(raw, len(slots)), None, client.requests_sent - before)
        except ReplyError as exc:
            if attempt == repairs:
                return BatchResult(batch, slots, {}, f"unusable reply: {exc}", client.requests_sent - before)
            messages = messages + [Message("assistant", raw[:4000]), Message("user", prompts.render_repair(str(exc)))]
    raise AssertionError("unreachable")


def format_examples(records: list[ProbeRecord], langs: set[str], rng_batch: int, seed: int, k: int = 4) -> str:
    pool = [r for r in records if r.get("lang") in langs]
    if not pool:
        return "(none)"
    rng = random.Random(f"probe-examples:{seed}:{rng_batch}")
    return "\n".join(f"- [{r['lang']}/{r['domain']}] {r['text']}" for r in rng.sample(pool, min(k, len(pool))))


@dataclass
class Generator:
    matcher: TermMatcher
    planner: Planner
    prompts: PromptSet
    out: Path
    existing: list[ProbeRecord]
    log: Callable[[str], None] = lambda msg: print(msg, file=sys.stderr)

    def __post_init__(self) -> None:
        self.state_path = self.out.with_name(self.out.name + ".state.json")
        self.rejects_path = self.out.with_name(self.out.name + ".rejects")
        drop_partial_last_line(self.out)
        self.generated: list[ProbeRecord] = load_records([self.out])
        self.index = DedupIndex()
        self.ids: set[str] = set()
        for r in self.existing + self.generated:
            self.index.add(r["id"], r["lang"], r["text"], [t["term"] for t in r["terms"]])
            self.ids.add(r["id"])
        self.seed_term_usage = Usage.from_records(self.existing, self.matcher).terms

    def next_batch_from_ids(self) -> int:
        """First batch number after every batch present in --out (guards a stale state file)."""
        nums = [int(m.group(1)) for r in self.generated if (m := _BATCH_ID_RE.match(r["id"]))]
        return max(nums) + 1 if nums else 0

    def usage(self) -> Usage:
        u = Usage.from_records(self.generated, self.matcher)
        u.terms.update(self.seed_term_usage)
        return u

    def plan_round(self, first_batch: int, n_batches: int) -> list[list[Slot]]:
        usage = self.usage()
        return [self.planner.plan_batch(first_batch + i, usage) for i in range(n_batches)]

    def examples_for(self, slots: list[Slot]) -> str:
        return format_examples(self.existing + self.generated, {s.lang for s in slots}, slots[0].batch, self.planner.seed)

    def commit(self, res: BatchResult) -> tuple[int, int]:
        """Validate and append one batch's sentences; return (accepted, rejected)."""
        acc = rej = 0
        with open(self.out, "a", encoding="utf-8") as fo, open(self.rejects_path, "a", encoding="utf-8") as fr:
            for slot in res.slots:
                cand = res.candidates.get(slot.index)
                if cand is None:
                    reasons = [res.error or "teacher returned no sentence for this slot"]
                    v = Verdict(None, reasons)
                else:
                    v = build_candidate_record(slot, cand, self.matcher)
                    if v.record is not None:
                        rec = v.record
                        if rec["id"] in self.ids:
                            v = Verdict(None, [f"id {rec['id']} already exists"])
                        else:
                            hit = self.index.check(rec["lang"], rec["text"], [t["term"] for t in rec["terms"]])
                            if hit:
                                v = Verdict(None, [f"{hit.kind} duplicate of {hit.other_id} (score {hit.score})"])
                if v.record is not None:
                    rec = v.record
                    fo.write(dumps(rec) + "\n")
                    self.generated.append(rec)
                    self.ids.add(rec["id"])
                    self.index.add(rec["id"], rec["lang"], rec["text"], [t["term"] for t in rec["terms"]])
                    acc += 1
                else:
                    fr.write(json.dumps({"id": slot.rid, "slot": slot.to_prompt(),
                                         "text": cand.text if cand else None,
                                         "reasons": v.reasons}, ensure_ascii=False) + "\n")
                    rej += 1
            fo.flush()
            os.fsync(fo.fileno())
        return acc, rej

    def run(self, client: TeacherClient, n: int, workers: int, max_requests: int, temperature: float | None,
            max_failed_batches: int = 5) -> int:
        state = RunState.load(self.state_path, self.planner.seed)
        if state.seed != self.planner.seed:
            self.log(f"error: {self.out} was generated with --seed {state.seed}; use that seed or another --out")
            return 2
        state.prompt_version = self.prompts.version
        state.next_batch = max(state.next_batch, self.next_batch_from_ids())
        failed_in_a_row = 0
        workers = max(1, min(workers, MAX_WORKERS))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            while len(self.generated) < n:
                if state.requests >= max_requests:
                    self.log(f"stopping: request budget {max_requests} reached ({len(self.generated)}/{n} sentences)")
                    return 1
                rounds = self.plan_round(state.next_batch, workers)
                t0 = time.time()
                futures = [pool.submit(ask_teacher, client, self.prompts, slots, self.examples_for(slots), temperature)
                           for slots in rounds]
                for fut in futures:  # commit in batch order -> deterministic output order
                    res = fut.result()
                    acc, rej = self.commit(res)
                    state.next_batch = res.batch + 1
                    state.requests += res.requests
                    state.accepted += acc
                    state.rejected += rej
                    state.save(self.state_path)
                    failed_in_a_row = failed_in_a_row + 1 if acc == 0 else 0
                    note = f" ({res.error})" if res.error else ""
                    self.log(f"batch {res.batch}: +{acc} accepted, {rej} rejected{note}; "
                             f"{len(self.generated)}/{n} in {self.out.name}, {state.requests} requests, "
                             f"{time.time() - t0:.0f}s")
                if failed_in_a_row >= max_failed_batches:
                    self.log(f"error: {failed_in_a_row} batches in a row produced nothing; see {self.rejects_path}")
                    return 1
        self.log(f"done: {len(self.generated)} sentences in {self.out}")
        return 0


# --------------------------------------------------------------------- CLI
def parse_shares(spec: str) -> dict[str, float]:
    out: dict[str, float] = {}
    for part in spec.split(","):
        lang, _, val = part.partition("=")
        lang = lang.strip()
        try:
            out[lang] = float(val)
        except ValueError:
            raise argparse.ArgumentTypeError(f"bad --langs item {part!r}") from None
        if lang not in LANGS or out[lang] < 0:
            raise argparse.ArgumentTypeError(f"bad --langs item {part!r}")
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n", type=int, required=True, help="total sentences wanted in --out (resumes if it has some)")
    ap.add_argument("--seed", type=int, default=0, help="planning seed (fixed per output file)")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"output JSONL (default {DEFAULT_OUT.name})")
    ap.add_argument("--existing", type=Path, nargs="*", default=[HERE / "seed.jsonl"],
                    help="probe files to de-duplicate against and to count term usage from")
    ap.add_argument("--lexicon", type=Path, default=HERE / "terms.jsonl")
    ap.add_argument("--prompt", type=Path, default=HERE / "gen_prompt.md")
    ap.add_argument("--batch", type=int, default=8, help="sentences per request")
    ap.add_argument("--langs", type=parse_shares, default=dict(DEFAULT_LANG_SHARE),
                    help="target language shares of the generated file, e.g. ru=0.3,en=0.25,mixed=0.45")
    ap.add_argument("--workers", type=int, default=1, help=f"concurrent requests (max {MAX_WORKERS})")
    ap.add_argument("--max-requests", type=int, default=200, help="request budget per output file, incl. retries")
    ap.add_argument("--temperature", type=float, default=None, help="sent only with MURMUR_TEACHER_API=chat")
    ap.add_argument("--dry-run", action="store_true", help="print the first planned request and exit")
    args = ap.parse_args(argv)
    if args.n < 0 or not 1 <= args.batch <= 20:
        ap.error("--n must be >= 0 and --batch in 1..20")

    try:
        entries = load_lexicon(args.lexicon)
        prompts = load_prompt(args.prompt)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    matcher = TermMatcher(entries)
    existing = load_records([p for p in args.existing if p.resolve() != args.out.resolve()])
    planner = Planner(entries, seed=args.seed, lang_share=args.langs, batch_size=args.batch)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    gen = Generator(matcher, planner, prompts, args.out, existing)

    if args.dry_run:
        state = RunState.load(gen.state_path, args.seed)
        state.next_batch = max(state.next_batch, gen.next_batch_from_ids())
        slots = gen.plan_round(state.next_batch, 1)[0]
        user = prompts.render_user(len(slots), slots_json(slots),
                                   gen.examples_for(slots))
        print(f"=== system ({prompts.version}) ===\n{prompts.system}\n\n=== user (batch {state.next_batch}) ===\n{user}")
        return 0
    try:
        config = config_from_env()
    except TeacherConfigError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    client = TeacherClient(config, log=lambda m: print(m, file=sys.stderr))
    return gen.run(client, args.n, args.workers, args.max_requests, args.temperature)


if __name__ == "__main__":
    sys.exit(main())
