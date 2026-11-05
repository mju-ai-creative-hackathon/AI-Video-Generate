"""나노바나나(Gemini 이미지) 어댑터.

요청 형식은 aistudio.google.com/docs/image-generation의 Interactions API에서 확인했다(2026-11-06).
이미지 응답의 원본 구조는 문서에 없어, 같은 API의 영상 응답(steps[].content[])과 SDK 예제를 따랐다.
"""

from __future__ import annotations

import base64
import io
import logging
import time
from pathlib import Path
from typing import Any

import httpx
from PIL import Image

from ..config import ImageSettings
from ..errors import ConfigError, ImageError
from .base import ImageProvider, ImageRequest

log = logging.getLogger(__name__)
URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
API_REVISION = "2026-05-20"
RETRYABLE = {429, 500, 502, 503, 504}
EXTENSIONS = {"image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp"}


class NanoBananaImage(ImageProvider):
    name = "nanobanana"

    def __init__(
        self,
        api_key: str,
        settings: ImageSettings,
        *,
        transport: httpx.BaseTransport | None = None,
        sleep=time.sleep,  # noqa: ANN001
    ) -> None:
        if not api_key:
            raise ConfigError("GEMINI_API_KEY가 없습니다. .env에 넣으세요.")
        self.settings = settings
        self._sleep = sleep
        self._client = httpx.Client(
            headers={"x-goog-api-key": api_key, "Api-Revision": API_REVISION},
            timeout=settings.timeout_sec,
            transport=transport,
        )

    def signature(self) -> dict[str, Any]:
        return {
            "provider": self.name,
            "model": self.settings.model,
            "image_size": self.settings.image_size,
        }

    def generate(self, request: ImageRequest) -> Path:
        cfg = self.settings
        body = {
            "model": cfg.model,
            "input": [
                {"type": "text", "text": request.prompt},
                {
                    "type": "image",
                    "mime_type": "image/jpeg",
                    "data": base64.b64encode(request.image_path.read_bytes()).decode("ascii"),
                },
            ],
            "response_format": {
                "type": "image",
                "aspect_ratio": "9:16",
                "image_size": cfg.image_size,
            },
            # 이어서 수정하지 않으므로 사진이 담긴 대화를 서버에 남기지 않는다.
            "store": False,
        }
        raw, mime = _extract_image(self._post_with_retry(body))
        try:
            Image.open(io.BytesIO(raw)).verify()
        except Exception as exc:  # noqa: BLE001 - Pillow는 형식별로 다른 예외를 낸다
            raise ImageError(f"나노바나나 응답을 이미지로 읽지 못했습니다: {exc}") from exc
        out_path = request.out_stem.with_suffix(EXTENSIONS.get(mime, ".png"))
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_bytes(raw)
        return out_path

    def _post_with_retry(self, body: dict[str, Any]) -> dict[str, Any]:
        cfg = self.settings
        last_error = ""
        for attempt in range(cfg.max_retries + 1):
            try:
                response = self._client.post(URL, json=body)
                if response.status_code == 200:
                    return response.json()
                last_error = f"{response.status_code}: {response.text[:800]}"
                if response.status_code in (401, 403):
                    raise ConfigError(f"나노바나나 인증 실패 {last_error}")
                if response.status_code not in RETRYABLE:
                    raise ImageError(f"나노바나나 오류 {last_error}")
            except httpx.TransportError as exc:
                last_error = str(exc)
            if attempt < cfg.max_retries:
                delay = 2.0**attempt
                log.warning("나노바나나 재시도 %d회차 (%.0f초 후): %s", attempt + 1, delay, last_error)
                self._sleep(delay)
        raise ImageError(f"나노바나나 호출 실패 ({cfg.max_retries + 1}회 시도): {last_error}")


def _extract_image(data: dict[str, Any]) -> tuple[bytes, str]:
    """응답에서 첫 이미지를 찾는다. 없으면 안전 필터 등으로 거절된 것이므로 모델의 설명을 보여준다."""
    texts: list[str] = []
    for step in data.get("steps") or []:
        for block in step.get("content") or []:
            if block.get("type") == "image" and block.get("data"):
                raw = base64.b64decode(block["data"])
                return raw, block.get("mime_type", "image/png")
            if block.get("type") == "text" and block.get("text"):
                texts.append(block["text"])
    detail = " ".join(texts)[:500] or f"status={data.get('status')}, 키={sorted(data)}"
    raise ImageError(f"나노바나나가 이미지를 돌려주지 않았습니다: {detail}")
