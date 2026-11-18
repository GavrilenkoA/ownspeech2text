"""Gold table for the style-vs-error decision on Latin terms written in Cyrillic.

Pairs come from real round-trip output (local proof and the 80-probe review run, 2026-11-18/19) plus the
reviewer's counter-examples. ``translit`` = the term written the way it is said (style the cleanup LLM
undoes by restoring the Latin spelling); ``error`` = a mishearing (wrong consonant or voicing, wrong
stressed vowel, missing or extra sounds, a different word).
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip.mine.align import ERROR_KINDS, align  # noqa: E402

T, E = "translit", "error"
GOLD: list[tuple[str, str, str]] = [
    # faithful renderings
    ("Kafka", "кафка", T), ("Kafka", "кафке", T), ("Kubernetes", "кубернетис", T), ("Kubernetes", "кубернетес", T),
    ("Kubernetes", "кубернетисе", T), ("Kubernetes", "кубер", T), ("JSON", "джейсон", T), ("Redis", "редис", T),
    ("Greenplum", "гринплам", T), ("Greenplum", "гринплум", T), ("Kafka Connect", "кафка коннект", T),
    ("Kafka Connect", "кафка конект", T), ("Heroku", "хероку", T), ("Heroku", "херроку", T), ("Heroku", "хэрокку", T),
    ("tmux", "тимукс", T), ("MongoDB", "монго диби", T), ("MongoDB", "монгодиби", T),
    ("DigitalOcean", "диджита лоушен", T), ("DigitalOcean", "диджит аллоушен", T), ("Crashlytics", "краш-литикс", T),
    ("Pydantic", "пайдантик", T), ("prefill", "префил", T), ("prefill", "префилов", T), ("API", "апи", T),
    ("API", "эй пи ай", T), ("SLA", "эс эл эй", T), ("VAD", "вад", T), ("TTL", "ти ти эл", T), ("TTL", "ттл", T),
    ("SSD", "эс эс ди", T), ("DPO", "дипио", T), ("DPO", "ди пи о", T), ("Silero", "силеро", T), ("Kotlin", "котлин", T),
("RabbitMQ", "рэббит эм кью", T), ("LlamaIndex", "ламейндекс", T),
    ("LM Studio", "лм студио", T), ("Android Studio", "андроид студия", T), ("values.yaml", "вальюс ямел", T),
    ("hallucination", "халлсинейшен", T), ("Ollama", "оллама", T), ("ONNX", "онникс", T), ("ONNX", "оникс", T),
    ("Sentry", "сентри", T), ("Slack", "слак", T), ("Slack", "слэк", T), ("ClickHouse", "клик хаус", T),
    ("ClickHouse", "кликхаус", T), ("GitHub", "гитхаб", T), ("Docker", "докер", T), ("Grafana", "графана", T),
    ("Jira", "джира", T), ("Jira", "жира", T), ("LoRA", "лора", T), ("LoRA", "лору", T), ("CORS", "корс", T),
    ("PEFT", "пефт", T), ("XSS", "икс эс эс", T), ("Traefik", "трафик", T),
    ("Webpack", "вебпак", T), ("SGLang", "эс джи ланг", T), ("pgBouncer", "пиджи баунсер", T),
    ("Fold7", "фолд семь", T), ("TensorFlow Lite", "тензорфлоу лайт", T), ("Helm chart", "хелм чарт", T),
    ("Elasticsearch", "эластиксерч", T), ("Nginx", "энджинкс", T), ("Laura", "лорой", T), ("Laura", "лаурой", T),
    ("Mark", "марка", T), ("Mark", "марком", T), ("YouTrack", "ютрек", T), ("YouTrack", "ютрэк", T),
    ("FLUX", "флакс", T), ("Q4_K_M", "ку четыре ка эм", T),
    # mishearings
    ("Kafka", "кофка", E), ("Kafka", "кавка", E), ("Kafka", "каска", E), ("Kafka Connect", "кавка конект", E),
    ("Slack", "слаг", E), ("Sentry", "центре", E), ("package.json", "пакедж сон", E), ("package.json", "пакетж", E),
    ("Heroku", "гироку", E), ("Heroku", "сероку", E), ("Heroku", "яроку", E), ("Redis", "рейдис", E),
    ("Redis", "редисп", E), ("Redis", "тредист", E), ("tmux", "макс", E), ("MongoDB", "мангодибе", E),
    ("Crashlytics", "краш-летекс", E), ("Pydantic", "пай-дотик", E), ("Pydantic", "предантик", E),
    ("Pydantic", "падотик", E), ("prefill", "приёфил", E), ("prefill", "префел", E), ("API", "апе", E),
    ("SLA", "слэй", E), ("VAD", "ват", E), ("VAD", "вед", E), ("TTL", "тети л", E), ("TTL", "тел", E), ("SSD", "с д", E),
    ("Silero", "селеру", E), ("Kotlin", "котлям", E), ("Cloudflare", "клод флэр", E), ("Elasticsearch", "эластич", E),
    ("Elasticsearch", "и ластик сэрч", E), ("Nginx", "инжингс", E), ("Webpack", "эпак", E), ("Ollama", "лама", E),
    ("XSS", "иксс", E), ("Traefik", "трафе", E), ("SGLang", "с-гланг", E), ("pgBouncer", "пиджин баунсер", E),
    ("pgBouncer", "пиди банцер", E), ("Fold7", "фолсе", E), ("YouTrack", "ветрек", E), ("YouTrack", "витрек", E),
    ("ClickHouse", "клипхаус", E), ("ClickHouse", "клыкхаус", E), ("GitHub", "гитхап", E), ("LoRA", "лоор", E),
    ("LoRA", "лога", E), ("CORS", "косе", E), ("RabbitMQ", "рабит мк", E), ("CoreDNS", "кордин", E), ("PEFT", "пирта", E), ("Greenplum", "гримплум", E),
    ("Kubernetes", "кубернетез", E), ("Kubernetes", "кубернец", E), ("FLUX", "флекс", E), ("JSON", "сон", E),
    ("Q4_K_M", "ку четыре крем", E), ("build.gradle.kts", "белграду ктс", E), ("Helm chart", "хельм шард", E),
]


def _kind(term: str, hyp: str) -> str:
    al = align(term, hyp)
    return E if any(op.kind in ERROR_KINDS for op in al.ops) else T


def test_gold_table_size():
    assert len(GOLD) >= 100 and len({(t, h) for t, h, _ in GOLD}) == len(GOLD)


@pytest.mark.parametrize("term,hyp,expected", GOLD)
def test_translit_vs_error(term, hyp, expected):
    assert _kind(term, hyp) == expected


def test_term_in_context_keeps_its_kind():
    # the same decisions inside a sentence, with the case ending a Russian speaker adds
    al = align("Kafka отстаёт, ссылку кину в Slack.", "Кофка отстаёт, ссылку кину в слак")
    got = {al.ref_span(op): op.kind for op in al.ops}
    assert got["Kafka"] in ERROR_KINDS and got["Slack"] == "translit"
