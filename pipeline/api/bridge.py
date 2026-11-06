"""Backend(Spring) 연동 경로.

iOS 앱은 Backend만 호출하고, Backend 워커가 이 경로로 소스를 넘기고 상태를 조회한다.
스토리보드 조회·수정·승인은 Backend가 기존 `/runs/...` 경로를 그대로 전달해 쓴다.

Backend의 작업 UUID(`Idempotency-Key`)를 실행 ID로 써서, 같은 키로 다시 보내면 같은 실행을 돌려준다.
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from ..app import App
from ..db.models import (
    JOB_ACTIVE,
    JOB_DONE,
    JOB_FAILED,
    JOB_NEEDS_REVIEW,
    JOB_QUEUED,
    Job,
    Mission,
    Run,
)
from ..db.repo import Repo, session_scope
from ..errors import NotFoundError
from ..media import extract_frame
from ..jobs import KIND_PLAN, KIND_REGEN_CLIP, KIND_RENDER, JobQueue, counter_of
from .models import FormatOption, MusicOption, SubmitItem
from .routes import PREFIX, check_choices, create_mission_run, format_options, music_options
from .security import require_api_key

router = APIRouter(prefix=PREFIX, dependencies=[Depends(require_api_key)], tags=["backend"])

# 같은 키로 동시에 들어온 요청이 실행을 두 번 만들지 않게 한다. 작업자가 하나라 직렬화해도 된다.
_submit_lock = asyncio.Lock()

_RENDER_KINDS = (KIND_RENDER, KIND_REGEN_CLIP)


class BackendSource(BaseModel):
    model_config = ConfigDict(extra="ignore")

    assetId: str | None = None
    position: int = 0
    contentType: str | None = None
    filename: str | None = None


class ShootingHint(BaseModel):
    model_config = ConfigDict(extra="ignore")

    missionId: int | None = None
    placeName: str | None = None
    kind: str | None = None
    title: str | None = None
    guide: str | None = None
    durationSec: int | None = None


class Manifest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    jobId: str | None = None
    projectId: str | None = None
    title: str | None = Field(default=None, max_length=200)
    style: str | None = None
    styleLabel: str | None = None
    mood: str | None = None
    moodLabel: str | None = None
    formatId: str | None = Field(default=None, description="템플릿 id. 없으면 자동 선택")
    musicId: str | None = Field(default=None, description="음악 id. 없으면 템플릿의 기본 음악")
    sources: list[BackendSource] = Field(default_factory=list)
    shootingHints: list[ShootingHint] = Field(default_factory=list)


class SubmitAccepted(BaseModel):
    jobId: str


class BridgeStatus(BaseModel):
    """Backend가 조회하는 상태. `status`는 Backend 계약의 값이다."""

    status: str  # QUEUED, PROCESSING, AWAITING_REVIEW, SUCCEEDED, FAILED
    phase: str | None  # DRAFTING, AWAITING_REVIEW, RENDERING, DONE
    busy: bool = False  # 검토 중 재생성 작업이 도는 중
    stage: str | None = None
    message: str | None = None
    current: int | None = None
    total: int | None = None
    storyboardVersion: int | None = None
    storyboardStatus: str | None = None
    error: str | None = None


def _ctx(request: Request) -> App:
    return request.app.state.ctx


def _queue(request: Request) -> JobQueue:
    return request.app.state.queue


def _parse_manifest(raw: str) -> Manifest:
    try:
        return Manifest.model_validate(json.loads(raw))
    except (ValueError, ValidationError) as exc:
        raise HTTPException(status_code=422, detail=f"manifest 형식이 잘못됐습니다: {exc}") from exc


def mission_text(manifest: Manifest) -> tuple[str, str]:
    """미션 제목과 설명. 설명은 대본 프롬프트의 사실 근거로 들어간다."""
    title = (manifest.title or "").strip() or "숏폼 브이로그"
    lines: list[str] = []
    style = manifest.styleLabel or manifest.style
    mood = manifest.moodLabel or manifest.mood
    if style:
        lines.append(f"영상 스타일: {style}")
    if mood:
        lines.append(f"분위기: {mood}")
    hints = [h for h in manifest.shootingHints if h.title or h.guide]
    if hints:
        # 소스와 미션의 대응 정보가 없으므로 파일별 메모로 넣지 않고 코스 설명으로만 쓴다.
        lines.append("코스 촬영 미션(순서대로, 어떤 사진이 어떤 미션인지는 알 수 없음):")
        for index, hint in enumerate(hints, start=1):
            place = f"[{hint.placeName}] " if hint.placeName else ""
            guide = f" — {hint.guide}" if hint.guide else ""
            lines.append(f"{index}. {place}{hint.title or ''}{guide}".rstrip())
    return title, "\n".join(lines)


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@router.post("/renders", response_model=SubmitAccepted, status_code=202)
async def submit_render(
    request: Request,
    idempotency_key: UUID = Header(alias="Idempotency-Key"),
    manifest: str = Form(description="Backend 제작 요청 메타데이터(JSON)"),
    files: list[UploadFile] = File(description="소스 파일. 이 순서가 컷 순서다"),
) -> SubmitAccepted:
    ctx = _ctx(request)
    run_id = idempotency_key.hex  # runs.id는 32자
    parsed = _parse_manifest(manifest)
    if parsed.sources and len(parsed.sources) != len(files):
        raise HTTPException(
            status_code=422,
            detail=f"sources와 files 수가 다릅니다: {len(parsed.sources)}, {len(files)}",
        )
    format_id = (parsed.formatId or "").strip() or None
    music_id = (parsed.musicId or "").strip() or None
    check_choices(ctx, format_id, music_id)

    async with _submit_lock:
        with session_scope(ctx.factory) as session:
            if session.get(Run, run_id) is not None:
                return SubmitAccepted(jobId=run_id)  # 재전송. 비용을 다시 쓰지 않는다
            title, description = mission_text(parsed)
            mission_id = f"m{run_id[:31]}"
            if session.get(Mission, mission_id) is None:
                session.add(Mission(id=mission_id, title=title, description=description))
        memo = f"미션: {title}\n{description}".strip()
        await create_mission_run(
            ctx,
            _queue(request),
            run_id,
            mission_id,
            memo,
            files,
            [SubmitItem() for _ in files],
            format_id,
            music_id,
        )
    return SubmitAccepted(jobId=run_id)


class Catalog(BaseModel):
    templates: list[FormatOption]
    music: list[MusicOption]


@router.get("/catalog", response_model=Catalog)
def catalog(request: Request) -> Catalog:
    """Backend가 앱에 보여줄 템플릿과 음악 목록. 음악은 파일까지 있는 곡만 나온다."""
    ctx = _ctx(request)
    return Catalog(templates=format_options(ctx), music=music_options(ctx))


def _progress(job: Job) -> dict[str, Any]:
    counter = counter_of(job.message)
    return {
        "stage": job.stage,
        "message": job.message,
        "current": counter[0] if counter else None,
        "total": counter[1] if counter else None,
    }


def bridge_status(repo: Repo, run_id: str) -> BridgeStatus:
    repo.get_run(run_id)
    latest = repo.latest_version(run_id)
    version = {
        "storyboardVersion": latest.version if latest else None,
        "storyboardStatus": latest.status if latest else None,
    }
    job = repo.latest_job(run_id)
    if job is None:
        if latest is None:
            return BridgeStatus(status="QUEUED", phase="DRAFTING", **version)
        return BridgeStatus(status="AWAITING_REVIEW", phase="AWAITING_REVIEW", **version)

    active = job.status in JOB_ACTIVE
    queued = job.status == JOB_QUEUED
    if job.kind == KIND_PLAN:
        if active:
            return BridgeStatus(
                status="QUEUED" if queued else "PROCESSING",
                phase="DRAFTING",
                **_progress(job),
                **version,
            )
        if latest is None:
            # 예외 원문에는 서버 경로가 들어갈 수 있어 Backend로 보내지 않는다.
            return BridgeStatus(
                status="FAILED", phase="DRAFTING", error="초안을 만들지 못했습니다.", **version
            )
        return BridgeStatus(status="AWAITING_REVIEW", phase="AWAITING_REVIEW", **version)

    if job.kind in _RENDER_KINDS:
        if active:
            return BridgeStatus(
                status="QUEUED" if queued else "PROCESSING",
                phase="RENDERING",
                **_progress(job),
                **version,
            )
        if job.status == JOB_DONE and repo.latest_video(run_id) is not None:
            return BridgeStatus(status="SUCCEEDED", phase="DONE", **version)
        # 실패나 재승인 필요(needs_review): 사람이 고쳐서 다시 승인할 수 있다.
        # needs_review 메시지는 파이프라인이 만든 안내문이라 그대로 보낸다.
        error = (
            job.error
            if job.status == JOB_NEEDS_REVIEW and job.error
            else "영상을 만들지 못했습니다. 다시 승인하면 끝난 컷은 재사용해 이어서 만듭니다."
        )
        return BridgeStatus(status="AWAITING_REVIEW", phase="AWAITING_REVIEW", error=error, **version)

    # 검토 중 재생성(regen)
    return BridgeStatus(
        status="AWAITING_REVIEW",
        phase="AWAITING_REVIEW",
        busy=active,
        error="재생성에 실패했습니다. 다시 시도해주세요." if job.status == JOB_FAILED else None,
        **(_progress(job) if active else {}),
        **version,
    )


@router.get("/renders/{run_id}", response_model=BridgeStatus)
def get_render(run_id: str, request: Request) -> BridgeStatus:
    with session_scope(_ctx(request).factory) as session:
        return bridge_status(Repo(session), run_id)


def _latest_video_path(ctx: App, run_id: str) -> Path:
    with session_scope(ctx.factory) as session:
        video = Repo(session).latest_video(run_id)
        if video is None:
            raise NotFoundError(f"아직 완성본이 없습니다: {run_id}")
        path = ctx.settings.resolve(video.path)
    if not path.is_file():
        raise NotFoundError("완성본 파일을 찾을 수 없습니다.")
    return path


@router.get("/renders/{run_id}/video")
def get_render_video(run_id: str, request: Request) -> FileResponse:
    return FileResponse(_latest_video_path(_ctx(request), run_id), media_type="video/mp4")


# 썸네일 시각. 첫 컷(제목이 있는 hook)이 보이도록 앞쪽에서 뽑는다.
THUMBNAIL_AT_SEC = 1.0


@router.get("/renders/{run_id}/thumbnail")
def get_render_thumbnail(run_id: str, request: Request) -> FileResponse:
    """완성본의 대표 이미지(JPEG). 처음 요청할 때 만들고, 영상이 바뀌면 다시 만든다."""
    video = _latest_video_path(_ctx(request), run_id)
    thumb = video.with_suffix(".thumb.jpg")
    if not thumb.exists() or thumb.stat().st_mtime < video.stat().st_mtime:
        extract_frame(video, THUMBNAIL_AT_SEC, thumb)
    return FileResponse(thumb, media_type="image/jpeg")
