"""Real-screenshot probe: capture screenshots + accessibility ground truth over adb, run the pipeline, compare
the error profile with rendered screens.

    python -m ml.ocr.real_probe capture --serial emulator-5580 --out DIR --name settings_ru
    python -m ml.ocr.real_probe report DIR [--rendered .cache/ml/ocr/measure/terms.json]

capture: `screencap -p` -> NAME.png, `uiautomator dump` -> NAME.xml, IME / status bar bounds from
`dumpsys window` -> NAME.meta.json (exclude_rects). Ground truth = text and content-desc of the dumped
nodes with their bounds (only nodes inside the screen and with non-empty text; EditText hints included
as role "hint"). This is what the phone's accessibility tree says is on screen; it is NOT used as context,
only to score OCR (CONTEXT_V2).
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from . import metrics as M

ADB = str(Path.home() / ".local/share/android-sdk/platform-tools/adb")


def adb(serial: str, *args, binary=False, timeout=60):
    r = subprocess.run([ADB, "-s", serial, *args], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def _bounds(s: str):
    v = list(map(int, re.findall(r"-?\d+", s)))
    return v if len(v) == 4 else None


def window_rects(serial: str) -> dict:
    """Status bar, navigation bar and IME window frames from dumpsys window (best effort, all versions)."""
    out = {}
    txt = adb(serial, "shell", "dumpsys", "window", "windows")
    cur = None
    for line in txt.splitlines():
        m = re.search(r"Window #\d+ Window\{\w+ u\d+ ([^}]+)\}", line)
        if m:
            cur = m.group(1).strip()
            continue
        if cur is None:
            continue
        m = re.search(r"(?:mFrame|frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", line)
        if m:
            b = list(map(int, m.groups()))
            key = None
            if cur.startswith("StatusBar"):
                key = "status_bar"
            elif cur.startswith("InputMethod"):
                key = "keyboard"
            elif cur.startswith("NavigationBar"):
                key = "nav_bar"
            if key and key not in out:
                out[key] = b
            cur = None
        if "mHasSurface=false" in line or "isVisible=false" in line:
            pass
    ime_shown = "mInputShown=true" in adb(serial, "shell", "dumpsys", "input_method")
    if not ime_shown:
        out.pop("keyboard", None)
    return out


def capture(serial: str, out: Path, name: str) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    png = adb(serial, "exec-out", "screencap", "-p", binary=True)
    (out / f"{name}.png").write_bytes(png)
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/murmur_ui.xml")
    xml = adb(serial, "shell", "cat", "/sdcard/murmur_ui.xml")
    (out / f"{name}.xml").write_text(xml, encoding="utf-8")
    rects = window_rects(serial)
    meta = {"name": name, "serial": serial, "time": time.strftime("%Y-%m-%dT%H:%M:%S"), "windows": rects,
            "exclude_rects": [rects[k] for k in ("status_bar", "keyboard") if k in rects],
            "wm_size": adb(serial, "shell", "wm", "size").strip(), "density": adb(serial, "shell", "wm", "density").strip(),
            "locale": adb(serial, "shell", "getprop", "persist.sys.locale").strip()}
    (out / f"{name}.meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    return meta


def ground_truth(xml_text: str, size) -> list[dict]:
    """Visible text from a uiautomator dump: role 'hint' for empty-looking EditText hints, 'content' otherwise
    (the dump does not say which text is app chrome; the report classifies chrome by a word list)."""
    W, H = size
    items = []
    try:
        root = ET.fromstring(xml_text[xml_text.find("<"):])
    except ET.ParseError:
        return items
    for n in root.iter("node"):
        b = _bounds(n.get("bounds", ""))
        if not b or b[2] <= b[0] or b[3] <= b[1] or b[0] >= W or b[1] >= H:
            continue
        b = [max(0, b[0]), max(0, b[1]), min(W, b[2]), min(H, b[3])]
        t = (n.get("text") or "").strip()
        if t:
            role = "hint" if n.get("class", "").endswith("EditText") and n.get("hint", "") == t else "message"
            items.append({"role": role, "text": t, "box": b, "cls": n.get("class", ""), "pkg": n.get("package", "")})
    return items


def focused_field(xml_text: str) -> dict | None:
    """The focused editable node of a uiautomator dump (what Murmur's ctx.field.box comes from on the phone):
    an EditText (or any node with focused="true" and an EditText class name) -> {"cls", "pkg", "box"}."""
    try:
        root = ET.fromstring(xml_text[xml_text.find("<"):])
    except ET.ParseError:
        return None
    for n in root.iter("node"):
        if n.get("focused") == "true" and "EditText" in n.get("class", ""):
            return {"cls": n.get("class", ""), "pkg": n.get("package", ""), "box": _bounds(n.get("bounds", ""))}
    return None


def report(d: Path) -> dict:
    from PIL import Image
    from . import pipeline as P
    res, per = [], []
    for meta_p in sorted(d.glob("*.meta.json")):
        meta = json.loads(meta_p.read_text())
        name = meta["name"]
        png = d / f"{name}.png"
        try:
            size = Image.open(png).size
        except Exception:   # failed capture (device restarting, locked screen)
            continue
        gt = ground_truth((d / f"{name}.xml").read_text(encoding="utf-8"), size)
        det = P.run_detailed(png, meta["exclude_rects"])
        lines = det["result"]["lines"]
        (d / f"{name}.ocr.json").write_text(json.dumps(det["result"], ensure_ascii=False, indent=1))
        # GT lines from the tree are whole multi-line paragraphs: split them by OCR rows is not possible, so
        # compare per OCR line instead: for every GT item, all OCR lines inside its box, joined.
        gts = [g for g in gt if not M._inside(g["box"], meta["exclude_rects"])]
        cmp = compare_tree(gts, lines)
        cmp["name"] = name
        cmp["focused_field"] = focused_field((d / f"{name}.xml").read_text(encoding="utf-8"))
        cmp["ms"] = det["ms"]
        cmp["size"] = list(size)
        per.append(cmp)
        res.append(cmp)
    agg = {
        "screens": len(per),
        "cer": sum(r["errors"] for r in per) / max(1, sum(r["chars"] for r in per)),
        "item_exact": sum(r["exact"] for r in per) / max(1, sum(r["items"] for r in per)),
        "missed_share": sum(r["missed"] for r in per) / max(1, sum(r["items"] for r in per)),
        "ocr_lines": sum(r["ocr_lines"] for r in per),
        "unmatched_ocr_line_share": sum(r["ocr_unmatched"] for r in per) / max(1, sum(r["ocr_lines"] for r in per)),
        "mixed_script_token_share": sum(r["mixed"] for r in per) / max(1, sum(r["tokens"] for r in per)),
        "ocr_lines_per_screen": sum(r["ocr_lines"] for r in per) / max(1, len(per)),
        "median_total_ms": sorted(r["ms"]["total"] for r in per)[len(per) // 2] if per else None,
    }
    # The dump is taken ~1 s after the screenshot, so a screen that changed in between (loading spinners,
    # dialogs, app switches) has a GT that does not describe the image. "consistent" screens: >= 50 % of the
    # GT items found by OCR; their numbers are the fair comparison with the rendered set.
    cons = [r for r in per if r["items"] and r["missed"] <= 0.5 * r["items"]]
    found = [x for r in per for x in r["detail"] if x["ocr"]]
    agg["consistent_screens"] = len(cons)
    agg["consistent_cer"] = sum(r["errors"] for r in cons) / max(1, sum(r["chars"] for r in cons))
    agg["consistent_item_exact"] = sum(r["exact"] for r in cons) / max(1, sum(r["items"] for r in cons))
    agg["consistent_missed_share"] = sum(r["missed"] for r in cons) / max(1, sum(r["items"] for r in cons))
    agg["found_item_cer"] = sum(x["dist"] for x in found) / max(1, sum(len(x["gt"]) for x in found))
    agg["label_like_line_share"] = label_like_share([t for r in per for t in r["ocr_texts"]])
    conf = {}
    for r in per:
        for k, v in r["confusions"].items():
            conf[k] = conf.get(k, 0) + v
    agg["top_confusions"] = sorted(conf.items(), key=lambda kv: -kv[1])[:30]
    # focused-field bounds per app: check the [data-focus] choice of each template (ml/screens/README.md)
    agg["focused_fields"] = {r["name"]: r["focused_field"] for r in per if r.get("focused_field")}
    doc = {"aggregate": agg, "screens": per}
    (d / "report.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1))
    return doc


def label_like_share(texts: list[str]) -> float:
    """Share of OCR lines that look like UI labels rather than content: at most 2 words and 18 characters,
    or a clock / counter / percentage (comparable proxy for real and rendered screens, which have no roles)."""
    def lab(t):
        t = t.strip()
        if re.fullmatch(r"[\d:%.,/ +\-]+[A-Za-zА-Яа-я]{0,3}", t):
            return True
        return len(t.split()) <= 2 and len(t) <= 18
    return sum(1 for t in texts if lab(t)) / max(1, len(texts))


def compare_tree(gts: list[dict], lines: list[dict]) -> dict:
    """GT items from the tree can span several visual lines (wrapped text): the OCR lines whose centre lies
    inside the item's box are joined in reading order and compared with the item text (spaces normalised)."""
    used = set()
    errors = chars = exact = missed = 0
    conf: dict[str, int] = {}
    items = []
    # innermost items first so a container's text does not swallow its children's lines
    for g in sorted(gts, key=lambda g: (g["box"][2] - g["box"][0]) * (g["box"][3] - g["box"][1])):
        t = M.clean(g["text"])
        if not t:
            continue
        inside = [i for i, o in enumerate(lines)
                  if g["box"][0] - 4 <= (o["box"][0] + o["box"][2]) / 2 <= g["box"][2] + 4
                  and g["box"][1] - 4 <= (o["box"][1] + o["box"][3]) / 2 <= g["box"][3] + 4]
        hyp = M.clean(" ".join(lines[i]["text"] for i in inside))
        if not inside:
            dist, ed = len(t), []
            missed += 1
        else:
            dist, ed = M.semiglobal(t.replace("\n", " "), hyp)
            dist = min(dist, len(t))
        used.update(inside)
        errors += dist
        chars += len(t)
        exact += dist == 0
        for a, b in ed:
            k = f"{a}->{b}"
            conf[k] = conf.get(k, 0) + 1
        items.append({"gt": t, "ocr": hyp, "dist": dist, "cls": g.get("cls", "")})
    toks = [t for o in lines for t in o["text"].split()]
    return {"items": len(items), "chars": chars, "errors": errors, "exact": exact, "missed": missed,
            "ocr_lines": len(lines), "ocr_unmatched": len(lines) - len(used),
            "unmatched_texts": [lines[i]["text"] for i in range(len(lines)) if i not in used],
            "ocr_texts": [o["text"] for o in lines],
            "mixed": sum(1 for t in toks if M.is_mixed_script(t)), "tokens": len(toks), "confusions": conf,
            "detail": items}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m ml.ocr.real_probe")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("capture")
    c.add_argument("--serial", required=True)
    c.add_argument("--out", required=True)
    c.add_argument("--name", required=True)
    r = sub.add_parser("report")
    r.add_argument("dir")
    a = ap.parse_args(argv)
    if a.cmd == "capture":
        print(json.dumps(capture(a.serial, Path(a.out), a.name), ensure_ascii=False))
    else:
        doc = report(Path(a.dir))
        print(json.dumps(doc["aggregate"], ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
