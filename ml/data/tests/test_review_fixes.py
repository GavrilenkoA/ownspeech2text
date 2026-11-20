"""Generator rules added after the 2026-11-20 data review (7 minor findings)."""
import random

from ml.data.synth import build as B
from ml.data.synth import channel as CH


def test_binding_rule_cyrillic_only_for_well_known():
    # F1: a Cyrillic rendering is restored without screen support only for a well-known term
    assert B.term_restore_ok("Docker", "докир")
    assert B.term_restore_ok("Jira", "Джира")
    assert B.term_restore_ok("DNS", "ди эн эс")
    assert not B.term_restore_ok("Traefik", "трефик")
    assert not B.term_restore_ok("OpenTable", "апентабл")
    assert not B.term_restore_ok("cream", "крем")
    # spelling variants stay restorable; a Latin misspelling only for a well-known term; another word never
    assert B.term_restore_ok("Confluence", "Confloence")
    assert not B.term_restore_ok("Parquet", "Parqet")
    assert B.term_restore_ok("zero-shot", "Zero Shot")
    assert B.term_restore_ok("DocuSign", "docu sign")
    assert not B.term_restore_ok("Traefik", "Traffic")
    assert B.is_well_known("NLP-сервиса") and not B.is_well_known("Traefik")


def test_unsupported_term_garble_uses_binding_rule():
    ctx = {"v": 2, "app": {"category": "messaging"}}
    ev = ["term:Traefik->трефик:observed_style"]
    assert B.unsupported_term_garbles(ev, "Traefik упал", ctx) == ev
    assert B.unsupported_term_garbles(["term:Docker->докир:garbled"], "Docker упал", ctx) == []


def test_real_word_guess():
    # F2: another real word restored without support is a guess; agreement repairs are not
    B.set_vocab(["would", "could", "проверю", "проверь", "понедельник", "понедельника", "появилось", "отвалилось"])
    try:
        assert B.real_word_guess("Could", "Would", "Could you send it?", "")
        assert B.real_word_guess("Проверь", "Проверю", "Проверь логи.", "")
        assert B.real_word_guess("отвалилось", "появилось", "Всё отвалилось.", "")
        assert not B.real_word_guess("понедельника", "понедельник", "До понедельника.", "")
        assert not B.real_word_guess("Could", "Would", "Could you send it?", "Could you")  # on screen
        assert not B.real_word_guess("Could", "Cuold", "Could you send it?", "")         # not a real word
        assert B.guessed_subs(["sub:Could->Would", "sub:the->a"], "Could you send the file?", "") == ["sub:Could->Would"]
    finally:
        B.set_vocab([])


def test_same_lemma_examples():
    assert B.same_lemma("ответа", "ответы") and B.same_lemma("новой", "новый") and B.same_lemma("hours", "hour")
    assert B.same_lemma("there", "their")
    for a, b in [("можете", "можешь"), ("успела", "успел"), ("Укажите", "Укажи"), ("вечера", "вчера"),
                 ("передать", "перед"), ("date", "data")]:
        assert not B.same_lemma(a, b), (a, b)


def test_numbers_rules():
    # F6
    assert B.digit_durations("Give me ten minutes, two or three hours.") == "Give me 10 minutes, 2 or 3 hours."
    assert B.digit_durations("Через пять минут, одну минуту.") == "Через 5 минут, одну минуту."
    assert B.digits_spelled_out("Give me 10 minutes", "Give me ten minutes.", "en") == [10]
    assert B.digits_spelled_out("Give me 10 minutes", "Give me 10 minutes.", "en") == []
    assert "sc-6a9f1251f168" in B.BAD_TEACHER_EXAMPLES
    assert B.address_slug_guess("ритмфит точка ру слэш расписание", "ritmfit.ru/raspisanie") == "raspisanie"
    assert B.address_slug_guess("Greenhouse dot io slash Northstar Labs", "greenhouse.io/northstarlabs") is None
    assert B.address_slug_guess("Maya dot Ellis at Gmail dot com", "maya.ellis@gmail.com") is None


def test_artifact_pools_disjoint():
    # F7: held-out splits never see a train string
    for lg in ("ru", "en"):
        tr, ho = set(B.tails_for(lg, "train")), set(B.tails_for(lg, "dev"))
        assert tr and ho and not tr & ho
        assert B.tails_for(lg, "test") == B.tails_for(lg, "dev")
    hyp, loop = B.stutter_loop("Считаем через сентенс трансформерс", random.Random(1))
    assert loop and loop.split()[0] * len(loop.split()) == loop.replace(" ", "") and loop in hyp


class _Ch(CH.Channel):
    def __init__(self, style):
        self._style = style


def test_final_style():
    # F5: the model's end-mark rate, also after a spoken command; straight apostrophes
    ch = _Ch({"m": {"ru": {"end_given_punct": 1.0}, "en": {"end_given_punct": 0.0}}})
    assert ch._final_style("ну да", "ну да", "m", "ru", [], random.Random(0)) == "ну да."
    ev = []
    assert ch._final_style("Ты придёшь, вопросительный знак", "Ты придёшь вопросительный знак", "m", "ru", ev,
                           random.Random(0)) == "Ты придёшь, вопросительный знак."
    assert ch._final_style("It’s fine, okay.", "It's fine, okay.", "m", "en", [], random.Random(0)) == "It's fine, okay"
    assert ch._final_style("без знаков", "без знаков", "m", "ru", ["style:no_punct"], random.Random(0)) == "без знаков"
