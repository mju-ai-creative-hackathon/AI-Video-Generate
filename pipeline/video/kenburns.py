"""Ken Burns 어댑터. AI 영상 없이 전체 흐름을 시험하고, 생성 실패 컷의 대체로 쓴다."""

from __future__ import annotations

from pathlib import Path

from ..media import run_ffmpeg
from .base import ClipRequest, VideoProvider

ZOOM = 1.12


def zoompan_filter(camera_move: str, frames: int, fps: int, size: tuple[int, int]) -> str:
    """shot의 카메라 움직임을 따르는 zoompan 필터를 만든다."""
    last = max(frames - 1, 1)
    t = f"on/{last}"
    center_x = "(iw-iw/zoom)/2"
    center_y = "(ih-ih/zoom)/2"
    z, x, y = "1", center_x, center_y
    if camera_move == "slow_push_in":
        z = f"1+{ZOOM - 1:.3f}*{t}"
    elif camera_move == "pull_out":
        z = f"{ZOOM:.3f}-{ZOOM - 1:.3f}*{t}"
    elif camera_move == "pan_left":
        z, x = f"{ZOOM:.3f}", f"(iw-iw/zoom)*(1-{t})"
    elif camera_move == "pan_right":
        z, x = f"{ZOOM:.3f}", f"(iw-iw/zoom)*{t}"
    elif camera_move == "tilt_up":
        z, y = f"{ZOOM:.3f}", f"(ih-ih/zoom)*(1-{t})"
    elif camera_move == "handheld":
        z = "1.06"
        x = "(iw-iw/zoom)*(0.5+0.25*sin(on/17))"
        y = "(ih-ih/zoom)*(0.5+0.25*cos(on/23))"
    width, height = size
    # 떨림을 줄이려고 두 배로 키운 뒤 움직인다.
    return (
        f"scale={width * 2}:{height * 2}:flags=lanczos,"
        f"zoompan=z='{z}':x='{x}':y='{y}':d={frames}:s={width}x{height}:fps={fps},"
        "format=yuv420p"
    )


class KenBurnsVideo(VideoProvider):
    name = "kenburns"
    requires_gpu = False

    def __init__(self, size: tuple[int, int] = (1080, 1920), output_fps: int = 30) -> None:
        self._size = size
        self.output_fps = output_fps

    @property
    def input_size(self) -> tuple[int, int]:
        return self._size

    def generate(self, request: ClipRequest) -> Path:
        duration = request.frames / request.fps
        frames = max(1, round(duration * self.output_fps))
        request.out_path.parent.mkdir(parents=True, exist_ok=True)
        run_ffmpeg(
            [
                "-i", str(request.image_path),
                "-vf", zoompan_filter(request.camera_move, frames, self.output_fps, self._size),
                "-frames:v", str(frames),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "18",
                str(request.out_path),
            ]
        )
        return request.out_path
