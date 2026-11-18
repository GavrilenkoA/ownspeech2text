"""TTS drivers for the round trip (inference only): CPU Piper / Supertonic 3 / Kokoro, GPU Qwen3-TTS / Chatterbox."""
from .base import LATIN_NATIVE, LATIN_RESPELL, TtsDriver, TtsUnavailable, VoiceInfo, choose_tts_input
from .registry import CPU_SYSTEMS, GPU_SYSTEMS, SYSTEMS, get_tts, resolve_systems

__all__ = ["CPU_SYSTEMS", "GPU_SYSTEMS", "LATIN_NATIVE", "LATIN_RESPELL", "SYSTEMS", "TtsDriver",
           "TtsUnavailable", "VoiceInfo", "choose_tts_input", "get_tts", "resolve_systems"]
