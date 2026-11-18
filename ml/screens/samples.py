"""Hand-written teacher-like screen contents (the shape of ml/data/synth/teacher_prompts.py "screen") per
family, and a deterministic spec sampler over templates x devices x themes x font scales.
Used for the golden suite, visual review and throughput measurements; the real dataset gets its content
from the teacher through specs.from_teacher()."""
from __future__ import annotations

import random

from .specs import from_teacher

S = {
 "chat_ru": {"title": "Лёша Козлов", "messages": [
     {"author": "Лёша Козлов", "is_me": False, "text": "Привет! Скинь ссылку на дашборд в Grafana по kube-prometheus"},
     {"author": "", "is_me": True, "text": "Ещё нужен доступ к ClickHouse кластеру prod-eu-2?"},
     {"author": "Лёша Козлов", "is_me": False, "text": "Да, и к Airflow тоже. Ревью PR #4182 назначил на Тимура Галиева 😉"}],
     "snippets": ["Созвон в 15:30 перенесён на четверг"], "before_cursor": ""},
 "chat_en": {"title": "Ingrid Sørensen", "messages": [
     {"author": "Ingrid Sørensen", "is_me": False, "text": "Did the Terraform plan for eu-west-1 go through?"},
     {"author": "", "is_me": True, "text": "Almost, the S3 bucket policy still fails on the KMS key"},
     {"author": "Ingrid Sørensen", "is_me": False, "text": "Ping Jürgen Weiß, he owns the IAM roles 🙏"}], "snippets": [], "before_cursor": ""},
 "group_mixed": {"title": "Бэкенд команда", "messages": [
     {"author": "Марина Ефимова", "is_me": False, "text": "Кто посмотрит flaky тест в CI? Опять упал test_payment_retry"},
     {"author": "Тимур Галиев", "is_me": False, "text": "Я гляну после стендапа, похоже на race condition в Redis lock"},
     {"author": "", "is_me": True, "text": "ок, я пока откачу релиз 3.14.2"},
     {"author": "Марина Ефимова", "is_me": False, "text": "Спасибо! И не забудьте про ретро в пятницу в 16:00"}],
     "snippets": ["Задача MUR-1423: миграция на PostgreSQL 17"], "before_cursor": ""},
 "work_en": {"title": "infra-alerts", "messages": [
     {"author": "Priya Raghunathan", "is_me": False, "text": "Heads up: p99 latency on checkout-api is 840 ms since the 14:05 deploy"},
     {"author": "Nikolai Petrov", "is_me": False, "text": "Rolling back to v2.31.7 now, Datadog monitor is still red"},
     {"author": "", "is_me": True, "text": "Thanks, I'll open an incident in PagerDuty"}],
     "snippets": ["INC-2291 · SEV2 · checkout-api"], "before_cursor": ""},
 "mail_ru": {"title": "Договор №17/2026 с ООО «Вектор»", "messages": [
     {"author": "Маргарита Юрьевна", "is_me": False, "text": "Добрый день! Направляю правки к договору. Просьба согласовать до пятницы, 21 ноября."},
     {"author": "", "is_me": True, "text": "Маргарита Юрьевна, спасибо, посмотрю сегодня."}],
     "snippets": ["Вложение: dogovor_v3.docx"], "before_cursor": "Маргарита Юрьевна, добрый день!\n"},
 "mail_en": {"title": "Q3 roadmap: Kubernetes autoscaling", "messages": [
     {"author": "Priya Raghunathan", "is_me": False, "text": "Hi team, attaching the draft for review. Key items: HPA on custom metrics, Karpenter for node pools, and cost dashboards in Looker."}],
     "snippets": [], "before_cursor": ""},
 "note_ru": {"title": "Идеи для доклада", "messages": [
     {"author": "", "is_me": True, "text": "3. Сравнить Whisper large-v3 и Parakeet на русской речи"}],
     "snippets": ["доклад", "ML"], "before_cursor": "1. Как мы ускорили инференс в 4 раза\n2. Квантизация Q4_0 против Q8_0\n"},
 "doc_en": {"title": "Design doc: on-device OCR", "messages": [
     {"author": "", "is_me": True, "text": "The detector runs once per dictation on the screenshot taken 0.6 s after the start."},
     {"author": "Ingrid Sørensen", "is_me": False, "text": "Do we need the NPU path for v1, or is CPU fine?"}],
     "snippets": ["Latency budget: 300 ms", "Models: PP-OCRv6 tiny det"], "before_cursor": "Open questions: "},
 "article_ru": {"title": "Яндекс открыл исходники YDB для аналитики", "messages": [
     {"author": "", "is_me": False, "text": "Компания опубликовала новую версию распределённой СУБД с поддержкой колоночных таблиц и федеративных запросов."},
     {"author": "", "is_me": False, "text": "По словам разработчиков, производительность на TPC-H выросла в 2,5 раза по сравнению с прошлым релизом."}],
     "snippets": ["Как устроен ClickHouse изнутри", "Kafka против Redpanda: тесты"], "before_cursor": ""},
 "search_en": {"title": "llama.cpp gguf q4_0 vs q4_k_m quality", "messages": [
     {"author": "github.com", "is_me": False, "text": "Discussion: perplexity of Q4_0, Q4_K_M and IQ4_XS on Qwen3.5 models, measured with wikitext-2."},
     {"author": "reddit.com", "is_me": False, "text": "Q4_K_M is usually the sweet spot, but Q4_0 is faster on ARM with repacking."}],
     "snippets": ["What is imatrix quantization?"], "before_cursor": ""},
 "pr_en": {"title": "Fix WhisperKit crash on cold start", "messages": [
     {"author": "dvoronin", "is_me": False, "text": "LGTM overall. Can you add a test for the AudioRecord buffer underrun?"},
     {"author": "", "is_me": True, "text": "Added in 3f2a9c1, also bumped onnxruntime to 1.32.0"}],
     "snippets": ["ci / build-android (pull_request) — passed"], "before_cursor": ""},
 "docs_en": {"title": "Configure the QNN execution provider", "messages": [
     {"author": "Prerequisites", "is_me": False, "text": "Install the Qualcomm AI Engine Direct SDK and set QNN_SDK_ROOT before building onnxruntime."},
     {"author": "", "is_me": False, "text": "Use the HTP backend for quantized models; FP16 precision requires Snapdragon 8 Gen 2 or newer."}],
     "snippets": ["sess_options.add_session_config_entry('ep.context_enable', '1')"], "before_cursor": ""},
 "term": {"title": "ssh build-01", "messages": [
     {"author": "", "is_me": True, "text": "kubectl get pods -n murmur-staging"},
     {"author": "", "is_me": False, "text": "NAME                         READY   STATUS             RESTARTS\nasr-worker-7c9f8d6b5-x2kqp   1/1     Running            0\nllm-gateway-5f4b9c7d8-9wz2m  0/1     CrashLoopBackOff   12"}],
     "snippets": [], "before_cursor": "kubectl logs llm-gateway-5f4b9c7d8-9wz2m "},
 "code": {"title": "pipeline.py", "messages": [
     {"author": "", "is_me": False, "text": "def run(image, exclude_rects=()):\n    rgb = load_rgb(image)\n    boxes = detect(rgb)\n    return recognise(rgb, boxes)"}],
     "snippets": ["TODO: batch the recogniser by width bucket"], "before_cursor": "    # "},
 "issue_ru": {"title": "Падает экспорт GGUF для LFM3-1.7B", "messages": [
     {"author": "Тимур Галиев", "is_me": False, "text": "При конвертации convert_hf_to_gguf.py падает на слое conv. Версия llama.cpp b12503."},
     {"author": "Марина Ефимова", "is_me": False, "text": "Воспроизвела на чистом окружении, нужен патч в gguf-py"}],
     "snippets": ["export", "blocker"], "before_cursor": ""},
 "ai_ru": {"title": "", "messages": [
     {"author": "", "is_me": True, "text": "Объясни разницу между LoRA и QLoRA в двух абзацах"},
     {"author": "", "is_me": False, "text": "LoRA добавляет к замороженным весам низкоранговые матрицы и обучает только их.\nQLoRA делает то же самое поверх модели, квантованной в 4 бита (NF4), что сильно экономит память GPU."}],
     "snippets": [], "before_cursor": ""},
 "cal_en": {"title": "Thu, Nov 20", "messages": [
     {"author": "Ingrid Sørensen", "is_me": False, "text": "Design review: Murmur OCR", "time": "10:30"},
     {"author": "", "is_me": False, "text": "1:1 with Priya", "time": "14:00"}],
     "snippets": ["Send the Q3 report to Nikolai"], "before_cursor": ""},
 "social_ru": {"title": "", "messages": [
     {"author": "Клуб любителей бега", "is_me": False, "text": "В субботу забег в Сокольниках, старт в 9:00 у главного входа. Берите воду! 🏃"},
     {"author": "Ольга Ветрова", "is_me": False, "text": "Я приду с подругой, можно?"}],
     "snippets": [], "before_cursor": ""},
 "empty": {"title": "", "messages": [], "snippets": [], "before_cursor": ""},
}

# content -> (app package, label, category, field kind, lang)
APPS = {
    "chat_ru": ("org.telegram.messenger", "Telegram", "messaging", "message", "ru"),
    "chat_en": ("com.whatsapp", "WhatsApp", "messaging", "message", "en"),
    "group_mixed": ("org.telegram.messenger", "Telegram", "messaging", "message", "mixed"),
    "work_en": ("com.slack", "Slack", "work_chat", "message", "en"),
    "mail_ru": ("com.google.android.gm", "Gmail", "email", "email_body", "ru"),
    "mail_en": ("com.google.android.gm", "Gmail", "email", "email_body", "en"),
    "note_ru": ("com.google.android.keep", "Keep Notes", "notes", "note", "ru"),
    "doc_en": ("com.google.android.apps.docs.editors.docs", "Docs", "notes", "note", "en"),
    "article_ru": ("com.android.chrome", "Chrome", "search", "generic", "ru"),
    "search_en": ("com.android.chrome", "Chrome", "search", "search", "en"),
    "pr_en": ("com.github.android", "GitHub", "code", "code", "en"),
    "docs_en": ("com.sec.android.app.sbrowser", "Samsung Internet", "search", "generic", "en"),
    "term": ("com.termux", "Termux", "code", "code", "en"),
    "code": ("com.example.codeeditor", "Code Editor", "code", "code", "en"),
    "issue_ru": ("com.atlassian.android.jira.core", "Jira", "work_chat", "message", "ru"),
    "ai_ru": ("com.openai.chatgpt", "ChatGPT", "default", "generic", "ru"),
    "cal_en": ("com.google.android.calendar", "Calendar", "default", "generic", "en"),
    "social_ru": ("com.vkontakte.android", "VK", "social", "message", "ru"),
    "empty": ("org.telegram.messenger", "Telegram", "messaging", "message", "ru"),
}
# content -> templates it fits
FITS = {
    "chat_ru": ["messenger_a", "messenger_b"], "chat_en": ["messenger_b", "messenger_a"], "group_mixed": ["messenger_a", "work_chat"],
    "work_en": ["work_chat"], "mail_ru": ["email_compose", "email_thread"], "mail_en": ["email_thread", "email_inbox"],
    "note_ru": ["notes"], "doc_en": ["doc_editor"], "article_ru": ["browser_article"], "search_en": ["browser_search"],
    "pr_en": ["browser_pr"], "docs_en": ["browser_docs"], "term": ["terminal"], "code": ["code_editor"],
    "issue_ru": ["issue_tracker"], "ai_ru": ["ai_chat"], "cal_en": ["calendar_tasks"], "social_ru": ["social_feed"],
    "empty": ["messenger_a", "notes", "ai_chat"],
}


def sample_spec(i: int, seed: int = 0, content: str | None = None) -> dict:
    r = random.Random(f"samples|{seed}|{i}")
    key = content or r.choice(sorted(S))
    pkg, label, cat, kind, lang = APPS[key]
    tpl = r.choice(FITS[key])
    sc = S[key]
    spec = from_teacher(sc, app={"package": pkg, "label": label, "category": cat}, field_kind=kind, lang=lang,
                        seed=r.randrange(1 << 30), template=tpl)
    spec["id"] = f"s{seed}-{i:05d}-{key}"
    return spec
