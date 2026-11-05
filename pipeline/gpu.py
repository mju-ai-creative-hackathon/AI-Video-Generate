"""VRAM 확인. GPU에는 한 번에 한 모델만 올린다."""

from __future__ import annotations

import logging
import shutil
import subprocess
import time
from collections.abc import Callable

from .errors import VideoError

log = logging.getLogger(__name__)


def free_vram_mb() -> int | None:
    if not shutil.which("nvidia-smi"):
        return None
    result = subprocess.run(
        ["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    try:
        return int(result.stdout.strip().splitlines()[0])
    except (IndexError, ValueError):
        return None


def ensure_free_vram(min_mb: int) -> None:
    free = free_vram_mb()
    if free is None:
        log.warning("nvidia-smi로 VRAM을 확인하지 못해 그대로 진행합니다.")
        return
    if free < min_mb:
        raise VideoError(
            f"남은 VRAM이 {free}MB로 필요한 {min_mb}MB보다 적습니다. "
            "`ollama ps`로 올라간 모델을 확인하고, 다른 GPU 프로그램을 닫거나 "
            "video.min_free_vram_mb를 조정하세요."
        )


def wait_for_free_vram(
    min_mb: int,
    *,
    timeout_sec: float = 30.0,
    poll_sec: float = 2.0,
    sleep: Callable[[float], None] = time.sleep,
) -> None:
    """모델을 내리라고 요청한 뒤 실제로 비워질 때까지 기다린다. ComfyUI는 바로 비우지 않는다."""
    waited = 0.0
    while True:
        try:
            ensure_free_vram(min_mb)
            return
        except VideoError:
            if waited >= timeout_sec:
                raise
        sleep(poll_sec)
        waited += poll_sec
