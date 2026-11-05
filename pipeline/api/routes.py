"""API 경로. 오래 걸리는 작업은 작업 큐에 넣고 202와 job_id를 바로 돌려준다."""

from __future__ import annotations

import json
import secrets
import shutil
from dataclasses import asdict
from pathlib import Path
from typing import Any, get_args

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse
from pydantic import TypeAdapter, ValidationError

from ..app import App
from ..db.models import RUN_PLANNING, Job, Mission, Run
from ..db.repo import Repo, session_scope
from ..errors import NotFoundError, UploadRejectedError, UploadTooLargeError, VlogError
from ..jobs import (
    KIND_PLAN,
    KIND_REGEN,
    KIND_REGEN_CLIP,
    KIND_RENDER,
    JobQueue,
    counter_of,
)
from ..plan import new_run_id
from ..preprocess import SUPPORTED, VIDEO_SUPPORTED
from ..schemas import (
    LOCKABLE_FIELDS,
    REGEN_FIELDS,
    AudioMode,
    CameraMove,
    EditFile,
    Role,
    ShotSize,
    Transition,
)
from ..store import relative_to_root
from .models import (
    ApproveRequest,
    ApproveResult,
    FormatOption,
    JobAccepted,
    MusicOption,
    JobOut,
    MissionCreate,
    MissionOut,
    Options,
    RegenClipRequest,
    RegenRequest,
    RenderRequest,
    RevertRequest,
    RunOut,
    SaveResult,
    StoryboardView,
    SubmitItem,
    TTSName,
    VideoName,
    VideoOut,
)
from .security import has_valid_key, require_api_key, sign_url, verify_signed_url
from .uploads import store_uploads

PREFIX = "/api/v1"

# API 키가 필요한 경로. 미디어 경로는 서명된 URL로 확인한다.
router = APIRouter(prefix=PREFIX, dependencies=[Depends(require_api_key)])
media_router = APIRouter(prefix=PREFIX, tags=["media"])

_items_adapter = TypeAdapter(list[SubmitItem])


def _ctx(request: Request) -> App:
    return request.app.state.ctx


def _queue(request: Request) -> JobQueue:
    return request.app.state.queue


def _signed(ctx: App, path: str) -> str:
    return sign_url(path, ctx.settings.env.api_key, ctx.settings.api.url_ttl_sec)


def _job_out(job: Job) -> JobOut:
    counter = counter_of(job.message)
    return JobOut(
        id=job.id,
        run_id=job.run_id,
        kind=job.kind,
        status=job.status,  # type: ignore[arg-type]
        stage=job.stage,
        message=job.message,
        current=counter[0] if counter else None,
        total=counter[1] if counter else None,
        result=job.result,
        error=job.error,
        created_at=job.created_at,
        started_at=job.started_at,
        finished_at=job.finished_at,
    )


def _run_out(ctx: App, repo: Repo, run: Run) -> RunOut:
    latest = repo.latest_version(run.id)
    active = repo.active_job(run.id)
    video = repo.latest_video(run.id)
    return RunOut(
        id=run.id,
        mission_id=run.mission_id,
        status=run.status,
        error=run.error,
        latest_version=latest.version if latest else None,
        latest_version_status=latest.status if latest else None,
        active_job=_job_out(active) if active else None,
        video=VideoOut(
            url=_signed(ctx, f"{PREFIX}/runs/{run.id}/video"),
            storyboard_version=video.storyboard_version,
            duration=video.duration,
            width=video.width,
            height=video.height,
            size_bytes=video.size_bytes,
            created_at=video.created_at,
        )
        if video
        else None,
        created_at=run.created_at,
        updated_at=run.updated_at,
    )


def _issues(report: Any) -> list[dict[str, str]]:
    return [asdict(issue) for issue in report.warnings]


def _changes(changes: list[Any]) -> list[dict[str, Any]]:
    return [asdict(change) for change in changes]


# ── 선택지 ────────────────────────────────────────────────────────────────
def format_options(ctx: App) -> list[FormatOption]:
    return [
        FormatOption(
            id=t.format_id,
            name=t.name,
            hook_type=t.hook_type,
            total_sec_min=t.total_sec.min,
            total_sec_max=t.total_sec.max,
            audio_mode=t.audio.mode,
            music=t.audio.music,
        )
        for t in ctx.formats.values()
    ]


def music_options(ctx: App) -> list[MusicOption]:
    return [
        MusicOption(id=t.id, name=t.name, moods=t.moods, bpm=t.bpm) for t in ctx.music.available()
    ]


def check_choices(ctx: App, format_id: str | None, music_id: str | None) -> None:
    """제출 전에 템플릿과 음악을 확인한다. 작업을 만든 뒤에 실패하지 않게 한다."""
    if format_id is not None and format_id not in ctx.formats:
        raise HTTPException(status_code=422, detail=f"없는 템플릿입니다: {format_id}")
    if music_id is not None and ctx.music.path(music_id) is None:
        raise HTTPException(status_code=422, detail=f"없는 음악입니다: {music_id}")


@router.get("/options", response_model=Options, tags=["options"])
def options(request: Request) -> Options:
    ctx = _ctx(request)
    cfg = ctx.settings.api
    return Options(
        formats=format_options(ctx),
        music=music_options(ctx),
        effects=list(ctx.effects.names),
        shot_sizes=list(get_args(ShotSize)),
        camera_moves=list(get_args(CameraMove)),
        transitions=list(get_args(Transition)),
        roles=list(get_args(Role)),
        audio_modes=list(get_args(AudioMode)),
        regen_fields=sorted(REGEN_FIELDS),
        lockable_fields=sorted(LOCKABLE_FIELDS),
        tts_providers=list(get_args(TTSName)),
        video_providers=list(get_args(VideoName)),
        image_extensions=sorted(SUPPORTED),
        video_extensions=sorted(VIDEO_SUPPORTED),
        max_files=cfg.max_files,
        max_file_mb=cfg.max_file_mb,
        max_video_sec=cfg.max_video_sec,
    )


# ── 미션 ──────────────────────────────────────────────────────────────────
@router.get("/missions", response_model=list[MissionOut], tags=["missions"])
def list_missions(request: Request) -> list[Mission]:
    with session_scope(_ctx(request).factory) as session:
        return Repo(session).list_missions()


@router.post("/missions", response_model=MissionOut, status_code=201, tags=["missions"])
def create_mission(body: MissionCreate, request: Request) -> Mission:
    with session_scope(_ctx(request).factory) as session:
        mission = Mission(
            id=f"m{secrets.token_hex(6)}", title=body.title.strip(), description=body.description
        )
        session.add(mission)
        session.flush()
        return mission


@router.get("/missions/{mission_id}", response_model=MissionOut, tags=["missions"])
def get_mission(mission_id: str, request: Request) -> Mission:
    with session_scope(_ctx(request).factory) as session:
        return Repo(session).get_mission(mission_id)


@router.get("/missions/{mission_id}/runs", response_model=list[RunOut], tags=["missions"])
def list_mission_runs(mission_id: str, request: Request) -> list[RunOut]:
    ctx = _ctx(request)
    with session_scope(ctx.factory) as session:
        repo = Repo(session)
        repo.get_mission(mission_id)
        return [_run_out(ctx, repo, run) for run in repo.list_runs(mission_id)]


# ── 제출 ──────────────────────────────────────────────────────────────────
def _parse_items(raw: str | None, count: int) -> list[SubmitItem]:
    if not raw:
        return [SubmitItem() for _ in range(count)]
    try:
        items = _items_adapter.validate_python(json.loads(raw))
    except (ValueError, ValidationError) as exc:
        raise HTTPException(status_code=422, detail=f"items 형식이 잘못됐습니다: {exc}") from exc
    if len(items) != count:
        raise HTTPException(
            status_code=422, detail=f"items는 파일 수와 같아야 합니다: 파일 {count}개, items {len(items)}개"
        )
    return items


@router.post(
    "/missions/{mission_id}/runs", response_model=JobAccepted, status_code=202, tags=["runs"]
)
async def submit_run(
    mission_id: str,
    request: Request,
    files: list[UploadFile] = File(description="사진·영상. 이 순서가 컷 순서다"),
    items: str | None = Form(
        default=None,
        description='파일별 정보의 JSON 배열. 예: [{"note": "주문 장면", "audio": "mix"}, {}]',
    ),
    format_id: str | None = Form(default=None, alias="format", description="생략하면 자동 선택"),
    music_id: str | None = Form(
        default=None, alias="music", description="배경 음악 id. 생략하면 템플릿의 기본 음악"
    ),
) -> JobAccepted:
    ctx = _ctx(request)
    cfg = ctx.settings.api
    declared = request.headers.get("content-length")
    limit = cfg.max_files * cfg.max_file_mb * 1024 * 1024
    if declared and declared.isdigit() and int(declared) > limit:
        raise UploadTooLargeError("업로드 전체 용량이 제한을 넘습니다.")
    parsed = _parse_items(items, len(files))
    check_choices(ctx, format_id, music_id)

    with session_scope(ctx.factory) as session:
        mission = Repo(session).get_mission(mission_id)
        memo = f"미션: {mission.title}\n{mission.description}".strip()

    run_id = new_run_id()
    job_id = await create_mission_run(
        ctx, _queue(request), run_id, mission_id, memo, files, parsed, format_id, music_id
    )
    return JobAccepted(job_id=job_id, run_id=run_id)


async def create_mission_run(
    ctx: App,
    queue: JobQueue,
    run_id: str,
    mission_id: str,
    memo: str,
    files: list[UploadFile],
    items: list[SubmitItem],
    format_id: str | None,
    music_id: str | None = None,
) -> str:
    """업로드를 검증·저장하고 실행을 만든 뒤 초안 생성 작업을 등록한다. 작업 ID를 돌려준다."""
    upload_dir = ctx.settings.storage_path / "runs" / run_id / "uploads"
    try:
        paths = await store_uploads(files, upload_dir, ctx.settings.api)
    except (UploadRejectedError, UploadTooLargeError):
        # 이번 요청이 만든 폴더만 지운다. 실행 기록은 아직 없다.
        shutil.rmtree(upload_dir.parent, ignore_errors=True)
        raise

    with session_scope(ctx.factory) as session:
        session.add(
            Run(
                id=run_id,
                mission_id=mission_id,
                status=RUN_PLANNING,
                memo=memo,
                config={
                    "mode": "mission",
                    "format_id": format_id,
                    "music_id": music_id,
                    "seeds": {},
                },
            )
        )
    params = {
        "format": format_id,
        "items": [
            {
                "path": relative_to_root(path, ctx.settings.root),
                "note": item.note,
                "audio": item.audio,
            }
            for path, item in zip(paths, items)
        ],
    }
    return queue.submit(run_id, KIND_PLAN, params)


# ── 작업과 실행 ───────────────────────────────────────────────────────────
@router.get("/jobs/{job_id}", response_model=JobOut, tags=["jobs"])
def get_job(job_id: str, request: Request) -> JobOut:
    with session_scope(_ctx(request).factory) as session:
        return _job_out(Repo(session).get_job(job_id))


@router.get("/runs/{run_id}", response_model=RunOut, tags=["runs"])
def get_run(run_id: str, request: Request) -> RunOut:
    ctx = _ctx(request)
    with session_scope(ctx.factory) as session:
        repo = Repo(session)
        return _run_out(ctx, repo, repo.get_run(run_id))


# ── 스토리보드 ────────────────────────────────────────────────────────────
def _add_urls(ctx: App, run_id: str, view: dict[str, Any]) -> dict[str, Any]:
    def image(photo_id: str) -> str:
        return _signed(ctx, f"{PREFIX}/runs/{run_id}/media/{photo_id}/poster")

    def video(photo_id: str) -> str:
        return _signed(ctx, f"{PREFIX}/runs/{run_id}/media/{photo_id}")

    for cut in view["cuts"]:
        media = cut["media"]
        media["image_url"] = image(media["photo_id"])
        media["video_url"] = video(media["photo_id"]) if media["type"] == "video" else None
    for photo in view["photos"]:
        photo["image_url"] = image(photo["photo_id"])
        photo["video_url"] = video(photo["photo_id"]) if photo["type"] == "video" else None
    return view


@router.get("/runs/{run_id}/storyboard", response_model=StoryboardView, tags=["storyboard"])
def get_storyboard(
    run_id: str, request: Request, version: int | None = Query(default=None)
) -> dict[str, Any]:
    ctx = _ctx(request)
    return _add_urls(ctx, run_id, ctx.service.to_view(run_id, version))


@router.put("/runs/{run_id}/storyboard", response_model=SaveResult, tags=["storyboard"])
def save_storyboard(run_id: str, body: EditFile, request: Request) -> SaveResult:
    _queue(request).ensure_idle(run_id)
    result = _ctx(request).service.import_edit(run_id, body)
    return SaveResult(
        version=result.version,
        changed=result.changed,
        changes=_changes(result.changes),
        warnings=_issues(result.warnings),
    )


@router.post(
    "/runs/{run_id}/storyboard/regen",
    response_model=JobAccepted,
    status_code=202,
    tags=["storyboard"],
)
def regen_storyboard(run_id: str, body: RegenRequest, request: Request) -> JobAccepted:
    unknown = sorted(set(body.fields or []) - REGEN_FIELDS)
    if unknown:
        raise HTTPException(status_code=422, detail=f"재생성할 수 없는 항목입니다: {unknown}")
    job_id = _queue(request).submit(run_id, KIND_REGEN, body.model_dump())
    return JobAccepted(job_id=job_id, run_id=run_id)


@router.get("/runs/{run_id}/storyboard/diff", tags=["storyboard"])
def diff_storyboard(
    run_id: str,
    request: Request,
    from_version: int = Query(alias="from"),
    to_version: int = Query(alias="to"),
) -> list[dict[str, Any]]:
    return _changes(_ctx(request).service.diff(run_id, from_version, to_version))


@router.post("/runs/{run_id}/storyboard/revert", tags=["storyboard"])
def revert_storyboard(run_id: str, body: RevertRequest, request: Request) -> dict[str, int]:
    _queue(request).ensure_idle(run_id)
    return {"version": _ctx(request).service.revert(run_id, body.version)}


@router.post("/runs/{run_id}/approve", response_model=ApproveResult, tags=["storyboard"])
def approve_storyboard(run_id: str, body: ApproveRequest, request: Request) -> ApproveResult:
    _queue(request).ensure_idle(run_id)
    version, report = _ctx(request).service.approve(
        run_id, body.version, accept_warnings=body.accept_warnings
    )
    return ApproveResult(version=version, warnings=_issues(report))


# ── 영상 생성 ─────────────────────────────────────────────────────────────
@router.post("/runs/{run_id}/render", response_model=JobAccepted, status_code=202, tags=["render"])
def render_run(run_id: str, request: Request, body: RenderRequest | None = None) -> JobAccepted:
    _ctx(request).service.require_approved(run_id)  # 미승인이면 작업을 만들지 않는다
    params = (body or RenderRequest()).model_dump()
    return JobAccepted(job_id=_queue(request).submit(run_id, KIND_RENDER, params), run_id=run_id)


@router.post(
    "/runs/{run_id}/cuts/{cut_id}/regen-clip",
    response_model=JobAccepted,
    status_code=202,
    tags=["render"],
)
def regen_clip(
    run_id: str, cut_id: str, request: Request, body: RegenClipRequest | None = None
) -> JobAccepted:
    ctx = _ctx(request)
    approved = ctx.service.require_approved(run_id)
    cut = approved.storyboard.cut(cut_id)
    if cut is None:
        raise NotFoundError(f"없는 컷입니다: {cut_id}")
    if cut.source_clip is not None:
        raise VlogError(f"컷 {cut_id}은 원본 영상을 쓰므로 다시 생성할 영상이 없습니다.")
    params = {"cut_id": cut_id, **(body or RegenClipRequest()).model_dump()}
    return JobAccepted(
        job_id=_queue(request).submit(run_id, KIND_REGEN_CLIP, params), run_id=run_id
    )


# ── 미디어 (서명된 URL) ───────────────────────────────────────────────────
def _check_signature(request: Request, expires: int | None, sig: str | None) -> None:
    """서명된 URL, 또는 API 키 헤더(Backend 프록시)로 연다."""
    key = _ctx(request).settings.env.api_key
    if expires is not None and sig and verify_signed_url(request.url.path, expires, sig, key):
        return
    if key and has_valid_key(request):
        return
    raise HTTPException(status_code=401, detail="URL이 만료됐거나 올바르지 않습니다.")


def _file_response(path: Path, media_type: str | None = None) -> FileResponse:
    if not path.is_file():
        raise NotFoundError("파일을 찾을 수 없습니다.")
    return FileResponse(path, media_type=media_type)


def _photo_record(ctx: App, run_id: str, photo_id: str) -> Any:
    # 경로를 직접 받지 않고 DB에 있는 항목만 제공한다.
    record = next((p for p in ctx.service.photos(run_id) if p.photo_id == photo_id), None)
    if record is None:
        raise NotFoundError(f"없는 자료입니다: {photo_id}")
    return record


@media_router.get("/runs/{run_id}/media/{photo_id}/poster")
def get_poster(
    run_id: str, photo_id: str, request: Request, expires: int | None = None, sig: str | None = None
) -> FileResponse:
    _check_signature(request, expires, sig)
    return _file_response(_photo_record(_ctx(request), run_id, photo_id).path, "image/jpeg")


@media_router.get("/runs/{run_id}/media/{photo_id}")
def get_media(
    run_id: str, photo_id: str, request: Request, expires: int | None = None, sig: str | None = None
) -> FileResponse:
    _check_signature(request, expires, sig)
    record = _photo_record(_ctx(request), run_id, photo_id)
    return _file_response(record.video_path or record.path)


@media_router.get("/runs/{run_id}/video")
def get_video(
    run_id: str, request: Request, expires: int | None = None, sig: str | None = None
) -> FileResponse:
    _check_signature(request, expires, sig)
    ctx = _ctx(request)
    with session_scope(ctx.factory) as session:
        video = Repo(session).latest_video(run_id)
        if video is None:
            raise NotFoundError(f"아직 완성본이 없습니다: {run_id}")
        path = ctx.settings.resolve(video.path)
    return _file_response(path, "video/mp4")
