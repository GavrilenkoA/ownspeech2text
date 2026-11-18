from ml.ocr import postprocess as pp
from ml.ocr.config import DEFAULT


def L(text, box, conf=0.9):
    return {"text": text, "conf": conf, "box": list(box)}


def test_homoglyph_mixed_tokens():
    assert pp.repair_homoglyphs("kлactepy") == "кластеру"
    assert pp.repair_homoglyphs("Grafanа") == "Grafana"
    assert pp.repair_homoglyphs("Привет, Grafana!") == "Привет, Grafana!"


def test_homoglyph_all_twin_tokens_follow_unanimous_neighbours():
    # "Bce" recognised in Latin between Russian words -> Cyrillic
    assert pp.repair_homoglyphs("Bce готово к релизу") == "Все готово к релизу"
    # Cyrillic "с" between a Russian and an English word stays as recognised
    assert pp.repair_homoglyphs("Встреча с Ingrid") == "Встреча с Ingrid"
    assert pp.repair_homoglyphs("Встреча c Ingrid") == "Встреча c Ingrid"
    # Latin context: Cyrillic twins turned into Latin
    assert pp.repair_homoglyphs("open the АРI docs") == "open the API docs"


def test_homoglyph_leaves_digits_and_symbols():
    assert pp.repair_homoglyphs("PR #4182 15:30") == "PR #4182 15:30"
    assert pp.repair_homoglyphs("") == ""


def test_filter():
    assert pp.keep(L("ok", (0, 0, 1, 1), 0.9), DEFAULT)
    assert not pp.keep(L("a", (0, 0, 1, 1), 0.9), DEFAULT)
    assert not pp.keep(L("hello", (0, 0, 1, 1), 0.3), DEFAULT)


def test_exclusion():
    assert pp.excluded([0, 0, 10, 10], [(0, 0, 10, 6)], 0.5)
    assert not pp.excluded([0, 0, 10, 10], [(0, 0, 10, 4)], 0.5)
    assert not pp.excluded([0, 0, 10, 10], [], 0.5)


def test_dedup():
    lines = [L("abc", (0, 0, 100, 20), 0.8), L("abc", (2, 1, 100, 21), 0.9), L("x y", (0, 50, 50, 70))]
    g = pp.dedup_geometric(lines, 0.5)
    assert [ln["conf"] for ln in g] == [0.9, 0.9]
    t = pp.dedup_text([L("12:41", (0, 0, 5, 5)), L("Hi", (0, 10, 5, 15)), L("12:41", (0, 20, 5, 25))])
    assert [ln["text"] for ln in t] == ["12:41", "Hi"]


def test_rows_left_to_right():
    lines = [L("time", (800, 104, 900, 130)), L("text", (50, 100, 600, 140)), L("next", (50, 160, 300, 190))]
    out = pp.reading_order(lines, 1080, 2520, DEFAULT)
    assert [ln["text"] for ln in out] == ["text", "time", "next"]


def test_two_pane():
    W, H = 1968, 2184
    head = [L("header", (40, 120, 1900, 180))]
    left = [L(f"L{i}", (40, 300 + i * 200, 700, 360 + i * 200)) for i in range(6)]
    right = [L(f"R{i}", (900, 280 + i * 200, 1900, 340 + i * 200)) for i in range(6)]
    out = pp.reading_order(head + right + left, W, H, DEFAULT)
    assert [ln["text"] for ln in out] == ["header"] + [f"L{i}" for i in range(6)] + [f"R{i}" for i in range(6)]


def test_no_two_pane_on_cover_or_crossing_lines():
    lines = [L(f"L{i}", (40, 300 + i * 200, 500, 360 + i * 200)) for i in range(6)] + \
            [L(f"R{i}", (600, 400 + i * 200, 1040, 460 + i * 200)) for i in range(6)]
    out = pp.reading_order(lines, 1080, 2520, DEFAULT)   # narrow display: plain rows
    assert [ln["text"] for ln in out][:3] == ["L0", "R0", "L1"]
    W, H = 1968, 2184
    lines2 = lines + [L("wide", (40, 900, 1500, 950))]
    assert pp.find_gutter(lines, W, H, DEFAULT) is not None
    assert pp.find_gutter(lines2, W, H, DEFAULT) is None   # a line crossing the gutter inside the band
