import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import render  # noqa: E402

# Rendered on 2026-11-18 with the official chat_template.jinja of each model (jinja2 3.1.6, HF
# {% generation %} tags removed), messages = system SYS, user U1, assistant A1, user U2,
# add_generation_prompt=True, enable_thinking=False.
OFFICIAL = {
    "qwen3.5": "<|im_start|>system\nSYS<|im_end|>\n<|im_start|>user\nU1<|im_end|>\n<|im_start|>assistant\nA1<|im_end|>\n"
               "<|im_start|>user\nU2<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n",
    "lfm3": "<|startoftext|><|im_start|>system\nSYS<|im_end|>\n<|im_start|>user\nU1<|im_end|>\n<|im_start|>assistant\n"
            "<think></think>A1<|im_end|>\n<|im_start|>user\nU2<|im_end|>\n<|im_start|>assistant\n<think></think>",
    "lfm2.5": "<|startoftext|><|im_start|>system\nSYS<|im_end|>\n<|im_start|>user\nU1<|im_end|>\n<|im_start|>assistant\n"
              "A1<|im_end|>\n<|im_start|>user\nU2<|im_end|>\n<|im_start|>assistant\n",
    "qwen3": "<|im_start|>system\nSYS<|im_end|>\n<|im_start|>user\nU1<|im_end|>\n<|im_start|>assistant\nA1<|im_end|>\n"
             "<|im_start|>user\nU2<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n",
}


class Render(unittest.TestCase):
    def test_goldens_unchanged(self):
        for inp in sorted((HERE / "examples").glob("golden_*.input.json")):
            g = json.loads(inp.read_text(encoding="utf-8"))
            stem = inp.name[: -len(".input.json")]
            for kind in ("default", "finetuned"):
                for tpl in ("qwen3.5", "lfm3"):
                    want = (HERE / "examples" / f"{stem}.{tpl}.{kind}.txt").read_text(encoding="utf-8")
                    got = render.chat(render.system_prompt(kind, g.get("preferences")),
                                      render.user_message(g["context"], g["transcript"]), tpl)
                    self.assertEqual(got, want, f"{stem} {tpl} {kind}")

    def test_turns_match_official_templates(self):
        for tpl, want in OFFICIAL.items():
            self.assertEqual(render.chat_turns("SYS", [("U1", "A1")], "U2", tpl), want, tpl)
            self.assertEqual(render.chat("SYS", "U2", tpl) if tpl not in ("qwen3.5", "lfm3") else
                             render.chat_turns("SYS", [], "U2", tpl), render.chat_turns("SYS", [], "U2", tpl))
        for tpl in ("qwen3.5", "lfm3"):
            self.assertEqual(render.chat("S", "U", tpl), render.chat_turns("S", [], "U", tpl))

    def test_prefix_is_prefix(self):
        profiles = ["default", "finetuned"] + [p.stem for p in (HERE / "profiles").glob("*.json")]
        g = json.loads((HERE / "examples" / "golden_ru.input.json").read_text(encoding="utf-8"))
        for prof in profiles:
            for tpl in render.TEMPLATES:
                full = render.render_prompt(prof, g["context"], g["transcript"], tpl)
                pre = render.cache_prefix(prof, tpl)
                self.assertTrue(full.startswith(pre), (prof, tpl))
                self.assertNotIn(g["transcript"][:20], pre)

    def test_versioned_names(self):
        self.assertEqual(render.system_prompt("default"), render.system_prompt("default.v1"))


if __name__ == "__main__":
    unittest.main()
