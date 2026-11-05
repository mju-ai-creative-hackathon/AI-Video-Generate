"""ffmpeg·ffprobe 호출과 이미지 준비."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageOps

from .errors import MediaError


def _binary(name: str, env_var: str) -> str:
    configured = os.environ.get(env_var)
    if configured:
        return configured
    found = shutil.which(name)
    if not found:
        raise MediaError(
            f"{name}을 찾을 수 없습니다. 설치하세요: winget install Gyan.FFmpeg "
            f"(또는 {env_var} 환경 변수로 경로 지정)"
        )
    return found


def ffmpeg_path() -> str:
    return _binary("ffmpeg", "VLOG_FFMPEG")


def ffprobe_path() -> str:
    return _binary("ffprobe", "VLOG_FFPROBE")


def run_ffmpeg(args: list[str], cwd: Path | None = None) -> None:
    command = [ffmpeg_path(), "-y", "-hide_banner", "-loglevel", "error", *args]
    result = subprocess.run(
        command, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if result.returncode != 0:
        raise MediaError(f"ffmpeg 실패 ({result.returncode}): {result.stderr[-1500:]}")


@dataclass
class MediaInfo:
    duration: float
    width: int | None = None
    height: int | None = None
    fps: float | None = None
    codec: str | None = None
    pix_fmt: str | None = None
    has_audio: bool = False


def probe(path: Path) -> MediaInfo:
    result = subprocess.run(
        [
            ffprobe_path(),
            "-v", "error",
            "-show_entries",
            "format=duration:stream=codec_type,codec_name,width,height,avg_frame_rate,pix_fmt",
            "-of", "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise MediaError(f"ffprobe 실패: {path}: {result.stderr[-500:]}")
    data = json.loads(result.stdout)
    streams = data.get("streams", [])
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    info = MediaInfo(
        duration=float(data.get("format", {}).get("duration", 0.0)), has_audio=audio is not None
    )
    if video:
        info.width = video.get("width")
        info.height = video.get("height")
        info.codec = video.get("codec_name")
        info.pix_fmt = video.get("pix_fmt")
        info.fps = _parse_rate(video.get("avg_frame_rate", "0/1"))
    return info


def _parse_rate(rate: str) -> float:
    numerator, _, denominator = rate.partition("/")
    try:
        bottom = float(denominator or 1)
        return float(numerator) / bottom if bottom else 0.0
    except ValueError:
        return 0.0


def probe_duration(path: Path) -> float:
    return probe(path).duration


def filter_path(path: Path | str) -> str:
    """ffmpeg 필터 인자에 넣을 경로. 윈도우 드라이브 문자의 콜론을 이스케이프한다."""
    return str(path).replace("\\", "/").replace(":", r"\:").replace("'", r"\'")


def prepare_input_image(
    photo_path: Path, crop_box: list[float], size: tuple[int, int], out_path: Path
) -> Path:
    """사진을 crop_box로 잘라 지정한 크기로 만든다. 비율이 다르면 늘리지 않고 가운데를 맞춘다."""
    with Image.open(photo_path) as opened:
        image = opened.convert("RGB")
    cropped = image.crop(
        (
            round(crop_box[0] * image.width),
            round(crop_box[1] * image.height),
            round(crop_box[2] * image.width),
            round(crop_box[3] * image.height),
        )
    )
    fitted = ImageOps.fit(cropped, size, method=Image.Resampling.LANCZOS)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fitted.save(out_path, "JPEG", quality=95)
    return out_path
