"""Few-shot demonstrations for profiles default.v4 / default.v4s (ctx v2: screens rendered + OCR'd by
build_v2.py through the same producer as the eval sets).

= the 13 v3 shots + 2 aimed at the v3 dev failures that matter most for OCR context:
  s4-ru-english-screen  Russian dictation on an English screen stays Russian (q2b translated 3 dev cases to match
                        the screen language)
  s4-mx-ocr-term        a garbled spoken term is spelled as on screen; other screen text is ignored
SHORT lists the ids of default.v4s.
"""
from ._build import case, ctx
from .shots_v3 import CASES as V3

S = "train"

NEW = [
    case("s4-ru-english-screen", "ru", "code_switch",
         "скину апдейт по релизу после обеда",
         "Скину апдейт по релизу после обеда.",
         ctx("Slack", "ru", title="Ryan Cole", msgs=[("Ryan Cole", "Any update on the release? QA is waiting for the build")])),
    case("s4-mx-ocr-term", "mixed", "context_names",
         "глянул в кибане это не энджинкс а таймаут апстрима",
         "Глянул в Kibana, это не nginx, а таймаут апстрима.",
         ctx("Telegram", "ru", title="Денис", msgs=[("Денис", "Глянь логи в Kibana, там опять 502 от nginx на проде")]),
         keep=["Kibana", "nginx"]),
]
for c in NEW:
    c["split"] = S
NEW[0]["meta"]["screen_locale"] = "en"  # English Slack screen, Russian dictation

# order: the new shots go before the final empty-output demo
CASES = V3[:-1] + NEW + V3[-1:]
SHORT = ["s3-ru-selfcorr", "s3-en-whisper-fillers", "s3-mx-codeswitch", "s3-ru-spoken-format",
         "s3-ru-address", "s4-ru-english-screen", "s3-en-continuation", "s3-en-noise"]
