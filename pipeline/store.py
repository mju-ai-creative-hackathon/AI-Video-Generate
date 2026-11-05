"""[8] 검증 후 저장: 파일은 저장소에, 경로·해시·정보는 DB에 둔다."""

from __future__ import annotations

import os
import shutil
from abc import ABC, abstractmethod
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from sqlalchemy.orm import Session, sessionmaker

from .config import ComposeSettings, Settings
from .db.models import RUN_COMPLETED, Video
from .db.repo import Repo, session_scope
from .errors import StoreError
from .media import MediaInfo, probe
from .preprocess import sha256_file


class VideoStore(ABC):
    """영상 저장소. MinIO·S3로 바꿀 때는 이 구현만 교체한다."""

    @abstractmethod
    def put(self, temp_path: Path, run_id: str, version: int) -> Path:
        """임시 파일을 저장소로 옮기고 최종 경로를 돌려준다. 기존 파일을 덮어쓰지 않는다."""


class LocalVideoStore(VideoStore):
    def __init__(self, root: Path) -> None:
        self.root = root

    def put(self, temp_path: Path, run_id: str, version: int) -> Path:
        directory = self.root / "videos" / run_id
        directory.mkdir(parents=True, exist_ok=True)
        target = directory / f"final_v{version}.mp4"
        number = 2
        while target.exists():
            target = directory / f"final_v{version}_{number}.mp4"
            number += 1
        shutil.move(str(temp_path), str(target))
        return target


def relative_to_root(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return str(path)


def verify_video(info: MediaInfo, cfg: ComposeSettings, expected_duration: float | None) -> None:
    problems = []
    if (info.width, info.height) != (cfg.width, cfg.height):
        problems.append(f"해상도 {info.width}x{info.height} (기대 {cfg.width}x{cfg.height})")
    if info.fps is None or abs(info.fps - cfg.fps) > 0.5:
        problems.append(f"fps {info.fps} (기대 {cfg.fps})")
    if info.codec != "h264":
        problems.append(f"코덱 {info.codec} (기대 h264)")
    if info.pix_fmt not in (None, "yuv420p"):
        problems.append(f"픽셀 형식 {info.pix_fmt} (기대 yuv420p)")
    if not info.has_audio:
        problems.append("음성 트랙 없음")
    if info.duration <= 0:
        problems.append("길이 0")
    if expected_duration is not None and abs(info.duration - expected_duration) > 0.5:
        problems.append(f"길이 {info.duration:.2f}초 (기대 {expected_duration:.2f}초)")
    if problems:
        raise StoreError("완성본 검증 실패: " + "; ".join(problems))


def save_final(
    factory: sessionmaker[Session],
    settings: Settings,
    store: VideoStore,
    temp_path: Path,
    *,
    run_id: str,
    version: int,
    compose_hash: str,
    expected_duration: float | None = None,
    probe_fn: Callable[[Path], MediaInfo] = probe,
) -> Video:
    info = probe_fn(temp_path)
    verify_video(info, settings.compose, expected_duration)
    final_path = store.put(temp_path, run_id, version)
    digest = sha256_file(final_path)
    size = final_path.stat().st_size
    try:
        # videos 행과 완료 상태를 한 트랜잭션에서 기록한다.
        with session_scope(factory) as session:
            repo = Repo(session)
            video = Video(
                run_id=run_id,
                storyboard_version=version,
                compose_hash=compose_hash,
                path=relative_to_root(final_path, settings.root),
                sha256=digest,
                size_bytes=size,
                duration=info.duration,
                width=info.width or 0,
                height=info.height or 0,
                fps=info.fps or 0.0,
                codec=info.codec or "",
            )
            session.add(video)
            run = repo.get_run(run_id)
            run.status = RUN_COMPLETED
            run.error = None
        return video
    except Exception as exc:
        raise StoreError(
            f"DB 기록에 실패했습니다. 파일은 남아 있습니다: {final_path}. "
            f"`vlog doctor`로 확인하세요. ({exc})"
        ) from exc


@dataclass
class DoctorReport:
    checked: int = 0
    missing_files: list[str] = field(default_factory=list)
    hash_mismatches: list[str] = field(default_factory=list)
    size_mismatches: list[str] = field(default_factory=list)
    orphan_files: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not (
            self.missing_files or self.hash_mismatches or self.size_mismatches or self.orphan_files
        )


def check_consistency(factory: sessionmaker[Session], settings: Settings) -> DoctorReport:
    """DB와 저장소를 대조해 불일치를 보고한다. 자동으로 지우지 않는다."""
    report = DoctorReport()
    known: set[str] = set()
    with session_scope(factory) as session:
        videos = Repo(session).videos()
    for video in videos:
        report.checked += 1
        path = settings.resolve(video.path)
        known.add(os.path.normcase(str(path.resolve())))
        if not path.exists():
            report.missing_files.append(f"{video.path} (run {video.run_id})")
            continue
        if path.stat().st_size != video.size_bytes:
            report.size_mismatches.append(video.path)
        if sha256_file(path) != video.sha256:
            report.hash_mismatches.append(video.path)
    videos_dir = settings.storage_path / "videos"
    if videos_dir.is_dir():
        for file in videos_dir.rglob("*.mp4"):
            if os.path.normcase(str(file.resolve())) not in known:
                report.orphan_files.append(relative_to_root(file, settings.root))
    return report
