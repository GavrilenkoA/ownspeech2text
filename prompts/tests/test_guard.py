"""Contract tests for prompts/guard.py. The Kotlin port must pass the same cases."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import guard as g  # noqa: E402

TG = {"app": {"category": "messaging"}, "field": {"kind": "message"}}


def ctx(cat="messaging", **kw):
    c = {"app": {"category": cat}, "field": {"kind": "message"}}
    c.update(kw)
    return c


class Precheck(unittest.TestCase):
    def test_skip(self):
        for t in ["", "  ", "э", "Ммм...", "ну э-э ммм", "uh um", "Um.", "[BLANK_AUDIO]", "Thanks for watching!",
                  "Продолжение следует...", "Субтитры сделал DimaTorzok", "Субтитры создавал DimaTorzok",
                  "Редактор субтитров А.Синецкая Корректор А.Егорова", "(музыка)", "Спасибо за просмотр!"]:
            self.assertTrue(g.precheck(t).skip, t)

    def test_keep(self):
        for t in ["ну да", "ок", "Да, конечно.", "um yes", "thanks", "Продолжение следует завтра, обещаю"]:
            p = g.precheck(t)
            self.assertFalse(p.skip, t)
            self.assertEqual(p.transcript, t)

    def test_tail(self):
        p = g.precheck("Буду через десять минут. Субтитры сделал DimaTorzok")
        self.assertEqual((p.skip, p.transcript, p.reason), (False, "Буду через десять минут.", "tail_removed"))
        self.assertEqual(g.precheck("see you soon. Thanks for watching!").transcript, "see you soon.")


class Repair(unittest.TestCase):
    def test_think_and_tokens(self):
        self.assertEqual(g.repair("<think>\nhmm\n</think>\n\nПривет.", "привет"), "Привет.")
        self.assertEqual(g.repair("Привет.<|im_end|>", "привет"), "Привет.")
        self.assertEqual(g.repair("</think>Привет.", "привет"), "Привет.")

    def test_labels_quotes(self):
        self.assertEqual(g.repair("Output: Hello there.", "hello there"), "Hello there.")
        self.assertEqual(g.repair("Текст: Привет.", "привет"), "Привет.")
        self.assertEqual(g.repair('"Hello there."', "hello there"), "Hello there.")
        self.assertEqual(g.repair("«Привет»", "привет"), "Привет")
        self.assertEqual(g.repair("```\nls -la\n```", "эл эс минус эл эй"), "ls -la")
        # quotes that are part of the text stay
        self.assertEqual(g.repair('"A" and "B"', "a and b"), '"A" and "B"')
        self.assertEqual(g.repair('"quoted"', '"quoted"'), '"quoted"')

    def test_before_cursor_repeat(self):
        c = ctx("email", field={"kind": "email_body", "before_cursor": "Hi Priya, thanks for the draft."})
        self.assertEqual(g.repair("Hi Priya, thanks for the draft. I will review it.", "i will review it", c),
                         "I will review it.")


class Rules(unittest.TestCase):
    def v(self, tr, out, c=TG):
        return g.guard(tr, out, c)

    def test_accept(self):
        cases = [
            ("ну короче давай в три нет в четыре", "Давай в 4."),
            ("тимур галеев уже выдал доступ к клик хаусу", "Тимур Галиев уже выдал доступ к ClickHouse."),
            ("я поднял эластик и кибану локально через докер компоуз",
             "Я поднял Elasticsearch и Kibana локально через Docker Compose."),
            ("sarah dot connor at sky net dot io", "sarah.connor@skynet.io"),
            ("слушай давай сделаем code review завтра and i still need to test it",
             "Слушай, давай сделаем code review завтра, and I still need to test it."),
            ("things to do first call the bank second renew the passport",
             "Things to do:\n1. Call the bank.\n2. Renew the passport."),
            ("Sure, I'll be there at six.", "Sure, I'll be there at 6."),
        ]
        for tr, out in cases:
            self.assertEqual(self.v(tr, out).action, "accept", (tr, out, self.v(tr, out)))

    def test_code_field_script_change_ok(self):
        c = ctx("code", field={"kind": "code"})
        self.assertEqual(g.guard("гит чекаут минус бэ фикс", "git checkout -b fix", c).action, "accept")

    def test_r1_empty(self):
        self.assertEqual(self.v("привет как дела", "").reason, "R1_empty")
        self.assertEqual(self.v("привет как дела", "").text, "Привет как дела")

    def test_r2_leak(self):
        self.assertEqual(self.v("привет", "<transcript>\nПривет\n</transcript>").reason, "R2_leaked_tags")
        self.assertEqual(self.v("привет", "Style: casual\nПривет").reason, "R2_leaked_tags")

    def test_r3_preamble(self):
        self.assertEqual(self.v("how do i reset my password", "Sure! To reset your password, open Settings.").reason,
                         "R3_preamble")
        self.assertEqual(self.v("как сбросить пароль", "Конечно, чтобы сбросить пароль, откройте настройки.").reason,
                         "R3_preamble")

    def test_r4_length(self):
        out = "Осень, листья падают с деревьев, ветер гонит их по пустым дорогам, и небо низкое и серое, а дождь стучит в окно всю ночь."
        self.assertEqual(self.v("напиши стих про осень", out).reason, "R4_length")
        self.assertEqual(self.v("мы решили что переносим релиз на следующую неделю потому что тесты падают", "Мы.").reason,
                         "R4_length")

    def test_r5_script(self):
        self.assertEqual(self.v("я задержусь на полчаса извини пожалуйста", "I'll be half an hour late, sorry.").reason,
                         "R5_script")
        self.assertEqual(self.v("слушай давай сделаем завтра and i still need to test it",
                                "Слушай, давай сделаем завтра, и мне ещё нужно это протестировать.").reason, "R5_script")
        self.assertEqual(self.v("i will be late sorry about that", "Я опоздаю, извини.").reason, "R5_script")

    def test_r4_char_loop_and_length(self):
        tr = "call me at four one five five five five zero one nine eight"
        self.assertEqual(self.v(tr, "Call me at 4-1-5-5-5-5-5-5-5-5-5-5-5-5-5-5-5-5-5-5-5-5").reason, "R4_loop")
        self.assertEqual(self.v("ok", "O" + "k" * 120).reason, "R4_loop")
        self.assertEqual(self.v("ok", "Okay-dokey-" * 9).reason, "R4_length")
        self.assertEqual(self.v(tr, "Call me at 415-555-0198.").action, "accept")
        self.assertEqual(self.v("ааааааааа сказал он", "Ааааааааа, сказал он.").action, "accept")  # in the transcript

    def test_r5b_single_word(self):
        self.assertEqual(self.v("honestly я не знаю what he was thinking",
                                "Honestly, I don't know what he was thinking.").reason, "R5b_translated_span")
        self.assertEqual(self.v("я закоммитил в гитхаб и запустил сиай", "Я закоммитил в GitHub и запустил CI.").action,
                         "accept")

    def test_r5b_translated_span(self):
        # an English half rendered in Russian (overall script share barely changes, so R5 cannot see it)
        self.assertEqual(self.v("давай обсудим это offline after the call",
                                "Давай обсудим это offline после звонка.").reason, "R5b_translated_span")
        self.assertEqual(self.v("honestly я не знаю what he was thinking",
                                "Honestly, я не знаю, что он думал.").reason, "R5b_translated_span")
        # transliterated terms rewritten in Latin, hyphenated compounds and self-corrections are not translation
        for tr, out in [("тимофей гаврилов уже обновил ингресс энджин икс и серт менеджер",
                         "Тимофей Гаврилов уже обновил ingress-nginx и cert-manager."),
                        ("логи смотри в графане через локи", "Логи смотри в Grafana через Loki."),
                        ("запусти пайплайн в дженкинсе и посмотри что упало в юнит тестах",
                         "Запусти пайплайн в Jenkins и посмотри, что упало в юнит-тестах."),
                        ("Короче, я закинул файлы в Google Drive, ой, то есть в Dropbox, проверь.",
                         "Я закинул файлы в Dropbox, проверь.")]:
            self.assertEqual(self.v(tr, out).action, "accept", (tr, out))

    def test_r6_novel(self):
        self.assertEqual(self.v("what is the capital of australia", "The capital of Australia is Canberra, a planned city.").reason,
                         "R6_novel")

    def test_r4_loop(self):
        out = "Да да да. " + "Я приду завтра. " * 5
        self.assertEqual(self.v("да я приду завтра", out).reason, "R4_loop")

    def test_r7_distractor(self):
        c = ctx(terms=[{"text": "Тимур Галиев", "score": 1}, {"text": "INV-0093", "score": 0.3}])
        self.assertEqual(g.guard("передай счёт тимуру", "Передай счёт INV-0093 Тимуру.", c).reason, "R7_distractor")
        c = ctx(terms=[{"text": "Тимофей Гаврилов", "score": 1}, {"text": "Тимур Гарипов", "score": 0.9}])
        self.assertEqual(g.guard("тимофей гаврилов обновил конфиг", "Тимур Гарипов обновил конфиг.", c).reason,
                         "R7_distractor")
        self.assertEqual(g.guard("тимофей гаврилов обновил конфиг", "Тимофей Гаврилов обновил конфиг.", c).action,
                         "accept")

    def test_empty_on_noise(self):
        self.assertEqual(self.v("э", "Э.").action, "empty")
        self.assertEqual(self.v("Thanks for watching!", "Thanks for watching!").text, "")


class Fallback(unittest.TestCase):
    def test_caps(self):
        self.assertEqual(g.fallback_text("привет", TG), "Привет")
        self.assertEqual(g.fallback_text("best ramen", ctx("search")), "best ramen")
        mid = ctx(field={"kind": "message", "before_cursor": "Купил билеты на"})
        self.assertEqual(g.fallback_text("концерт в субботу", mid), "концерт в субботу")
        end = ctx(field={"kind": "message", "before_cursor": "Привет!"})
        self.assertEqual(g.fallback_text("как дела", end), "Как дела")


class Phonetic(unittest.TestCase):
    def test_keys(self):
        self.assertEqual(g.phonetic_key("Kubernetes"), g.phonetic_key("кубернетис"))
        self.assertTrue(g.spoken("ClickHouse", "доступ к клик хаусу"))
        self.assertTrue(g.spoken("Raghunathan", "thanks priya ragunathan"))
        self.assertFalse(g.spoken("PagerDuty", "about the data dog alert"))


if __name__ == "__main__":
    unittest.main()
