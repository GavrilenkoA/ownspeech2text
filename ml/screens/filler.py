"""Deterministic filler content around the teacher's screen content (other chats, inbox rows, results, UI
strings). Generic everyday text only: no technical terms, products or brands, so filler never supplies a
term a category rule cares about. `avoid` (case-folded substrings) removes any filler item that contains one.
"""
from __future__ import annotations

import random

NAMES_RU = ["Анна Смирнова", "Дмитрий Орлов", "Мама", "Папа", "Ольга Ветрова", "Сергей Кузнецов", "Катя",
            "Игорь Белов", "Наталья Павловна", "Миша Лебедев", "Светлана Морозова", "Артём", "Юля Соколова",
            "Бабушка", "Вера Николаевна", "Павел Громов", "Женя", "Ирина Ковалёва", "Роман Зайцев", "Оксана"]
NAMES_EN = ["Emma Wilson", "James Carter", "Mom", "Olivia Brown", "Liam Johnson", "Sophie Martin", "Noah Davis",
            "Chloe Taylor", "Ethan Moore", "Grace Lee", "Lucas White", "Mia Clark", "Ben Harris", "Ava Lewis"]
GROUPS_RU = ["Семья", "Дом 12, подъезд 3", "Родительский чат 5Б", "Друзья", "Дача", "Бег по субботам", "Кино"]
GROUPS_EN = ["Family", "Book club", "Neighbours", "Weekend hikes", "Football", "Flatmates"]
MSG_RU = ["Хорошо, договорились", "Купи, пожалуйста, хлеб", "Я уже выхожу", "Спасибо большое!", "Перезвоню позже",
          "Фото с выходных", "Ты дома?", "Отлично 👍", "Завтра в семь, не забудь", "С днём рождения! 🎉",
          "Посмотри, когда будет время", "Ок", "Голосовое сообщение", "Где встречаемся?", "Уже еду",
          "Можно я позвоню вечером?", "Ахаха 😂", "Скинь адрес", "Всё получилось", "Не забудь зонт ☔"]
MSG_EN = ["Sounds good!", "Can you pick up milk?", "On my way", "Thanks so much!", "Call you later",
          "Photos from the weekend", "Are you home?", "Great 👍", "Tomorrow at seven", "Happy birthday! 🎉",
          "Let me know when you're free", "OK", "Voice message", "Where are we meeting?", "See you soon",
          "Haha 😂", "Send me the address", "All done", "Don't forget the umbrella ☔"]
SUBJ_RU = ["Счёт за ноябрь", "Ваш заказ отправлен", "Приглашение на встречу", "Напоминание о записи",
           "Новости недели", "Подтверждение бронирования", "Квитанция об оплате", "Изменения в расписании"]
SUBJ_EN = ["Your order has shipped", "Invoice for November", "Meeting invitation", "Appointment reminder",
           "Weekly newsletter", "Booking confirmation", "Payment receipt", "Schedule change"]
SNIP_RU = ["Здравствуйте! Напоминаем, что", "Добрый день, во вложении", "Спасибо за покупку. Ваш заказ",
           "Уважаемый клиент, сообщаем", "Привет! Как и договаривались"]
SNIP_EN = ["Hi there, just a reminder that", "Hello, please find attached", "Thanks for your purchase. Your order",
           "Dear customer, we would like to", "Hey! As we discussed"]
SENDERS_RU = ["Почта России", "Магазин у дома", "Поликлиника №3", "Банк", "Кинотеатр", "Школа №57", "ЖКХ Сервис"]
SENDERS_EN = ["City Library", "Corner Store", "Dental Clinic", "Bank", "Cinema", "School Office", "Travel Desk"]
WEEKDAYS_RU = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
WEEKDAYS_EN = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
MONTHS_RU = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября",
             "ноября", "декабря"]
MONTHS_EN = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
TASKS_RU = ["Позвонить в поликлинику", "Оплатить интернет", "Купить подарок", "Забрать посылку", "Записаться к врачу",
            "Полить цветы", "Сдать отчёт", "Продлить страховку"]
TASKS_EN = ["Call the dentist", "Pay the internet bill", "Buy a gift", "Pick up the parcel", "Book a haircut",
            "Water the plants", "Renew insurance", "Return library books"]
EVENTS_RU = ["Встреча с командой", "Обед", "Тренировка", "Родительское собрание", "Созвон", "Стоматолог", "Кино"]
EVENTS_EN = ["Team sync", "Lunch", "Gym", "Parent meeting", "Call", "Dentist", "Movie night"]

UI = {
    "ru": {"message": "Сообщение", "search": "Поиск", "chats": "Чаты", "calls": "Звонки", "contacts": "Контакты",
           "settings": "Настройки", "online": "в сети", "recently": "был(а) недавно", "today": "Сегодня",
           "yesterday": "Вчера", "inbox": "Входящие", "primary": "Несортированные", "compose": "Написать",
           "to": "Кому", "subject": "Тема", "from": "От", "reply": "Ответить", "forward": "Переслать",
           "pinned": "Закреплённое сообщение", "notes": "Заметки", "all_notes": "Все заметки", "tasks": "Задачи",
           "updates": "Обновления", "communities": "Сообщества", "home": "Главная", "dms": "Личные", "activity": "Упоминания",
           "you": "Вы", "edited": "изменено", "typing": "печатает…", "write_note": "Начните писать",
           "mail": "Почта", "meet": "Видеовстречи", "new_chat": "Новый чат", "ask": "Спросите что-нибудь",
           "comment": "Добавить комментарий", "reply_thread": "Ответить в ветке", "unread": "непрочитанных",
           "members": "участников", "files": "Файлы", "more": "Ещё", "all": "Все", "work": "Работа", "personal": "Личное",
           "sent": "Отправлено", "drafts": "Черновики", "assignee": "Исполнитель", "status": "Статус",
           "priority": "Приоритет", "in_progress": "В работе", "todo": "К выполнению", "done": "Готово",
           "labels": "Метки", "description": "Описание", "activity_tab": "Активность", "comments": "Комментарии"},
    "en": {"message": "Message", "search": "Search", "chats": "Chats", "calls": "Calls", "contacts": "Contacts",
           "settings": "Settings", "online": "online", "recently": "last seen recently", "today": "Today",
           "yesterday": "Yesterday", "inbox": "Inbox", "primary": "Primary", "compose": "Compose", "to": "To",
           "subject": "Subject", "from": "From", "reply": "Reply", "forward": "Forward", "pinned": "Pinned message",
           "notes": "Notes", "all_notes": "All notes", "tasks": "Tasks", "updates": "Updates",
           "communities": "Communities", "home": "Home", "dms": "DMs", "activity": "Activity", "you": "You",
           "edited": "edited", "typing": "typing…", "write_note": "Start writing", "mail": "Mail", "meet": "Meet",
           "new_chat": "New chat", "ask": "Ask anything", "comment": "Add a comment", "reply_thread": "Reply in thread",
           "unread": "unread", "members": "members", "files": "Files", "more": "More", "all": "All", "work": "Work",
           "personal": "Personal", "sent": "Sent", "drafts": "Drafts", "assignee": "Assignee", "status": "Status",
           "priority": "Priority", "in_progress": "In progress", "todo": "To do", "done": "Done", "labels": "Labels",
           "description": "Description", "activity_tab": "Activity", "comments": "Comments"},
}


class Filler:
    def __init__(self, r: random.Random, locale: str, avoid=()):
        self.r = r
        self.ru = locale == "ru"
        self.avoid = [a.casefold() for a in avoid if a]

    def ok(self, s: str) -> bool:
        f = s.casefold()
        return not any(a in f for a in self.avoid)

    def pick(self, pool_ru, pool_en, k=1, mix=0.15):
        """k distinct items from the locale's pool (with a small share from the other language)."""
        out = []
        for _ in range(k * 4):
            pool = pool_ru if (self.ru ^ (self.r.random() < mix)) else pool_en
            s = self.r.choice(pool)
            if s not in out and self.ok(s):
                out.append(s)
            if len(out) == k:
                break
        return out

    def names(self, k):
        return self.pick(NAMES_RU + GROUPS_RU, NAMES_EN + GROUPS_EN, k)

    def msgs(self, k):
        return self.pick(MSG_RU, MSG_EN, k)

    def clock(self) -> str:
        return f"{self.r.randint(7, 23)}:{self.r.randint(0, 59):02d}"

    def time_ago(self, i: int) -> str:
        """Chat-list style timestamp: clock for today, weekday, then date."""
        if i < 3:
            return self.clock()
        if i < 6:
            return (WEEKDAYS_RU if self.ru else WEEKDAYS_EN)[self.r.randint(0, 6)]
        d = self.r.randint(1, 28)
        m = self.r.randint(0, 11)
        return f"{d:02d}.{m + 1:02d}" if self.ru else f"{MONTHS_EN[m]} {d}"

    def date_sep(self) -> str:
        d, m = self.r.randint(1, 28), self.r.randint(0, 11)
        return (f"{d} {MONTHS_RU[m]}" if self.ru else f"{MONTHS_EN[m]} {d}") if self.r.random() < 0.6 else \
            (UI["ru" if self.ru else "en"]["today"])
