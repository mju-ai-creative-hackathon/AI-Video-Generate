"""[7] 합성: 컷별로 렌더링한 뒤 concat demuxer로 연결한다."""

from __future__ import annotations

import logging
import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from PIL import Image

from .config import ComposeSettings
from .errors import MediaError
from .hashing import stable_hash
from .media import filter_path, probe, run_ffmpeg
from .subtitles import SubtitleEvent, build_ass

log = logging.getLogger(__name__)
WINDOWS_FONTS = Path(r"C:\Windows\Fonts")
# 컷 렌더링 필터를 바꾸면 올린다. 이전 필터로 만든 컷 캐시가 재사용되지 않게 한다.
CUT_RENDER_VERSION = 2


@dataclass
class ComposeCut:
    cut_id: str
    clip_path: Path
    audio_path: Path | None  # 원본 소리만 쓰는 컷은 나레이션이 없다
    duration_sec: float
    speed: float
    events: list[SubtitleEvent]
    on_screen_text: str | None
    transition_in: str
    # 원본 영상 컷에서만 쓴다. source_start가 None이면 생성된 클립이다.
    source_start: float | None = None
    crop_box: list[float] | None = None
    audio_mode: str = "narration"
    has_source_audio: bool = False

    @property
    def is_source(self) -> bool:
        return self.source_start is not None

    @property
    def audible(self) -> bool:
        narration = self.audio_path is not None and self.audio_mode != "source"
        source = self.has_source_audio and self.audio_mode != "narration"
        return narration or source


@dataclass(frozen=True)
class MusicInput:
    """실행마다 고른 음악. 없으면 config의 compose.bgm_path를 쓴다."""

    path: Path
    volume: float
    duck: bool  # 나레이션 구간에서 줄인다. 음악 전용이면 False
    fade_out_sec: float = 0.0


class Composer(Protocol):
    def compose(
        self,
        cuts: list[ComposeCut],
        work_dir: Path,
        out_path: Path,
        *,
        music: MusicInput | None = None,
    ) -> Path: ...


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


def _source_prefix(cut: ComposeCut, hdr: bool) -> list[str]:
    """원본 영상의 HDR 톤 매핑과 크롭. 생성된 클립에는 쓰지 않는다."""
    steps: list[str] = []
    if hdr:
        # 안 하면 아이폰 HDR 영상이 물 빠진 색으로 나온다.
        steps += [
            "zscale=t=linear:npl=100",
            "format=gbrpf32le",
            "zscale=p=bt709",
            "tonemap=hable:desat=0",
            "zscale=t=bt709:m=bt709:r=tv",
            "format=yuv420p",
        ]
    if cut.crop_box is not None:
        x1, y1, x2, y2 = cut.crop_box
        steps.append(f"crop=iw*{x2 - x1:.5f}:ih*{y2 - y1:.5f}:iw*{x1:.5f}:ih*{y1:.5f}")
    return steps


def _audio_graph(cut: ComposeCut, cfg: ComposeSettings, duration: float) -> str:
    """입력 0은 영상, 입력 1은 나레이션 또는 무음이다. 출력은 [a]."""
    normalize = "aresample=48000,aformat=channel_layouts=stereo"
    tail = [
        f"apad=whole_dur={duration:.3f}",
        f"atrim=duration={duration:.3f}",
        "asetpts=PTS-STARTPTS",
    ]
    # 원본 소리의 컷 경계에서 튀는 소리를 막는다.
    edge = 0.02
    fades = [
        f"afade=t=in:st=0:d={edge}",
        f"afade=t=out:st={max(duration - edge, 0):.3f}:d={edge}",
    ]
    if cut.audio_mode == "source" and cut.has_source_audio:
        return f"[0:a]{normalize},{','.join(tail + fades)}[a]"
    if cut.audio_mode == "mix" and cut.has_source_audio and cut.audio_path is not None:
        return (
            f"[0:a]{normalize},volume={cfg.source_mix_volume}[source];"
            f"[1:a]{normalize}[narration];"
            "[narration][source]amix=inputs=2:duration=longest:normalize=0,"
            f"{','.join(tail + fades)}[a]"
        )
    # 나레이션, 또는 소리 없는 영상에 넣은 무음
    plain = ["aresample=48000", *tail]
    return f"[1:a]{','.join(plain)}[a]"


def build_cut_filter(
    cut: ComposeCut,
    cfg: ComposeSettings,
    ass_name: str | None,
    fonts_arg: str | None,
    *,
    hdr: bool = False,
) -> tuple[str, float]:
    duration = frame_exact(cut.duration_sec, cfg.fps)
    video = [
        *(_source_prefix(cut, hdr) if cut.is_source else []),
        f"setpts={cut.speed:.4f}*PTS",
        f"fps={cfg.fps}",
        # 입력 비율이 달라도 늘리지 않고 잘라서 맞춘다 (Wan 480×832는 정확한 9:16이 아니다).
        # JPEG에서 온 풀레인지 입력도 표준 범위 BT.709로 맞춘다. 안 하면 yuvj420p로 저장된다.
        f"scale={cfg.width}:{cfg.height}:force_original_aspect_ratio=increase:flags=lanczos"
        ":out_range=limited:out_color_matrix=bt709",
        f"crop={cfg.width}:{cfg.height}",
        "format=yuv420p",
        "setsar=1",
        # 클립이 짧으면 마지막 프레임을 늘려 컷 길이를 맞춘다.
        f"tpad=stop_mode=clone:stop_duration={duration:.3f}",
        f"trim=duration={duration:.3f}",
        "setpts=PTS-STARTPTS",
    ]
    if cut.transition_in == "fade":
        # 앞 컷과 겹치지 않는 페이드 인이므로 컷 길이가 줄어들지 않는다.
        video.append(f"fade=t=in:st=0:d={min(cfg.fade_sec, duration / 2):.3f}")
    if ass_name is not None:  # 자막과 화면 문구가 모두 없으면 필터를 넣지 않는다
        ass = f"ass='{filter_path(ass_name)}'"
        if fonts_arg:
            ass += f":fontsdir='{fonts_arg}'"
        video.append(ass)
    graph = f"[0:v]{','.join(video)}[v];{_audio_graph(cut, cfg, duration)}"
    return graph, duration


def build_final_audio_filter(
    cfg: ComposeSettings,
    has_bgm: bool,
    *,
    normalize: bool = True,
    bgm_volume: float | None = None,
    duck: bool = True,
    fade_out: tuple[float, float] | None = None,
) -> str:
    """입력 0은 컷을 이은 소리, 입력 1은 음악(반복 재생). fade_out은 (시작 초, 길이)."""
    loudnorm = f"loudnorm=I={cfg.loudness_lufs}:TP=-1.5:LRA=11,aresample=48000"
    if not normalize and not has_bgm:
        # 소리가 전혀 없으면 loudnorm이 NaN을 낼 수 있어 건너뛴다.
        return "[0:a]aresample=48000[a]"
    if not has_bgm:
        return f"[0:a]{loudnorm}[a]"
    volume = cfg.bgm_volume if bgm_volume is None else bgm_volume
    music = f"[1:a]aresample=48000,aformat=channel_layouts=stereo,volume={volume}"
    if fade_out is not None and fade_out[1] > 0:
        music += f",afade=t=out:st={max(fade_out[0], 0):.3f}:d={fade_out[1]:.3f}"
    if not duck:
        # 음악 전용: 컷 소리(원본 소리를 고른 영상 컷 등)와 음악을 그대로 더한다.
        return (
            f"{music}[bgm];"
            f"[0:a][bgm]amix=inputs=2:duration=first:normalize=0,{loudnorm}[a]"
        )
    # 나레이션이 나오는 구간에서 BGM을 줄인다.
    return (
        "[0:a]asplit=2[narration][sidechain];"
        f"{music}[bgm];"
        "[bgm][sidechain]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=400[ducked];"
        f"[narration][ducked]amix=inputs=2:duration=first:normalize=0,{loudnorm}[a]"
    )


class FFmpegComposer:
    def __init__(self, cfg: ComposeSettings, root: Path) -> None:
        self.cfg = cfg
        self.root = root

    def compose(
        self,
        cuts: list[ComposeCut],
        work_dir: Path,
        out_path: Path,
        *,
        music: MusicInput | None = None,
    ) -> Path:
        work_dir.mkdir(parents=True, exist_ok=True)
        font_name, fonts_dir = resolve_font(self.cfg, self.root)
        fonts_arg = self._fonts_arg(fonts_dir, work_dir)
        rendered = [self._render_cut(c, work_dir, font_name, fonts_arg) for c in cuts]

        list_file = work_dir / "concat.txt"
        list_file.write_text("".join(f"file '{name}'\n" for name in rendered), encoding="utf-8")
        total = sum(frame_exact(c.duration_sec, self.cfg.fps) for c in cuts)

        if music is not None:
            bgm_path: Path | None = music.path
        else:
            bgm = self.cfg.bgm_path
            bgm_path = (self.root / bgm) if bgm else None
        if bgm_path is not None and not bgm_path.exists():
            raise MediaError(f"BGM 파일이 없습니다: {bgm_path}")
        fade = (
            (total - music.fade_out_sec, min(music.fade_out_sec, total))
            if music is not None and music.fade_out_sec > 0
            else None
        )
        args = ["-f", "concat", "-safe", "0", "-i", list_file.name]
        if bgm_path is not None:
            args += ["-stream_loop", "-1", "-i", str(bgm_path)]
        args += [
            "-filter_complex",
            build_final_audio_filter(
                self.cfg,
                bgm_path is not None,
                normalize=any(c.audible for c in cuts),
                bgm_volume=music.volume if music is not None else None,
                duck=music.duck if music is not None else True,
                fade_out=fade,
            ),
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
        payload = {
            "render_version": CUT_RENDER_VERSION,
            "clip": str(cut.clip_path),
            "clip_mtime": cut.clip_path.stat().st_mtime_ns,
            "audio": str(cut.audio_path),
            "audio_mtime": cut.audio_path.stat().st_mtime_ns if cut.audio_path else None,
            "duration": cut.duration_sec,
            "speed": cut.speed,
            "events": [(e.text, e.start, e.end) for e in cut.events],
            "text": cut.on_screen_text,
            "transition": cut.transition_in,
            "font": font_name,
            "settings": cfg.model_dump(exclude={"source_mix_volume"}),
        }
        # 영상 컷에만 넣어 사진 컷의 캐시 키가 그대로 유지된다.
        if cut.is_source:
            payload["source"] = {
                "start": cut.source_start,
                "crop": cut.crop_box,
                "audio_mode": cut.audio_mode,
                "has_audio": cut.has_source_audio,
                "mix_volume": cfg.source_mix_volume,
            }
        key = stable_hash(payload)[:16]
        name = f"cut_{key}.mp4"
        if (work_dir / name).exists():
            return name
        duration = frame_exact(cut.duration_sec, cfg.fps)
        ass_name: str | None = None
        if cut.events or cut.on_screen_text:
            ass_name = f"cut_{key}.ass"
            (work_dir / ass_name).write_text(
                build_ass(cut.events, cut.on_screen_text, duration, cfg, font_name),
                encoding="utf-8",
            )
        hdr = cut.is_source and probe(cut.clip_path).is_hdr
        graph, _ = build_cut_filter(cut, cfg, ass_name, fonts_arg, hdr=hdr)
        temp = f"cut_{key}.part.mp4"
        run_ffmpeg(
            [
                *_cut_inputs(cut, duration),
                "-filter_complex", graph,
                "-map", "[v]",
                "-map", "[a]",
                "-c:v", "libx264",
                "-preset", cfg.preset,
                "-crf", str(cfg.crf),
                "-pix_fmt", "yuv420p",
                "-color_range", "tv",
                "-colorspace", "bt709",
                "-color_primaries", "bt709",
                "-color_trc", "bt709",
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


def _cut_inputs(cut: ComposeCut, duration: float) -> list[str]:
    """입력 0은 영상, 입력 1은 나레이션 또는 무음이다. _audio_graph의 번호와 맞춰야 한다."""
    clip = str(cut.clip_path.resolve())
    silence = ["-f", "lavfi", "-t", f"{duration:.3f}", "-i", "anullsrc=r=48000:cl=stereo"]
    if not cut.is_source:
        if cut.audio_path is None:  # 음악 전용 템플릿: 컷 소리는 무음, 음악은 최종 합성에서 얹는다
            return ["-i", clip, *silence]
        return ["-i", clip, "-i", str(cut.audio_path.resolve())]
    video = ["-ss", f"{cut.source_start:.3f}", "-t", f"{duration:.3f}", "-i", clip]
    if cut.audio_path is not None and cut.audio_mode != "source":
        return [*video, "-i", str(cut.audio_path.resolve())]
    # 원본 소리를 쓸 때도 입력 번호를 맞추려고 무음을 넣는다.
    return [*video, *silence]


def make_contact_sheet(clips: list[Any], out_path: Path, thumb_h: int = 480) -> Path:
    """컷별 대표 프레임을 한 장으로 모은다. 생성 결과를 보고 regen할 컷을 고를 때 쓴다.

    항목은 (컷 ID, 경로) 또는 (컷 ID, 경로, 뽑을 시각)이다.
    """
    frames: list[Image.Image] = []
    temp_dir = out_path.parent / "contact_frames"
    temp_dir.mkdir(parents=True, exist_ok=True)
    for item in clips:
        cut_id, clip = item[0], item[1]
        frame_path = temp_dir / f"{cut_id}.jpg"
        middle = item[2] if len(item) > 2 else probe(clip).duration / 2
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
