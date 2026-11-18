"""Post-processing of recognised lines: pure Python on plain lists, so it ports line by line to Kotlin.

A line is a dict {"text": str, "conf": float, "box": [x0, y0, x1, y1]} (integer screenshot pixels).
Order of operations (pipeline.py): exclusion -> recognition -> filter -> homoglyph repair -> geometric
de-duplication -> reading order -> textual de-duplication.
"""
from __future__ import annotations

import unicodedata

from .config import CYR2LAT_STRONG, CYR2LAT_WEAK, LAT2CYR_STRONG, LAT2CYR_WEAK, WEAK_WEIGHT, OcrConfig

# ----------------------------------------------------------------------------- geometry


def area(b) -> int:
    return max(0, b[2] - b[0]) * max(0, b[3] - b[1])


def inter(a, b) -> int:
    return area((max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])))


def excluded(box, rects, min_overlap: float) -> bool:
    a = area(box)
    if a == 0:
        return True
    return any(inter(box, r) >= min_overlap * a for r in rects)


# ----------------------------------------------------------------------------- filters


def norm_text(s: str) -> str:
    """NFC, collapse whitespace runs to one space, strip."""
    return " ".join(unicodedata.normalize("NFC", s).split())


def keep(line: dict, cfg: OcrConfig) -> bool:
    if line["conf"] < cfg.min_conf:
        return False
    return sum(1 for c in line["text"] if not c.isspace()) >= cfg.min_chars


# ----------------------------------------------------------------------------- homoglyph repair


def _is_cyr(c: str) -> bool:
    return "Ѐ" <= c <= "ӿ"


def _is_lat(c: str) -> bool:
    return ("a" <= c <= "z") or ("A" <= c <= "Z") or ("À" <= c <= "ɏ" and c.isalpha())


def script_score(tok: str) -> tuple[float, float]:
    """(cyrillic, latin) evidence: 1 per letter that has no twin, WEAK_WEIGHT per weak twin, 0 per strong twin."""
    cy = la = 0.0
    for c in tok:
        if _is_cyr(c):
            cy += 0.0 if c in CYR2LAT_STRONG else WEAK_WEIGHT if c in CYR2LAT_WEAK else 1.0
        elif _is_lat(c):
            la += 0.0 if c in LAT2CYR_STRONG else WEAK_WEIGHT if c in LAT2CYR_WEAK else 1.0
    return cy, la


def _to_cyr(tok: str) -> str:
    return "".join(LAT2CYR_STRONG.get(c) or LAT2CYR_WEAK.get(c) or c for c in tok)


def _to_lat(tok: str) -> str:
    return "".join(CYR2LAT_STRONG.get(c) or CYR2LAT_WEAK.get(c) or c for c in tok)


def _has_both(tok: str) -> bool:
    return any(_is_cyr(c) for c in tok) and any(_is_lat(c) for c in tok)


def repair_homoglyphs(text: str) -> str:
    """Make every whitespace token single-script where the evidence says so.

    Token decision: the script with more evidence wins (script_score). A token with no evidence either way
    (only strong twins, e.g. "Сосо", "ТОР", "с") takes the script of the decisive tokens among the two on each
    side on the same line, but only when they all agree; otherwise it is left as recognised. Ties with
    evidence on both sides are left alone (genuinely mixed tokens are rare but exist).
    Only tokens that contain both scripts are changed, plus all-twin tokens whose neighbours decide the
    opposite script of what they were recognised as.
    """
    toks = text.split(" ")
    scores = [script_score(t) for t in toks]
    out = []
    for i, t in enumerate(toks):
        cy, la = scores[i]
        if cy == 0 and la == 0:
            # all-twin token: follow the neighbours only when every decisive neighbour agrees
            votes = set()
            for j in range(max(0, i - 2), min(len(toks), i + 3)):
                if j != i and scores[j][0] != scores[j][1]:
                    votes.add("cyr" if scores[j][0] > scores[j][1] else "lat")
            letters = [c for c in t if _is_cyr(c) or _is_lat(c)]
            if not letters or len(votes) != 1:
                out.append(t)
            elif votes == {"cyr"}:
                out.append(_to_cyr(t) if all(c in LAT2CYR_STRONG or _is_cyr(c) for c in letters) else t)
            else:
                out.append(_to_lat(t) if all(c in CYR2LAT_STRONG or _is_lat(c) for c in letters) else t)
            continue
        if not _has_both(t) or cy == la:
            out.append(t)
        elif cy > la:
            out.append(_to_cyr(t))
        else:
            out.append(_to_lat(t))
    return " ".join(out)


# ----------------------------------------------------------------------------- de-duplication


def iou(a, b) -> float:
    i = inter(a, b)
    u = area(a) + area(b) - i
    return i / u if u else 0.0


def dedup_geometric(lines: list[dict], thr: float) -> list[dict]:
    """Drop a line that overlaps an already kept line with IoU >= thr, or lies >= 80 % inside it.
    Lines are visited by descending conf, then by (y0, x0), so the result does not depend on input order."""
    order = sorted(range(len(lines)), key=lambda i: (-lines[i]["conf"], lines[i]["box"][1], lines[i]["box"][0]))
    kept: list[int] = []
    for i in order:
        b = lines[i]["box"]
        if any(iou(b, lines[k]["box"]) >= thr or inter(b, lines[k]["box"]) >= 0.8 * max(1, area(b))
               for k in kept):
            continue
        kept.append(i)
    return [lines[i] for i in sorted(kept)]


def fold(s: str) -> str:
    return " ".join(s.casefold().split())


def dedup_text(lines: list[dict]) -> list[dict]:
    """Keep the first occurrence (in reading order) of each case/space-folded text."""
    seen, out = set(), []
    for ln in lines:
        k = fold(ln["text"])
        if k in seen:
            continue
        seen.add(k)
        out.append(ln)
    return out


# ----------------------------------------------------------------------------- reading order


def _rows(lines: list[dict], overlap: float) -> list[dict]:
    """Group into rows (vertical overlap >= overlap * min height with the row's first box), rows top to
    bottom by their first box's y0, boxes inside a row left to right."""
    order = sorted(lines, key=lambda ln: (ln["box"][1], ln["box"][0]))
    rows: list[list[dict]] = []
    for ln in order:
        b = ln["box"]
        for row in rows:
            r = row[0]["box"]
            ov = min(b[3], r[3]) - max(b[1], r[1])
            if ov >= overlap * min(b[3] - b[1], r[3] - r[1]) and not any(inter(b, o["box"]) for o in row):
                row.append(ln)
                break
        else:
            rows.append([ln])
    out = []
    for row in rows:
        out.extend(sorted(row, key=lambda ln: (ln["box"][0], ln["box"][1])))
    return out


def find_gutter(lines: list[dict], width: int, height: int, cfg: OcrConfig):
    """Two-pane layout: a vertical line x that no box crosses in the band where both sides have content.
    Returns (x, band_y0, band_y1) or None. The widest free gap inside pane_gutter_range is used."""
    if not lines or width / height < cfg.pane_min_aspect:
        return None
    lo, hi = int(cfg.pane_gutter_range[0] * width), int(cfg.pane_gutter_range[1] * width)
    best = None
    # candidate gutters: gaps between box edges, scanned on a 4-px grid (deterministic, cheap)
    for x in range(lo, hi + 1, 4):
        left = [ln for ln in lines if ln["box"][2] <= x]
        right = [ln for ln in lines if ln["box"][0] >= x]
        if len(left) < cfg.pane_min_lines or len(right) < cfg.pane_min_lines:
            continue
        y0 = max(min(ln["box"][1] for ln in left), min(ln["box"][1] for ln in right))
        y1 = min(max(ln["box"][3] for ln in left), max(ln["box"][3] for ln in right))
        if y1 - y0 < cfg.pane_min_span * height:
            continue
        crossing = [ln for ln in lines if ln["box"][0] < x < ln["box"][2] and ln["box"][3] > y0 and ln["box"][1] < y1]
        if crossing:
            continue
        lspan = [ln for ln in left if ln["box"][1] >= y0 and ln["box"][3] <= y1]
        rspan = [ln for ln in right if ln["box"][1] >= y0 and ln["box"][3] <= y1]
        if len(lspan) < cfg.pane_min_lines or len(rspan) < cfg.pane_min_lines:
            continue
        # gap width around x
        gl = max((ln["box"][2] for ln in left), default=0)
        gr = min((ln["box"][0] for ln in right), default=width)
        gap = gr - gl
        if best is None or gap > best[0]:
            best = (gap, (gl + gr) // 2, y0, y1)
    return None if best is None else best[1:]


def reading_order(lines: list[dict], width: int, height: int, cfg: OcrConfig) -> list[dict]:
    g = find_gutter(lines, width, height, cfg)
    if g is None:
        return _rows(lines, cfg.row_overlap)
    x, y0, y1 = g
    above = [ln for ln in lines if ln["box"][3] <= y0 or (ln["box"][1] < y0 and ln["box"][0] < x < ln["box"][2])]
    below = [ln for ln in lines if ln not in above and ln["box"][1] >= y1]
    rest = [ln for ln in lines if ln not in above and ln not in below]
    left = [ln for ln in rest if (ln["box"][0] + ln["box"][2]) / 2 < x]
    right = [ln for ln in rest if (ln["box"][0] + ln["box"][2]) / 2 >= x]
    return (_rows(above, cfg.row_overlap) + _rows(left, cfg.row_overlap) + _rows(right, cfg.row_overlap)
            + _rows(below, cfg.row_overlap))
