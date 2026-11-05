"""무음 TTS. API 키와 요금 없이 전체 흐름을 시험할 때 쓴다 (`--tts silent`)."""

from __future__ import annotations

import wave
from pathlib import Path
from typing import Any

from ..subtitles import proportional_words
from ..validation import spoken_chars
from .base import TTSProvider, TTSRequest, TTSResult

SAMPLE_RATE = 48000


class SilentTTS(TTSProvider):
    name = "silent"
    extension = ".wav"

    def __init__(self, chars_per_sec: float = 5.5) -> None:
        self.chars_per_sec = chars_per_sec

    def signature(self) -> dict[str, Any]:
        return {"provider": self.name, "chars_per_sec": self.chars_per_sec}

    def synthesize(self, request: TTSRequest, out_path: Path) -> TTSResult:
        duration = max(0.5, spoken_chars(request.text) / self.chars_per_sec)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(out_path), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(SAMPLE_RATE)
            handle.writeframes(b"\x00\x00" * int(duration * SAMPLE_RATE))
        return TTSResult(words=proportional_words(request.text, duration))
