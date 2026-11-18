"""Mined-noise sampler: clean intended text -> simulated raw hypothesis of a given ASR model.

It replays what mining measured for that model (ml/roundtrip/mine/mine_errors.py outputs):

  terms     the model's observed manglings of that term (sampled by count, exact form at the observed rate);
            unseen terms fall back to the model's Latin-vs-Cyrillic habit, a phonetic transliteration
            ("ClickHouse" -> "клик хаус") and, at the observed term error rate, a garbled variant
  numbers   digits kept or spelled out ("10:30" -> "десять тридцать") at the observed rate
  words     substitutions (observed confusions first, else a near-miss perturbation), deletions and
            insertions at the observed per-word rates
  style     lowercase / first-letter casing, punctuation presence, commas, terminal marks, ё->е
  failures  empty output and hallucinated tails at the observed rates

`severity` scales the error part only (substitutions, deletions, insertions, garbled terms, tails, empty
output): 0 = style only, 1 = as mined, 2 = roughly twice the errors. Style is never scaled.

Deterministic: the same (text, model, seed, severity) gives the same output.

  python -m ml.roundtrip.mine.noise sample --mined ml/roundtrip/samples/mined/fixture \\
      --asr-model t-one@onnx --text "Созвон с Laura по Kubernetes в 10:30."
  python -m ml.roundtrip.mine.noise simulate --mined DIR --in probes.jsonl --out sim.jsonl --all-models
  python -m ml.roundtrip.mine.noise calibrate --rows real_roundtrip.jsonl --mined DIR --out DIR/calibration
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence

from .align import ERROR_KINDS, classify, locate_terms
from .mine_errors import MinedTables, guess_lang, mine_rows, read_jsonl, write_outputs
from .norm import (Token, cardinal_word_run, fold, is_number_word, is_numeric_token, script_of, spell_numeric_token,
                   tokenize)
from .translit import camel_parts, is_acronym, lat2cyr

_EDGE_PUNCT = re.compile(r"[.,!?;:…—–\-\"«»„“”'()\[\]{}]")
_FALLBACK_INS = {"cyr": ["и", "а", "в", "ну", "вот", "это"], "lat": ["the", "a", "and", "uh", "to", "it"]}
_VOWEL_SWAPS_CYR = {"о": "а", "а": "о", "е": "и", "и": "е", "я": "е", "э": "е", "ы": "и"}
_VOICING_CYR = {"б": "п", "п": "б", "в": "ф", "ф": "в", "г": "к", "к": "г", "д": "т", "т": "д", "з": "с",
                "с": "з", "ж": "ш", "ш": "ж"}
_ENDINGS_CYR = ["у", "ю", "ы", "и", "е", "а", "ой", "ом", "ем", "ть"]
# function words that ASR confuses with each other (fallback when a short word must be substituted)
_SHORT_SWAPS = {
    "в": ["во", "и", "на"], "во": ["в"], "и": ["а", "в"], "а": ["и", "о"], "на": ["но", "а"], "не": ["ни", "и"],
    "с": ["со", "из"], "со": ["с"], "к": ["ко", "и"], "по": ["па", "под"], "о": ["об", "а"], "у": ["в"],
    "что": ["чтобы", "то"], "это": ["то", "эта"], "the": ["a", "that"], "a": ["the", "uh"], "to": ["the", "do"],
    "in": ["and", "on"], "on": ["in", "an"], "and": ["in", "an"], "of": ["off", "a"], "it": ["that", "is"],
    "is": ["was", "his"], "for": ["four", "from"], "at": ["that", "it"],
}
_VOWEL_SWAPS_LAT = {"a": "e", "e": "a", "i": "e", "o": "u", "u": "o", "y": "i"}


def seed_for(*parts: Any) -> int:
    """Stable 64-bit seed from arbitrary parts (Python's hash() is salted per process)."""
    h = hashlib.sha256("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()
    return int(h[:16], 16)


def _weighted(rng: random.Random, items: Sequence[tuple[Any, float]]) -> Any:
    total = sum(w for _, w in items)
    x = rng.random() * total
    for it, w in items:
        x -= w
        if x < 0:
            return it
    return items[-1][0]


def perturb_word(word: str, rng: random.Random) -> str:
    """A plausible near-miss of `word` in its own script (vowel reduction, devoicing, ending change...)."""
    lower = word.lower()
    script = script_of(word)
    for _ in range(8):
        w = list(lower)
        if script == "cyr":
            op = rng.randrange(5)
            idx = [i for i, c in enumerate(w) if c in _VOWEL_SWAPS_CYR]
            if op == 0 and idx:
                i = rng.choice(idx)
                w[i] = _VOWEL_SWAPS_CYR[w[i]]
            elif op == 1 and w and w[-1] in _VOICING_CYR:
                w[-1] = _VOICING_CYR[w[-1]]
            elif op == 2 and len(w) > 4:
                cut = rng.choice((1, 2))
                w = w[:-cut] + list(rng.choice(_ENDINGS_CYR))
            elif op == 3:
                dbl = [i for i in range(1, len(w)) if w[i] == w[i - 1]]
                soft = [i for i, c in enumerate(w) if c in "ьъ"]
                if dbl:
                    del w[rng.choice(dbl)]
                elif soft:
                    del w[rng.choice(soft)]
                else:
                    continue
            elif op == 4 and len(w) > 5:
                del w[rng.randrange(1, len(w) - 1)]
            else:
                continue
        else:
            op = rng.randrange(4)
            idx = [i for i, c in enumerate(w) if c in _VOWEL_SWAPS_LAT]
            if op == 0 and idx:
                i = rng.choice(idx)
                w[i] = _VOWEL_SWAPS_LAT[w[i]]
            elif op == 1 and len(w) > 3 and w[-1] == "s":
                w = w[:-1]
            elif op == 2:
                dbl = [i for i in range(1, len(w)) if w[i] == w[i - 1]]
                if not dbl:
                    continue
                del w[rng.choice(dbl)]
            elif op == 3 and len(w) > 4:
                del w[rng.randrange(1, len(w) - 1)]
            else:
                continue
        out = "".join(w)
        if out != lower and out:
            return _match_case(word, out)
    return word


def _match_case(src: str, out: str) -> str:
    if src.isupper() and len(src) > 1:
        return out.upper()
    if src[:1].isupper():
        return out[:1].upper() + out[1:]
    return out


@dataclass
class Simulation:
    hypothesis: str
    events: list[str] = field(default_factory=list)


@dataclass
class _Piece:
    text: str
    lead: str = ""
    trail: str = ""
    kind: str = "word"  # word | term | number


def _speech_group(lang: str) -> str:
    """Spoken language of a context: "en", or "ru" for Russian and mixed (Russian speech with Latin terms)."""
    return "en" if lang == "en" else "ru"


class NoiseModel:
    """Noise sampler backed by mined tables (profiles + confusions + term manglings)."""

    def __init__(self, profiles: dict[str, dict], confusions: Iterable[dict], manglings: Iterable[dict]):
        self.profiles = profiles
        self._conf: dict[str, dict[str, list[tuple[tuple[str, str], int]]]] = defaultdict(dict)
        tmp: dict[str, dict[str, Counter]] = defaultdict(lambda: defaultdict(Counter))
        self._sub_vocab: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
        for c in confusions:
            if c.get("in_term") or c["kind"] not in ("sub", "sub_near", "del"):
                continue
            tmp[c["asr_model"]][c["ref"]][(c["hyp"], c["kind"])] += c["count"]
            if c["kind"] == "sub" and c["hyp"]:
                self._sub_vocab[c["asr_model"]][script_of(c["hyp"])].append(c["hyp"])
        for m, d in tmp.items():
            self._conf[m] = {w: sorted(cnt.items(), key=lambda kv: (-kv[1], kv[0])) for w, cnt in d.items()}
        self._terms: dict[str, dict[str, dict[str, Counter]]] = defaultdict(lambda: defaultdict(dict))
        for t in manglings:
            cnt = Counter({v["hyp"]: v["count"] for v in t["variants"]})
            by_lang = self._terms[t["asr_model"]][t["term"]]
            by_lang[t["lang_context"]] = by_lang.get(t["lang_context"], Counter()) + cnt

    # ------------------------------------------------------------------ construction

    @classmethod
    def load(cls, mined_dir: str | Path) -> "NoiseModel":
        d = Path(mined_dir)
        profiles = json.loads((d / "profiles.json").read_text(encoding="utf-8"))
        return cls(profiles, read_jsonl_plain(d / "confusions.jsonl"), read_jsonl_plain(d / "term_manglings.jsonl"))

    @classmethod
    def from_tables(cls, tables: MinedTables) -> "NoiseModel":
        return cls(tables.profiles, tables.confusions, tables.term_manglings)

    def models(self) -> list[str]:
        return sorted(self.profiles)

    # ------------------------------------------------------------------ helpers

    def _p(self, model: str, *path: str, default: float = 0.0) -> float:
        cur: Any = self.profiles[model]
        for k in path:
            if not isinstance(cur, dict) or k not in cur:
                return default
            cur = cur[k]
        return default if cur is None else float(cur)

    def _term_variant(self, term: str, model: str, lang: str, rng: random.Random, severity: float,
                      events: list[str]) -> str:
        """One rendering of `term`: a recognition error with the mined term-error probability (x severity),
        otherwise a style-only variant (exact, case, transliteration) in the model's observed proportions."""
        seen = self._terms.get(model, {}).get(term)
        # borrow manglings only from the same spoken language: a term heard inside English speech is not
        # rendered like the same term inside Russian speech ("LoRA" -> "лора" is a Russian-context mangling)
        group = _speech_group(lang)
        counter = (seen.get(lang) or sum((c for l, c in seen.items() if _speech_group(l) == group), Counter())
                   if seen else Counter())
        if counter:
            n = sum(counter.values())
            ranked = sorted(counter.items(), key=lambda kv: (-kv[1], kv[0]))
            errors = [(h, float(c)) for h, c in ranked if _is_error_variant(term, h)]
            styles = [(h, float(c)) for h, c in ranked if not _is_error_variant(term, h)]
            p_err = min(1.0, sum(c for _, c in errors) / n * severity)
            if rng.random() < p_err:
                # above severity 1 part of the error draws come from the generic garbler, for variety
                if errors and (severity <= 1.0 or rng.random() < 1.0 / severity):
                    v = _weighted(rng, errors)
                    events.append(f"term:{term}->{v or '∅'}:observed_error")
                    return v
                v = self._fallback_term(term, model, lang, rng, force_error=True)
                events.append(f"term:{term}->{v or '∅'}:garbled")
                return v
            if styles:
                v = _weighted(rng, styles)
            else:
                v = self._fallback_term(term, model, lang, rng, force_error=False)
            if v != term:
                events.append(f"term:{term}->{v}:observed_style")
            return v
        stats = self.profiles[model].get("terms", {})
        bl = stats.get("by_lang", {}).get(lang) or stats
        err = bl.get("error_rate") or 0.0
        force = rng.random() < min(1.0, err * severity)
        v = self._fallback_term(term, model, lang, rng, force_error=force)
        if v != term:
            events.append(f"term:{term}->{v or '∅'}:{'garbled' if force else 'unseen_style'}")
        return v

    def _fallback_term(self, term: str, model: str, lang: str, rng: random.Random, force_error: bool) -> str:
        """Style-only rendering of an unseen term from the model's Latin and casing habits; garbled on request.

        Cyrillic terms are returned as written: ё loss, lowercasing and hyphen splitting are applied to the whole
        hypothesis later, at the model's mined rates.
        """
        v = term
        if script_of(term) in ("lat", "mixed"):
            lb = self.profiles[model].get("latin", {})
            keep = (lb.get("by_lang", {}).get(lang) or {}).get("kept_latin_rate")
            if keep is None:
                keep = lb.get("kept_latin_rate")
            if keep is None:
                keep = 1.0 if lang == "en" else 0.5
            if lang == "en":
                keep = max(keep, 0.9)
            if rng.random() < keep:
                # how often a correctly recognised term keeps its exact written form (vs case/separator loss)
                tb = self.profiles[model].get("terms", {}).get("by_lang", {}).get(lang) or {}
                if tb.get("folded_rate"):
                    proper = min(1.0, (tb.get("exact_rate") or 0.0) / tb["folded_rate"])
                else:
                    proper = self._p(model, "casing", "proper_case_kept_rate", default=1.0)
                lower = any(c.isupper() for c in term) and rng.random() >= proper
                parts = camel_parts(term)
                if len(parts) > 1 and not is_acronym(term) and rng.random() < _CAMEL_SPLIT_P:
                    v = " ".join(parts)
                if lower:
                    v = v.lower()
            else:
                v = lat2cyr(term, split_camel=rng.random() < 0.5, acronym_style="en" if rng.random() < 0.8 else "ru")
        if force_error:
            # garble until the miner itself would count it as a recognition error: a single vowel swap in a
            # transliteration ("кубернетез") is still a faithful transliteration, not an error
            base = v
            for _ in range(_MAX_GARBLE_STEPS):
                words = v.split()
                if not words:
                    break
                i = rng.randrange(len(words))
                words[i] = perturb_word(words[i], rng)
                v = " ".join(words)
                if _is_error_variant(term, v):
                    break
            if not _is_error_variant(term, v):
                # still within the aligner's transliteration tolerance: keep only the onset of the term
                # (a truncated, half-heard word), and as a last resort drop it entirely
                v = v[: max(1, len(v) // 2)].rstrip()
                if v == base or not _is_error_variant(term, v):
                    v = ""
        return v

    # ------------------------------------------------------------------ main entry

    def simulate(self, text: str, asr_model: str, terms: Sequence[dict] | None = None, lang: str | None = None,
                 seed: int = 0, severity: float = 1.0) -> Simulation:
        if asr_model not in self.profiles:
            raise KeyError(f"no mined profile for {asr_model!r}; have: {', '.join(self.models())}")
        if severity < 0:
            raise ValueError("severity must be >= 0")
        rng = random.Random(seed)
        lang = lang or guess_lang(text)
        spoken_lang = "en" if lang == "en" else "ru"
        prof = self.profiles[asr_model]
        sampler = prof.get("sampler", {})
        events: list[str] = []

        if rng.random() < min(0.95, self._p(asr_model, "hallucination", "empty_rate") * severity):
            return Simulation("", ["empty"])

        tokens = tokenize(text)
        spans = {r0: (r1, t) for t, r0, r1 in locate_terms(text, tokens, terms or [])}
        # unannotated Latin words in a Russian sentence behave like terms too
        covered = {i for r0, (r1, _) in spans.items() for i in range(r0, r1)}
        for i, tok in enumerate(tokens):
            if i not in covered and lang != "en" and script_of(tok.core) in ("lat", "mixed") \
                    and not is_numeric_token(tok.core):
                spans[i] = (i + 1, {"term": tok.core, "category": "auto_latin"})

        base_sub, base_del = sampler.get("p_sub", 0.0), sampler.get("p_del", 0.0)
        p_sub = base_sub * severity
        p_del = base_del * severity
        p_ins = sampler.get("p_ins", 0.0) * severity
        near_share = sampler.get("near_share", 0.5) or 0.5
        conf_words = sampler.get("confusable_words", {})
        conf = self._conf.get(asr_model, {})
        ins_vocab = [(w, float(c)) for w, c in sampler.get("ins_words", []) if w]
        spell_rate = self._p(asr_model, "digits", "spelled_rate", default=-1.0)
        if spell_rate < 0:
            # no digit numbers in the mined references: a model seen writing digits keeps them; a model that
            # heard spelled-out numbers and never wrote one as digits spells them; otherwise toss a coin
            if self._p(asr_model, "digits", "hyp_digit_rate", default=0.0) > 0:
                spell_rate = 0.0
            elif (self._p(asr_model, "digits", "word_numbers") >= MIN_WORD_NUMBERS_FOR_SPELLING
                  and self._p(asr_model, "digits", "words_to_digits_rate", default=1.0) == 0.0):
                spell_rate = 1.0
            else:
                spell_rate = 0.5

        words_to_digits = self._p(asr_model, "digits", "words_to_digits_rate", default=0.0)

        # deletion weights: short words go first, renormalised so the expected rate stays p_del
        plain = [i for i in range(len(tokens)) if i not in covered and i not in spans
                 and not is_numeric_token(tokens[i].core)]
        wts = {i: (1.6 if len(tokens[i].core) <= 2 else 1.0) for i in plain}
        wsum = sum(wts.values()) or 1.0

        pieces: list[_Piece] = []
        i = 0
        while i < len(tokens):
            tok = tokens[i]
            if i in spans:
                r1, term = spans[i]
                surface = text[tokens[i].start:tokens[r1 - 1].end]
                v = self._term_variant(term["term"] if term["term"] == surface else surface, asr_model, lang, rng,
                                       severity, events)
                pieces.append(_Piece(v, tokens[i].lead, tokens[r1 - 1].trail, "term"))
                i = r1
                continue
            run_start = is_number_word(tok.core) and not (i > 0 and (i - 1) not in covered
                                                          and is_number_word(tokens[i - 1].core))
            if words_to_digits > 0 and run_start:
                # a spelled-out number: one decision for the whole run ("двадцать пять" -> "25" or kept)
                j = i
                while j < len(tokens) and j not in covered and is_number_word(tokens[j].core):
                    j += 1
                digits = cardinal_word_run([t.core for t in tokens[i:j]])
                if digits is not None and rng.random() < words_to_digits:
                    events.append(f"num:{' '.join(t.core for t in tokens[i:j])}->{digits}")
                    pieces.append(_Piece(digits, tok.lead, tokens[j - 1].trail, "number"))
                    i = j
                    continue
            if is_numeric_token(tok.core):
                text_out = tok.core
                if rng.random() < spell_rate:
                    nxt = tokens[i + 1].core if i + 1 < len(tokens) else ""
                    spoken = spell_numeric_token(tok.core, spoken_lang, nxt, variant=rng.randrange(3))
                    if spoken:
                        text_out = spoken
                pieces.append(_Piece(text_out, tok.lead, tok.trail, "number"))
                i += 1
                continue
            w = fold(tok.core)
            specific = conf.get(w)
            n_ref = conf_words.get(w, 0)
            if specific and n_ref:
                # word-specific rates, smoothed toward the model's base rates (alpha pseudo-observations)
                sub_c = sum(c for (_h, k), c in specific if k != "del")
                del_c = sum(c for (_h, k), c in specific if k == "del")
                alpha = 5.0
                ps = min(0.9, (sub_c + alpha * base_sub) / (n_ref + alpha) * severity)
                pd = min(0.9, (del_c + alpha * base_del) / (n_ref + alpha) * severity)
            else:
                pd = min(0.9, p_del * len(plain) * wts.get(i, 1.0) / wsum) if plain else 0.0
                ps = p_sub
            r = rng.random()
            if r < pd:
                events.append(f"del:{tok.core}")
                if pieces and tok.trail:
                    pieces[-1].trail += tok.trail
                i += 1
                continue
            if r < pd + ps:
                target = None
                if specific:
                    subs = [((h, k), c) for (h, k), c in specific if k != "del"]
                    if subs:
                        target = _weighted(rng, [(h, float(c)) for (h, _k), c in subs])
                if target is None:
                    vocab = [v for v in sorted(set(self._sub_vocab.get(asr_model, {}).get(script_of(tok.core), [])))
                             if abs(len(v) - len(tok.core)) <= 2 and fold(v) != w]
                    if vocab and rng.random() >= near_share:
                        target = rng.choice(vocab)
                    elif w in _SHORT_SWAPS:
                        target = rng.choice(_SHORT_SWAPS[w])
                    else:
                        target = perturb_word(tok.core, rng)
                target = _match_case(tok.core, target)
                events.append(f"sub:{tok.core}->{target}")
                pieces.append(_Piece(target, tok.lead, tok.trail))
            else:
                pieces.append(_Piece(tok.core, tok.lead, tok.trail))
            if rng.random() < p_ins:
                script = "lat" if lang == "en" else "cyr"
                pool = [(w2, c) for w2, c in ins_vocab if script_of(w2) == script] or \
                       [(w2, 1.0) for w2 in _FALLBACK_INS[script]]
                ins = _weighted(rng, pool)
                events.append(f"ins:{ins}")
                pieces.append(_Piece(ins))
            i += 1

        hyp = self._render(pieces, asr_model, rng, events)
        tails = [(t, float(c)) for t, c in prof.get("hallucination", {}).get("tails", [])]
        if tails and rng.random() < min(0.95, self._p(asr_model, "hallucination", "tail_rate") * severity):
            tail = _weighted(rng, tails)
            if "style:no_punct" in events:
                tail = _EDGE_PUNCT.sub("", tail).strip()
            if "style:lowercase" in events:
                tail = tail.lower()
            if hyp and not tail[:1].isupper() and hyp[-1:] in ".!?…":
                tail = tail[:1].upper() + tail[1:]
            events.append(f"tail:{tail}")
            hyp = f"{hyp} {tail}".strip()
        return Simulation(hyp, events)

    def _render(self, pieces: list[_Piece], model: str, rng: random.Random, events: list[str]) -> str:
        punct_any = self._p(model, "punctuation", "any_rate", default=1.0)
        keep_punct = rng.random() < punct_any
        # the mined mark rates are over all utterances; given that this one has punctuation at all they are
        # higher by 1/any_rate (otherwise utterances without any punctuation would be counted twice)
        cond = 1.0 / punct_any if punct_any > 0 else 1.0
        comma = min(1.0, self._p(model, "punctuation", "comma_ratio", default=1.0) * cond)
        other = min(1.0, self._p(model, "punctuation", "other_ratio", default=1.0) * cond)
        terminal = min(1.0, self._p(model, "punctuation", "terminal_kept_rate", default=1.0) * cond)
        question = min(1.0, self._p(model, "punctuation", "question_kept_rate", default=1.0) * cond)
        if not keep_punct:
            events.append("style:no_punct")
        out: list[str] = []
        last = len(pieces) - 1
        for k, p in enumerate(pieces):
            text = p.text
            if not keep_punct:
                lead, trail = "", ""
                text = re.sub(r"(?<=\w)[\-/](?=\w)", " ", text)
            else:
                lead = "".join(c for c in p.lead if rng.random() < other)
                trail = ""
                for c in p.trail:
                    if c == ",":
                        trail += c if rng.random() < comma else ""
                    elif c in ".!?…" and k == last:
                        if c == "?":
                            trail += "?" if rng.random() < question else "."
                        else:
                            trail += c if rng.random() < terminal else ""
                    elif c in ".!?…":
                        trail += c
                    else:
                        trail += c if rng.random() < other else ""
            if text:
                out.append(lead + text + trail)
            elif trail.strip() and out:
                out[-1] += trail
        hyp = " ".join(out)
        hyp = re.sub(r"\s+", " ", hyp).strip()
        if not hyp:
            return hyp
        if rng.random() < self._p(model, "casing", "all_lower_rate"):
            hyp = hyp.lower()
            events.append("style:lowercase")
        else:
            # the recogniser decides the first letter's case itself, whatever the intended text starts with
            # (reference fragments may start lower-case); words with inner capitals ("iPhone") are left alone
            first_up = self._p(model, "casing", "first_upper_rate", default=1.0)
            first = hyp.split(" ", 1)[0]
            if rng.random() < first_up:
                if first[1:] == first[1:].lower():
                    hyp = hyp[:1].upper() + hyp[1:]
            elif not (first.isupper() and len(first) > 1):
                hyp = hyp[:1].lower() + hyp[1:]
        yo = self.profiles[model].get("yo", {}).get("kept_rate")
        if yo is not None and "ё" in hyp.lower() and rng.random() >= yo:
            hyp = hyp.replace("ё", "е").replace("Ё", "Е")
        return hyp


_MAX_GARBLE_STEPS = 6
_CAMEL_SPLIT_P = 0.25  # unseen CamelCase term kept in Latin but split into words ("Click House"); no data yet


def _is_error_variant(term: str, variant: str) -> bool:
    """Would the aligner classify `term` -> `variant` as a recognition error (not a style difference)?"""
    ref = tuple(t.core for t in tokenize(term))
    hyp = tuple(t.core for t in tokenize(variant))
    if not ref or not hyp:
        return True
    res = classify(ref, hyp)
    return res is None or res[0] in ERROR_KINDS


def read_jsonl_plain(path: str | Path) -> list[dict]:
    out = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


# ----------------------------------------------------------------------------- simulate rows

_PASSTHROUGH = ("style_comparable", "hyp_normalised", "domain")


def simulate_rows(model: NoiseModel, rows: Sequence[dict], asr_models: Sequence[str] | None, seed: int,
                  severity: float, repeats: int = 1, use_row_model: bool = False) -> list[dict]:
    """Simulated round-trip rows (interface 2 with tts_system='sim'); hypothesis is the sampler's output."""
    out: list[dict] = []
    for row in rows:
        pid = row.get("probe_id") or row.get("id") or hashlib.sha256(row["text"].encode()).hexdigest()[:12]
        models = [row["asr_model"]] if use_row_model else list(asr_models or [])
        for m in models:
            if m not in model.profiles:
                continue
            for rep in range(repeats):
                s = seed_for(seed, pid, m, rep, severity, row.get("tts_voice", ""), row.get("channel", ""))
                sim = model.simulate(row["text"], m, row.get("terms"), row.get("lang"), seed=s, severity=severity)
                out.append({
                    "probe_id": pid, "lang": row.get("lang") or guess_lang(row["text"]), "text": row["text"],
                    "terms": row.get("terms") or [], "tts_system": "sim", "tts_voice": f"noise/sev{severity:g}",
                    "channel": "sim", "audio": "", "audio_s": 0.0, "asr_model": m, "hypothesis": sim.hypothesis,
                    "asr_wall_s": 0.0, "sim_seed": s, "sim_events": sim.events,
                    # keep the source row's style-comparability so real and simulated statistics cover the same rows
                    **{k: row[k] for k in _PASSTHROUGH if k in row},
                })
    return out


# ----------------------------------------------------------------------------- calibration

CAL_METRICS: list[tuple[str, tuple[str, ...], float, tuple[str, ...]]] = [
    # name, path in profile, absolute tolerance, path of the metric's support count in the real profile
    ("wer", ("wer",), 0.05, ("n_ref_words",)),
    ("wer_style_aware", ("wer_style_aware",), 0.03, ("n_ref_words",)),
    ("cer", ("cer",), 0.05, ("n_ref_words",)),
    ("term_exact_rate", ("terms", "exact_rate"), 0.08, ("terms", "n")),
    ("term_error_rate", ("terms", "error_rate"), 0.08, ("terms", "n")),
    ("all_lower_rate", ("casing", "all_lower_rate"), 0.08, ("casing", "n_rows")),
    ("first_upper_rate", ("casing", "first_upper_rate"), 0.08, ("casing", "n_rows")),
    ("punct_any_rate", ("punctuation", "any_rate"), 0.08, ("punctuation", "n_rows")),
    ("terminal_kept_rate", ("punctuation", "terminal_kept_rate"), 0.1, ("punctuation", "n_rows")),
    ("digits_kept_rate", ("digits", "kept_digits_rate"), 0.1, ("digits", "ref_numbers")),
    ("words_to_digits_rate", ("digits", "words_to_digits_rate"), 0.1, ("digits", "word_numbers")),
    ("latin_kept_rate", ("latin", "kept_latin_rate"), 0.1, ("latin", "ref_latin_words")),
    ("tail_rate", ("hallucination", "tail_rate"), 0.05, ("n",)),
    ("empty_rate", ("hallucination", "empty_rate"), 0.05, ("n",)),
]
# a gap is judged only when the real value rests on at least this many observations (rows, words, numbers);
# below it the binomial noise of the real rate alone exceeds the tolerances above
CAL_MIN_SUPPORT = 10
MIN_WORD_NUMBERS_FOR_SPELLING = 3


def _get(p: dict, path: Sequence[str]) -> float | None:
    cur: Any = p
    for k in path:
        if not isinstance(cur, dict) or k not in cur:
            return None
        cur = cur[k]
    return None if cur is None else float(cur)


def compare_profiles(real: dict[str, dict], sim: dict[str, dict],
                     mined: dict[str, dict] | None = None) -> dict[str, dict[str, dict]]:
    """Per model and metric: real vs simulated value, gap and tolerance check.

    With `mined` (the profiles the sampler was built from, out-of-sample runs only) each entry also carries
    "mined" and "sampler_gap" = sim - mined, which separates the sampler's own fidelity from the
    difference between the mining rows and the held-out rows.
    """
    out: dict[str, dict[str, dict]] = {}
    for m in sorted(real):
        if m not in sim:
            continue
        rows = {}
        for name, path, tol, support_path in CAL_METRICS:
            a, b = _get(real[m], path), _get(sim[m], path)
            support = int(_get(real[m], support_path) or 0)
            gap = None if a is None or b is None else round(b - a, 4)
            judged = gap is not None and support >= CAL_MIN_SUPPORT
            rows[name] = {"real": a, "sim": b, "gap": gap, "tol": tol, "support": support,
                          "ok": abs(gap) <= tol if judged else None}
            if mined is not None:
                c = _get(mined.get(m, {}), path)
                rows[name]["mined"] = c
                rows[name]["sampler_gap"] = None if b is None or c is None else round(b - c, 4)
        out[m] = rows
    return out


def render_calibration(cmp: dict[str, dict[str, dict]], title: str, note: str) -> str:
    L = [f"# {title}", "", note, "",
         "Gap = simulated − real. A metric is flagged (⚠) when |gap| exceeds its tolerance. Values in brackets "
         f"rest on fewer than {CAL_MIN_SUPPORT} real observations and are shown but not judged.", ""]
    names = [n for n, _, _, _ in CAL_METRICS]
    L.append("| ASR model | " + " | ".join(names) + " |")
    L.append("|---|" + "---:|" * len(names))
    for m, rows in cmp.items():
        cells = []
        for n in names:
            r = rows[n]
            if r["real"] is None or r["sim"] is None:
                cells.append("–")
            elif r["ok"] is None:
                cells.append(f"({r['real']:.3f}→{r['sim']:.3f})")
            else:
                flag = "" if r["ok"] else " ⚠"
                cells.append(f"{r['real']:.3f}→{r['sim']:.3f}{flag}")
        L.append(f"| `{m}` | " + " | ".join(cells) + " |")
    flagged = [(m, n) for m, rows in cmp.items() for n, r in rows.items() if r["ok"] is False]
    L += ["", f"Flagged gaps: {len(flagged)} of {sum(1 for rows in cmp.values() for r in rows.values() if r['ok'] is not None)}."]
    for m, n in flagged:
        r = cmp[m][n]
        extra = ""
        if r.get("mined") is not None:
            extra = f"; mined-on rows {r['mined']:.3f}, sampler gap {r['sampler_gap']:+.3f}"
        L.append(f"- `{m}` {n}: real {r['real']:.3f}, sim {r['sim']:.3f} (gap {r['gap']:+.3f}, tol {r['tol']}, "
                 f"n={r['support']}{extra})")
    sampler_bad = [(m, n) for m, rows in cmp.items() for n, r in rows.items()
                   if r["ok"] is not None and r.get("sampler_gap") is not None and abs(r["sampler_gap"]) > r["tol"]]
    if any("mined" in r for rows in cmp.values() for r in rows.values()):
        L += ["", "Simulated held-out vs the rows the tables were mined from (different texts, so this mixes "
              "sampler error with the difference in text mix; indicative only): "
              f"{len(sampler_bad)} metric(s) outside tolerance."]
        for m, n in sampler_bad:
            r = cmp[m][n]
            L.append(f"- `{m}` {n}: mined {r['mined']:.3f}, sim {r['sim']:.3f} (gap {r['sampler_gap']:+.3f})")
    L.append("")
    return "\n".join(L)


SIM_ROWS_KEPT = 120  # simulated rows written next to the calibration report, for eyeballing


def _split_holdout(rows: Sequence[dict], frac: float, seed: int) -> tuple[list[dict], list[dict]]:
    train, test = [], []
    for r in rows:
        x = seed_for(seed, "holdout", r.get("probe_id", r["text"])) / 2 ** 64
        (test if x < frac else train).append(r)
    return train, test


def calibrate(real_rows: Sequence[dict], mined: MinedTables | None, out_dir: Path, seed: int, repeats: int,
              severity: float = 1.0, holdout_frac: float = 0.0, jobs: int = 1) -> dict[str, dict[str, dict]]:
    if holdout_frac > 0:
        train, test = _split_holdout(real_rows, holdout_frac, seed)
        mined = mine_rows(train, jobs=jobs)
        mined_profiles: dict[str, dict] | None = mined.profiles
        note = (f"Out-of-sample: tables mined on {len(train)} rows, simulated and compared on {len(test)} held-out "
                f"rows (by probe_id), {repeats} repeat(s), severity {severity:g}.")
        eval_rows = test
    else:
        assert mined is not None
        mined_profiles = None
        note = (f"In-sample: simulated over the same {len(real_rows)} rows the tables were mined from, "
                f"{repeats} repeat(s), severity {severity:g}.")
        eval_rows = list(real_rows)
    nm = NoiseModel.from_tables(mined)
    real_tables = mine_rows(eval_rows, jobs=jobs)
    sim_rows = simulate_rows(nm, eval_rows, None, seed, severity, repeats, use_row_model=True)
    sim_tables = mine_rows(sim_rows, jobs=jobs)
    cmp = compare_profiles(real_tables.profiles, sim_tables.profiles, mined_profiles)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "calibration.json").write_text(json.dumps(cmp, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                                              encoding="utf-8")
    (out_dir / "CALIBRATION.md").write_text(render_calibration(cmp, "Noise sampler calibration", note),
                                            encoding="utf-8")
    write_outputs(sim_tables, out_dir / "simulated_mined", "Mining of simulated hypotheses", ["simulated"])
    with open(out_dir / "simulated_rows.jsonl", "w", encoding="utf-8") as fh:
        for r in sim_rows[:SIM_ROWS_KEPT]:
            fh.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    return cmp


# ----------------------------------------------------------------------------- CLI

def main(argv: Sequence[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    s1 = sub.add_parser("sample", help="simulate one text and print it")
    s1.add_argument("--mined", required=True)
    s1.add_argument("--asr-model", required=True)
    s1.add_argument("--text", required=True)
    s1.add_argument("--terms", nargs="*", default=[], help="term surfaces present in the text")
    s1.add_argument("--lang", choices=("ru", "en", "mixed"))
    s1.add_argument("--seed", type=int, default=0)
    s1.add_argument("--severity", type=float, default=1.0)
    s1.add_argument("-n", type=int, default=1, help="number of samples (seeds seed..seed+n-1)")
    s1.add_argument("--events", action="store_true", help="also print the applied noise events")

    s2 = sub.add_parser("simulate", help="simulate hypotheses for a JSONL of texts (probe rows or any {text,...})")
    s2.add_argument("--mined", required=True)
    s2.add_argument("--in", dest="inp", required=True)
    s2.add_argument("--out", required=True)
    g = s2.add_mutually_exclusive_group(required=True)
    g.add_argument("--asr-model", nargs="+")
    g.add_argument("--all-models", action="store_true")
    s2.add_argument("--seed", type=int, default=0)
    s2.add_argument("--severity", type=float, default=1.0)
    s2.add_argument("--repeats", type=int, default=1)

    s3 = sub.add_parser("calibrate", help="simulate the real round trip's texts and compare statistics")
    s3.add_argument("--rows", nargs="+", required=True, help="real round-trip JSONL (interface 2)")
    s3.add_argument("--mined", help="mined tables of the same rows (in-sample check)")
    s3.add_argument("--holdout-frac", type=float, default=0.0,
                    help="mine on the other probes and compare on this fraction of probe_ids (out-of-sample)")
    s3.add_argument("--out", required=True)
    s3.add_argument("--seed", type=int, default=0)
    s3.add_argument("--repeats", type=int, default=5)
    s3.add_argument("--severity", type=float, default=1.0)
    s3.add_argument("--jobs", type=int, default=1)
    args = ap.parse_args(argv)

    if args.cmd == "sample":
        nm = NoiseModel.load(args.mined)
        terms = [{"term": t, "category": ""} for t in args.terms]
        for k in range(args.n):
            sim = nm.simulate(args.text, args.asr_model, terms, args.lang, seed=args.seed + k, severity=args.severity)
            print(sim.hypothesis)
            if args.events:
                print("   ", "; ".join(sim.events))
        return 0
    if args.cmd == "simulate":
        nm = NoiseModel.load(args.mined)
        rows = read_jsonl_plain(args.inp)
        for k, r in enumerate(rows):
            if "text" not in r:
                raise SystemExit(f"{args.inp}:{k + 1}: row has no 'text'")
        models = nm.models() if args.all_models else args.asr_model
        unknown = [m for m in models if m not in nm.profiles]
        if unknown:
            raise SystemExit(f"no mined profile for {unknown}; have: {', '.join(nm.models())}")
        out = simulate_rows(nm, rows, models, args.seed, args.severity, args.repeats)
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as fh:
            for r in out:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"wrote {len(out)} simulated rows -> {args.out}")
        return 0
    real = read_jsonl(args.rows)
    if args.holdout_frac <= 0 and not args.mined:
        raise SystemExit("calibrate needs --mined DIR (in-sample) or --holdout-frac F (out-of-sample)")
    mined = None
    if args.holdout_frac <= 0:
        nm_dir = Path(args.mined)
        profiles = json.loads((nm_dir / "profiles.json").read_text(encoding="utf-8"))
        mined = MinedTables(profiles, read_jsonl_plain(nm_dir / "confusions.jsonl"),
                            read_jsonl_plain(nm_dir / "term_manglings.jsonl"), len(real), 0)
    cmp = calibrate(real, mined, Path(args.out), args.seed, args.repeats, args.severity, args.holdout_frac, args.jobs)
    flagged = sum(1 for rows in cmp.values() for r in rows.values() if r["ok"] is False)
    total = sum(1 for rows in cmp.values() for r in rows.values() if r["ok"] is not None)
    print(f"calibration: {flagged}/{total} metrics outside tolerance -> {args.out}/CALIBRATION.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
