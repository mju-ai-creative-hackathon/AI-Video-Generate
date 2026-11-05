"""DB 접근 계층."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import Session, sessionmaker

from ..errors import NotFoundError
from ..schemas import PhotoAnalysis, PhotoRecord
from .models import (
    ASSET_DONE,
    JOB_ACTIVE,
    JOB_QUEUED,
    CutAsset,
    DatasetSource,
    EditLog,
    FramingExample,
    Job,
    Mission,
    Photo,
    Run,
    StoryboardVersion,
    Video,
)


def make_session_factory(database_url: str) -> sessionmaker[Session]:
    engine = create_engine(database_url, future=True)
    return sessionmaker(engine, expire_on_commit=False)


@contextmanager
def session_scope(factory: sessionmaker[Session]) -> Iterator[Session]:
    session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


class Repo:
    def __init__(self, session: Session) -> None:
        self.session = session

    # ── runs ──────────────────────────────────────────────────────────────
    def get_run(self, run_id: str) -> Run:
        run = self.session.get(Run, run_id)
        if run is None:
            raise NotFoundError(f"실행을 찾을 수 없습니다: {run_id}")
        return run

    def list_runs(self, mission_id: str | None = None) -> list[Run]:
        stmt = select(Run).order_by(Run.created_at.desc())
        if mission_id is not None:
            stmt = stmt.where(Run.mission_id == mission_id)
        return list(self.session.scalars(stmt))

    # ── missions ──────────────────────────────────────────────────────────
    def get_mission(self, mission_id: str) -> Mission:
        mission = self.session.get(Mission, mission_id)
        if mission is None:
            raise NotFoundError(f"미션을 찾을 수 없습니다: {mission_id}")
        return mission

    def list_missions(self) -> list[Mission]:
        return list(self.session.scalars(select(Mission).order_by(Mission.created_at.desc())))

    # ── jobs ──────────────────────────────────────────────────────────────
    def get_job(self, job_id: str) -> Job:
        job = self.session.get(Job, job_id)
        if job is None:
            raise NotFoundError(f"작업을 찾을 수 없습니다: {job_id}")
        return job

    def active_job(self, run_id: str) -> Job | None:
        stmt = (
            select(Job)
            .where(Job.run_id == run_id, Job.status.in_(JOB_ACTIVE))
            .order_by(Job.created_at.desc())
            .limit(1)
        )
        return self.session.scalars(stmt).first()

    def latest_job(self, run_id: str, kinds: tuple[str, ...] | None = None) -> Job | None:
        stmt = select(Job).where(Job.run_id == run_id)
        if kinds:
            stmt = stmt.where(Job.kind.in_(kinds))
        return self.session.scalars(stmt.order_by(Job.created_at.desc()).limit(1)).first()

    def next_queued_job(self) -> Job | None:
        stmt = select(Job).where(Job.status == JOB_QUEUED).order_by(Job.created_at).limit(1)
        return self.session.scalars(stmt).first()

    # ── photos ────────────────────────────────────────────────────────────
    def photos(self, run_id: str) -> list[Photo]:
        stmt = select(Photo).where(Photo.run_id == run_id).order_by(Photo.photo_id)
        return list(self.session.scalars(stmt))

    def photo_records(self, run_id: str, root: Path) -> list[PhotoRecord]:
        return [_to_record(photo, root) for photo in self.photos(run_id)]

    # ── storyboard versions ───────────────────────────────────────────────
    def latest_version(self, run_id: str) -> StoryboardVersion | None:
        stmt = (
            select(StoryboardVersion)
            .where(StoryboardVersion.run_id == run_id)
            .order_by(StoryboardVersion.version.desc())
            .limit(1)
        )
        return self.session.scalars(stmt).first()

    def get_version(self, run_id: str, version: int) -> StoryboardVersion:
        stmt = select(StoryboardVersion).where(
            StoryboardVersion.run_id == run_id, StoryboardVersion.version == version
        )
        row = self.session.scalars(stmt).first()
        if row is None:
            raise NotFoundError(f"스토리보드 버전을 찾을 수 없습니다: {run_id} v{version}")
        return row

    def list_versions(self, run_id: str) -> list[StoryboardVersion]:
        stmt = (
            select(StoryboardVersion)
            .where(StoryboardVersion.run_id == run_id)
            .order_by(StoryboardVersion.version)
        )
        return list(self.session.scalars(stmt))

    def add_edit_logs(self, logs: list[EditLog]) -> None:
        self.session.add_all(logs)

    def edit_logs(self, run_id: str) -> list[EditLog]:
        stmt = select(EditLog).where(EditLog.run_id == run_id).order_by(EditLog.id)
        return list(self.session.scalars(stmt))

    # ── cut assets ────────────────────────────────────────────────────────
    def find_asset(self, run_id: str, cut_id: str, kind: str, input_hash: str) -> CutAsset | None:
        stmt = (
            select(CutAsset)
            .where(
                CutAsset.run_id == run_id,
                CutAsset.cut_id == cut_id,
                CutAsset.kind == kind,
                CutAsset.input_hash == input_hash,
                CutAsset.status == ASSET_DONE,
            )
            .order_by(CutAsset.id.desc())
            .limit(1)
        )
        return self.session.scalars(stmt).first()

    def done_assets(self, run_id: str, cut_id: str, kind: str) -> list[CutAsset]:
        stmt = (
            select(CutAsset)
            .where(
                CutAsset.run_id == run_id,
                CutAsset.cut_id == cut_id,
                CutAsset.kind == kind,
                CutAsset.status == ASSET_DONE,
            )
            .order_by(CutAsset.id.desc())
        )
        return list(self.session.scalars(stmt))

    # ── videos ────────────────────────────────────────────────────────────
    def videos(self, run_id: str | None = None) -> list[Video]:
        stmt = select(Video).order_by(Video.id)
        if run_id is not None:
            stmt = stmt.where(Video.run_id == run_id)
        return list(self.session.scalars(stmt))

    def latest_video(self, run_id: str) -> Video | None:
        stmt = select(Video).where(Video.run_id == run_id).order_by(Video.id.desc()).limit(1)
        return self.session.scalars(stmt).first()

    def find_video(self, run_id: str, compose_hash: str) -> Video | None:
        stmt = select(Video).where(Video.run_id == run_id, Video.compose_hash == compose_hash)
        return self.session.scalars(stmt.order_by(Video.id.desc()).limit(1)).first()

    # ── corpus ────────────────────────────────────────────────────────────
    def corpus_stats(self) -> dict[str, Any]:
        def counts(column: Any) -> dict[str, int]:
            rows = self.session.execute(
                select(column, func.count()).select_from(FramingExample).group_by(column)
            )
            return {str(key): value for key, value in rows}

        return {
            "datasets": self.session.scalar(select(func.count()).select_from(DatasetSource)) or 0,
            "allowed_datasets": self.session.scalar(
                select(func.count())
                .select_from(DatasetSource)
                .where(DatasetSource.allowed_use.is_(True))
            )
            or 0,
            "examples": self.session.scalar(select(func.count()).select_from(FramingExample)) or 0,
            "by_status": counts(FramingExample.status),
            "by_split": counts(FramingExample.split),
        }


def _to_record(photo: Photo, root: Path) -> PhotoRecord:
    return PhotoRecord(
        photo_id=photo.photo_id,
        path=root / photo.path,
        width=photo.width,
        height=photo.height,
        sha256=photo.sha256,
        analysis=PhotoAnalysis.model_validate(photo.analysis) if photo.analysis else None,
        excluded_reason=photo.excluded_reason,
        analysis_path=(root / photo.analysis_path) if photo.analysis_path else None,
        media_type=photo.media_type,  # type: ignore[arg-type]
        video_path=(root / photo.video_path) if photo.video_path else None,
        media_duration=photo.media_duration,
        has_audio=photo.has_audio,
        note=photo.note,
        audio_mode=photo.audio_mode,  # type: ignore[arg-type]
    )
