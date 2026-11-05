"""Ollama /api/chat 어댑터. 동작은 docs.ollama.com/api/chat 기준으로 확인했다."""

from __future__ import annotations

import base64
import logging
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import httpx

from ..config import LLMSettings
from ..errors import LLMError
from .base import LLMClient

log = logging.getLogger(__name__)
# 메모리가 빠듯하면 모델 로드가 연달아 실패한다(로그에서 3회 연속 실패를 확인함).
SERVER_ERROR_RETRIES = 4
SERVER_ERROR_DELAY_SEC = 10.0
HOLD_KEEP_ALIVE = "10m"


class OllamaLLM(LLMClient):
    def __init__(
        self,
        base_url: str,
        settings: LLMSettings,
        *,
        transport: httpx.BaseTransport | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._sleep = sleep
        self._holding = False
        self._held_models: set[str] = set()
        self.settings = settings
        self._client = httpx.Client(
            base_url=base_url, timeout=settings.timeout_sec, transport=transport
        )

    @contextmanager
    def hold_models(self) -> Iterator[None]:
        """사진마다 17GB 모델을 다시 올리면 느리고 로드 실패 기회도 그만큼 늘어난다."""
        self._holding = True
        try:
            yield
        finally:
            self._holding = False
            for model in self._held_models:
                self._unload(model)
            self._held_models.clear()

    def _unload(self, model: str) -> None:
        try:
            self._client.post("/api/generate", json={"model": model, "keep_alive": 0})
        except httpx.HTTPError as exc:
            log.warning("모델 %s을 내리지 못했습니다: %s", model, exc)

    def chat_json(
        self,
        *,
        model: str,
        system: str,
        prompt: str,
        schema: dict[str, Any],
        images: list[Path] | None = None,
    ) -> str:
        user: dict[str, Any] = {"role": "user", "content": prompt}
        if images:
            user["images"] = [base64.b64encode(p.read_bytes()).decode("ascii") for p in images]
        body: dict[str, Any] = {
            "model": model,
            "messages": [{"role": "system", "content": system}, user],
            "format": schema,
            "stream": False,
            # 0이면 응답 직후 모델을 내린다. GPU에는 한 번에 한 모델만 올린다.
            "keep_alive": HOLD_KEEP_ALIVE if self._holding else self.settings.keep_alive,
            "options": {
                "temperature": self.settings.temperature,
                "num_ctx": self.settings.num_ctx,
            },
        }
        if self.settings.think is not None:
            body["think"] = self.settings.think

        if self._holding:
            self._held_models.add(model)
        # llama-server가 모델을 올리다 죽으면 500이 오지만, 다시 요청하면 새 프로세스로 뜬다.
        for attempt in range(SERVER_ERROR_RETRIES + 1):
            try:
                response = self._client.post("/api/chat", json=body)
            except httpx.HTTPError as exc:
                raise LLMError(
                    f"Ollama에 연결하지 못했습니다 ({self._client.base_url}): {exc}"
                ) from exc
            if response.status_code < 500 or attempt == SERVER_ERROR_RETRIES:
                break
            log.warning(
                "Ollama 오류 %d, %.0f초 후 다시 시도 (%d/%d): %s",
                response.status_code,
                SERVER_ERROR_DELAY_SEC,
                attempt + 1,
                SERVER_ERROR_RETRIES,
                response.text[:200],
            )
            self._sleep(SERVER_ERROR_DELAY_SEC)

        if response.status_code == 404:
            raise LLMError(f"모델이 없습니다. 먼저 실행하세요: ollama pull {model}")
        if response.status_code >= 400:
            raise LLMError(f"Ollama 오류 {response.status_code}: {response.text[:500]}")
        return response.json().get("message", {}).get("content", "")
