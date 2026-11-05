"""초기 스키마

Revision ID: 0001
Revises:
"""

import sqlalchemy as sa
from pgvector.sqlalchemy import Vector
from sqlalchemy.dialects.postgresql import JSONB

from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None

JSONType = sa.JSON().with_variant(JSONB(), "postgresql")
TS = sa.DateTime(timezone=True)


def _embedding(dim: int):
    return sa.JSON().with_variant(Vector(dim), "postgresql")


def upgrade() -> None:
    if op.get_bind().dialect.name == "postgresql":
        op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    op.create_table(
        "dataset_sources",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("version", sa.String(64)),
        sa.Column("terms_url", sa.Text()),
        sa.Column("allowed_use", sa.Boolean(), nullable=False),
        sa.Column("redistribution", sa.Text()),
        sa.Column("attribution", sa.Text()),
        sa.Column("checked_at", TS),
    )
    op.create_table(
        "framing_examples",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("dataset_id", sa.String(64), sa.ForeignKey("dataset_sources.id"), nullable=False),
        sa.Column("image_ref", sa.Text(), nullable=False),
        sa.Column("split", sa.String(16), nullable=False),
        sa.Column("crop_box", JSONType, nullable=False),
        sa.Column("crop_source", sa.String(16), nullable=False),
        sa.Column("crop_score", sa.Float()),
        sa.Column("subject", JSONType, nullable=False),
        sa.Column("shot", JSONType, nullable=False),
        sa.Column("framing", JSONType, nullable=False),
        sa.Column("image_embedding", _embedding(768)),
        sa.Column("layout_vector", _embedding(11)),
        sa.Column("status", sa.String(16), nullable=False),
    )
    op.create_index(
        "ix_framing_examples_filter", "framing_examples", ["status", "split", "dataset_id"]
    )
    op.create_table(
        "motion_presets",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("shot_size", sa.String(16), nullable=False),
        sa.Column("camera_move", sa.String(16), nullable=False),
        sa.Column("prompt", sa.Text(), nullable=False),
        sa.Column("reproduction_score", sa.Float()),
        sa.Column("notes", sa.Text()),
    )
    op.create_table(
        "runs",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("memo", sa.Text()),
        sa.Column("config", JSONType, nullable=False),
        sa.Column("error", sa.Text()),
        sa.Column("created_at", TS, nullable=False),
        sa.Column("updated_at", TS, nullable=False),
    )
    op.create_table(
        "photos",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("run_id", sa.String(32), sa.ForeignKey("runs.id"), nullable=False),
        sa.Column("photo_id", sa.String(16), nullable=False),
        sa.Column("source_path", sa.Text(), nullable=False),
        sa.Column("path", sa.Text(), nullable=False),
        sa.Column("analysis_path", sa.Text()),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("width", sa.Integer(), nullable=False),
        sa.Column("height", sa.Integer(), nullable=False),
        sa.Column("taken_at", TS),
        sa.Column("excluded_reason", sa.Text()),
        sa.Column("analysis", JSONType),
        sa.UniqueConstraint("run_id", "photo_id", name="uq_photos_run_photo"),
    )
    op.create_index("ix_photos_run_id", "photos", ["run_id"])
    op.create_table(
        "storyboard_versions",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("run_id", sa.String(32), sa.ForeignKey("runs.id"), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("parent_version", sa.Integer()),
        sa.Column("author", sa.String(8), nullable=False),
        sa.Column("content", JSONType, nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("warnings_accepted", JSONType),
        sa.Column("format_id", sa.String(64)),
        sa.Column("created_at", TS, nullable=False),
        sa.Column("approved_at", TS),
        sa.UniqueConstraint("run_id", "version", name="uq_storyboard_run_version"),
    )
    op.create_index("ix_storyboard_versions_run_id", "storyboard_versions", ["run_id"])
    op.create_table(
        "edit_logs",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("run_id", sa.String(32), sa.ForeignKey("runs.id"), nullable=False),
        sa.Column("from_version", sa.Integer(), nullable=False),
        sa.Column("to_version", sa.Integer(), nullable=False),
        sa.Column("cut_id", sa.String(32)),
        sa.Column("field", sa.String(64), nullable=False),
        sa.Column("before", JSONType),
        sa.Column("after", JSONType),
        sa.Column("created_at", TS, nullable=False),
    )
    op.create_index("ix_edit_logs_run_id", "edit_logs", ["run_id"])
    op.create_table(
        "cut_assets",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("run_id", sa.String(32), sa.ForeignKey("runs.id"), nullable=False),
        sa.Column("cut_id", sa.String(32), nullable=False),
        sa.Column("kind", sa.String(8), nullable=False),
        sa.Column("input_hash", sa.String(64), nullable=False),
        sa.Column("path", sa.Text()),
        sa.Column("audio_sec", sa.Float()),
        sa.Column("seed", sa.BigInteger()),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("error", sa.Text()),
        sa.Column("meta", JSONType, nullable=False),
        sa.Column("created_at", TS, nullable=False),
    )
    op.create_index("ix_cut_assets_run_id", "cut_assets", ["run_id"])
    op.create_index(
        "ix_cut_assets_lookup", "cut_assets", ["run_id", "cut_id", "kind", "input_hash"]
    )
    op.create_table(
        "videos",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("run_id", sa.String(32), sa.ForeignKey("runs.id"), nullable=False),
        sa.Column("storyboard_version", sa.Integer(), nullable=False),
        sa.Column("compose_hash", sa.String(64), nullable=False),
        sa.Column("path", sa.Text(), nullable=False),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("size_bytes", sa.BigInteger(), nullable=False),
        sa.Column("duration", sa.Float(), nullable=False),
        sa.Column("width", sa.Integer(), nullable=False),
        sa.Column("height", sa.Integer(), nullable=False),
        sa.Column("fps", sa.Float(), nullable=False),
        sa.Column("codec", sa.String(32), nullable=False),
        sa.Column("created_at", TS, nullable=False),
    )
    op.create_index("ix_videos_run_id", "videos", ["run_id"])


def downgrade() -> None:
    for table in (
        "videos",
        "cut_assets",
        "edit_logs",
        "storyboard_versions",
        "photos",
        "runs",
        "motion_presets",
        "framing_examples",
        "dataset_sources",
    ):
        op.drop_table(table)
