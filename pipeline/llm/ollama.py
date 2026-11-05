"""Ollama /api/chat 어댑터. 동작은 docs.ollama.com/api/chat 기준으로 확인했다."""

from __future__ import annotations

import base64
from pathlib import Path
from typing import Any

import httpx

from ..config import LLMSettings
from ..errors import LLMError
from .base import LLMClient


class OllamaLLM(LLMClient):
    def __init__(
        self,
        base_url: str,
        settings: LLMSettings,
        *,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self.settings = settings
        self._client = httpx.Client(
            base_url=base_url, timeout=settings.timeout_sec, transport=transport
        )

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
            "keep_alive": self.settings.keep_alive,
            "options": {
                "temperature": self.settings.temperature,
                "num_ctx": self.settings.num_ctx,
            },
        }
        if self.settings.think is not None:
            body["think"] = self.settings.think

        try:
            response = self._client.post("/api/chat", json=body)
        except httpx.HTTPError as exc:
            raise LLMError(f"Ollama에 연결하지 못했습니다 ({self._client.base_url}): {exc}") from exc

        if response.status_code == 404:
            raise LLMError(f"모델이 없습니다. 먼저 실행하세요: ollama pull {model}")
        if response.status_code >= 400:
            raise LLMError(f"Ollama 오류 {response.status_code}: {response.text[:500]}")
        return response.json().get("message", {}).get("content", "")
