"""Error mining over ASR round-trip results and the mined-noise sampler.

Modules:
  norm        tokenisation, style folding, number words <-> digits (ru, en)
  translit    Latin <-> Cyrillic transliteration and a cross-script phonetic key
  align       style-aware word/phrase alignment of intended text vs raw ASR hypothesis
  mine_errors round-trip JSONL -> profiles.json, confusions.jsonl, term_manglings.jsonl, REPORT.md
  noise       mined profile + tables -> simulated raw hypothesis; calibration against the real run
  adapters    public datasets with real ASR hypotheses -> round-trip JSONL rows

Standard library only, except `adapters` (pyarrow, imported lazily).
"""
