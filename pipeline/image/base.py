"""컷 이미지 생성 어댑터 인터페이스."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass
class ImageRequest:
    image_path: Path  # crop_box로 잘라 9:16으로 맞춘 사진
    prompt: str
    out_stem: Path  # 확장자는 어댑터가 응답 형식에 맞춰 붙인다


class ImageProvider(ABC):
    name: str

    @abstractmethod
    def signature(self) -> dict[str, Any]:
        """결과에 영향을 주는 설정. 바뀌면 이미지를 다시 만든다."""

    @abstractmethod
    def generate(self, request: ImageRequest) -> Path:
        """이미지를 저장하고 경로를 돌려준다."""
