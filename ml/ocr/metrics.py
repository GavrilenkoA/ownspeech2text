"""OCR output vs ground truth (the renderer's `visible`, or uiautomator text on real screenshots).

Matching: a GT item is compared with the OCR lines whose boxes overlap it (vertical overlap >= 50 % of the
smaller height and any horizontal overlap), concatenated left to right; the distance is the semi-global edit
distance of the GT text against the best-matching substring of that concatenation (so an OCR line that also
holds a neighbouring timestamp is not penalised). Missing GT = all characters wrong. Emoji / pictographs are
removed from GT and OCR before comparing (the recogniser has no emoji in its dictionary).
"""
from __future__ import annotations

import re
import unicodedata
from collections import Counter

IGNORED_ROLES = {"keyboard", "status", "murmur"}
CONTENT_ROLES = {"title", "message", "author", "snippet", "field"}
_EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿️‍\U0001F1E6-\U0001F1FF]")


def clean(s: str) -> str:
    return " ".join(_EMOJI.sub("", unicodedata.normalize("NFC", s)).split())


def lev(a: str, b: str) -> int:
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def semiglobal(gt: str, hyp: str) -> tuple[int, list[tuple[str, str]]]:
    """min edit distance of gt against any substring of hyp, plus the aligned (gt_char, hyp_char) edits."""
    n, m = len(gt), len(hyp)
    D = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        D[i][0] = i
        for j in range(1, m + 1):
            D[i][j] = min(D[i - 1][j] + 1, D[i][j - 1] + 1, D[i - 1][j - 1] + (gt[i - 1] != hyp[j - 1]))
    j = min(range(m + 1), key=lambda k: (D[n][k], k))
    best = D[n][j]
    edits, i = [], n
    while i > 0 and j > 0:
        if D[i][j] == D[i - 1][j - 1] + (gt[i - 1] != hyp[j - 1]):
            if gt[i - 1] != hyp[j - 1]:
                edits.append((gt[i - 1], hyp[j - 1]))
            i, j = i - 1, j - 1
        elif D[i][j] == D[i - 1][j] + 1:
            edits.append((gt[i - 1], ""))
            i -= 1
        else:
            j -= 1   # insertion inside the matched span
            edits.append(("", hyp[j]))
    while i > 0:
        edits.append((gt[i - 1], ""))
        i -= 1
    return best, edits


def _overlap(g, o) -> bool:
    vy = min(g[3], o[3]) - max(g[1], o[1])
    vx = min(g[2], o[2]) - max(g[0], o[0])
    return vy >= 0.5 * min(g[3] - g[1], o[3] - o[1]) and vx > 0


def _inside(b, rects, share=0.5) -> bool:
    a = max(1, (b[2] - b[0]) * (b[3] - b[1]))
    for r in rects:
        ix = max(0, min(b[2], r[2]) - max(b[0], r[0]))
        iy = max(0, min(b[3], r[3]) - max(b[1], r[1]))
        if ix * iy >= share * a:
            return True
    return False


def is_mixed_script(tok: str) -> bool:
    cy = any("Ѐ" <= c <= "ӿ" for c in tok)
    la = any(c.isascii() and c.isalpha() for c in tok)
    return cy and la


def compare(visible: list[dict], ocr_lines: list[dict], exclude_rects=()) -> dict:
    gts, seen = [], set()
    for g in sorted(visible, key=lambda g: (g["box"][1], g["box"][0])):
        k = " ".join(clean(g["text"]).casefold().split())
        if g["role"] in IGNORED_ROLES or _inside(g["box"], exclude_rects) or not k or k in seen:
            continue   # repeated texts are removed by the pipeline's textual de-duplication: count them once
        seen.add(k)
        gts.append(g)
    errs = chars = 0
    content_errs = content_chars = 0
    missed = exact = 0
    conf = Counter()
    per_role = Counter(), Counter()
    items = []
    for g in gts:
        t = clean(g["text"])
        hits = sorted((o for o in ocr_lines if _overlap(g["box"], o["box"])), key=lambda o: o["box"][0])
        hyp = clean(" ".join(o["text"] for o in hits))
        if not hits:
            d, ed = len(t), [(c, "") for c in t]
            missed += 1
        else:
            d, ed = semiglobal(t, hyp)
        d = min(d, len(t))
        exact += d == 0
        errs += d
        chars += len(t)
        per_role[0][g["role"]] += d
        per_role[1][g["role"]] += len(t)
        if g["role"] in CONTENT_ROLES:
            content_errs += d
            content_chars += len(t)
        for a, b in ed:
            conf[(a, b)] += 1
        items.append({"role": g["role"], "gt": t, "ocr": hyp, "dist": d})
    # role of each OCR line = role of the GT item it overlaps most (None = no GT: hallucination / icon text)
    line_roles = []
    for o in ocr_lines:
        best, area = None, 0
        for g in visible:
            ix = max(0, min(g["box"][2], o["box"][2]) - max(g["box"][0], o["box"][0]))
            iy = max(0, min(g["box"][3], o["box"][3]) - max(g["box"][1], o["box"][1]))
            if ix * iy > area:
                best, area = g["role"], ix * iy
        line_roles.append(best)
    toks = [t for o in ocr_lines for t in o["text"].split()]
    return {
        "gt_items": len(gts), "gt_chars": chars, "errors": errs, "cer": errs / max(1, chars),
        "content_cer": content_errs / max(1, content_chars), "content_chars": content_chars, "content_errors": content_errs,
        "missed_items": missed, "exact_items": exact,
        "ocr_lines": len(ocr_lines), "line_roles": Counter(line_roles),
        "non_content_lines": sum(1 for r in line_roles if r not in CONTENT_ROLES),
        "mixed_script_tokens": sum(1 for t in toks if is_mixed_script(t)), "tokens": len(toks),
        "confusions": conf, "per_role_err": per_role[0], "per_role_chars": per_role[1], "items": items,
    }


def aggregate(results: list[dict]) -> dict:
    tot = Counter()
    conf, roles, pre, prc = Counter(), Counter(), Counter(), Counter()
    for r in results:
        for k in ("gt_items", "gt_chars", "errors", "content_chars", "content_errors", "missed_items", "exact_items",
                  "ocr_lines", "non_content_lines", "mixed_script_tokens", "tokens"):
            tot[k] += r[k]
        conf.update(r["confusions"])
        roles.update(r["line_roles"])
        pre.update(r["per_role_err"])
        prc.update(r["per_role_chars"])
    return {
        "screens": len(results), **tot,
        "cer": tot["errors"] / max(1, tot["gt_chars"]), "content_cer": tot["content_errors"] / max(1, tot["content_chars"]),
        "item_recall_exact": tot["exact_items"] / max(1, tot["gt_items"]),
        "missed_share": tot["missed_items"] / max(1, tot["gt_items"]),
        "non_content_line_share": tot["non_content_lines"] / max(1, tot["ocr_lines"]),
        "mixed_script_token_share": tot["mixed_script_tokens"] / max(1, tot["tokens"]),
        "line_roles": dict(roles.most_common()),
        "cer_by_role": {k: round(pre[k] / max(1, prc[k]), 4) for k in prc},
        "top_confusions": [[a, b, n] for (a, b), n in conf.most_common(30)],
    }
