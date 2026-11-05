"""서버의 오래 걸리는 작업 큐.

GPU에는 한 번에 한 모델만 올리므로 작업자 스레드 하나가 오래된 작업부터 하나씩 처리한다.
상태는 DB에 두어 서버를 다시 켜도 이어서 실행한다. 입력 해시 덕분에 끝난 컷은 재사용된다.
"""

from __future__ import annotations

import logging
import re
import secrets
import threading
from collections.abc import Callable
from typing import Any

from sqlalchemy import update
from sqlalchemy.orm import Session, sessionmaker

from .app import App
from .db.models import (
    JOB_DONE,
    JOB_FAILED,
    JOB_NEEDS_REVIEW,
    JOB_QUEUED,
    JOB_RUNNING,
    Job,
    utcnow,
)
from .db.repo import Repo, session_scope
from .errors import JobConflictError, ReapprovalRequiredError, VlogError
from .preprocess import MediaItem

log = logging.getLogger(__name__)

KIND_PLAN = "plan"
KIND_REGEN = "regen"
KIND_RENDER = "render"
KIND_REGEN_CLIP = "regen_clip"

# 진행 메시지를 프론트가 쓰기 쉬운 단계 이름으로 바꾼다.
_STAGES = (
    ("전처리", "preprocess"),
    ("분석", "analyze"),
    ("대본", "script"),
    ("프롬프트", "script"),
    ("이미지", "image"),
    ("나레이션", "narration"),
    ("영상 생성", "video"),
    ("합성", "compose"),
    ("저장", "store"),
)
_COUNTER = re.compile(r"(\d+)/(\d+)")

Handler = Callable[[dict[str, Any], str, Callable[[str], None]], dict[str, Any] | None]


def stage_of(message: str) -> str | None:
    return next((stage for word, stage in _STAGES if word in message), None)


def counter_of(message: str | None) -> tuple[int, int] | None:
    match = _COUNTER.search(message or "")
    return (int(match.group(1)), int(match.group(2))) if match else None


class JobQueue:
    """작업 등록과 상태 변경. API 스레드와 작업자 스레드가 함께 쓴다."""

    def __init__(self, factory: sessionmaker[Session]) -> None:
        self.factory = factory
        self._lock = threading.Lock()
        self._wake = threading.Event()

    def submit(self, run_id: str, kind: str, params: dict[str, Any] | None = None) -> str:
        with self._lock, session_scope(self.factory) as session:
            repo = Repo(session)
            repo.get_run(run_id)
            self._raise_if_busy(repo, run_id)
            job_id = f"j{secrets.token_hex(8)}"
            session.add(Job(id=job_id, run_id=run_id, kind=kind, params=params or {}))
        self._wake.set()
        return job_id

    def ensure_idle(self, run_id: str) -> None:
        """생성 중에는 스토리보드를 바꾸지 못하게 한다."""
        with session_scope(self.factory) as session:
            self._raise_if_busy(Repo(session), run_id)

    @staticmethod
    def _raise_if_busy(repo: Repo, run_id: str) -> None:
        active = repo.active_job(run_id)
        if active is not None:
            raise JobConflictError(
                f"진행 중인 작업이 있습니다: {active.id} ({active.kind}, {active.status})"
            )

    def recover(self) -> int:
        """서버가 꺼질 때 실행 중이던 작업을 다시 대기열에 넣는다."""
        with session_scope(self.factory) as session:
            result = session.execute(
                update(Job).where(Job.status == JOB_RUNNING).values(status=JOB_QUEUED)
            )
            return result.rowcount or 0

    def claim_next(self) -> tuple[str, str, str, dict[str, Any]] | None:
        with self._lock, session_scope(self.factory) as session:
            job = Repo(session).next_queued_job()
            if job is None:
                return None
            job.status = JOB_RUNNING
            job.started_at = utcnow()
            job.error = None
            return job.id, job.run_id, job.kind, dict(job.params or {})

    def set_progress(self, job_id: str, message: str) -> None:
        with session_scope(self.factory) as session:
            job = session.get(Job, job_id)
            if job is not None:
                job.message = message[:500]
                job.stage = stage_of(message) or job.stage

    def finish(
        self,
        job_id: str,
        status: str,
        *,
        result: dict[str, Any] | None = None,
        error: str | None = None,
    ) -> None:
        with session_scope(self.factory) as session:
            job = session.get(Job, job_id)
            if job is not None:
                job.status = status
                job.result = result
                job.error = error[:2000] if error else None
                job.finished_at = utcnow()

    def wait(self, timeout: float) -> None:
        self._wake.wait(timeout)
        self._wake.clear()


class JobWorker(threading.Thread):
    def __init__(
        self, queue: JobQueue, handlers: dict[str, Handler], poll_sec: float = 2.0
    ) -> None:
        super().__init__(name="vlog-job-worker", daemon=True)
        self.queue = queue
        self.handlers = handlers
        self.poll_sec = poll_sec
        self._stop_flag = threading.Event()

    def stop(self) -> None:
        self._stop_flag.set()
        self.queue._wake.set()

    def run(self) -> None:
        recovered = self.queue.recover()
        if recovered:
            log.info("중단됐던 작업 %d개를 다시 실행합니다.", recovered)
        while not self._stop_flag.is_set():
            try:
                claimed = self.queue.claim_next()
            except Exception:  # noqa: BLE001 - DB가 잠시 끊겨도 작업자는 살아 있어야 한다
                log.exception("작업을 가져오지 못했습니다.")
                claimed = None
            if claimed is None:
                self.queue.wait(self.poll_sec)
                continue
            self.run_one(*claimed)

    def run_one(self, job_id: str, run_id: str, kind: str, params: dict[str, Any]) -> None:
        handler = self.handlers.get(kind)
        if handler is None:
            self.queue.finish(job_id, JOB_FAILED, error=f"알 수 없는 작업입니다: {kind}")
            return

        def progress(message: str) -> None:
            self.queue.set_progress(job_id, message)

        try:
            result = handler(params, run_id, progress)
        except ReapprovalRequiredError as exc:
            # 실패가 아니다. 프론트가 줄인 초안을 다시 불러와 승인받게 한다.
            self.queue.finish(job_id, JOB_NEEDS_REVIEW, error=str(exc))
        except VlogError as exc:
            log.warning("작업 %s 실패: %s", job_id, exc)
            self.queue.finish(job_id, JOB_FAILED, error=str(exc))
        except Exception as exc:  # noqa: BLE001 - 한 작업의 실패로 작업자가 멈추면 안 된다
            log.exception("작업 %s에서 예상하지 못한 오류", job_id)
            self.queue.finish(job_id, JOB_FAILED, error=f"{type(exc).__name__}: {exc}")
        else:
            self.queue.finish(job_id, JOB_DONE, result=result)


def build_handlers(app: App) -> dict[str, Handler]:
    def plan(params: dict[str, Any], run_id: str, progress: Callable[[str], None]) -> dict[str, Any]:
        with session_scope(app.factory) as session:
            existing = Repo(session).latest_version(run_id)
            if existing is not None:  # 저장 직후 서버가 꺼진 경우
                return {"version": existing.version, "resumed": True}
        items = [
            MediaItem(app.settings.resolve(i["path"]), i.get("note"), i.get("audio"))
            for i in params["items"]
        ]
        result = app.plan_pipeline(progress).run_mission(run_id, items, params.get("format"))
        return {"version": result.version, "notes": result.notes}

    def regen(params: dict[str, Any], run_id: str, progress: Callable[[str], None]) -> dict[str, Any]:
        progress("대본과 화면 설계 재생성 중")
        outcome = app.service.regen(
            run_id, cut_ids=params.get("cut_ids"), fields=params.get("fields")
        )
        return {
            "version": outcome.version,
            "changed": len(outcome.changes),
            "skipped_locked": [{"cut_id": c, "field": f} for c, f in outcome.skipped_locked],
        }

    def render(params: dict[str, Any], run_id: str, progress: Callable[[str], None]) -> dict[str, Any]:
        pipeline = app.render_pipeline(
            tts=params.get("tts"), video=params.get("video"), progress=progress
        )
        return _render_result(pipeline.run(run_id))

    def regen_clip(
        params: dict[str, Any], run_id: str, progress: Callable[[str], None]
    ) -> dict[str, Any]:
        pipeline = app.render_pipeline(
            tts=params.get("tts"), video=params.get("video"), progress=progress
        )
        return _render_result(pipeline.regen_clip(run_id, params["cut_id"], params.get("seed")))

    return {
        KIND_PLAN: plan,
        KIND_REGEN: regen,
        KIND_RENDER: render,
        KIND_REGEN_CLIP: regen_clip,
    }


def _render_result(result: Any) -> dict[str, Any]:
    # 서버 경로는 프론트에 보내지 않는다. 완성본은 실행 조회의 URL로 받는다.
    return {
        "version": result.version,
        "reused_video": result.reused_video,
        "images_generated": result.images_generated,
        "tts_generated": result.tts_generated,
        "clips_generated": result.clips_generated,
        "fallback_cuts": result.fallback_cuts,
    }
