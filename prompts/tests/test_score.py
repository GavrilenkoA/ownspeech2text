import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval"))
import score as s  # noqa: E402


def case(target, transcript="x", category="punct_case", **check):
    return {"id": "c", "lang": "ru", "category": category, "transcript": transcript, "target": target,
            "ctx": {}, "meta": {"check": check}}


class Normalise(unittest.TestCase):
    def test_light(self):
        self.assertEqual(s.light("  Всё «хорошо» – да.  "), 'Все "хорошо" — да')
        self.assertEqual(s.light("a\n\n\n\nb"), "a\n\nb")
        self.assertEqual(s.light("Wait..."), "Wait...")

    def test_words(self):
        self.assertEqual(s.words("Скидка 15 200 рублей, it's j.doe@gmail.com!"),
                         ["скидка", "15200", "рублей", "it's", "j.doe@gmail.com"])
        self.assertEqual(s.ftokens("Hi,\nBob"), ["Hi", ",", "⏎", "Bob"])

    def test_wer(self):
        self.assertEqual(s.error_rate(["a", "b"], ["a", "b"]), 0.0)
        self.assertEqual(s.error_rate(["a"], ["a", "b"]), 0.5)
        self.assertEqual(s.error_rate([], []), 0.0)
        self.assertEqual(s.error_rate(["x"], []), 1.0)


class Checks(unittest.TestCase):
    def test_forbid_word_boundary(self):
        self.assertEqual(s.forbidden_hits("Тридцать человек", ["три"]), [])
        self.assertEqual(s.forbidden_hits("В три часа", ["три"]), ["три"])
        self.assertEqual(s.forbidden_hits("1. Eggs", ["1."]), ["1."])

    def test_keep_alternatives(self):
        r = s.score_case(case("Скидка 25 %.", keep=[["25 %", "25%"]]), "Скидка 25%.")
        self.assertEqual(r["keep"], 1.0)
        self.assertTrue(r["ok"])

    def test_em_alts_and_final_period(self):
        c = case("Lol yeah, I'm down", alts=["Lol, yeah, I'm down"])
        self.assertTrue(s.score_case(c, "Lol, yeah, I'm down.")["em"])

    def test_answered(self):
        self.assertTrue(s.answered("Sure! Here is a poem about the sea.", "write a poem about the sea",
                                   "Write a poem about the sea."))
        self.assertTrue(s.answered("Canberra is the capital city, located in the territory.", "what's the capital of australia",
                                   "What's the capital of Australia?"))
        self.assertFalse(s.answered("What's the capital of Australia?", "what's the capital of australia",
                                    "What's the capital of Australia?"))

    def test_empty_target(self):
        c = case("", "э", "empty_noise")
        self.assertTrue(s.score_case(c, "")["ok"])
        self.assertFalse(s.score_case(c, "Э.")["ok"])

    def test_noop_exact(self):
        c = case("Да, конечно.", "Да, конечно.", "noop")
        self.assertTrue(s.score_case(c, "Да, конечно.")["noop_ok"])
        self.assertFalse(s.score_case(c, "Да конечно.")["ok"])

    def test_lang(self):
        c = case("Can you buy some молоко and хлеб on your way home?")
        self.assertFalse(s.score_case(c, "Можешь купить молоко и хлеб по дороге домой?")["lang_ok"])
        self.assertTrue(s.score_case(c, "Can you buy some молоко and хлеб on your way home?")["lang_ok"])

    def test_raw_lowercase_fails(self):
        c = case("Ты не знаешь, сколько стоит виза в Японию?", "ты не знаешь сколько стоит виза в японию")
        self.assertFalse(s.score_case(c, c["transcript"])["ok"])


if __name__ == "__main__":
    unittest.main()
