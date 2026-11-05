"""[7] 합성: 컷별로 렌더링한 뒤 concat demuxer로 연결한다."""

from __future__ import annotations

import logging
import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from PIL import Image

from .config import ComposeSettings
from .errors import MediaError
from .hashing import stable_hash
from .media import filter_path, probe, run_ffmpeg
from .subtitles import SubtitleEvent, build_ass

log = logging.getLogger(__name__)
WINDOWS_FONTS = Path(r"C:\Windows\Fonts")


@dataclass
class ComposeCut:
    cut_id: str
    clip_path: Path
    audio_path: Path
    duration_sec: float
    speed: float
    events: list[SubtitleEvent]
    on_screen_text: str | None
    transition_in: str


class Composer(Protocol):
    def compose(self, cuts: list[ComposeCut], work_dir: Path, out_path: Path) -> Path: ...


def resolve_font(cfg: ComposeSettings, root: Path) -> tuple[str, Path | None]:
    """설정한 글꼴이 없으면 윈도우 기본 한글 글꼴로 대체한다."""
    fonts_dir = root / cfg.fonts_dir
    if fonts_dir.is_dir() and any(fonts_dir.glob(f"{cfg.font_name}*")):
        return cfg.font_name, fonts_dir
    if (WINDOWS_FONTS / "malgun.ttf").exists():
        log.warning("글꼴 %s이 %s에 없어 Malgun Gothic을 씁니다.", cfg.font_name, fonts_dir)
        return "Malgun Gothic", WINDOWS_FONTS
    log.warning("글꼴 %s을 찾지 못했습니다. 자막의 한글이 깨질 수 있습니다.", cfg.font_name)
    return cfg.font_name, None


def frame_exact(duration: float, fps: int) -> float:
    """연결했을 때 음성과 영상이 어긋나지 않도록 프레임 단위로 맞춘다."""
    return math.ceil(duration * fps) / fps


def build_cut_filter(
    cut: ComposeCut, cfg: ComposeSettings, ass_name: str, fonts_arg: str | None
) -> tuple[str, float]:
    duration = frame_exact(cut.duration_sec, cfg.fps)
    video = [
        f"setpts={cut.speed:.4f}*PTS",
        f"fps={cfg.fps}",
        # 입력 비율이 달라도 늘리지 않고 잘라서 맞춘다 (Wan 480×832는 정확한 9:16이 아니다).
        f"scale={cfg.width}:{cfg.height}:force_original_aspect_ratio=increase:flags=lanczos",
        f"crop={cfg.width}:{cfg.height}",
        "setsar=1",
        # 클립이 짧으면 마지막 프레임을 늘려 컷 길이를 맞춘다.
        f"tpad=stop_mode=clone:stop_duration={duration:.3f}",
        f"trim=duration={duration:.3f}",
        "setpts=PTS-STARTPTS",
    ]
    if cut.transition_in == "fade":
        # 앞 컷과 겹치지 않는 페이드 인이므로 컷 길이가 줄어들지 않는다.
        video.append(f"fade=t=in:st=0:d={min(cfg.fade_sec, duration / 2):.3f}")
    ass = f"ass='{filter_path(ass_name)}'"
    if fonts_arg:
        ass += f":fontsdir='{fonts_arg}'"
    video.append(ass)
    audio = [
        "aresample=48000",
        f"apad=whole_dur={duration:.3f}",
        f"atrim=duration={duration:.3f}",
        "asetpts=PTS-STARTPTS",
    ]
    graph = f"[0:v]{','.join(video)}[v];[1:a]{','.join(audio)}[a]"
    return graph, duration


def build_final_audio_filter(cfg: ComposeSettings, has_bgm: bool) -> str:
    loudnorm = f"loudnorm=I={cfg.loudness_lufs}:TP=-1.5:LRA=11,aresample=48000"
    if not has_bgm:
        return f"[0:a]{loudnorm}[a]"
    # 나레이션이 나오는 구간에서 BGM을 줄인다.
    return (
        "[0:a]asplit=2[narration][sidechain];"
        f"[1:a]aresample=48000,volume={cfg.bgm_volume}[bgm];"
        "[bgm][sidechain]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=400[ducked];"
        f"[narration][ducked]amix=inputs=2:duration=first:normalize=0,{loudnorm}[a]"
    )


class FFmpegComposer:
    def __init__(self, cfg: ComposeSettings, root: Path) -> None:
        self.cfg = cfg
        self.root = root

    def compose(self, cuts: list[ComposeCut], work_dir: Path, out_path: Path) -> Path:
        work_dir.mkdir(parents=True, exist_ok=True)
        font_name, fonts_dir = resolve_font(self.cfg, self.root)
        fonts_arg = self._fonts_arg(fonts_dir, work_dir)
        rendered = [self._render_cut(c, work_dir, font_name, fonts_arg) for c in cuts]

        list_file = work_dir / "concat.txt"
        list_file.write_text("".join(f"file '{name}'\n" for name in rendered), encoding="utf-8")
        total = sum(frame_exact(c.duration_sec, self.cfg.fps) for c in cuts)

        bgm = self.cfg.bgm_path
        bgm_path = (self.root / bgm) if bgm else None
        if bgm_path is not None and not bgm_path.exists():
            raise MediaError(f"BGM 파일이 없습니다: {bgm_path}")
        args = ["-f", "concat", "-safe", "0", "-i", list_file.name]
        if bgm_path is not None:
            args += ["-stream_loop", "-1", "-i", str(bgm_path)]
        args += [
            "-filter_complex", build_final_audio_filter(self.cfg, bgm_path is not None),
            "-map", "0:v",
            "-map", "[a]",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", self.cfg.audio_bitrate,
            "-ar", "48000",
            "-t", f"{total:.3f}",
            "-movflags", "+faststart",
            str(out_path.resolve()),
        ]
        out_path.parent.mkdir(parents=True, exist_ok=True)
        run_ffmpeg(args, cwd=work_dir)
        return out_path

    def _fonts_arg(self, fonts_dir: Path | None, work_dir: Path) -> str | None:
        if fonts_dir is None:
            return None
        try:
            return filter_path(os.path.relpath(fonts_dir, work_dir))
        except ValueError:  # 드라이브가 다르면 상대 경로를 만들 수 없다
            return filter_path(fonts_dir)

    def _render_cut(
        self, cut: ComposeCut, work_dir: Path, font_name: str, fonts_arg: str | None
    ) -> str:
        cfg = self.cfg
        key = stable_hash(
            {
                "clip": str(cut.clip_path),
                "clip_mtime": cut.clip_path.stat().st_mtime_ns,
                "audio": str(cut.audio_path),
                "audio_mtime": cut.audio_path.stat().st_mtime_ns,
                "duration": cut.duration_sec,
                "speed": cut.speed,
                "events": [(e.text, e.start, e.end) for e in cut.events],
                "text": cut.on_screen_text,
                "transition": cut.transition_in,
                "font": font_name,
                "settings": cfg.model_dump(),
            }
        )[:16]
        name = f"cut_{key}.mp4"
        if (work_dir / name).exists():
            return name
        ass_name = f"cut_{key}.ass"
        duration = frame_exact(cut.duration_sec, cfg.fps)
        (work_dir / ass_name).write_text(
            build_ass(cut.events, cut.on_screen_text, duration, cfg, font_name), encoding="utf-8"
        )
        graph, _ = build_cut_filter(cut, cfg, ass_name, fonts_arg)
        temp = f"cut_{key}.part.mp4"
        run_ffmpeg(
            [
                "-i", str(cut.clip_path.resolve()),
                "-i", str(cut.audio_path.resolve()),
                "-filter_complex", graph,
                "-map", "[v]",
                "-map", "[a]",
                "-c:v", "libx264",
                "-preset", cfg.preset,
                "-crf", str(cfg.crf),
                "-pix_fmt", "yuv420p",
                "-r", str(cfg.fps),
                "-c:a", "aac",
                "-b:a", cfg.audio_bitrate,
                "-ar", "48000",
                "-ac", "2",
                temp,
            ],
            cwd=work_dir,
        )
        (work_dir / temp).replace(work_dir / name)
        return name


def make_contact_sheet(clips: list[tuple[str, Path]], out_path: Path, thumb_h: int = 480) -> Path:
    """컷별 대표 프레임을 한 장으로 모은다. 생성 결과를 보고 regen할 컷을 고를 때 쓴다."""
    frames: list[Image.Image] = []
    temp_dir = out_path.parent / "contact_frames"
    temp_dir.mkdir(parents=True, exist_ok=True)
    for cut_id, clip in clips:
        frame_path = temp_dir / f"{cut_id}.jpg"
        middle = probe(clip).duration / 2
        run_ffmpeg(["-ss", f"{middle:.3f}", "-i", str(clip), "-frames:v", "1", str(frame_path)])
        with Image.open(frame_path) as opened:
            image = opened.convert("RGB")
        width = max(1, int(image.width * thumb_h / image.height))
        frames.append(image.resize((width, thumb_h)))
    if not frames:
        raise MediaError("대표 프레임을 만들 클립이 없습니다.")
    columns = min(6, len(frames))
    rows = math.ceil(len(frames) / columns)
    cell_w = max(f.width for f in frames)
    sheet = Image.new("RGB", (columns * cell_w, rows * thumb_h), (20, 20, 20))
    for index, frame in enumerate(frames):
        sheet.paste(frame, ((index % columns) * cell_w, (index // columns) * thumb_h))
    sheet.save(out_path, "JPEG", quality=88)
    return out_path
