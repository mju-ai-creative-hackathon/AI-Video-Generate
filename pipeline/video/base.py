"""영상 생성 어댑터 인터페이스."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path


@dataclass
class ClipRequest:
    image_path: Path  # crop_box로 잘라 input_size로 맞춘 사진
    prompt: str
    frames: int
    fps: int
    seed: int
    camera_move: str
    out_path: Path


class VideoProvider(ABC):
    name: str
    requires_gpu: bool = False

    @property
    @abstractmethod
    def input_size(self) -> tuple[int, int]:
        """입력 이미지의 (너비, 높이)."""

    @abstractmethod
    def generate(self, request: ClipRequest) -> Path:
        """클립을 request.out_path에 저장한다."""

    def release(self) -> None:
        """단계가 끝나면 모델을 내린다. GPU에는 한 번에 한 모델만 올린다."""
