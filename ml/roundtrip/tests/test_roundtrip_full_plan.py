"""The full-run plan builder on the real probe set: coverage, shares, balance, determinism, early cut-off."""
from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip import full_plan as fp  # noqa: E402


@pytest.fixture(scope="module")
def built():
    probes = fp.load_probes([fp.PROBE_DIR / "seed.jsonl", fp.PROBE_DIR / "gen.jsonl"])
    lex = fp.load_lexicon(fp.PROBE_DIR / "terms.jsonl")
    chosen = fp.select(probes, lex, 1000, 13)
    ordered = fp.interleave(chosen, lex, 13)
    clips = fp.build_plan(ordered, 0.15, 0.20, 0.5, 40)
    return probes, lex, chosen, ordered, clips


def test_every_lexicon_entry_is_covered(built):
    probes, lex, chosen, _, _ = built
    assert len(chosen) == 1000 and len({r["id"] for r in chosen}) == 1000
    assert {t for r in chosen for t in fp.lex_terms(r, lex)} == set(lex)  # 725 of 725


def test_language_and_source_mix_follow_the_probe_set(built):
    probes, _, chosen, _, _ = built
    full = Counter(r["lang"] for r in probes)
    got = Counter(r["lang"] for r in chosen)
    for lang in fp.LANGS:
        assert abs(got[lang] / 1000 - full[lang] / len(probes)) < 0.03


def test_shares_and_voice_policy(built):
    _, _, chosen, _, clips = built
    n = len(chosen)
    assert abs((len(clips) - n) / n - 0.15) < 0.01  # second voices
    sents_noisy = {c["probe_id"] for c in clips if c["channel"] == "phone_noisy"}
    assert abs(len(sents_noisy) / n - 0.20) < 0.01
    assert abs(sum(fp.MEDIUM in c["asr"] for c in clips) / len(clips) - 0.5) < 0.01
    by_probe = {}
    for c in clips:
        by_probe.setdefault(c["probe_id"], []).append(c)
    for cs in by_probe.values():
        assert len({c["tts_system"] for c in cs}) == len(cs)  # a second voice is always another system
        assert len({c["channel"] for c in cs}) == 1  # the pair shares the channel
        first = cs[0]
        if first["lang"] != "en":
            assert first["tts_system"] == "supertonic3"  # Piper's Russian voices only as second voice
    voices = Counter(c["tts_voice"] for c in clips if c["tts_system"] == "supertonic3")
    assert max(voices.values()) - min(voices.values()) <= 1
    en_sys = Counter(c["tts_system"] for c in clips if c["lang"] == "en")
    assert max(en_sys.values()) - min(en_sys.values()) <= 0.05 * sum(en_sys.values())


def test_every_prefix_of_waves_is_balanced(built):
    _, _, _, _, clips = built
    total = Counter(c["lang"] for c in clips)
    for w in (0, 2, 5):
        pre = [c for c in clips if c["wave"] <= w]
        got = Counter(c["lang"] for c in pre)
        for lang in fp.LANGS:
            assert abs(got[lang] / len(pre) - total[lang] / len(clips)) < 0.06
        assert len({c["tts_voice"] for c in pre if c["tts_system"] == "supertonic3"}) == 10


def test_deterministic(built):
    _, _, _, _, clips = built
    probes = fp.load_probes([fp.PROBE_DIR / "seed.jsonl", fp.PROBE_DIR / "gen.jsonl"])
    lex = fp.load_lexicon(fp.PROBE_DIR / "terms.jsonl")
    again = fp.build_plan(fp.interleave(fp.select(probes, lex, 1000, 13), lex, 13), 0.15, 0.20, 0.5, 40)
    assert again == clips
