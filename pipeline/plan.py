"""`vlog plan`: 전처리 → 사진 분석 → AI 초안(v1) 저장 후 멈춘다.

이 단계는 모두 로컬에서 실행되며 TTS와 영상 생성을 호출하지 않는다.
"""

from __future__ import annotations

import logging
import secrets
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from .analyze import analyze_photo
from .config import Settings
from .db.models import RUN_FAILED, RUN_PLANNING, Photo, Run
from .db.repo import Repo, session_scope
from .detect import SubjectDetector
from .errors import VlogError
from .llm.base import LLMClient
from .preprocess import preprocess_folder
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
    ) -> None:
        self.factory = factory
        self.settings = settings
        self.llm = llm
        self.generator = generator
        self.service = service
        self.detector = detector

    def run(
        self,
        photos_dir: Path | None,
        memo: str | None = None,
        *,
        format_id: str | None = None,
        resume_run_id: str | None = None,
    ) -> PlanResult:
        run_id = resume_run_id or self._start_run(photos_dir, memo, format_id)
        try:
            return self._continue(run_id, format_id)
        except Exception as exc:
            self._mark_failed(run_id, exc)
            raise

    def _work_dir(self, run_id: str) -> Path:
        return self.settings.storage_path / "runs" / run_id

    def _start_run(self, photos_dir: Path | None, memo: str | None, format_id: str | None) -> str:
        if photos_dir is None or not photos_dir.is_dir():
            raise VlogError(f"사진 폴더를 찾을 수 없습니다: {photos_dir}")
        run_id = new_run_id()
        with session_scope(self.factory) as session:
            session.add(
                Run(
                    id=run_id,
                    status=RUN_PLANNING,
                    memo=memo,
                    config={"format_id": format_id, "seeds": {}},
                )
            )
        try:
            prepared = preprocess_folder(
                photos_dir, self._work_dir(run_id), self.settings.preprocess
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
            run.status = RUN_PLANNING
            run.error = None

        self._analyze_pending(run_id)
        photos = self.service.photos(run_id)
        draft = self.generator.generate(
            photos,
            memo,
            format_id=format_id,
            failure_dir=self._work_dir(run_id) / "llm_failures",
        )
        version, report = self.service.save_ai_draft(run_id, draft.storyboard)
        excluded = [(p.photo_id, p.excluded_reason or "") for p in photos if p.excluded_reason]
        return PlanResult(run_id, version, report, draft.notes, excluded)

    def _analyze_pending(self, run_id: str) -> None:
        """분석이 끝난 사진은 건너뛰어, 중단돼도 이어서 실행한다."""
        for record in self.service.photos(run_id):
            if record.excluded_reason or record.analysis is not None:
                continue
            log.info("사진 분석: %s", record.photo_id)
            analysis = analyze_photo(
                self.llm,
                self.settings.llm,
                record.analysis_path or record.path,
                self.detector,
                self._work_dir(run_id) / "llm_failures",
            )
            with session_scope(self.factory) as session:
                row = session.scalars(
                    select(Photo).where(Photo.run_id == run_id, Photo.photo_id == record.photo_id)
                ).one()
                row.analysis = analysis.model_dump(mode="json")

    def _mark_failed(self, run_id: str, exc: Exception) -> None:
        with session_scope(self.factory) as session:
            run = session.get(Run, run_id)
            if run is not None:
                run.status = RUN_FAILED
                run.error = str(exc)[:2000]
