"""Adapters: public datasets that already contain real ASR hypotheses -> round-trip rows (interface 2).

These rows have real human speech instead of TTS audio, no audio file in this repo and usually no term
annotations, so they complement the TTS round trip: they measure each recogniser's style and everyday
word confusions on natural Russian speech, not its manglings of IT terms.

Supported source
  enrich   MishZor256/russian-asr-enrichment (CC-BY-SA-4.0 annotations; audio stays upstream). Every row
           carries two raw recogniser outputs, `gigaam_text` and `nemotron_text`, next to the upstream human
           reference `ref` and a consensus `clean_text`. Downloaded by ml/data/public (read-only here) to
           .cache/ml/data/enrich/train.parquet.

Reference choice: `clean_text` is built FROM the two hypotheses (tier A copies the Nemotron text), so
mining against it understates both models' errors. The default is the independent human `ref`, restricted
to domains whose references are written, cased and punctuated texts that were read aloud: cv21_ru (Common
Voice prompts) and rulibrispeech (book text; the reader sometimes departs from it, which shows up as extra
deletions/insertions for both models alike). SOVA references are lowercase crowd labels without punctuation;
rows like that are marked "style_comparable": false so the miner keeps them out of the style statistics.
FLEURS and Golos rows carry no hypotheses in this dataset (checked: 0 non-null of 2562 / 17562).

Recogniser ids: the dataset card names "NVIDIA Nemotron 3.5 Transcribe" and "GigaAM v2". Neither maps to a
docs/research/asr-catalog.json id with certainty (GigaAM v2 does not emit punctuation natively, yet
`gigaam_text` is punctuated), so the rows use the dataset-scoped ids below, not catalog ids.

  python -m ml.roundtrip.mine.adapters enrich --out .cache/ml/roundtrip/mined/enrich_rows.jsonl
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterator, Sequence

from .mine_errors import guess_lang
from .noise import seed_for

REPO = Path(__file__).resolve().parents[3]
ENRICH_PARQUET = REPO / ".cache" / "ml" / "data" / "enrich" / "train.parquet"
ENRICH_MODELS = {"gigaam": ("gigaam_text", "gigaam-v2@enrich-dataset"),
                 "nemotron": ("nemotron_text", "nemotron-3.5-transcribe@enrich-dataset")}
ENRICH_COLUMNS = ["source_id", "domain", "duration_s", "ref", "clean_text", "gigaam_text", "nemotron_text", "tier",
                  "has_latin"]
DEFAULT_DOMAINS = ("cv21_ru", "rulibrispeech")


def _read_parquet(path: Path, columns: Sequence[str]) -> list[dict[str, Any]]:
    try:
        import pyarrow.parquet as pq
    except ImportError as e:  # pragma: no cover - environment problem
        raise SystemExit("pyarrow is required for the adapters (pip install -r ml/roundtrip/requirements.txt)") from e
    if not path.exists():
        raise SystemExit(f"{path} not found: download it with ml/data/public (source 'enrich') first")
    return pq.read_table(path, columns=list(columns)).to_pylist()


def enrich_rows(records: Sequence[dict[str, Any]], domains: Sequence[str] | None, reference: str,
                per_domain: int, seed: int, models: Sequence[str]) -> Iterator[dict[str, Any]]:
    """Deterministic per-domain sample of enrich records -> one interface-2 row per (record, model).

    Sampling orders records by a hash of (seed, source_id), so the subset does not depend on file order.
    """
    if reference not in ("ref", "clean_text"):
        raise ValueError("reference must be 'ref' or 'clean_text'")
    unknown = [m for m in models if m not in ENRICH_MODELS]
    if unknown:
        raise ValueError(f"unknown enrich model(s) {unknown}; have {sorted(ENRICH_MODELS)}")
    by_domain: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for r in records:
        if domains and r.get("domain") not in domains:
            continue
        if not (r.get(reference) or "").strip() or not r.get("source_id"):
            continue
        if any(r.get(ENRICH_MODELS[m][0]) is None for m in models):
            continue
        by_domain[r["domain"]].append(r)
    for dom in sorted(by_domain):
        recs = sorted(by_domain[dom], key=lambda r: (seed_for(seed, "enrich", r["source_id"]), r["source_id"]))
        for r in recs[:per_domain] if per_domain > 0 else recs:
            text = r[reference].strip()
            styled = any(c.isupper() for c in text) or any(c in ".,!?" for c in text)
            for m in models:
                col, model_id = ENRICH_MODELS[m]
                yield {
                    "probe_id": r["source_id"], "lang": guess_lang(text), "domain": dom, "text": text, "terms": [],
                    "tts_system": "human", "tts_voice": dom, "channel": "dataset", "audio": "",
                    "audio_s": float(r.get("duration_s") or 0.0), "asr_model": model_id,
                    "hypothesis": r[col].strip(), "asr_wall_s": 0.0,
                    "source": "MishZor256/russian-asr-enrichment", "reference_source": reference,
                    "tier": r.get("tier"), "style_comparable": styled,
                }


def main(argv: Sequence[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("enrich", help="MishZor256/russian-asr-enrichment -> round-trip rows")
    e.add_argument("--parquet", type=Path, default=ENRICH_PARQUET)
    e.add_argument("--out", type=Path, required=True)
    e.add_argument("--domains", nargs="*", default=list(DEFAULT_DOMAINS),
                   help="dataset domains to keep (empty list = all); default: written-reference domains")
    e.add_argument("--reference", choices=("ref", "clean_text"), default="ref")
    e.add_argument("--per-domain", type=int, default=1500, help="rows sampled per domain (0 = all)")
    e.add_argument("--models", nargs="+", default=sorted(ENRICH_MODELS), choices=sorted(ENRICH_MODELS))
    e.add_argument("--seed", type=int, default=0)
    args = ap.parse_args(argv)

    records = _read_parquet(args.parquet, ENRICH_COLUMNS)
    rows = list(enrich_rows(records, args.domains or None, args.reference, args.per_domain, args.seed, args.models))
    if not rows:
        print("no rows selected", file=sys.stderr)
        return 1
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"wrote {len(rows)} rows ({len(rows) // len(args.models)} utterances x {len(args.models)} models) -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
