"""음성 길이로 컷 길이와 영상 프레임 수를 정한다."""

from __future__ import annotations

import math
from dataclasses import dataclass

from .config import TimingSettings


@dataclass(frozen=True)
class CutTiming:
    audio_sec: float
    duration_sec: float  # 합성 때의 컷 길이
    frames: int  # 생성할 프레임 수 (4n+1)
    speed: float  # 1.0보다 크면 그만큼 느리게 재생
    too_long: bool = False  # 나레이션을 줄여야 함


def round_up_frames(frames: float) -> int:
    """Wan 입력 규칙에 맞춰 4n+1로 올림한다."""
    n = math.ceil((frames - 1) / 4)
    return int(4 * max(n, 0) + 1)


def plan_timing(audio_sec: float, cfg: TimingSettings) -> CutTiming:
    needed = audio_sec + cfg.pad_sec
    duration = max(needed, cfg.min_cut_sec)
    max_clip_sec = cfg.max_frames / cfg.source_fps
    if needed <= max_clip_sec:
        wanted = math.ceil(duration * cfg.source_fps)
        frames = min(cfg.max_frames, max(cfg.min_frames, round_up_frames(wanted)))
        return CutTiming(audio_sec, round(duration, 3), frames, 1.0)
    speed = needed / max_clip_sec
    if speed <= cfg.max_slowdown:
        return CutTiming(audio_sec, round(duration, 3), cfg.max_frames, round(speed, 4))
    return CutTiming(audio_sec, round(duration, 3), cfg.max_frames, 1.0, too_long=True)


def plan_fixed_timing(target_sec: float, cfg: TimingSettings, min_sec: float) -> CutTiming:
    """나레이션 없이 정해진 길이로 만든다(음악 전용 템플릿). 생성 모델의 최소 프레임보다 짧으면
    클립을 그만큼 만들고 합성에서 잘라 쓴다."""
    max_clip_sec = cfg.max_frames / cfg.source_fps
    duration = round(min(max(target_sec, min_sec), max_clip_sec), 3)
    wanted = math.ceil(duration * cfg.source_fps)
    frames = min(cfg.max_frames, max(cfg.min_frames, round_up_frames(wanted)))
    return CutTiming(0.0, duration, frames, 1.0)


def plan_source_timing(clip_sec: float, audio_sec: float | None, cfg: TimingSettings) -> CutTiming:
    """원본 영상 컷의 길이는 고른 구간이 정한다. 프레임을 생성하지 않으므로 frames는 0이다."""
    too_long = audio_sec is not None and audio_sec + cfg.pad_sec > clip_sec
    return CutTiming(audio_sec or 0.0, round(clip_sec, 3), 0, 1.0, too_long=too_long)


def allowed_audio_sec(cfg: TimingSettings) -> float:
    return cfg.max_frames / cfg.source_fps * cfg.max_slowdown - cfg.pad_sec
