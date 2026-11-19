"""report.analyse: harness failures count as failed cases and never shrink the denominator."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import report  # noqa: E402
import score  # noqa: E402

SET = "dev.v2.jsonl"


class LostCases(unittest.TestCase):
    def setUp(self):
        self.cases = score.load_jsonl(Path(report.HERE) / "sets" / SET)
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _run(self, rows, **meta):
        p = self.dir / "m.p.dev.v2.jsonl"
        p.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
        p.with_suffix(".meta.json").write_text(json.dumps({"set": SET, **meta}), encoding="utf-8")
        return report.analyse(p)

    def test_missing_and_error_rows_are_failures(self):
        a, b = self.cases[0], self.cases[1]
        rows = [{"id": a["id"], "out": a["target"]}, {"id": b["id"], "out": "", "error": "TimeoutError: timed out"}]
        r = self._run(rows)
        self.assertEqual(r["n"], len(self.cases))  # the whole set, not the 2 rows that came back
        self.assertEqual(r["guard"]["lost"], len(self.cases) - 1)
        self.assertTrue(r["rows"]["final"][0]["ok"])
        self.assertFalse(r["rows"]["final"][1]["ok"] if b["target"] else False)

    def test_ids_subset_scores_only_what_was_asked(self):
        a = self.cases[0]
        r = self._run([{"id": a["id"], "out": a["target"]}], ids=a["id"])
        self.assertEqual((r["n"], r["guard"]["lost"]), (1, 0))


if __name__ == "__main__":
    unittest.main()
