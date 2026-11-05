"""FastAPI 앱 조립과 오류 응답."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import asdict
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from ..app import App
from ..errors import (
    ApprovalRequiredError,
    ImmutableVersionError,
    JobConflictError,
    NotFoundError,
    StaleBaseVersionError,
    StoryboardValidationError,
    UploadRejectedError,
    UploadTooLargeError,
    VlogError,
    WarningsNotAcceptedError,
)
from ..jobs import JobQueue, JobWorker, build_handlers
from .bridge import router as bridge_router
from .routes import media_router, router


def _error(status: int, code: str, message: str, **details: Any) -> JSONResponse:
    return JSONResponse(
        status_code=status, content={"error": {"code": code, "message": message, **details}}
    )


def _status_for(exc: VlogError) -> tuple[int, str]:
    if isinstance(exc, NotFoundError):
        return 404, "not_found"
    if isinstance(exc, UploadTooLargeError):
        return 413, "upload_too_large"
    if isinstance(exc, UploadRejectedError):
        return 415, "unsupported_media"
    if isinstance(exc, ApprovalRequiredError):
        return 409, "approval_required"
    if isinstance(exc, JobConflictError):
        return 409, "job_in_progress"
    if isinstance(exc, ImmutableVersionError):
        return 409, "version_immutable"
    return 400, "bad_request"


def _register_errors(app: FastAPI) -> None:
    @app.exception_handler(StoryboardValidationError)
    async def _validation(_request: Request, exc: StoryboardValidationError) -> JSONResponse:
        return _error(
            422,
            "storyboard_invalid",
            "스토리보드 검증 오류로 저장하지 않았습니다.",
            issues=[asdict(i) for i in exc.report.errors],
            warnings=[asdict(i) for i in exc.report.warnings],
        )

    @app.exception_handler(StaleBaseVersionError)
    async def _stale(_request: Request, exc: StaleBaseVersionError) -> JSONResponse:
        return _error(
            409,
            "stale_base_version",
            str(exc),
            base_version=exc.base_version,
            latest_version=exc.latest_version,
            changes=[asdict(c) for c in exc.changes],
        )

    @app.exception_handler(WarningsNotAcceptedError)
    async def _warnings(_request: Request, exc: WarningsNotAcceptedError) -> JSONResponse:
        return _error(
            409,
            "warnings_not_accepted",
            "경고를 확인한 뒤 accept_warnings를 true로 보내세요.",
            warnings=[asdict(i) for i in exc.report.warnings],
        )

    @app.exception_handler(VlogError)
    async def _vlog(_request: Request, exc: VlogError) -> JSONResponse:
        status, code = _status_for(exc)
        return _error(status, code, str(exc))

    @app.exception_handler(RequestValidationError)
    async def _request(_request: Request, exc: RequestValidationError) -> JSONResponse:
        issues = [
            {
                "location": ".".join(str(part) for part in err["loc"]),
                "code": err["type"],
                "message": err["msg"],
            }
            for err in exc.errors()
        ]
        return _error(422, "invalid_request", "요청 형식이 잘못됐습니다.", issues=issues)


def create_app(config_path: str | Path = "config.yaml", *, start_worker: bool = True) -> FastAPI:
    ctx = App.load(config_path)
    queue = JobQueue(ctx.factory)

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        worker = None
        if start_worker:
            worker = JobWorker(queue, build_handlers(ctx), ctx.settings.api.job_poll_sec)
            worker.start()
        yield
        if worker is not None:
            worker.stop()  # 실행 중인 작업은 다음 시작 때 이어서 실행한다

    app = FastAPI(
        title="Vlog Shorts API",
        version="1.0.0",
        description=(
            "미션별 사진·영상으로 스토리보드를 만들고, 사람이 검토·승인한 뒤 영상을 생성한다. "
            "승인 전에는 TTS와 영상 생성을 시작하지 않는다."
        ),
        lifespan=lifespan,
    )
    app.state.ctx = ctx
    app.state.queue = queue
    origins = ctx.settings.api.cors_origins
    if origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=origins,
            allow_methods=["GET", "POST", "PUT", "OPTIONS"],
            allow_headers=["X-API-Key", "Authorization", "Content-Type", "Range"],
            expose_headers=["Content-Range", "Accept-Ranges", "Content-Length"],
        )
    _register_errors(app)
    app.include_router(bridge_router)
    app.include_router(router)
    app.include_router(media_router)
    return app
