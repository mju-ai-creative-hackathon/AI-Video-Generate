"""미션, 작업, 영상 입력

Revision ID: 0002
Revises: 0001
"""

import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None

JSONType = sa.JSON().with_variant(JSONB(), "postgresql")
TS = sa.DateTime(timezone=True)


def upgrade() -> None:
    op.create_table(
        "missions",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("created_at", TS, nullable=False),
    )
    # SQLite는 제약 조건을 나중에 추가할 수 없어 PostgreSQL에서만 외래 키를 건다.
    op.add_column("runs", sa.Column("mission_id", sa.String(32)))
    op.create_index("ix_runs_mission_id", "runs", ["mission_id"])
    if op.get_bind().dialect.name == "postgresql":
        op.create_foreign_key("fk_runs_mission_id", "runs", "missions", ["mission_id"], ["id"])

    op.add_column(
        "photos", sa.Column("media_type", sa.String(8), nullable=False, server_default="image")
    )
    op.add_column("photos", sa.Column("video_path", sa.Text()))
    op.add_column("photos", sa.Column("media_duration", sa.Float()))
    op.add_column(
        "photos", sa.Column("has_audio", sa.Boolean(), nullable=False, server_default=sa.false())
    )
    op.add_column("photos", sa.Column("note", sa.Text()))
    op.add_column("photos", sa.Column("audio_mode", sa.String(16)))

    op.create_table(
        "jobs",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("run_id", sa.String(32), sa.ForeignKey("runs.id"), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("stage", sa.String(32)),
        sa.Column("message", sa.Text()),
        sa.Column("params", JSONType, nullable=False),
        sa.Column("result", JSONType),
        sa.Column("error", sa.Text()),
        sa.Column("created_at", TS, nullable=False),
        sa.Column("started_at", TS),
        sa.Column("finished_at", TS),
    )
    op.create_index("ix_jobs_run_id", "jobs", ["run_id"])
    op.create_index("ix_jobs_status_created", "jobs", ["status", "created_at"])


def downgrade() -> None:
    op.drop_table("jobs")
    with op.batch_alter_table("photos") as batch:
        for column in ("audio_mode", "note", "has_audio", "media_duration", "video_path", "media_type"):
            batch.drop_column(column)
    if op.get_bind().dialect.name == "postgresql":
        op.drop_constraint("fk_runs_mission_id", "runs", type_="foreignkey")
    op.drop_index("ix_runs_mission_id", table_name="runs")
    with op.batch_alter_table("runs") as batch:
        batch.drop_column("mission_id")
    op.drop_table("missions")
