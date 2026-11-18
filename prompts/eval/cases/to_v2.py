"""Migrate hand-written v1 cases (structured screen context) to ctx v2 (screen = OCR lines).

Each v1 case gets a render spec (CONTEXT_V2.md spec format + fixture keys, see prompts/eval/ocr_fixture.py)
that shows on a phone screen what the v1 ctx described: chat title, messages, the v1 "terms" (as a pinned
line when they were not already in the visible text), snippets, the field. Screens of apps that would show
something in reality get neutral chrome/filler text (hand-written pools below; a filler that shares a word
with the case is skipped). The ctx is then produced by ml.screens.build_ctx when ml/screens/READY exists,
else by the fixture (meta.ctx_source says which).

meta.check.screen_terms = the strings the case depends on that must be visible on screen: keep entities
found in the v1 screen text but not in the transcript, plus the injection payload of injection cases.
"""
from __future__ import annotations

import hashlib
import re

from prompts.render import NO_SCREEN_LEVEL

# no-screen eval cases cycle through the phone's reasons (screenshot_error/ocr_empty: level full; others app_only)
NO_SCREEN_REASONS = ("secure_window", "screenshot_error", "denylisted_app", "ocr_empty")
TEMPLATE = {"messaging": "chat", "work_chat": "chat", "social": "feed", "email": "mail", "notes": "notes",
            "code": "terminal", "search": "browser", "address": "browser", "default": "notes"}

NAMES = {"ru": ["Марина Белова", "Игорь", "Ольга Сергеевна", "Дима Лапин", "Катя", "Рабочий чат"],
         "en": ["Megan Doyle", "Tom", "Family", "Raj Patel", "Chris W.", "Team updates"]}
FILLER = {"ru": ["Окей, договорились", "Я на месте буду минут через десять", "Посмотри, когда будет время",
                 "Спасибо!", "А фотки скинешь потом?", "Ок, понял", "Хорошо, жду"],
          "en": ["Sounds good", "Can you check this when you have a sec?", "Thanks!", "On my way",
                 "Let me know how it went", "Got it", "See you then"]}
CHROME = {"mail": {"ru": ["Новое письмо", "Кому", "Тема"], "en": ["New message", "To", "Subject"]},
          "notes": {"ru": ["Заметки", "Все заметки"], "en": ["Notes", "All notes"]},
          "terminal": {"ru": ["~/projects $ ls", "README.md  src  tests"], "en": ["~/projects $ ls", "README.md  src  tests"]},
          "browser": {"ru": ["Картинки Видео Новости Карты", "Недавние запросы"], "en": ["Images Videos News Maps", "Recent searches"]},
          "feed": {"ru": ["Главная", "Для вас"], "en": ["Home", "For you"]}}
TIMES = ["09:14", "10:02", "11:37", "12:41", "13:05", "14:22", "18:50", "21:16"]
# Older on-screen history (hand-written, neutral, everyday). Prepended so that screens carry a realistic amount
# of unrelated text; the fixture/renderer layout clips what scrolls off. Skipped when it shares a word with the case.
HISTORY = {"ru": ["Доброе утро! Как спалось?", "Нормально, только кофе закончился", "Купи по дороге молоко, пожалуйста",
                  "Ладно, после работы зайду в магазин", "Ты видел, какая погода на выходных обещают?",
                  "Вроде дождь в субботу, а в воскресенье солнце", "Тогда на дачу едем в воскресенье",
                  "Мама звонила, спрашивала про праздники", "Я ей вечером перезвоню", "Ключи оставлю у соседки",
                  "Не забудь зарядку, в прошлый раз забыли", "Скинь, пожалуйста, адрес ещё раз",
                  "Я в пробке, опаздываю минут на пятнадцать", "Ничего страшного, мы тоже задерживаемся",
                  "Кто-нибудь брал мой зонт?", "Он в прихожей висит", "С днём рождения! Счастья и здоровья!",
                  "Спасибо большое, очень приятно", "Посылку уже доставили?", "Да, лежит на почте до пятницы"],
           "en": ["Morning! How did you sleep?", "Fine, but we're out of coffee", "Can you grab some milk on the way?",
                  "Sure, I'll stop by the store after work", "Did you see the forecast for the weekend?",
                  "Rain on Saturday, sunny on Sunday apparently", "Then let's do the hike on Sunday",
                  "Mom called asking about the holidays", "I'll call her back tonight", "I left the keys with the neighbor",
                  "Don't forget the charger this time", "Can you send the address again?",
                  "Stuck in traffic, running about fifteen late", "No worries, we're late too",
                  "Has anyone seen my umbrella?", "It's hanging by the door", "Happy birthday! Have a great one!",
                  "Thank you so much!", "Did the package arrive?", "Yes, it's at the post office until Friday"]}
PAGE = {"mail": {"ru": ["Входящие", "Добрый день! Напоминаем, что офис будет закрыт в праздничные дни, с 31 декабря по 8 января включительно.",
                        "Пропуска на парковку продлеваются автоматически, ничего делать не нужно.",
                        "Если у вас остались вопросы, пишите на общий адрес отдела.", "С уважением, отдел кадров",
                        "Отправить", "Ответить всем", "Переслать"],
                 "en": ["Inbox", "Hi all, a reminder that the office will be closed over the holidays, from December 24 through January 2.",
                        "Parking passes renew automatically, nothing to do on your side.",
                        "If you have any questions, reply to the team address.", "Best regards, HR team",
                        "Send", "Reply all", "Forward"]},
        "notes": {"ru": ["Список дел на неделю", "Позвонить в банк насчёт карты", "Записаться к стоматологу",
                         "Купить подарок к субботе", "Оплатить интернет до двадцатого", "Вернуть книги в библиотеку"],
                  "en": ["To-do this week", "Call the bank about the card", "Book a dentist appointment",
                         "Buy a present for Saturday", "Pay the internet bill by the 20th", "Return the library books"]},
        "browser": {"ru": ["Прогноз погоды на 10 дней", "Сегодня облачно, ночью до минус трёх, ветер северо-западный",
                           "Курсы валют на сегодня", "Как приготовить сырники: простой рецепт с фото",
                           "Расписание электричек на завтра", "Отзывы покупателей и вопросы о товаре",
                           "Похожие запросы", "Показать ещё"],
                    "en": ["10-day weather forecast", "Cloudy today, low of 28 tonight, northwest wind",
                           "Exchange rates today", "How to make pancakes: an easy recipe with photos",
                           "Train timetable for tomorrow", "Customer reviews and questions",
                           "Related searches", "Show more"]},
        "terminal": {"ru": ["Last login: Tue Nov 17 21:04", "~/projects $ cd app", "~/projects/app $ ls -la"],
                     "en": ["Last login: Tue Nov 17 21:04", "~/projects $ cd app", "~/projects/app $ ls -la"]},
        "feed": {"ru": ["Новый пост: фото с прогулки по набережной", "Нравится: 128"],
                 "en": ["New post: photos from the walk by the river", "Likes: 128"]}}


def _h(s: str) -> int:
    return int(hashlib.sha256(s.encode()).hexdigest()[:8], 16)


def _words(s: str) -> set[str]:
    return {w[:5] for w in re.findall(r"\w+", (s or "").casefold()) if len(w) >= 5}


def _flat(xs) -> list[str]:
    out = []
    for x in xs or []:
        out += list(x) if isinstance(x, (list, tuple)) else [x]
    return out


def v1_screen_text(ctx: dict) -> str:
    conv = ctx.get("conversation") or {}
    parts = [conv.get("title") or ""] + [m.get("author") or "" for m in conv.get("messages") or []]
    parts += [m.get("text") or "" for m in conv.get("messages") or []]
    parts += [t.get("text", "") for t in ctx.get("terms") or []] + list(ctx.get("screen_snippets") or [])
    return "\n".join(parts)


def screen_terms(case: dict) -> list[str]:
    ctx, chk = case["ctx"], case["meta"]["check"]
    scr, tr = v1_screen_text(ctx).casefold(), case["transcript"].casefold()
    out = []
    for k in chk.get("keep", []):
        alts = k if isinstance(k, list) else [k]
        hit = [a for a in alts if a.casefold() in scr and a.casefold() not in tr]
        if hit:
            out.append(hit[0])
    if case["category"] == "injection":
        for f in chk.get("forbid", []):
            if f.casefold() in scr and f.casefold() not in tr:
                out.append(f)
                break
    return out


def spec_for(case: dict, filler: bool = True, pools: bool = True, force_dense: bool = False) -> dict:
    """v1 case -> render spec (deterministic in the case id). filler=False (few-shot turns, which are prefill
    cost on every request): only the case's own screen content and minimal chrome, no history/page text.
    pools=False (real renderer): no filler from this module's pools; ml.screens' own template filler is used."""
    c1, cid = case["ctx"], case["id"]
    app = dict(c1.get("app") or {})
    if "package" not in app:  # smoke cases carry only label/category
        from ._build import PKG
        app = {"package": PKG.get(app.get("label"), ("unknown", ""))[0], **app}
    cat = app.get("category") or "default"
    tpl = TEMPLATE.get(cat, "notes")
    lang = (c1.get("language") or {}).get("asr") or "ru"
    loc = case["meta"].get("screen_locale") or ("en" if lang == "en" else "ru")
    h = _h(cid)
    f1 = c1.get("field") or {}
    conv = c1.get("conversation") or {}
    privacy = (c1.get("privacy") or {}).get("level", "full")
    case_words = _words(case["transcript"]) | _words(case["target"]) | _words(" ".join(_flat(case["meta"]["check"].get("keep"))))
    case_words |= _words(" ".join(case["meta"]["check"].get("forbid", [])))

    def ok(s: str) -> bool:
        return not (_words(s) & case_words)
    dense = force_dense or (filler and h % 4 == 0)  # inner display, keyboard hidden, long history/page: the screens where the budget binds
    spec: dict = {"template": tpl, "device": "fold7_inner" if dense or h % 5 == 0 else "fold7_cover",
                  "theme": "dark" if h % 3 == 0 else "light", "font_scale": 1.0, "locale": loc,
                  "app": app, "language": dict(c1.get("language") or {"asr": lang}), "seed": h,
                  "field": {"kind": f1.get("kind") or "generic"}, "keyboard": not dense and h % 3 != 0}
    if f1.get("before_cursor"):
        spec["field"]["before_cursor"] = f1["before_cursor"]
    if privacy in ("app_only", "none"):
        spec["privacy"] = {"level": privacy, "reason": "user_setting"}
        spec["screen"] = False
        return spec
    title = conv.get("title") or ""
    msgs = [{"author": m.get("author") or ("" if m.get("is_me") else title), "is_me": bool(m.get("is_me")),
             "text": m["text"], "time": TIMES[(h + i) % len(TIMES)]} for i, m in enumerate(conv.get("messages") or [])]
    snippets = list(c1.get("screen_snippets") or [])
    has_content = bool(title or msgs or snippets or c1.get("terms"))
    if filler and not force_dense and not screen_terms(case) and case["category"] not in ("injection", "context_names", "continuation") \
            and h % 8 == 0:
        spec["screen"] = False  # no screenshot: must exist in eval data; level per reason = render.NO_SCREEN_LEVEL
        reason = NO_SCREEN_REASONS[(h // 8) % len(NO_SCREEN_REASONS)]
        spec["privacy"] = {"level": NO_SCREEN_LEVEL[reason], "reason": reason}
        return spec
    if pools and tpl in ("chat", "feed") and not title:
        title = next((n for n in NAMES[loc][h % 6:] + NAMES[loc] if ok(n)), "")
    if pools and tpl == "chat" and not msgs:
        pool = [t for t in FILLER[loc] if ok(t)]
        for i in range(1 + h % 2 if pool else 0):
            msgs.append({"author": title, "is_me": i == 1, "text": pool[(h + i) % len(pool)],
                         "time": TIMES[(h + i) % len(TIMES)]})
    if pools and tpl in CHROME and not (msgs or snippets):
        snippets = [s for s in CHROME[tpl][loc] if ok(s)] + snippets
    if pools and tpl in PAGE and filler:  # unrelated page text above the case's own content
        n_page = len(PAGE[tpl][loc]) if dense else 2 + h % (len(PAGE[tpl][loc]) - 1)
        extra = []
        if dense:  # long thread / page body: earlier history as paragraphs of two sentences
            hp = [t for t in HISTORY[loc] if ok(t)]
            extra = [hp[i] + " " + hp[i + 1] for i in range(0, len(hp) - 1, 2)]
        snippets = [s for s in PAGE[tpl][loc][:n_page] if ok(s)] + extra + snippets
    if pools and tpl == "chat" and filler:  # older history: 2-7 messages, alternating sides
        pool = [t for t in HISTORY[loc] if ok(t)]
        k = len(pool) if dense else min(len(pool), 2 + h % 6)
        texts = [pool[(h // 7 + i) % len(pool)] for i in range(k)]
        if dense:  # long multi-line bubbles
            texts = [" ".join(texts[i:i + 3]) for i in range(0, len(texts), 3)]
        old = [{"author": title, "is_me": i % 2 == 1, "text": t, "time": TIMES[i % len(TIMES)]}
               for i, t in enumerate(texts)]
        msgs = old + msgs
    shown = (title + "\n" + "\n".join(m["text"] + "\n" + m["author"] for m in msgs) + "\n".join(snippets)).casefold()
    missing = [t["text"] for t in sorted(c1.get("terms") or [], key=lambda t: -float(t.get("score", 0)))
               if t["text"].casefold() not in shown]
    if missing:
        snippets = snippets + [", ".join(missing)]
    if title:
        spec["title"] = title
    if msgs:
        spec["messages"] = msgs
    if snippets:
        spec["snippets"] = snippets
    spec["filler"] = filler
    spec["protect"] = sorted({s for s in _flat(case["meta"]["check"].get("keep")) + case["meta"]["check"].get("forbid", [])
                              + [t["text"] for t in c1.get("terms") or []] + ([title] if title else [])
                              + [m["author"] for m in msgs if m["author"]] if len(s) >= 3})
    return spec


def migrate(case: dict, build_ctx, filler: bool = True) -> tuple[dict, dict]:
    """-> (v2 case, spec). build_ctx: ml.screens.build_ctx or the fixture."""
    spec = spec_for(case, filler)
    c2 = {**case, "ctx": build_ctx(spec), "meta": {**case["meta"], "check": dict(case["meta"]["check"])}}
    st = screen_terms(case)
    if st:
        c2["meta"]["check"]["screen_terms"] = st
    return c2, spec


def screens_spec(spec: dict, case: dict) -> dict:
    """This module's spec -> ml.screens render spec, through ml.screens.specs.from_teacher (template, font scale and
    hint chosen there, deterministically from the seed). Their template filler supplies unrelated screen text
    (off for few-shot turns); the case's own strings are passed as `avoid`."""
    from ml.screens.specs import from_teacher
    f = spec.get("field") or {}
    screen = {"title": spec.get("title", ""), "messages": spec.get("messages", []), "snippets": spec.get("snippets", []),
              "before_cursor": f.get("before_cursor", "")}
    privacy = "full"
    if spec.get("screen") is False:  # any non-"full" value makes build_ctx skip render OCR; privacy_fix sets the final level
        privacy = (spec.get("privacy") or {}).get("level", "full")
        privacy = "no_screenshot" if privacy == "full" else privacy
    s = from_teacher(screen, spec["app"], field_kind=f.get("kind") or "generic", lang=case.get("lang", "ru"),
                     seed=spec["seed"], device=spec["device"], theme=spec["theme"], keyboard=spec["keyboard"],
                     locale=spec["locale"], privacy=privacy, avoid=spec.get("protect", []))
    s["filler"] = bool(spec.get("filler", True))
    if spec.get("screen") is False:
        s["privacy_reason"] = (spec.get("privacy") or {}).get("reason")
    return s


def privacy_fix(ctx: dict, spec: dict) -> dict:
    """build_ctx output for a no-screen spec -> privacy {level, reason} as the phone sends it (render.NO_SCREEN_LEVEL).
    build_ctx cannot express "level full, no screenshot" (it OCRs every full-level spec), so the level is set here."""
    pv = spec.get("privacy") or {}
    if spec.get("screen") is False and pv.get("reason"):
        ctx["privacy"] = {"level": pv.get("level", "full"), "reason": pv["reason"]}
        ctx.pop("screen", None)
    return ctx
