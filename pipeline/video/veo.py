"""Veo 어댑터. Google Cloud(Vertex AI)의 정식 모델을 쓴다.

Gemini API의 Veo 3.1 프리뷰는 2026-10-22에 종료되어 Gemini API 키로는 호출할 수 없다.
흐름과 필드는 docs.cloud.google.com/vertex-ai/generative-ai/docs/video/generate-videos-from-an-image에서
확인했다(2026-11-06). durationSeconds, generateAudio, 응답의 bytesBase64Encoded는 그 페이지 예제에
없어 기억에 의존했으므로 첫 실행에서 확인해야 한다.
"""

from __future__ import annotations

import base64
import logging
import shutil
import subprocess
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

from ..config import VeoSettings
from ..errors import ConfigError, VideoError
from .base import ClipRequest, VideoProvider

log = logging.getLogger(__name__)
DURATIONS = (4, 6, 8)
SIZES = {"720p": (720, 1280), "1080p": (1080, 1920)}
RETRYABLE = {429, 500, 502, 503, 504}


def gcloud_token() -> str:
    gcloud = shutil.which("gcloud")
    if not gcloud:
        raise ConfigError(
            "gcloud가 없습니다. Google Cloud CLI를 설치하고 `gcloud auth login`을 실행하세요."
        )
    result = subprocess.run(
        [gcloud, "auth", "print-access-token"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise ConfigError(f"gcloud 인증 실패. `gcloud auth login`을 실행하세요: {result.stderr[-500:]}")
    return result.stdout.strip()


def choose_duration(frames: int, fps: int) -> int:
    """Veo는 4, 6, 8초만 만든다. 필요한 길이 이상 중 가장 짧은 값을 고르고, 남는 부분은 합성에서 잘린다."""
    needed = frames / fps
    return next((d for d in DURATIONS if d >= needed), DURATIONS[-1])


class VeoVideo(VideoProvider):
    requires_gpu = False

    def __init__(
        self,
        project: str,
        location: str,
        settings: VeoSettings,
        negative_prompt: str = "",
        *,
        token_provider: Callable[[], str] = gcloud_token,
        transport: httpx.BaseTransport | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        if not project:
            raise ConfigError("GOOGLE_CLOUD_PROJECT가 없습니다. .env에 넣으세요.")
        if settings.resolution not in SIZES:
            raise ConfigError(f"veo.resolution은 {sorted(SIZES)} 중 하나여야 합니다.")
        self.settings = settings
        # 모델이나 해상도를 바꾸면 해시가 달라져 클립을 다시 만든다.
        self.name = f"veo/{settings.model}/{settings.resolution}"
        self.negative_prompt = negative_prompt
        self._token_provider = token_provider
        self._sleep = sleep
        self._model_url = (
            f"https://{location}-aiplatform.googleapis.com/v1/projects/{project}"
            f"/locations/{location}/publishers/google/models/{settings.model}"
        )
        self._client = httpx.Client(timeout=300, transport=transport)

    @property
    def input_size(self) -> tuple[int, int]:
        return SIZES[self.settings.resolution]

    def generate(self, request: ClipRequest) -> Path:
        cfg = self.settings
        token = self._token_provider()
        parameters: dict[str, Any] = {
            "sampleCount": 1,
            "aspectRatio": "9:16",
            "resolution": cfg.resolution,
            "durationSeconds": choose_duration(request.frames, request.fps),
            "seed": request.seed,
            "generateAudio": cfg.generate_audio,
        }
        if self.negative_prompt:
            parameters["negativePrompt"] = self.negative_prompt
        started = self._post(
            f"{self._model_url}:predictLongRunning",
            {
                "instances": [
                    {
                        "prompt": request.prompt,
                        "image": {
                            "bytesBase64Encoded": base64.b64encode(
                                request.image_path.read_bytes()
                            ).decode("ascii"),
                            "mimeType": "image/jpeg",
                        },
                    }
                ],
                "parameters": parameters,
            },
            token,
        )
        result = self._wait(started["name"], token)
        video = _first_video(result)
        request.out_path.parent.mkdir(parents=True, exist_ok=True)
        request.out_path.write_bytes(base64.b64decode(video["bytesBase64Encoded"]))
        return request.out_path

    def _wait(self, operation: str, token: str) -> dict[str, Any]:
        deadline = time.monotonic() + self.settings.timeout_sec
        while time.monotonic() < deadline:
            self._sleep(self.settings.poll_sec)
            status = self._post(
                f"{self._model_url}:fetchPredictOperation", {"operationName": operation}, token
            )
            if status.get("done"):
                if status.get("error"):
                    raise VideoError(f"Veo 생성 실패: {status['error']}")
                return status.get("response") or {}
        raise VideoError(f"Veo 작업 시간 초과 ({self.settings.timeout_sec}초): {operation}")

    def _post(self, url: str, body: dict[str, Any], token: str) -> dict[str, Any]:
        headers = {"Authorization": f"Bearer {token}"}
        attempts = self.settings.max_retries + 1
        last_error = ""
        for attempt in range(attempts):
            try:
                response = self._client.post(url, json=body, headers=headers)
                if response.status_code == 200:
                    return response.json()
                last_error = f"{response.status_code}: {response.text[:800]}"
                if response.status_code in (401, 403, 404):
                    # 설정 문제는 다시 시도하거나 Ken Burns로 넘어가도 해결되지 않으므로 멈춘다.
                    raise ConfigError(f"Veo 프로젝트·권한·모델을 확인하세요. {last_error}")
                if response.status_code not in RETRYABLE:
                    raise VideoError(f"Veo 오류 {last_error}")
            except httpx.TransportError as exc:
                last_error = str(exc)
            if attempt < attempts - 1:
                delay = 5.0 * 2**attempt
                log.warning("Veo 재시도 %d회차 (%.0f초 후): %s", attempt + 1, delay, last_error)
                self._sleep(delay)
        raise VideoError(f"Veo 호출 실패 ({attempts}회 시도): {last_error}")


def _first_video(result: dict[str, Any]) -> dict[str, Any]:
    videos = result.get("videos") or []
    if not videos:
        reasons = result.get("raiMediaFilteredReasons") or result.get("raiMediaFilteredCount")
        raise VideoError(f"Veo가 영상을 돌려주지 않았습니다(안전 필터 등): {reasons}")
    if not videos[0].get("bytesBase64Encoded"):
        raise VideoError(f"Veo 응답에 영상 데이터가 없습니다: {sorted(videos[0])}")
    return videos[0]
