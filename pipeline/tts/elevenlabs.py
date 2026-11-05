"""ElevenLabs with-timestamps 어댑터. 파이프라인에서 유일한 외부 호출이다.

요청·응답 형식은 elevenlabs.io/docs/api-reference/text-to-speech/convert-with-timestamps에서
확인했다. eleven_multilingual_v2는 language_code를 지원하지 않으므로 보내지 않는다.
"""

from __future__ import annotations

import base64
import logging
import time
from pathlib import Path
from typing import Any

import httpx

from ..config import TTSSettings
from ..errors import ConfigError, TTSError
from ..subtitles import words_from_alignment
from .base import TTSProvider, TTSRequest, TTSResult

log = logging.getLogger(__name__)
BASE_URL = "https://api.elevenlabs.io"
RETRYABLE = {429, 500, 502, 503, 504}


class ElevenLabsTTS(TTSProvider):
    name = "elevenlabs"
    extension = ".mp3"

    def __init__(
        self,
        api_key: str,
        settings: TTSSettings,
        *,
        transport: httpx.BaseTransport | None = None,
        sleep=time.sleep,  # noqa: ANN001
    ) -> None:
        if not api_key:
            raise ConfigError("ELEVENLABS_API_KEY가 없습니다. .env에 넣으세요.")
        if not settings.voice_id:
            raise ConfigError("config.yaml의 tts.voice_id가 비어 있습니다.")
        self.settings = settings
        self._sleep = sleep
        self._client = httpx.Client(
            base_url=BASE_URL,
            headers={"xi-api-key": api_key},
            timeout=settings.timeout_sec,
            transport=transport,
        )

    def signature(self) -> dict[str, Any]:
        cfg = self.settings
        return {
            "provider": self.name,
            "voice_id": cfg.voice_id,
            "model_id": cfg.model_id,
            "output_format": cfg.output_format,
            "stability": cfg.stability,
            "similarity_boost": cfg.similarity_boost,
            "style": cfg.style,
            "speed": cfg.speed,
        }

    def synthesize(self, request: TTSRequest, out_path: Path) -> TTSResult:
        cfg = self.settings
        body: dict[str, Any] = {
            "text": request.text,
            "model_id": cfg.model_id,
            "voice_settings": {
                "stability": cfg.stability,
                "similarity_boost": cfg.similarity_boost,
                "style": cfg.style,
                "speed": cfg.speed,
            },
        }
        if request.previous_text:
            body["previous_text"] = request.previous_text
        if request.next_text:
            body["next_text"] = request.next_text

        data = self._post_with_retry(body)
        try:
            audio = base64.b64decode(data["audio_base64"])
        except (KeyError, ValueError) as exc:
            raise TTSError(f"ElevenLabs 응답에 음성이 없습니다: {exc}") from exc
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_bytes(audio)

        alignment = data.get("alignment") or {}
        words = words_from_alignment(
            alignment.get("characters", []),
            alignment.get("character_start_times_seconds", []),
            alignment.get("character_end_times_seconds", []),
        )
        return TTSResult(words=words)

    def _post_with_retry(self, body: dict[str, Any]) -> dict[str, Any]:
        cfg = self.settings
        url = f"/v1/text-to-speech/{cfg.voice_id}/with-timestamps"
        last_error = ""
        for attempt in range(cfg.max_retries + 1):
            retry_after: float | None = None
            try:
                response = self._client.post(
                    url, params={"output_format": cfg.output_format}, json=body
                )
                if response.status_code == 200:
                    return response.json()
                last_error = f"{response.status_code}: {response.text[:500]}"
                if response.status_code not in RETRYABLE:
                    raise TTSError(f"ElevenLabs 오류 {last_error}")
                header = response.headers.get("retry-after")
                retry_after = float(header) if header and header.replace(".", "").isdigit() else None
            except httpx.TransportError as exc:
                last_error = str(exc)
            if attempt < cfg.max_retries:
                delay = retry_after if retry_after is not None else 2.0**attempt
                log.warning("ElevenLabs 재시도 %d회차 (%.1f초 후): %s", attempt + 1, delay, last_error)
                self._sleep(delay)
        raise TTSError(f"ElevenLabs 호출 실패 ({cfg.max_retries + 1}회 시도): {last_error}")
