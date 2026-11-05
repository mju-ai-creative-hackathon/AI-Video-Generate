"""TTS 어댑터 인터페이스."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..subtitles import WordTiming


@dataclass
class TTSRequest:
    text: str
    previous_text: str | None = None
    next_text: str | None = None


@dataclass
class TTSResult:
    # 빈 목록이면 타임스탬프가 없다는 뜻이다. 자막은 글자 수에 비례해 배분한다.
    words: list[WordTiming] = field(default_factory=list)


class TTSProvider(ABC):
    name: str
    extension: str = ".mp3"

    @abstractmethod
    def signature(self) -> dict[str, Any]:
        """입력 해시에 넣을 음성 설정."""

    @abstractmethod
    def synthesize(self, request: TTSRequest, out_path: Path) -> TTSResult:
        """음성을 out_path에 저장한다."""
