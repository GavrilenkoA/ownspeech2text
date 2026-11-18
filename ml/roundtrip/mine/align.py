"""Style-aware alignment of the intended text against a raw ASR hypothesis.

One weighted edit-distance DP over word tokens with phrase blocks: besides 1:1 substitutions it may map one
reference word onto up to 6 hypothesis words ("ClickHouse" -> "клик хаус", "CI/CD" -> "си ай си ди",
"2025" -> "две тысячи двадцать пять") or up to 4 reference words onto one hypothesis word
("Hugging Face" -> "хаггингфейс"). Every aligned pair gets a kind:

  style (not a recognition error; the cleanup LLM still has to undo it)
    match     identical surface
    case      differs only in letter case
    yo        differs only in ё/е or apostrophe form (and maybe case)
    punct     differs only in separators or word splitting ("e-mail" / "email", "CI/CD" / "ci cd")
    number    same number written as digits vs words, or different digit formatting ("10:30" / "десять тридцать")
    translit  same term in the other script, phonetically equivalent ("Kubernetes" / "кубернетис")
  error
    sub_near  phonetically or orthographically close, but not the same word ("Kafka" / "кофка")
    sub       unrelated substitution
    del, ins  deletion, insertion

`wer_style` counts only errors; `raw_wer()` is the conventional normalised WER (case and punctuation
folded, nothing else forgiven) for comparison with published numbers.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from functools import lru_cache
from typing import Iterable, Sequence

from .norm import (CONNECTORS, Token, fold, is_number_word, is_numeric_token, number_groups, numbers_equivalent, script_of,
                   squash, tokenize)
from .translit import (acronym_readings, camel_parts, char_sim, cyr2lat, is_acronym, lat2cyr, levenshtein,
                       phonetic_key, term_sim)

STYLE_KINDS = ("match", "case", "yo", "punct", "number", "translit")
ERROR_KINDS = ("sub_near", "sub", "del", "ins")
ALL_KINDS = STYLE_KINDS + ERROR_KINDS

TRANSLIT_MIN_SIM = 0.8   # cross-script phonetic similarity to count as the same term
TRANSLIT_MIN_SPELL = 0.65 # ... and spelling similarity to its expected transliteration
NEAR_MIN_SIM = 0.5       # 1:1 pair above this is a near miss, below it an unrelated substitution
BLOCK_MIN_SIM = 0.6      # a multi-word block must be at least this similar to exist at all
MAX_REF_BLOCK = 4
MAX_HYP_BLOCK = 6


@dataclass(frozen=True)
class Op:
    kind: str
    r0: int
    r1: int
    h0: int
    h1: int
    sim: float = 1.0

    @property
    def n_ref(self) -> int:
        return self.r1 - self.r0

    @property
    def n_hyp(self) -> int:
        return self.h1 - self.h0

    @property
    def is_error(self) -> bool:
        return self.kind in ERROR_KINDS

    @property
    def errors(self) -> int:
        """Word errors this op contributes (a 1:3 garbled block costs 3, like 1 sub + 2 ins)."""
        if self.kind == "del":
            return self.n_ref
        if self.kind == "ins":
            return self.n_hyp
        if self.kind in ("sub", "sub_near"):
            return max(self.n_ref, self.n_hyp)
        return 0


@dataclass
class Alignment:
    ref: list[Token]
    hyp: list[Token]
    ops: list[Op]
    ref_text: str = ""
    hyp_text: str = ""

    @property
    def n_ref(self) -> int:
        return len(self.ref)

    @property
    def errors(self) -> int:
        return sum(op.errors for op in self.ops)

    @property
    def wer_style(self) -> float:
        return self.errors / max(1, self.n_ref)

    def ref_span(self, op: Op) -> str:
        return " ".join(t.core for t in self.ref[op.r0:op.r1])

    def hyp_span(self, op: Op) -> str:
        return " ".join(t.core for t in self.hyp[op.h0:op.h1])

    def hyp_surface(self, h0: int, h1: int) -> str:
        """Raw hypothesis text covering tokens h0..h1 (keeps internal hyphens/spaces as written)."""
        if h0 >= h1:
            return ""
        return self.hyp_text[self.hyp[h0].start:self.hyp[h1 - 1].end]

    def kind_counts(self) -> dict[str, int]:
        out = {k: 0 for k in ALL_KINDS}
        for op in self.ops:
            out[op.kind] += max(op.n_ref, op.n_hyp)
        return out

    def trailing_insertions(self) -> tuple[int, int]:
        """Hypothesis token range inserted after the last aligned reference word (hallucination tail)."""
        i = len(self.ops)
        while i > 0 and self.ops[i - 1].kind == "ins":
            i -= 1
        if i == len(self.ops):
            return (len(self.hyp), len(self.hyp))
        return (self.ops[i].h0, self.ops[-1].h1)

    def leading_insertions(self) -> tuple[int, int]:
        i = 0
        while i < len(self.ops) and self.ops[i].kind == "ins":
            i += 1
        if i == 0:
            return (0, 0)
        return (0, self.ops[i - 1].h1)


# ----------------------------------------------------------------------------- pair classification

def _scripts(words: Sequence[str]) -> set[str]:
    out = set()
    for w in words:
        s = script_of(w)
        if s == "mixed":
            out.update(("lat", "cyr"))
        elif s in ("lat", "cyr"):
            out.add(s)
    return out


@lru_cache(maxsize=262144)
def classify(ref: tuple[str, ...], hyp: tuple[str, ...]) -> tuple[str, float] | None:
    """Kind and similarity of aligning ref words with hyp words; None if a multi-word block is implausible."""
    single = len(ref) == 1 and len(hyp) == 1
    if single:
        r, h = ref[0], hyp[0]
        if r == h:
            return ("match", 1.0)
        if r.casefold() == h.casefold():
            return ("case", 1.0)
        if fold(r) == fold(h):
            return ("yo", 1.0)
    rj, hj = " ".join(ref), " ".join(hyp)
    ng_r, ng_h = number_groups(ref), number_groups(hyp)
    if ng_r is not None and ng_h is not None and numbers_equivalent(ng_r, ng_h):
        return ("number", 1.0)
    rs, hs = squash(rj.replace(" ", "")), squash(hj.replace(" ", ""))
    if rs and rs == hs:
        return ("punct", 1.0)
    if (ng_r is None) != (ng_h is None) and not single:
        # a number on one side only can never be a multi-word phrase match
        return None
    sr, sh = _scripts(ref), _scripts(hyp)
    cross = bool(sr) and bool(sh) and sr != sh
    if cross:
        sim = term_sim(rj, hj)
    else:
        sim = max(char_sim(rj, hj), 0.9 * term_sim(rj, hj)) if not single else char_sim(rj, hj)
    if not single:
        ka, kb = phonetic_key(rj), phonetic_key(hj)
        if not ka or not kb:
            return None
    # a transliteration maps a one-script span onto the other script; a block that also swallows a Cyrillic
    # neighbour of a Latin term ("по Kubernetes" -> "покубернитесь", "в Slack" -> "вслаг") is a merge error
    pure = len(sr) == 1 and len(sh) == 1
    if cross and pure and sim >= TRANSLIT_MIN_SIM and len(hyp) <= _parts(ref) \
            and _spelling_sim(rj, hj) >= TRANSLIT_MIN_SPELL:
        return ("translit", sim)
    if single:
        return ("sub_near", sim) if sim >= NEAR_MIN_SIM else ("sub", sim)
    if sim >= BLOCK_MIN_SIM:
        return ("sub_near", sim)
    return None


def _parts(ref: Sequence[str]) -> int:
    """How many spoken words a written span may legitimately become (CamelCase parts, acronym letters)."""
    n = 0
    for w in ref:
        if is_acronym(w):
            n += sum(1 for c in w if c.isalnum())
            continue
        parts = camel_parts(w) or [w]
        for p in parts:
            if p.isdigit():
                n += max(1, len(p))  # "100" may be read "сто" or "один ноль ноль"
            elif p.isupper() and len(p) >= 2:
                n += len(p)
            else:
                n += 1
    return n


def _spelling_sim(ref: str, hyp: str) -> float:
    """How close hyp is to a plausible transliteration of ref (either direction)."""
    cands = [char_sim(cyr2lat(ref), cyr2lat(hyp))]
    if script_of(ref) in ("lat", "mixed"):
        cands += [char_sim(lat2cyr(ref), hyp), char_sim(lat2cyr(ref, acronym_style="ru"), hyp)]
        cands += [char_sim(r, hyp) for r in acronym_readings(ref)]
    if script_of(hyp) in ("lat", "mixed"):
        cands.append(char_sim(lat2cyr(hyp), ref))
    return max(cands)


def _cost(kind: str, sim: float, a: int, b: int) -> float:
    extra = 0.1 * (a + b - 2)
    if kind == "match":
        return 0.0
    if kind in ("case", "yo", "punct"):
        return 0.001 + 0.01 * (a + b - 2)
    if kind == "number":
        return 0.002 + 0.01 * (a + b - 2)
    if kind == "translit":
        return 0.05 + 0.3 * (1.0 - sim) + extra
    if kind == "sub_near":
        return max(a, b) * (0.6 + 0.4 * (1.0 - sim)) + extra
    return 1.0  # sub


def _block_plausible_by_length(ref: Sequence[str], hyp: Sequence[str]) -> bool:
    # acronyms and letter+digit names expand a lot when spoken ("p99" -> "пи девяносто девять")
    if any(is_acronym(w) or any(c.isdigit() for c in w) for w in ref):
        return True
    lr = sum(len(w) for w in ref)
    lh = sum(len(w) for w in hyp)
    return min(lr, lh) >= 0.35 * max(lr, lh)


# ----------------------------------------------------------------------------- DP

def align_tokens(ref: list[Token], hyp: list[Token], ref_text: str = "", hyp_text: str = "") -> Alignment:
    rw = [t.core for t in ref]
    hw = [t.core for t in hyp]
    n, m = len(rw), len(hw)
    inf = float("inf")
    dp = [[inf] * (m + 1) for _ in range(n + 1)]
    back: list[list[tuple[int, int, str, float] | None]] = [[None] * (m + 1) for _ in range(n + 1)]
    dp[0][0] = 0.0
    numberish = [is_numeric_token(w) or is_number_word(w) for w in rw]
    numberish_h = [is_numeric_token(w) or is_number_word(w) for w in hw]
    numberish_c = [x or fold(w) in CONNECTORS for x, w in zip(numberish, rw)]  # "point", "целых" inside numbers
    # spoken numbers on the reference side may be as long as on the hypothesis side ("zero point zero zero zero
    # three" -> "0.0003"); other many-to-one blocks stay within MAX_REF_BLOCK
    blocks = [(1, b) for b in range(2, MAX_HYP_BLOCK + 1)] + [(a, 1) for a in range(2, MAX_HYP_BLOCK + 1)]
    # many-to-many blocks only for number spans ("128 тысяч" / "128 000")
    num_blocks = [(a, b) for a in range(2, 4) for b in range(2, 5)]
    for i in range(n + 1):
        for j in range(m + 1):
            if i == 0 and j == 0:
                continue
            best = inf
            choice: tuple[int, int, str, float] | None = None
            if i and j:
                kind, sim = classify((rw[i - 1],), (hw[j - 1],))  # type: ignore[misc]
                c = dp[i - 1][j - 1] + _cost(kind, sim, 1, 1)
                if c < best:
                    best, choice = c, (1, 1, kind, sim)
            if i:
                c = dp[i - 1][j] + 1.0
                if c < best:
                    best, choice = c, (1, 0, "del", 0.0)
            if j:
                c = dp[i][j - 1] + 1.0
                if c < best:
                    best, choice = c, (0, 1, "ins", 0.0)
            for a, b in blocks + num_blocks:
                if a > i or b > j or dp[i - a][j - b] == inf:
                    continue
                if a > 1 and b > 1 and not (all(numberish[i - a:i]) and all(numberish_h[j - b:j])):
                    continue
                if a > MAX_REF_BLOCK and not (all(numberish_c[i - a:i]) and numberish_h[j - 1]):
                    continue
                rs, hs = rw[i - a:i], hw[j - b:j]
                if not (any(numberish[i - a:i]) and any(numberish_h[j - b:j])) and not _block_plausible_by_length(rs, hs):
                    continue
                res = classify(tuple(rs), tuple(hs))
                if res is None:
                    continue
                kind, sim = res
                c = dp[i - a][j - b] + _cost(kind, sim, a, b)
                if c < best - 1e-9:
                    best, choice = c, (a, b, kind, sim)
            dp[i][j] = best
            back[i][j] = choice
    ops: list[Op] = []
    i, j = n, m
    while i or j:
        ch = back[i][j]
        assert ch is not None
        a, b, kind, sim = ch
        ops.append(Op(kind, i - a, i, j - b, j, round(sim, 4)))
        i, j = i - a, j - b
    ops.reverse()
    return Alignment(ref=ref, hyp=hyp, ops=ops, ref_text=ref_text, hyp_text=hyp_text)


def align(ref_text: str, hyp_text: str) -> Alignment:
    """Align intended text against raw hypothesis text."""
    from .norm import nfc

    ref_text, hyp_text = nfc(ref_text or ""), nfc(hyp_text or "")
    return align_tokens(tokenize(ref_text), tokenize(hyp_text), ref_text, hyp_text)


# ----------------------------------------------------------------------------- conventional metrics

@dataclass
class EditCounts:
    sub: int = 0
    dele: int = 0
    ins: int = 0
    n: int = 0

    @property
    def rate(self) -> float:
        return (self.sub + self.dele + self.ins) / max(1, self.n)

    def add(self, other: "EditCounts") -> None:
        self.sub += other.sub
        self.dele += other.dele
        self.ins += other.ins
        self.n += other.n


def edit_counts(ref: Sequence[str], hyp: Sequence[str]) -> EditCounts:
    n, m = len(ref), len(hyp)
    prev = [(j, 0, 0, j) for j in range(m + 1)]  # (cost, sub, del, ins)
    for i in range(1, n + 1):
        cur = [(i, 0, i, 0)]
        for j in range(1, m + 1):
            same = ref[i - 1] == hyp[j - 1]
            d = prev[j - 1]
            cands = [(d[0] + (0 if same else 1), d[1] + (0 if same else 1), d[2], d[3])]
            u = prev[j]
            cands.append((u[0] + 1, u[1], u[2] + 1, u[3]))
            le = cur[j - 1]
            cands.append((le[0] + 1, le[1], le[2], le[3] + 1))
            cur.append(min(cands))
        prev = cur
    _, s, dl, ins = prev[m]
    return EditCounts(s, dl, ins, n)


def raw_words(text: str) -> list[str]:
    """Conventional WER tokens: case folded, ё->е, punctuation removed."""
    return [w for w in (squash(t.core) for t in tokenize(text)) if w]


def raw_wer_counts(ref_text: str, hyp_text: str) -> EditCounts:
    return edit_counts(raw_words(ref_text), raw_words(hyp_text))


def cer_counts(ref_text: str, hyp_text: str) -> EditCounts:
    r = " ".join(raw_words(ref_text))
    h = " ".join(raw_words(hyp_text))
    dist = levenshtein(r, h)
    return EditCounts(sub=dist, n=len(r))


# ----------------------------------------------------------------------------- terms

@dataclass
class TermHit:
    term: str
    category: str
    r0: int
    r1: int
    h0: int
    h1: int
    kinds: list[str] = field(default_factory=list)
    hyp: str = ""

    @property
    def exact(self) -> bool:
        return self.hyp == self.term

    @property
    def folded_equal(self) -> bool:
        return squash(self.hyp) == squash(self.term)

    @property
    def is_error(self) -> bool:
        return any(k in ERROR_KINDS for k in self.kinds)


def locate_terms(text: str, tokens: Sequence[Token], terms: Iterable[dict]) -> list[tuple[dict, int, int]]:
    """Token spans [r0, r1) of every occurrence of each term's exact surface in `text`, left to right.

    A term listed once but occurring twice is mapped to both occurrences; overlapping hits keep the first.
    """
    out: list[tuple[dict, int, int]] = []
    taken: set[int] = set()
    for term in terms:
        surface = term.get("term", "")
        if not surface:
            continue
        pos = 0
        while True:
            k = text.find(surface, pos)
            if k < 0:
                break
            pos = k + len(surface)
            idx = [i for i, t in enumerate(tokens) if t.start < pos and t.end > k]
            if idx and not taken.intersection(idx):
                taken.update(idx)
                out.append((term, idx[0], idx[-1] + 1))
    out.sort(key=lambda x: x[1])
    return out


def term_hits(al: Alignment, terms: Iterable[dict]) -> list[TermHit]:
    hits: list[TermHit] = []
    for term, r0, r1 in locate_terms(al.ref_text, al.ref, terms):
        sel = [k for k, op in enumerate(al.ops) if op.r0 < r1 and op.r1 > r0]
        if not sel:
            continue
        lo, hi = sel[0], sel[-1]
        # insertions sandwiched inside the term belong to it
        ops = al.ops[lo:hi + 1]
        h0 = min((op.h0 for op in ops if op.n_hyp), default=None)
        h1 = max((op.h1 for op in ops if op.n_hyp), default=None)
        if h0 is None or h1 is None:
            h0 = h1 = ops[0].h0
        hits.append(TermHit(term=term["term"], category=term.get("category", ""), r0=r0, r1=r1, h0=h0, h1=h1,
                            kinds=[op.kind for op in ops], hyp=al.hyp_surface(h0, h1)))
    return hits


def ref_token_kinds(al: Alignment) -> list[str]:
    """Kind of the op covering each reference token (for per-token statistics)."""
    out = [""] * len(al.ref)
    for op in al.ops:
        for i in range(op.r0, op.r1):
            out[i] = op.kind
    return out


def describe(al: Alignment) -> str:
    """Human-readable alignment, one op per line (debugging and docs)."""
    lines = []
    for op in al.ops:
        lines.append(f"{op.kind:8} {al.ref_span(op)!r:28} -> {al.hyp_span(op)!r}  ({op.sim:.2f})")
    return "\n".join(lines)
