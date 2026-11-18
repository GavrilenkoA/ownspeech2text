"""Deterministic stub teacher: same interface as OpenAITeacher (complete_json), no network.

Used to test the pipeline plumbing end to end. It parses the batch request written by teacher_prompts,
fills a small hand-written template bank (target, spoken) with the scenario's terms/people and writes a
plausible screen. It is NOT a data source for training (meta.teacher = "stub").
"""
from __future__ import annotations

import json
import random

from ml.common.util import rng as mkrng

# (target, spoken). Slots: {P} person, {T} tech/product, {C} code id, {X} place/company. {сказал} gender-agrees.
BANK = {
    ("ru", "plain"): [
        ("Буду через 10 минут, уже выхожу.", "буду через десять минут уже выхожу"),
        ("Давай созвонимся завтра после обеда, мне так удобнее.", "давай созвонимся завтра после обеда мне так удобнее"),
        ("Скинь, пожалуйста, презентацию, я посмотрю вечером.", "скинь пожалуйста презентацию я посмотрю вечером"),
        ("Мы всё ещё ждём ответа от юристов, так что пока ничего не подписываем.", "мы всё ещё ждём ответа от юристов так что пока ничего не подписываем"),
        ("Купи по дороге хлеб и молоко, а то дома ничего нет.", "купи по дороге хлеб и молоко а то дома ничего нет"),
        ("Я сегодня поработаю из дома, если что, звони.", "я сегодня поработаю из дома если что звони"),
        ("Отличная идея, давай так и сделаем.", "отличная идея давай так и сделаем"),
        ("Посмотрел макеты, в целом всё нравится, но шрифт в заголовке мелковат.", "посмотрел макеты в целом всё нравится но шрифт в заголовке мелковат"),
    ],
    ("en", "plain"): [
        ("I'll be there in 10 minutes, just leaving now.", "i'll be there in ten minutes just leaving now"),
        ("Can you send me the slides? I'll take a look tonight.", "can you send me the slides i'll take a look tonight"),
        ("We're still waiting on legal, so let's not sign anything yet.", "we're still waiting on legal so let's not sign anything yet"),
        ("Sounds good, let's do it that way.", "sounds good let's do it that way"),
        ("I looked at the mockups and I like them overall, but the heading font is a bit small.", "i looked at the mockups and i like them overall but the heading font is a bit small"),
        ("I'm working from home today, so call me if anything comes up.", "i'm working from home today so call me if anything comes up"),
    ],
    ("ru", "self_correction"): [
        ("Давай встретимся в 4 часа.", "давай встретимся в три нет в четыре часа"),
        ("Отправь отчёт в пятницу.", "отправь отчёт в четверг ой нет в пятницу"),
        ("Возьми два билета на вечерний сеанс.", "возьми три билета точнее два билета на вечерний сеанс"),
        ("Созвон переносим на среду.", "созвон переносим на вторник вернее на среду"),
        ("Я буду в офисе после обеда.", "я буду в офисе утром в смысле после обеда"),
    ],
    ("en", "self_correction"): [
        ("The deploy is scheduled for Thursday at 3 PM.", "the deploy is scheduled for friday no wait thursday at three pm"),
        ("Book a table for six people.", "book a table for four people sorry make that six people"),
        ("Send it to the design team.", "send it to the marketing team scratch that send it to the design team"),
        ("Let's meet at the north entrance.", "let's meet at the south entrance i mean the north entrance"),
    ],
    ("ru", "context_resolution"): [
        ("Я посмотрел, что {T} пишет в логи, вроде всё ок.", "я посмотрел что {T} пишет в логи вроде всё ок"),
        ("Давай завтра обсудим, стоит ли тащить {T} в прод.", "давай завтра обсудим стоит ли тащить {T} в прод"),
        ("Настроил {T} по твоей инструкции, работает.", "настроил {T} по твоей инструкции работает"),
    ],
    ("en", "context_resolution"): [
        ("I looked at the {T} logs and it seems fine.", "i looked at the {T} logs and it seems fine"),
        ("Let's discuss tomorrow whether we need {T} at all.", "let's discuss tomorrow whether we need {T} at all"),
    ],
    ("ru", "context_names"): [
        ("{P} {сказал}, что доступ уже выдан.", "{P} {сказал} что доступ уже выдан"),
        ("Да, {P} уже в курсе, отпишусь вечером.", "да {P} уже в курсе отпишусь вечером"),
    ],
    ("en", "context_names"): [
        ("{P} said the config is ready for review.", "{P} said the config is ready for review"),
        ("Can you ask {P} about the migration?", "can you ask {P} about the migration"),
    ],
    ("ru", "numbers"): [
        ("Перевёл тебе 15 200 рублей 25 ноября.", "перевёл тебе пятнадцать тысяч двести рублей двадцать пятого ноября"),
        ("Созвон в 15:30, ссылка будет в календаре.", "созвон в пятнадцать тридцать ссылка будет в календаре"),
        ("Скидка 25% действует до 1 декабря.", "скидка двадцать пять процентов действует до первого декабря"),
        ("Поезд отправляется в 7:45 с 3 пути.", "поезд отправляется в семь сорок пять с третьего пути"),
    ],
    ("en", "numbers"): [
        ("The invoice is $1,200 and it's due on March 3.", "the invoice is twelve hundred dollars and it's due on march third"),
        ("We grew 18% last quarter, up from 12%.", "we grew eighteen percent last quarter up from twelve percent"),
        ("The meeting moved to 4:30 PM on the 21st.", "the meeting moved to four thirty pm on the twenty-first"),
    ],
    ("ru", "formatting_commands"): [
        ("С точки зрения бюджета всё нормально.\n\nОсталось согласовать сроки.", "с точки зрения бюджета всё нормально точка новый абзац осталось согласовать сроки"),
        ("Привет!\nЗавтра не смогу, давай в четверг?", "привет восклицательный знак новая строка завтра не смогу запятая давай в четверг вопросительный знак"),
    ],
    ("en", "formatting_commands"): [
        ("Hi Anna,\nThanks for the update.", "hi anna comma new line thanks for the update period"),
        ("It took a period of time to fix. Done now.", "it took a period of time to fix period done now period"),
    ],
    ("ru", "list"): [
        ("План на завтра:\n1. Позвонить в банк.\n2. Забрать посылку.\n3. Купить подарок.", "план на завтра во-первых позвонить в банк во-вторых забрать посылку и в-третьих купить подарок"),
        ("Что нужно сделать до релиза:\n1. Обновить документацию.\n2. Прогнать тесты.\n3. Предупредить поддержку.", "что нужно сделать до релиза первое обновить документацию второе прогнать тесты третье предупредить поддержку"),
    ],
    ("en", "list"): [
        ("Things to do:\n1. Call the bank.\n2. Renew the passport.\n3. Buy a gift.", "things to do first call the bank second renew the passport and third buy a gift"),
    ],
    ("ru", "dictated_question"): [
        ("Ты не знаешь, во сколько завтра открывается офис?", "ты не знаешь во сколько завтра открывается офис"),
        ("А вы кто вообще? Откуда у вас мой номер?", "а вы кто вообще откуда у вас мой номер"),
        ("Как перенести базу данных на новую версию?", "как перенести базу данных на новую версию"),
    ],
    ("en", "dictated_question"): [
        ("Do you know if the Q3 roadmap was approved?", "do you know if the q3 roadmap was approved"),
        ("What time does the pharmacy close on Sundays?", "what time does the pharmacy close on sundays"),
    ],
    ("ru", "dictated_command"): [
        ("Напиши маме, что я задержусь на полчаса.", "напиши маме что я задержусь на полчаса"),
        ("Напомни мне завтра позвонить в поликлинику.", "напомни мне завтра позвонить в поликлинику"),
        ("Переведи этот договор на английский до пятницы.", "переведи этот договор на английский до пятницы"),
    ],
    ("en", "dictated_command"): [
        ("Translate this paragraph into Spanish and make it more formal.", "translate this paragraph into spanish and make it more formal"),
        ("Remind me to call the dentist tomorrow morning.", "remind me to call the dentist tomorrow morning"),
    ],
    ("ru", "ai_addressed"): [
        ("Напиши стихотворение про осень на четыре строчки.", "напиши стихотворение про осень на четыре строчки"),
        ("Объясни простыми словами, как работает ипотека.", "объясни простыми словами как работает ипотека"),
    ],
    ("en", "ai_addressed"): [
        ("Write a short poem about the sea.", "write a short poem about the sea"),
        ("Explain how a mortgage works in simple terms.", "explain how a mortgage works in simple terms"),
    ],
    ("ru", "continuation"): [
        ("в целом всё ок, но есть пара вопросов по срокам.", "в целом всё ок но есть пара вопросов по срокам", "Привет! Посмотрел договор и"),
        ("Спасибо за письмо. Пришлю ответ до конца недели.", "спасибо за письмо пришлю ответ до конца недели", "Добрый день,"),
    ],
    ("en", "continuation"): [
        ("and the numbers look fine to me.", "and the numbers look fine to me", "I checked the report"),
        ("Thanks for sending the figures. I'll check them this afternoon.", "thanks for sending the figures i'll check them this afternoon", "Hi Priya,"),
    ],
    ("ru", "meaningful_filler"): [
        ("Мне такой вариант не нравится, вот этот лучше.", "мне такой вариант не нравится вот этот лучше"),
        ("Главное — как бы не опоздать на поезд.", "главное как бы не опоздать на поезд"),
    ],
    ("en", "meaningful_filler"): [
        ("I really like the blue one, it looks like the one we had before.", "i really like the blue one it looks like the one we had before"),
        ("I mean it, thank you so much.", "i mean it thank you so much"),
    ],
    ("mixed", "switch"): [
        ("Слушай, давай сделаем code review завтра, а то deadline в пятницу, and I still need to test it.", "слушай давай сделаем code review завтра а то deadline в пятницу and i still need to test it"),
        ("Я посмотрел твой PR, looks good to me, только поправь тесты.", "я посмотрел твой PR looks good to me только поправь тесты"),
        ("Okay, I'll join the call, но мне нужно уйти в 5.", "okay i'll join the call но мне нужно уйти в пять"),
    ],
    ("mixed", "ru_terms"): [
        ("Я запушил фикс в {T} и открыл пул-реквест, глянь, когда будет время.", "я запушил фикс в {T} и открыл пул реквест глянь когда будет время"),
        ("Helm-чарт для {T} я обновил, можно катить на staging.", "Helm чарт для {T} я обновил можно катить на staging"),
        ("Задеплоил новую версию, но {T} пока ругается на конфиг.", "задеплоил новую версию но {T} пока ругается на конфиг"),
    ],
}
SCREEN_MSGS = {
    "ru": ["Привет! Как там дела с задачей?", "Ок, жду", "Можешь глянуть, когда будет минутка?", "Во сколько завтра созвон?",
           "Скинь, пожалуйста, ссылку", "Спасибо!"],
    "en": ["Hey, how's it going with the task?", "Ok, waiting", "Can you take a look when you have a minute?",
           "What time is the call tomorrow?", "Please send me the link", "Thanks!"],
}


def _fill(tpl: str, slots: dict, gender: str) -> str:
    s = tpl
    for k, v in slots.items():
        s = s.replace("{" + k + "}", v)
    s = s.replace("{сказал}", "сказала" if gender == "f" else "сказал")
    s = s.replace("{просил}", "просила" if gender == "f" else "просил")
    return s


def _sentence(spoken: str) -> str:
    s = spoken.strip()
    return (s[:1].upper() + s[1:] + ("" if s.endswith(("?", ".", "!")) else ".")) if s else s


class StubTeacher:
    name = "stub"

    def __init__(self):
        self.gate = None

    def complete_json(self, instructions: str, user_text: str, schema: dict, name: str):
        specs = json.loads(user_text.split("\n", 1)[1])
        return {"examples": [self._one(sp) for sp in specs]}, {"input_tokens": 0, "output_tokens": 0}

    def _one(self, spec: dict) -> dict:
        r = mkrng("stub", json.dumps(spec, sort_keys=True, ensure_ascii=False))
        lang = {"Russian": "ru", "English": "en"}.get(spec["language"], "mixed")
        cat = spec["category"]
        key_cat = {"near_miss": "context_resolution", "context_irrelevant": "plain", "injection": "plain",
                   "no_op": "plain", "near_no_op": "plain", "asr_artifact": "plain", "self_correction": "self_correction",
                   "address": "plain"}.get(cat, cat)
        said = spec.get("said_terms") or []
        people = spec.get("said_people") or []
        slots = {"T": said[0] if said else "Grafana", "P": people[0] if people else ""}
        gender = "f" if slots["P"].split(" ")[-1].endswith(("а", "я")) else "m"
        if cat == "near_miss" and people and not said:
            key_cat = "context_names"
        bl = "ru" if lang == "mixed" and key_cat in ("context_resolution", "context_names") else lang
        if lang == "mixed" and key_cat not in ("context_resolution", "context_names"):
            bank = BANK[("mixed", "ru_terms" if "technical" in spec.get("mix", "") else "switch")]
        else:
            bank = BANK.get((bl, key_cat)) or BANK[(bl if bl != "mixed" else "ru", "plain")]
        item = r.choice(bank)
        target, spoken = _fill(item[0], slots, gender), _fill(item[1], slots, gender)
        before = item[2] if len(item) > 2 and cat == "continuation" else ""
        if cat == "continuation" and not before:
            before = "Привет!" if bl != "en" else "Hi,"
        if cat in ("no_op", "near_no_op"):
            spoken = target
        sl = "en" if lang == "en" else "ru"
        msgs = [{"author": "Коллега" if sl == "ru" else "Colleague", "is_me": False, "text": r.choice(SCREEN_MSGS[sl])}]
        for x in spec.get("screen_must_show", [])[:3]:
            msgs.append({"author": x if (" " in x and x[:1].isupper() and x not in said) else msgs[0]["author"],
                         "is_me": False, "text": (f"Кстати, {x} тоже в теме" if sl == "ru" else f"By the way, {x} is in the loop too")})
        empty = spec.get("screen") == "empty"
        screen = {"title": "" if empty else (people[0] if people else spec["app"]), "messages": [] if empty else msgs[-4:],
                  "before_cursor": before, "snippets": [] if empty else [", ".join(spec.get("screen_also_show", [])[:3])][:1]}
        if screen["snippets"] == [""]:
            screen["snippets"] = []
        return {"sid": spec["sid"], "screen": screen, "spoken": _sentence(spoken), "target": target}
