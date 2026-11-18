"""Load and render the generation prompt kept in ``gen_prompt.md``.

The Markdown file documents the prompt; only the text between
``<!-- BEGIN name -->`` and ``<!-- END name -->`` markers is sent. Blocks use
``string.Template`` placeholders (``$name``).
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from string import Template

REQUIRED_BLOCKS = ("system", "user", "repair")
_BLOCK_RE = re.compile(r"<!-- BEGIN (\w+) -->\n(.*?)\n<!-- END \1 -->", re.DOTALL)


@dataclass(frozen=True)
class PromptSet:
    system: str
    user: Template
    repair: Template
    version: str  # first Markdown heading, recorded in the run state

    def render_user(self, n: int, slots_json: str, examples: str) -> str:
        return self.user.substitute(n=n, slots_json=slots_json, examples=examples)

    def render_repair(self, error: str) -> str:
        return self.repair.substitute(error=error)


def parse_prompt(text: str) -> PromptSet:
    blocks = {m.group(1): m.group(2).strip() for m in _BLOCK_RE.finditer(text)}
    missing = [b for b in REQUIRED_BLOCKS if b not in blocks]
    if missing:
        raise ValueError(f"prompt file lacks blocks {missing}")
    heading = next((ln[2:].strip() for ln in text.splitlines() if ln.startswith("# ")), "unversioned")
    system = Template(blocks["system"]).substitute()  # resolves $$ and rejects stray placeholders
    return PromptSet(system=system, user=Template(blocks["user"]), repair=Template(blocks["repair"]), version=heading)


def load_prompt(path: str | Path) -> PromptSet:
    return parse_prompt(Path(path).read_text(encoding="utf-8"))
