"""피사체 검출 인터페이스.

구도 데이터 구축과 입력 사진 분석은 같은 검출 도구를 써야 상대값을 비교할 수 있다.
검출 도구는 데이터셋 구축 단계에서 라이선스를 확인한 뒤 확정하므로, 지금은 추가 모델 없이
쓸 수 있는 OpenCV 얼굴 검출만 제공한다. 확정되면 이 인터페이스의 구현을 바꾼다.
"""

from __future__ import annotations

from pathlib import Path
from typing import Protocol

import cv2
import numpy as np
from PIL import Image


class SubjectDetector(Protocol):
    def detect_faces(self, image_path: Path) -> list[list[float]]:
        """얼굴 위치를 [x1, y1, x2, y2] (0~1) 목록으로 돌려준다."""
        ...


class OpenCVFaceDetector:
    def __init__(self, min_size_ratio: float = 0.06) -> None:
        self.min_size_ratio = min_size_ratio
        self._cascade = cv2.CascadeClassifier(
            cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
        )

    def detect_faces(self, image_path: Path) -> list[list[float]]:
        with Image.open(image_path) as opened:
            gray = np.asarray(opened.convert("L"))
        height, width = gray.shape
        min_side = max(24, int(min(width, height) * self.min_size_ratio))
        boxes = self._cascade.detectMultiScale(
            gray, scaleFactor=1.1, minNeighbors=6, minSize=(min_side, min_side)
        )
        return [
            [x / width, y / height, (x + w) / width, (y + h) / height]
            for (x, y, w, h) in np.asarray(boxes).reshape(-1, 4).tolist()
        ]


def union_box(boxes: list[list[float]]) -> list[float]:
    return [
        min(b[0] for b in boxes),
        min(b[1] for b in boxes),
        max(b[2] for b in boxes),
        max(b[3] for b in boxes),
    ]
