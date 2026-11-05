"""DB 스키마. 운영은 PostgreSQL + pgvector이고, 단위 테스트는 SQLite에서 돈다."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    event,
    inspect,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from ..errors import ImmutableVersionError

# 임베딩 모델은 데이터셋 구축 단계에서 확정한다(SigLIP 2 또는 OpenCLIP).
# 차원이 달라지면 Alembic 마이그레이션으로 바꾼다.
IMAGE_EMBEDDING_DIM = 768
# rag.framing.layout_vector()의 길이: 피사체 위치·크기 4 + 빈 공간 4 + 깊이 분포 3
LAYOUT_VECTOR_DIM = 11

JSONType = JSON().with_variant(JSONB(), "postgresql")

# runs.status
RUN_PLANNING = "planning"
RUN_REVIEW = "storyboard_review"
RUN_APPROVED = "approved"
RUN_NARRATED = "narrated"
RUN_RENDERED = "rendered"
RUN_COMPLETED = "completed"
RUN_FAILED = "failed"

VERSION_DRAFT = "draft"
VERSION_APPROVED = "approved"

ASSET_AUDIO = "audio"
ASSET_CLIP = "clip"
ASSET_DONE = "done"
ASSET_FAILED = "failed"


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _embedding(dim: int) -> Any:
    return JSON().with_variant(Vector(dim), "postgresql")


class Base(DeclarativeBase):
    pass


class DatasetSource(Base):
    __tablename__ = "dataset_sources"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(200))
    version: Mapped[str | None] = mapped_column(String(64))
    terms_url: Mapped[str | None] = mapped_column(Text)
    # 비영리 개인 학습 허용을 확인한 데이터셋만 True. 검색은 True인 것만 사용한다.
    allowed_use: Mapped[bool] = mapped_column(Boolean, default=False)
    redistribution: Mapped[str | None] = mapped_column(Text)
    attribution: Mapped[str | None] = mapped_column(Text)
    checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class FramingExample(Base):
    __tablename__ = "framing_examples"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    dataset_id: Mapped[str] = mapped_column(ForeignKey("dataset_sources.id"))
    image_ref: Mapped[str] = mapped_column(Text)
    split: Mapped[str] = mapped_column(String(16), default="search")  # search / eval
    crop_box: Mapped[list[float]] = mapped_column(JSONType)
    crop_source: Mapped[str] = mapped_column(String(16), default="native")  # native / derived
    crop_score: Mapped[float | None] = mapped_column(Float)
    subject: Mapped[dict[str, Any]] = mapped_column(JSONType, default=dict)
    shot: Mapped[dict[str, Any]] = mapped_column(JSONType, default=dict)
    framing: Mapped[dict[str, Any]] = mapped_column(JSONType, default=dict)
    image_embedding: Mapped[list[float] | None] = mapped_column(_embedding(IMAGE_EMBEDDING_DIM))
    layout_vector: Mapped[list[float] | None] = mapped_column(_embedding(LAYOUT_VECTOR_DIM))
    # draft → reviewed → indexed → retired. indexed만 검색한다.
    status: Mapped[str] = mapped_column(String(16), default="draft")

    __table_args__ = (Index("ix_framing_examples_filter", "status", "split", "dataset_id"),)


class MotionPreset(Base):
    """M4 벤치마크의 재현 평가 기록. 프롬프트 원본은 data/motion_presets.yaml이다."""

    __tablename__ = "motion_presets"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    shot_size: Mapped[str] = mapped_column(String(16))
    camera_move: Mapped[str] = mapped_column(String(16))
    prompt: Mapped[str] = mapped_column(Text)
    reproduction_score: Mapped[float | None] = mapped_column(Float)
    notes: Mapped[str | None] = mapped_column(Text)


class Run(Base):
    __tablename__ = "runs"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    status: Mapped[str] = mapped_column(String(32), default=RUN_PLANNING)
    memo: Mapped[str | None] = mapped_column(Text)
    config: Mapped[dict[str, Any]] = mapped_column(JSONType, default=dict)
    error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )


class Photo(Base):
    __tablename__ = "photos"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    run_id: Mapped[str] = mapped_column(ForeignKey("runs.id"), index=True)
    photo_id: Mapped[str] = mapped_column(String(16))
    source_path: Mapped[str] = mapped_column(Text)
    path: Mapped[str] = mapped_column(Text)
    analysis_path: Mapped[str | None] = mapped_column(Text)
    sha256: Mapped[str] = mapped_column(String(64))
    width: Mapped[int] = mapped_column(Integer)
    height: Mapped[int] = mapped_column(Integer)
    taken_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    excluded_reason: Mapped[str | None] = mapped_column(Text)
    analysis: Mapped[dict[str, Any] | None] = mapped_column(JSONType)

    __table_args__ = (UniqueConstraint("run_id", "photo_id", name="uq_photos_run_photo"),)


class StoryboardVersion(Base):
    __tablename__ = "storyboard_versions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    run_id: Mapped[str] = mapped_column(ForeignKey("runs.id"), index=True)
    version: Mapped[int] = mapped_column(Integer)
    parent_version: Mapped[int | None] = mapped_column(Integer)
    author: Mapped[str] = mapped_column(String(8))  # ai / human
    content: Mapped[dict[str, Any]] = mapped_column(JSONType)
    status: Mapped[str] = mapped_column(String(16), default=VERSION_DRAFT)
    warnings_accepted: Mapped[list[Any] | None] = mapped_column(JSONType)
    format_id: Mapped[str | None] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    approved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (UniqueConstraint("run_id", "version", name="uq_storyboard_run_version"),)


@event.listens_for(StoryboardVersion, "before_update")
def _forbid_approved_update(_mapper, _connection, target: StoryboardVersion) -> None:
    """승인된 버전은 어떤 경로로도 바꿀 수 없다."""
    history = inspect(target).attrs.status.history
    previous = history.deleted[0] if history.deleted else target.status
    if previous == VERSION_APPROVED:
        raise ImmutableVersionError(
            f"승인된 버전은 바꿀 수 없습니다: {target.run_id} v{target.version}"
        )


class EditLog(Base):
    __tablename__ = "edit_logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    run_id: Mapped[str] = mapped_column(ForeignKey("runs.id"), index=True)
    from_version: Mapped[int] = mapped_column(Integer)
    to_version: Mapped[int] = mapped_column(Integer)
    cut_id: Mapped[str | None] = mapped_column(String(32))
    field: Mapped[str] = mapped_column(String(64))
    before: Mapped[Any] = mapped_column(JSONType, nullable=True)
    after: Mapped[Any] = mapped_column(JSONType, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class CutAsset(Base):
    __tablename__ = "cut_assets"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    run_id: Mapped[str] = mapped_column(ForeignKey("runs.id"), index=True)
    cut_id: Mapped[str] = mapped_column(String(32))
    kind: Mapped[str] = mapped_column(String(8))  # audio / clip
    input_hash: Mapped[str] = mapped_column(String(64))
    path: Mapped[str | None] = mapped_column(Text)
    audio_sec: Mapped[float | None] = mapped_column(Float)
    seed: Mapped[int | None] = mapped_column(BigInteger)
    status: Mapped[str] = mapped_column(String(16))
    error: Mapped[str | None] = mapped_column(Text)
    meta: Mapped[dict[str, Any]] = mapped_column(JSONType, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    __table_args__ = (Index("ix_cut_assets_lookup", "run_id", "cut_id", "kind", "input_hash"),)


class Video(Base):
    __tablename__ = "videos"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    run_id: Mapped[str] = mapped_column(ForeignKey("runs.id"), index=True)
    storyboard_version: Mapped[int] = mapped_column(Integer)
    compose_hash: Mapped[str] = mapped_column(String(64))
    path: Mapped[str] = mapped_column(Text)
    sha256: Mapped[str] = mapped_column(String(64))
    size_bytes: Mapped[int] = mapped_column(BigInteger)
    duration: Mapped[float] = mapped_column(Float)
    width: Mapped[int] = mapped_column(Integer)
    height: Mapped[int] = mapped_column(Integer)
    fps: Mapped[float] = mapped_column(Float)
    codec: Mapped[str] = mapped_column(String(32))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
