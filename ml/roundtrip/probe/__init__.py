"""Probe-set tooling for the Murmur ASR round trip (PLAN_V2 stage A).

Modules:
  textutil   script detection, word counting, normalisation (stdlib only)
  lexicon    hard-term lexicon: loading, validation, term matching in text
  schema     probe-sentence record checks (the binding JSONL interface)
  dedup      exact / fuzzy / template duplicate detection
  respell    pronunciation-friendly ``tts_text`` from the lexicon
  teacher    teacher-LLM client (Responses or chat completions) with retries (env-configured)
  prompt     loads the generation prompt blocks from gen_prompt.md
  plan       balanced, seeded slot planning for generation
  build_probe     src/*.txt -> terms.jsonl + seed.jsonl
  validate_probe  CLI validator + coverage report
  gen_probe       CLI teacher-driven scaling of the probe set

Everything here is stdlib-only (pytest for the tests).
"""
