"""ComfyUI 어댑터. 경로와 필드는 ComfyUI의 server.py에서 확인했다.

흐름: /upload/image → /prompt → /ws로 진행 수신 → /history/{id} → /view → /free
"""

from __future__ import annotations

import json
import logging
import re
import time
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

from ..config import VideoSettings
from ..errors import VideoError
from .base import ClipRequest, VideoProvider

log = logging.getLogger(__name__)
PLACEHOLDER = re.compile(r"\{\{([A-Z0-9_]+)\}\}")
VIDEO_SUFFIXES = {".mp4", ".webm", ".mov", ".mkv"}


def render_workflow(template: Any, values: dict[str, Any]) -> Any:
    """워크플로의 {{이름}}을 값으로 바꾼다. 문자열 전체가 자리표시자면 값의 타입을 유지한다."""
    if isinstance(template, dict):
        return {key: render_workflow(value, values) for key, value in template.items()}
    if isinstance(template, list):
        return [render_workflow(value, values) for value in template]
    if isinstance(template, str):
        whole = PLACEHOLDER.fullmatch(template)
        if whole:
            return _lookup(values, whole.group(1))
        return PLACEHOLDER.sub(lambda m: str(_lookup(values, m.group(1))), template)
    return template


def _lookup(values: dict[str, Any], name: str) -> Any:
    if name not in values:
        raise VideoError(f"워크플로의 자리표시자에 넣을 값이 없습니다: {name}")
    return values[name]


def _default_ws(url: str, timeout: float) -> Any:
    import websocket  # websocket-client

    return websocket.create_connection(url, timeout=timeout)


class ComfyUIVideo(VideoProvider):
    name = "comfyui"
    requires_gpu = True

    def __init__(
        self,
        base_url: str,
        settings: VideoSettings,
        workflow_path: Path,
        *,
        transport: httpx.BaseTransport | None = None,
        ws_factory: Callable[[str, float], Any] | None = _default_ws,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.settings = settings
        self.base_url = base_url.rstrip("/")
        if not workflow_path.exists():
            raise VideoError(f"워크플로 파일이 없습니다: {workflow_path}")
        self.workflow = json.loads(workflow_path.read_text(encoding="utf-8"))
        self.client_id = uuid.uuid4().hex
        self._client = httpx.Client(base_url=self.base_url, timeout=120, transport=transport)
        self._ws_factory = ws_factory
        self._sleep = sleep

    @property
    def input_size(self) -> tuple[int, int]:
        return self.settings.width, self.settings.height

    def generate(self, request: ClipRequest) -> Path:
        try:
            image_name = self._upload(request.image_path)
            workflow = render_workflow(self.workflow, self._values(request, image_name))
            ws = self._connect_ws()
            try:
                prompt_id = self._submit(workflow)
                entry = self._wait(prompt_id, ws)
            finally:
                if ws is not None:
                    ws.close()
            self._download(entry, request.out_path)
        except httpx.HTTPError as exc:
            raise VideoError(f"ComfyUI 통신 실패 ({self.base_url}): {exc}") from exc
        return request.out_path

    def release(self) -> None:
        try:
            self._client.post("/free", json={"unload_models": True, "free_memory": True})
        except httpx.HTTPError as exc:
            log.warning("ComfyUI /free 실패: %s", exc)

    def _values(self, request: ClipRequest, image_name: str) -> dict[str, Any]:
        cfg = self.settings
        models = cfg.models
        return {
            "IMAGE": image_name,
            "PROMPT": request.prompt,
            "NEGATIVE_PROMPT": cfg.negative_prompt,
            "SEED": request.seed,
            "FRAMES": request.frames,
            "WIDTH": cfg.width,
            "HEIGHT": cfg.height,
            "STEPS": cfg.steps,
            "SPLIT_STEP": max(1, cfg.steps // 2),
            "MODEL_HIGH": models.get("high_noise", ""),
            "MODEL_LOW": models.get("low_noise", ""),
            "LORA_HIGH": models.get("lora_high", ""),
            "LORA_LOW": models.get("lora_low", ""),
            "TEXT_ENCODER": models.get("text_encoder", ""),
            "VAE": models.get("vae", ""),
            "RIFE_MODEL": models.get("rife", ""),
            "OUTPUT_PREFIX": f"vlog/{request.out_path.stem}",
        }

    def _upload(self, image_path: Path) -> str:
        with image_path.open("rb") as handle:
            response = self._client.post(
                "/upload/image",
                files={"image": (image_path.name, handle, "image/jpeg")},
                data={"overwrite": "true", "type": "input"},
            )
        if response.status_code != 200:
            raise VideoError(f"이미지 업로드 실패 {response.status_code}: {response.text[:500]}")
        data = response.json()
        subfolder = data.get("subfolder")
        return f"{subfolder}/{data['name']}" if subfolder else data["name"]

    def _connect_ws(self) -> Any:
        if self._ws_factory is None:
            return None
        ws_base = self.base_url.replace("https://", "wss://").replace("http://", "ws://")
        try:
            return self._ws_factory(f"{ws_base}/ws?clientId={self.client_id}", 15.0)
        except Exception as exc:  # noqa: BLE001 - 진행 표시가 없어도 기록 조회로 끝을 알 수 있다
            log.warning("ComfyUI WebSocket 연결 실패, 기록 조회로 대체: %s", exc)
            return None

    def _submit(self, workflow: Any) -> str:
        response = self._client.post(
            "/prompt", json={"prompt": workflow, "client_id": self.client_id}
        )
        if response.status_code != 200:
            raise VideoError(f"워크플로 제출 실패 {response.status_code}: {response.text[:1500]}")
        return response.json()["prompt_id"]

    def _wait(self, prompt_id: str, ws: Any) -> dict[str, Any]:
        deadline = time.monotonic() + self.settings.timeout_sec
        while time.monotonic() < deadline:
            if ws is not None:
                ws = self._read_ws(ws, prompt_id)
            else:
                self._sleep(2.0)
            entry = self._history(prompt_id)
            if entry is not None:
                return entry
        raise VideoError(f"ComfyUI 작업 시간 초과 ({self.settings.timeout_sec}초): {prompt_id}")

    def _read_ws(self, ws: Any, prompt_id: str) -> Any:
        """다음 메시지까지 기다린다. 연결이 끊기면 None을 돌려 기록 조회로 넘어간다."""
        try:
            message = ws.recv()
        except TimeoutError:
            return ws
        except Exception as exc:  # noqa: BLE001
            if type(exc).__name__ == "WebSocketTimeoutException":
                return ws
            log.warning("ComfyUI WebSocket 끊김, 기록 조회로 대체: %s", exc)
            return None
        if isinstance(message, (bytes, bytearray)):  # 미리보기 이미지
            return ws
        event = json.loads(message)
        data = event.get("data", {})
        if data.get("prompt_id") not in (None, prompt_id):
            return ws
        if event.get("type") == "execution_error":
            raise VideoError(
                f"ComfyUI 실행 오류 ({data.get('node_type')}): {data.get('exception_message')}"
            )
        if event.get("type") == "progress":
            log.debug("ComfyUI 진행 %s/%s", data.get("value"), data.get("max"))
        return ws

    def _history(self, prompt_id: str) -> dict[str, Any] | None:
        response = self._client.get(f"/history/{prompt_id}")
        if response.status_code != 200:
            return None
        entry = response.json().get(prompt_id)
        if not entry:
            return None
        status = entry.get("status", {})
        if status.get("status_str") == "error":
            raise VideoError(f"ComfyUI 실행 오류: {json.dumps(status.get('messages'))[:1500]}")
        if not status.get("completed", bool(entry.get("outputs"))):
            return None
        return entry

    def _download(self, entry: dict[str, Any], out_path: Path) -> None:
        output = _find_video_output(entry.get("outputs", {}))
        if output is None:
            raise VideoError(f"ComfyUI 결과에 영상이 없습니다: {list(entry.get('outputs', {}))}")
        out_path.parent.mkdir(parents=True, exist_ok=True)
        params = {
            "filename": output["filename"],
            "subfolder": output.get("subfolder", ""),
            "type": output.get("type", "output"),
        }
        temp = out_path.with_suffix(out_path.suffix + ".part")
        with self._client.stream("GET", "/view", params=params, timeout=300) as response:
            if response.status_code != 200:
                raise VideoError(f"결과 내려받기 실패 {response.status_code}")
            with temp.open("wb") as handle:
                for chunk in response.iter_bytes():
                    handle.write(chunk)
        temp.replace(out_path)


def _find_video_output(outputs: dict[str, Any]) -> dict[str, Any] | None:
    for node_output in outputs.values():
        # 저장 노드에 따라 결과가 videos, gifs, images 중 하나에 담긴다.
        for key in ("videos", "gifs", "images"):
            for item in node_output.get(key, []) or []:
                if Path(item.get("filename", "")).suffix.lower() in VIDEO_SUFFIXES:
                    return item
    return None
