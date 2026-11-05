"""`vlog plan`: 전처리 → 사진 분석 → AI 초안(v1) 저장 후 멈춘다.

이 단계는 모두 로컬에서 실행되며 TTS와 영상 생성을 호출하지 않는다.
"""

from __future__ import annotations

import logging
import secrets
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from .analyze import analyze_photo, analyze_video
from .config import Settings
from .db.models import RUN_FAILED, RUN_PLANNING, Photo, Run
from .db.repo import Repo, session_scope
from .detect import SubjectDetector
from .errors import VlogError
from .llm.base import LLMClient
from .preprocess import MediaItem, numbered_photos, prepare_media, preprocess_folder
from .store import relative_to_root
from .storyboard import StoryboardGenerator
from .storyboard_service import StoryboardService
from .validation import ValidationReport

log = logging.getLogger(__name__)


@dataclass
class PlanResult:
    run_id: str
    version: int
    report: ValidationReport
    notes: list[str] = field(default_factory=list)
    excluded: list[tuple[str, str]] = field(default_factory=list)


def new_run_id() -> str:
    return f"r{datetime.now():%Y%m%d}_{secrets.token_hex(3)}"


class PlanPipeline:
    def __init__(
        self,
        factory: sessionmaker[Session],
        settings: Settings,
        llm: LLMClient,
        generator: StoryboardGenerator,
        service: StoryboardService,
        detector: SubjectDetector | None = None,
        progress: Callable[[str], None] | None = None,
    ) -> None:
        self.factory = factory
        self.settings = settings
        self.llm = llm
        self.generator = generator
        self.service = service
        self.detector = detector
        self.progress = progress or (lambda _message: None)

    def run(
        self,
        photos_dir: Path | None,
        memo: str | None = None,
        *,
        format_id: str | None = None,
        resume_run_id: str | None = None,
        order: str = "time",
    ) -> PlanResult:
        run_id = resume_run_id or self._start_run(photos_dir, memo, format_id, order)
        try:
            return self._continue(run_id, format_id)
        except Exception as exc:
            self._mark_failed(run_id, exc)
            raise

    def run_mission(
        self, run_id: str, items: list[MediaItem], format_id: str | None = None
    ) -> PlanResult:
        """API로 제출된 사진·영상을 순서 그대로 처리한다. 실행 기록은 API가 먼저 만든다."""
        try:
            self._prepare_mission_media(run_id, items)
            return self._continue(run_id, format_id)
        except Exception as exc:
            self._mark_failed(run_id, exc)
            raise

    def _prepare_mission_media(self, run_id: str, items: list[MediaItem]) -> None:
        with session_scope(self.factory) as session:
            if Repo(session).photos(run_id):
                return  # 재시작으로 이어서 실행하는 경우
        self.progress(f"run_id: {run_id}, 자료 전처리 중")
        prepared = prepare_media(items, self._work_dir(run_id), self.settings.preprocess)
        root = self.settings.root
        with session_scope(self.factory) as session:
            session.add_all(
                Photo(
                    run_id=run_id,
                    photo_id=p.photo_id,
                    source_path=str(p.source_path),
                    path=relative_to_root(p.path, root),
                    analysis_path=relative_to_root(p.analysis_path, root),
                    sha256=p.sha256,
                    width=p.width,
                    height=p.height,
                    taken_at=p.taken_at,
                    excluded_reason=p.excluded_reason,
                    media_type=p.media_type,
                    video_path=relative_to_root(p.video_path, root) if p.video_path else None,
                    media_duration=p.media_duration,
                    has_audio=p.has_audio,
                    note=p.note,
                    audio_mode=p.audio_mode,
                )
                for p in prepared
            )

    def _work_dir(self, run_id: str) -> Path:
        return self.settings.storage_path / "runs" / run_id

    def _start_run(
        self, photos_dir: Path | None, memo: str | None, format_id: str | None, order: str
    ) -> str:
        if photos_dir is None or not photos_dir.is_dir():
            raise VlogError(f"사진 폴더를 찾을 수 없습니다: {photos_dir}")
        if order == "number":
            numbered_photos(photos_dir)  # 실행 기록을 만들기 전에 파일 이름부터 확인한다
        run_id = new_run_id()
        self.progress(f"run_id: {run_id}, 사진 전처리 중")
        with session_scope(self.factory) as session:
            session.add(
                Run(
                    id=run_id,
                    status=RUN_PLANNING,
                    memo=memo,
                    config={"format_id": format_id, "order": order, "seeds": {}},
                )
            )
        try:
            prepared = preprocess_folder(
                photos_dir, self._work_dir(run_id), self.settings.preprocess, order=order
            )
            root = self.settings.root
            with session_scope(self.factory) as session:
                session.add_all(
                    Photo(
                        run_id=run_id,
                        photo_id=p.photo_id,
                        source_path=str(p.source_path),
                        path=relative_to_root(p.path, root),
                        analysis_path=relative_to_root(p.analysis_path, root),
                        sha256=p.sha256,
                        width=p.width,
                        height=p.height,
                        taken_at=p.taken_at,
                        excluded_reason=p.excluded_reason,
                    )
                    for p in prepared
                )
        except Exception as exc:
            self._mark_failed(run_id, exc)
            raise
        return run_id

    def _continue(self, run_id: str, format_id: str | None) -> PlanResult:
        with session_scope(self.factory) as session:
            repo = Repo(session)
            run = repo.get_run(run_id)
            if repo.latest_version(run_id) is not None:
                raise VlogError(f"이미 스토리보드가 있습니다: {run_id}")
            memo = run.memo
            format_id = format_id or (run.config or {}).get("format_id")
            keep_order = (run.config or {}).get("order") == "number"
            one_per_cut = (run.config or {}).get("mode") == "mission"
            music_id = (run.config or {}).get("music_id")
            run.status = RUN_PLANNING
            run.error = None

        self._analyze_pending(run_id, memo)
        photos = self.service.photos(run_id)
        draft = self.generator.generate(
            photos,
            memo,
            format_id=format_id,
            failure_dir=self._work_dir(run_id) / "llm_failures",
            progress=self.progress,
            keep_order=keep_order,
            one_per_cut=one_per_cut,
            music_id=music_id,
        )
        version, report = self.service.save_ai_draft(run_id, draft.storyboard)
        excluded = [(p.photo_id, p.excluded_reason or "") for p in photos if p.excluded_reason]
        return PlanResult(run_id, version, report, draft.notes, excluded)

    def _analyze_pending(self, run_id: str, memo: str | None = None) -> None:
        """분석이 끝난 사진은 건너뛰어, 중단돼도 이어서 실행한다."""
        records = self.service.photos(run_id)
        usable = [r for r in records if not r.excluded_reason]
        failure_dir = self._work_dir(run_id) / "llm_failures"
        with self.llm.hold_models():
            for number, record in enumerate(usable, start=1):
                if record.analysis is not None:
                    continue
                if record.is_video:
                    self.progress(f"영상 분석 {number}/{len(usable)}: {record.photo_id}")
                    analysis = analyze_video(
                        self.llm,
                        self.settings.llm,
                        self.settings.video_input,
                        self.settings.preprocess,
                        record,
                        memo,
                        self.settings.timing.min_cut_sec,
                        self._work_dir(run_id),
                        self.detector,
                        failure_dir,
                    )
                else:
                    self.progress(f"사진 분석 {number}/{len(usable)}: {record.photo_id}")
                    analysis = analyze_photo(
                        self.llm,
                        self.settings.llm,
                        record.analysis_path or record.path,
                        self.detector,
                        failure_dir,
                    )
                with session_scope(self.factory) as session:
                    row = session.scalars(
                        select(Photo).where(
                            Photo.run_id == run_id, Photo.photo_id == record.photo_id
                        )
                    ).one()
                    row.analysis = analysis.model_dump(mode="json")

    def _mark_failed(self, run_id: str, exc: Exception) -> None:
        with session_scope(self.factory) as session:
            run = session.get(Run, run_id)
            if run is not None:
                run.status = RUN_FAILED
                run.error = str(exc)[:2000]
