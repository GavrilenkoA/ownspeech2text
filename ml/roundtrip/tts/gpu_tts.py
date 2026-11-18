"""GPU voice-cloning TTS systems for the pod: Qwen3-TTS 1.7B Base and Chatterbox multilingual.

Both clone a voice from a short reference clip (see :mod:`.refvoices`); every accepted reference
clip becomes one voice. Neither is executed on the CPU server: the code is import- and
dry-run-tested only. Facts below were read from the model cards / PyPI on 2026-11-18:

- Qwen3-TTS: ``pip install qwen-tts`` (0.1.1, Apache-2.0, pins transformers==4.57.3), model
  ``Qwen/Qwen3-TTS-12Hz-1.7B-Base`` (Apache-2.0, sha fd4b2543...). Signatures read from the 0.1.1
  wheel source (``qwen_tts/inference/qwen3_tts_model.py``): ``Qwen3TTSModel.from_pretrained(path,
  **kwargs)`` forwards kwargs to ``AutoModel.from_pretrained`` (card: ``device_map="cuda:0",
  dtype=torch.bfloat16, attn_implementation="flash_attention_2"``); ``create_voice_clone_prompt(
  ref_audio, ref_text, x_vector_only_mode) -> List[VoiceClonePromptItem]``;
  ``generate_voice_clone(text: list, language: list, voice_clone_prompt: List[item], ...) ->
  (List[np.ndarray], sr)``, one prompt item per text (a single item is broadcast), so one call can
  batch different voices. Language names are validated case-insensitively ("Russian", "English").
  We download a pinned snapshot first and load from the local directory, because the processor is
  loaded from the path without the revision.
- Chatterbox: ``pip install chatterbox-tts`` (0.1.7, MIT, pins transformers==5.2.0 and torch -- its own
  venv), model ``ResembleAI/chatterbox`` (MIT). From the 0.1.7 wheel source (``chatterbox/mtl_tts.py``):
  ``ChatterboxMultilingualTTS.from_pretrained(device)`` downloads ``t3_mtl23ls_v2`` weights from
  revision ``main``; we instead snapshot the same files at a pinned revision and call
  ``from_local(ckpt_dir, device)`` (both classmethods read from the wheel); ``generate(text, language_id, audio_prompt_path=None,
  exaggeration=0.5, cfg_weight=0.5, temperature=0.8, ...)`` returns a (1, N) torch tensor at
  ``model.sr``; "ru" and "en" are in ``SUPPORTED_LANGUAGES``. The GitHub README's newer
  ``t3_model="v3"`` kwarg is not in 0.1.7; we pass it only when the signature has it. Outputs carry
  Resemble's Perth watermark (inaudible). The README states the project is archived.
- Not executed here (no GPU, and torch would not fit the disk budget): loading, generation speed,
  output quality, flash-attn availability -- all UNVERIFIED until the first pod run.
"""
from __future__ import annotations

import inspect
import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np

from .base import LATIN_NATIVE, TtsDriver, TtsUnavailable, VoiceInfo, to_16k
from .refvoices import RefVoice, load_ref_voices

QWEN3_TTS_MODEL = "Qwen/Qwen3-TTS-12Hz-1.7B-Base"
QWEN3_TTS_REVISION = "fd4b254389122332181a7c3db7f27e918eec64e3"
CHATTERBOX_MODEL = "ResembleAI/chatterbox"
CHATTERBOX_REVISION = "5bb1f6ee58e50c3b8d408bc82a6d3740c2db6e18"  # HF API sha, 2026-11-18
# The exact file set ChatterboxMultilingualTTS.from_pretrained (0.1.7) downloads; all present at the
# pinned revision (checked with the HF tree API). We download them ourselves at that revision and
# call from_local, so the weights cannot drift with ``main``.
CHATTERBOX_FILES = ["ve.pt", "t3_mtl23ls_v2.safetensors", "s3gen.pt", "grapheme_mtl_merged_expanded_v1.json",
                    "conds.pt", "Cangjie5_TC.json"]
QWEN_LANG = {"ru": "Russian", "mixed": "Russian", "en": "English"}


def _ref_dir(explicit: Optional[str]) -> Optional[Path]:
    d = explicit or os.environ.get("MURMUR_REFVOICE_DIR")
    return Path(d) if d else None


class _CloningDriver(TtsDriver):
    gpu = True

    def __init__(self, num_threads: int = 2, ref_dir: Optional[str] = None, device: str = "cuda:0") -> None:
        super().__init__(num_threads)
        self.ref_dir = _ref_dir(ref_dir)
        self.device = device
        self._refs: Optional[Dict[str, RefVoice]] = None
        self.rejected: List[str] = []
        self._model: Any = None

    def refs(self) -> Dict[str, RefVoice]:
        if self._refs is None:
            if self.ref_dir is None:
                self._refs = {}
            else:
                ok, self.rejected = load_ref_voices(self.ref_dir)
                self._refs = {r.voice_id: r for r in ok}
        return self._refs

    def voices(self) -> List[VoiceInfo]:
        return [VoiceInfo(system=self.system, voice=r.voice_id, languages=frozenset({"ru", "en"}),
                          licence=f"model: {self.model_licence}; reference clip: {r.licence}",
                          latin_in_ru=LATIN_NATIVE, extra={"ref_lang": r.lang})
                for r in self.refs().values()]

    model_licence = ""

    def unload(self) -> None:
        self._model = None
        try:
            import torch

            torch.cuda.empty_cache()
        except Exception:  # torch missing or no CUDA: nothing to free
            pass

    def _require_voices(self) -> None:
        if not self.refs():
            raise TtsUnavailable(
                f"{self.system}: no reference voices (set MURMUR_REFVOICE_DIR to a directory with "
                f"manifest.jsonl; rejected: {self.rejected or 'none'})")

    @staticmethod
    def _seed(seed: int) -> None:
        import random

        random.seed(seed)
        np.random.seed(seed % (2 ** 32))
        try:
            import torch
        except ImportError:  # dry runs without torch (unit tests); real runs always have it
            return
        torch.manual_seed(seed)


class Qwen3TtsDriver(_CloningDriver):
    system = "qwen3tts-1.7b"
    model_licence = "Apache-2.0"

    def __init__(self, *a, attn_implementation: str = "flash_attention_2", **kw) -> None:
        super().__init__(*a, **kw)
        self.attn_implementation = attn_implementation
        self._prompts: Dict[str, Any] = {}

    def load(self) -> None:
        if self._model is not None:
            return
        self._require_voices()
        try:
            import torch
            from qwen_tts import Qwen3TTSModel
        except ImportError as exc:
            raise TtsUnavailable(f"{self.system}: pip install qwen-tts (and torch with CUDA): {exc}") from exc
        from huggingface_hub import snapshot_download

        local = snapshot_download(QWEN3_TTS_MODEL, revision=QWEN3_TTS_REVISION)
        self._model = Qwen3TTSModel.from_pretrained(
            local, device_map=self.device, dtype=torch.bfloat16, attn_implementation=self.attn_implementation)

    def unload(self) -> None:
        self._prompts.clear()
        super().unload()

    def _prompt(self, voice_id: str):
        p = self._prompts.get(voice_id)
        if p is None:
            r = self.refs()[voice_id]
            items = self._model.create_voice_clone_prompt(ref_audio=str(r.path), ref_text=r.text,
                                                          x_vector_only_mode=False)
            p = items[0]
            self._prompts[voice_id] = p
        return p

    def _synthesize(self, text: str, voice: VoiceInfo, lang: str, seed: int) -> np.ndarray:
        return self._generate([text], [voice.voice], [lang], seed)[0]

    def _generate(self, texts: List[str], voice_ids: List[str], langs: List[str], seed: int) -> List[np.ndarray]:
        self.load()
        self._seed(seed)
        wavs, sr = self._model.generate_voice_clone(
            text=texts, language=[QWEN_LANG[x] for x in langs],
            voice_clone_prompt=[self._prompt(v) for v in voice_ids])
        if len(wavs) != len(texts):
            raise RuntimeError(f"{self.system}: asked for {len(texts)} clips, got {len(wavs)}")
        return [to_16k(np.asarray(w, dtype=np.float32), sr) for w in wavs]

    def synthesize_batch(self, items: Sequence[Tuple[str, str, str, int]]) -> List[np.ndarray]:
        """One ``generate_voice_clone`` call for the whole batch (one prompt item per text, any voices).

        The batch shares one sampling seed (the first item's)."""
        for _, v, lang, _ in items:
            if not self.voice(v).speaks(lang):
                raise ValueError(f"{self.system}/{v} does not speak {lang!r}")
        if not items:
            return []
        return self._generate([t for t, *_ in items], [v for _, v, _, _ in items], [x[2] for x in items],
                              items[0][3])


class ChatterboxDriver(_CloningDriver):
    system = "chatterbox-mtl"
    model_licence = "MIT"
    exaggeration = 0.5
    cfg_weight = 0.5

    def load(self) -> None:
        if self._model is not None:
            return
        self._require_voices()
        try:
            from chatterbox.mtl_tts import ChatterboxMultilingualTTS
        except ImportError as exc:
            raise TtsUnavailable(f"{self.system}: pip install chatterbox-tts (own venv): {exc}") from exc
        from huggingface_hub import snapshot_download

        device = self.device.split(":")[0] if self.device.startswith("cuda") else self.device
        kw: Dict[str, Any] = {}
        if "t3_model" in inspect.signature(ChatterboxMultilingualTTS.from_local).parameters:
            kw["t3_model"] = "v3"  # newer GitHub API only; 0.1.7 has no such argument
        ckpt = snapshot_download(CHATTERBOX_MODEL, revision=CHATTERBOX_REVISION, allow_patterns=CHATTERBOX_FILES)
        self._model = ChatterboxMultilingualTTS.from_local(ckpt, device, **kw)

    def _synthesize(self, text: str, voice: VoiceInfo, lang: str, seed: int) -> np.ndarray:
        self.load()
        self._seed(seed)
        r = self.refs()[voice.voice]
        # Cross-language cloning inherits the reference accent; README advises cfg_weight=0 then.
        cfg = self.cfg_weight if r.lang == ("en" if lang == "en" else "ru") else 0.0
        wav = self._model.generate(text, language_id="en" if lang == "en" else "ru",
                                   audio_prompt_path=str(r.path), exaggeration=self.exaggeration,
                                   cfg_weight=cfg)
        arr = wav.detach().float().cpu().numpy().reshape(-1)
        return to_16k(arr, int(self._model.sr))
