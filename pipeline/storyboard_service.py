"""생성 전 수정과 승인. CLI와 이후의 웹 화면은 이 계층만 통해 스토리보드를 바꾼다.

규칙:
- 수정은 덮어쓰지 않고 새 버전으로 저장한다.
- 승인된 버전은 바꿀 수 없다. 승인 후 수정하면 새 초안이 되고 다시 승인해야 한다.
- base_version이 최신이 아니면 가져오기를 거부한다.
- TTS와 영상 생성은 require_approved()를 통과해야 시작할 수 있다.
"""

from __future__ import annotations

import io
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from pydantic import ValidationError
from ruamel.yaml import YAML
from ruamel.yaml.comments import CommentedMap, CommentedSeq
from ruamel.yaml.error import YAMLError
from sqlalchemy.orm import Session, sessionmaker

from .config import Settings
from .db.models import (
    RUN_APPROVED,
    RUN_REVIEW,
    VERSION_APPROVED,
    VERSION_DRAFT,
    EditLog,
    StoryboardVersion,
    utcnow,
)
from .db.repo import Repo, session_scope
from .errors import (
    ApprovalRequiredError,
    StaleBaseVersionError,
    StoryboardValidationError,
    VlogError,
    WarningsNotAcceptedError,
)
from .format_match import FormatTemplate, slot_fit
from .rag.framing import CropPlanner, PhotoGeom
from .schemas import (
    REGEN_FIELDS,
    Cut,
    EditCut,
    EditFile,
    PhotoRecord,
    ShotDesign,
    Storyboard,
    new_cut_id,
)
from .storyboard import MotionPresets, StoryboardGenerator, apply_crop_plan
from .validation import ValidationReport, validate_storyboard

CUT_FIELDS = ("position", "photo_id", "role", "narration", "target_sec", "motion_prompt", "locked")
SHOT_FIELDS = (
    "shot_size",
    "camera_move",
    "subject_action",
    "crop_choice",
    "crop_box",
    "on_screen_text",
    "transition_in",
)


@dataclass
class FieldChange:
    cut_id: str | None
    field: str
    before: Any
    after: Any


@dataclass
class ImportResult:
    version: int
    changed: bool
    changes: list[FieldChange] = field(default_factory=list)
    warnings: ValidationReport = field(default_factory=ValidationReport)


@dataclass
class RegenOutcome:
    version: int
    changes: list[FieldChange]
    skipped_locked: list[tuple[str, str]]
    warnings: ValidationReport


@dataclass
class ApprovedStoryboard:
    run_id: str
    version: int
    storyboard: Storyboard


def diff_storyboards(old: Storyboard, new: Storyboard) -> list[FieldChange]:
    changes: list[FieldChange] = []
    if old.title != new.title:
        changes.append(FieldChange(None, "title", old.title, new.title))
    old_cuts = {c.cut_id: c for c in old.cuts}
    new_cuts = {c.cut_id: c for c in new.cuts}
    for cut_id, cut in old_cuts.items():
        if cut_id not in new_cuts:
            changes.append(FieldChange(cut_id, "cut_removed", cut.narration, None))
    for cut_id, cut in new_cuts.items():
        before = old_cuts.get(cut_id)
        if before is None:
            changes.append(FieldChange(cut_id, "cut_added", None, cut.narration))
            continue
        for name in CUT_FIELDS:
            a, b = getattr(before, name), getattr(cut, name)
            if a != b:
                changes.append(FieldChange(cut_id, name, a, b))
        for name in SHOT_FIELDS:
            a, b = getattr(before.shot, name), getattr(cut.shot, name)
            if a != b:
                changes.append(FieldChange(cut_id, f"shot.{name}", a, b))
    return changes


class StoryboardService:
    def __init__(
        self,
        factory: sessionmaker[Session],
        settings: Settings,
        formats: dict[str, FormatTemplate],
        planner: CropPlanner,
        presets: MotionPresets,
        *,
        generator_factory: Callable[[], StoryboardGenerator] | None = None,
    ) -> None:
        self.factory = factory
        self.settings = settings
        self.formats = formats
        self.planner = planner
        self.presets = presets
        self.generator_factory = generator_factory

    # ── 조회 ──────────────────────────────────────────────────────────────
    def get_storyboard(self, run_id: str, version: int | None = None) -> tuple[int, Storyboard]:
        with session_scope(self.factory) as session:
            repo = Repo(session)
            repo.get_run(run_id)
            row = (
                repo.get_version(run_id, version)
                if version is not None
                else repo.latest_version(run_id)
            )
            if row is None:
                raise VlogError(f"아직 스토리보드가 없습니다: {run_id}")
            return row.version, Storyboard.model_validate(row.content)

    def photos(self, run_id: str) -> list[PhotoRecord]:
        with session_scope(self.factory) as session:
            return Repo(session).photo_records(run_id, self.settings.root)

    def validate(self, run_id: str, version: int | None = None) -> ValidationReport:
        _, storyboard = self.get_storyboard(run_id, version)
        return self._validate(storyboard, self.photos(run_id))

    def _validate(self, storyboard: Storyboard, photos: list[PhotoRecord]) -> ValidationReport:
        report = validate_storyboard(
            storyboard,
            photos,
            self.formats.get(storyboard.format_id),
            self.settings.storyboard,
        )
        for cut in storyboard.cuts:
            if "prompt_stale" in cut.flags:
                report.warn(
                    f"cut({cut.cut_id}).motion_prompt",
                    "prompt_stale",
                    "샷을 바꿨지만 영상 프롬프트는 그대로입니다. 프롬프트를 고치거나 "
                    "`vlog storyboard regen --field motion_prompt`를 실행하세요.",
                )
        return report

    # ── 저장 공통 ─────────────────────────────────────────────────────────
    def _append_version(
        self,
        repo: Repo,
        run_id: str,
        storyboard: Storyboard,
        author: str,
        changes: list[FieldChange],
    ) -> int:
        """새 초안 버전을 만들고 수정 기록을 남긴다. 실행은 검수 상태로 돌아간다."""
        latest = repo.latest_version(run_id)
        version = (latest.version + 1) if latest else 1
        parent = latest.version if latest else None
        repo.session.add(
            StoryboardVersion(
                run_id=run_id,
                version=version,
                parent_version=parent,
                author=author,
                content=storyboard.model_dump(mode="json"),
                status=VERSION_DRAFT,
                format_id=storyboard.format_id,
            )
        )
        if parent is not None:
            repo.add_edit_logs(
                [
                    EditLog(
                        run_id=run_id,
                        from_version=parent,
                        to_version=version,
                        cut_id=change.cut_id,
                        field=change.field,
                        before=change.before,
                        after=change.after,
                    )
                    for change in changes
                ]
            )
        repo.get_run(run_id).status = RUN_REVIEW
        return version

    def save_ai_draft(self, run_id: str, storyboard: Storyboard) -> tuple[int, ValidationReport]:
        report = self._validate(storyboard, self.photos(run_id))
        if not report.ok:
            raise StoryboardValidationError(report)
        with session_scope(self.factory) as session:
            repo = Repo(session)
            latest = repo.latest_version(run_id)
            changes = (
                diff_storyboards(Storyboard.model_validate(latest.content), storyboard)
                if latest
                else []
            )
            version = self._append_version(repo, run_id, storyboard, "ai", changes)
        return version, report

    # ── 내보내기 ──────────────────────────────────────────────────────────
    def export_yaml(self, run_id: str, version: int | None = None) -> tuple[int, str]:
        number, storyboard = self.get_storyboard(run_id, version)
        return number, render_yaml(number, storyboard, self.photos(run_id))

    # ── 가져오기 ──────────────────────────────────────────────────────────
    def import_yaml(self, run_id: str, text: str) -> ImportResult:
        edit = _parse_edit_file(text)
        photos = self.photos(run_id)
        with session_scope(self.factory) as session:
            repo = Repo(session)
            repo.get_run(run_id)
            latest = repo.latest_version(run_id)
            if latest is None:
                raise VlogError(f"아직 스토리보드가 없습니다: {run_id}")
            base = Storyboard.model_validate(latest.content)

            if edit.base_version != latest.version:
                changes: list[FieldChange] = []
                try:
                    older = repo.get_version(run_id, edit.base_version)
                    changes = diff_storyboards(Storyboard.model_validate(older.content), base)
                except VlogError:
                    pass
                raise StaleBaseVersionError(edit.base_version, latest.version, changes)

            report = ValidationReport()
            merged = self._merge(edit, base, photos, report)
            if report.ok:
                report = self._validate(merged, photos)
            if not report.ok:
                raise StoryboardValidationError(report)

            changes = diff_storyboards(base, merged)
            if not changes:
                return ImportResult(latest.version, False, [], report)
            version = self._append_version(repo, run_id, merged, "human", changes)
            return ImportResult(version, True, changes, report)

    def _merge(
        self,
        edit: EditFile,
        base: Storyboard,
        photos: list[PhotoRecord],
        report: ValidationReport,
    ) -> Storyboard:
        by_photo = {p.photo_id: p for p in photos}
        base_cuts = {c.cut_id: c for c in base.cuts}
        seen: set[str] = set()
        existing = set(base_cuts)
        cuts: list[Cut] = []
        for index, item in enumerate(edit.cuts):
            where = f"cuts[{index}]"
            if item.cut_id is not None:
                if item.cut_id in seen:
                    report.error(where, "duplicate_cut_id", f"cut_id가 중복됩니다: {item.cut_id}")
                    continue
                seen.add(item.cut_id)
                if item.cut_id not in base_cuts:
                    report.error(
                        where,
                        "unknown_cut_id",
                        f"없는 cut_id입니다: {item.cut_id}. 새 컷이면 cut_id를 비우세요.",
                    )
                    continue
            photo = by_photo.get(item.photo_id)
            if photo is None:
                report.error(
                    f"{where}.photo_id", "unknown_photo", f"없는 사진입니다: {item.photo_id}"
                )
                continue
            before = base_cuts.get(item.cut_id) if item.cut_id else None
            if before is None:
                cut_id = new_cut_id(existing)
                existing.add(cut_id)
                cuts.append(self._new_cut(cut_id, item, photo))
            else:
                cuts.append(self._merged_cut(before, item, photo, base.format_id))

        cuts.sort(key=lambda c: c.position)
        positions = [c.position for c in cuts]
        duplicates = sorted({p for p in positions if positions.count(p) > 1})
        if duplicates:
            report.error("cuts", "duplicate_position", f"position이 중복됩니다: {duplicates}")
        else:
            for number, cut in enumerate(cuts, start=1):
                cut.position = number
        return base.model_copy(update={"title": edit.title, "cuts": cuts})

    def _new_cut(self, cut_id: str, item: EditCut, photo: PhotoRecord) -> Cut:
        plan = self.planner.plan(PhotoGeom.from_record(photo), item.shot.shot_size)
        cut = Cut(
            cut_id=cut_id,
            position=item.position,
            locked=item.locked,
            photo_id=item.photo_id,
            role=item.role,
            narration=item.narration,
            target_sec=item.target_sec,
            shot=ShotDesign(
                shot_size=item.shot.shot_size,
                camera_move=item.shot.camera_move,
                subject_action=item.shot.subject_action,
                crop_box=plan.options[0],
                on_screen_text=item.shot.on_screen_text,
                transition_in=item.shot.transition_in,
            ),
            motion_prompt=item.motion_prompt or self._default_prompt(item, photo),
            flags=["human_added"],
        )
        apply_crop_plan(cut, plan)
        _apply_crop_edit(cut, item)
        return cut

    def _default_prompt(self, item: EditCut, photo: PhotoRecord) -> str:
        return self.presets.render(
            item.shot.shot_size,
            item.shot.camera_move,
            is_person=bool(photo.analysis and photo.analysis.subject_type == "인물"),
        )

    def _merged_cut(
        self, before: Cut, item: EditCut, photo: PhotoRecord, format_id: str
    ) -> Cut:
        cut = before.model_copy(deep=True)
        photo_changed = item.photo_id != before.photo_id
        shot_changed = (
            item.shot.shot_size != before.shot.shot_size
            or item.shot.camera_move != before.shot.camera_move
        )
        cut.position = item.position
        cut.locked = item.locked
        cut.photo_id = item.photo_id
        cut.role = item.role
        cut.narration = item.narration
        cut.target_sec = item.target_sec
        cut.shot.shot_size = item.shot.shot_size
        cut.shot.camera_move = item.shot.camera_move
        cut.shot.subject_action = item.shot.subject_action
        cut.shot.on_screen_text = item.shot.on_screen_text
        cut.shot.transition_in = item.shot.transition_in

        # 사진이나 샷 크기가 바뀌면 이전 크롭 후보는 의미가 없으므로 다시 계산한다.
        if photo_changed or item.shot.shot_size != before.shot.shot_size:
            apply_crop_plan(
                cut, self.planner.plan(PhotoGeom.from_record(photo), item.shot.shot_size)
            )
        if photo_changed:
            template = self.formats.get(format_id)
            slot = template.slot(cut.slot) if template else None
            cut.fit_score = slot_fit(photo, slot) if slot else None
        _apply_crop_edit(cut, item)

        new_prompt = item.motion_prompt or before.motion_prompt
        prompt_changed = new_prompt != before.motion_prompt
        cut.motion_prompt = new_prompt
        flags = [f for f in cut.flags if f != "prompt_stale"]
        if prompt_changed:
            cut.flags = flags
        elif shot_changed or photo_changed or "prompt_stale" in before.flags:
            cut.flags = flags + ["prompt_stale"]
        else:
            cut.flags = flags
        return cut

    # ── 부분 재생성 ───────────────────────────────────────────────────────
    def regen(
        self,
        run_id: str,
        *,
        cut_ids: list[str] | None = None,
        fields: list[str] | None = None,
    ) -> RegenOutcome:
        if self.generator_factory is None:
            raise VlogError("LLM이 설정되지 않아 재생성할 수 없습니다.")
        names = fields or sorted(REGEN_FIELDS)
        unknown = sorted(set(names) - REGEN_FIELDS)
        if unknown:
            raise VlogError(f"재생성할 수 없는 항목입니다: {unknown}. 가능: {sorted(REGEN_FIELDS)}")

        base_version, base = self.get_storyboard(run_id)
        targets = cut_ids or [c.cut_id for c in base.cuts]
        missing = [c for c in targets if base.cut(c) is None]
        if missing:
            raise VlogError(f"없는 컷입니다: {missing}")
        photos = self.photos(run_id)
        with session_scope(self.factory) as session:
            memo = Repo(session).get_run(run_id).memo

        # LLM 호출은 오래 걸리므로 트랜잭션 밖에서 실행한다.
        result = self.generator_factory().regenerate(
            base,
            photos,
            memo,
            cut_ids=targets,
            fields=names,
            failure_dir=self._failure_dir(run_id),
        )
        report = self._validate(result.storyboard, photos)
        if not report.ok:
            raise StoryboardValidationError(report)
        changes = diff_storyboards(base, result.storyboard)
        if not changes:
            return RegenOutcome(base_version, [], result.skipped_locked, report)
        with session_scope(self.factory) as session:
            repo = Repo(session)
            latest = repo.latest_version(run_id)
            if latest is None or latest.version != base_version:
                raise VlogError("재생성 중 새 버전이 저장되어 결과를 버렸습니다. 다시 실행하세요.")
            version = self._append_version(repo, run_id, result.storyboard, "ai", changes)
        return RegenOutcome(version, changes, result.skipped_locked, report)

    def _failure_dir(self, run_id: str) -> Path:
        return self.settings.storage_path / "runs" / run_id / "llm_failures"

    # ── 비교와 되돌리기 ───────────────────────────────────────────────────
    def diff(self, run_id: str, from_version: int, to_version: int) -> list[FieldChange]:
        _, old = self.get_storyboard(run_id, from_version)
        _, new = self.get_storyboard(run_id, to_version)
        return diff_storyboards(old, new)

    def revert(self, run_id: str, version: int) -> int:
        """이전 버전의 내용을 새 초안으로 복사한다. 기록은 지우지 않는다."""
        _, target = self.get_storyboard(run_id, version)
        _, current = self.get_storyboard(run_id)
        with session_scope(self.factory) as session:
            return self._append_version(
                Repo(session), run_id, target, "human", diff_storyboards(current, target)
            )

    # ── 승인 관문 ─────────────────────────────────────────────────────────
    def approve(
        self, run_id: str, version: int | None = None, *, accept_warnings: bool = False
    ) -> tuple[int, ValidationReport]:
        photos = self.photos(run_id)
        with session_scope(self.factory) as session:
            repo = Repo(session)
            run = repo.get_run(run_id)
            latest = repo.latest_version(run_id)
            if latest is None:
                raise VlogError(f"아직 스토리보드가 없습니다: {run_id}")
            row = repo.get_version(run_id, version) if version is not None else latest
            if row.version != latest.version:
                raise VlogError(
                    f"최신 버전(v{latest.version})만 승인할 수 있습니다. v{row.version}으로 돌아가려면 "
                    f"`vlog storyboard revert {run_id} --version {row.version}`을 실행하세요."
                )
            report = self._validate(Storyboard.model_validate(row.content), photos)
            if row.status == VERSION_APPROVED:
                return row.version, report
            if not report.ok:
                raise StoryboardValidationError(report)
            if report.warnings and not accept_warnings:
                raise WarningsNotAcceptedError(report)
            row.status = VERSION_APPROVED
            row.approved_at = utcnow()
            row.warnings_accepted = report.warnings_as_dicts()
            run.status = RUN_APPROVED
            return row.version, report

    def require_approved(self, run_id: str) -> ApprovedStoryboard:
        """생성 단계의 유일한 입구. 최신 버전이 승인 상태가 아니면 거부한다."""
        with session_scope(self.factory) as session:
            repo = Repo(session)
            repo.get_run(run_id)
            latest = repo.latest_version(run_id)
            if latest is None:
                raise ApprovalRequiredError(f"스토리보드가 없어 생성할 수 없습니다: {run_id}")
            if latest.status != VERSION_APPROVED:
                raise ApprovalRequiredError(
                    f"최신 스토리보드 v{latest.version}이 승인되지 않아 생성을 시작하지 않습니다. "
                    f"검토 후 `vlog storyboard approve {run_id} --version {latest.version}`을 "
                    "실행하세요."
                )
            return ApprovedStoryboard(
                run_id, latest.version, Storyboard.model_validate(latest.content)
            )

    def propose_shortened(self, run_id: str, limits: dict[str, int]) -> int:
        """길이 초과 나레이션을 줄인 새 초안을 만든다. 사람이 다시 승인해야 사용된다."""
        if self.generator_factory is None:
            raise VlogError("LLM이 설정되지 않아 나레이션을 줄일 수 없습니다.")
        _, base = self.get_storyboard(run_id)
        shortened = self.generator_factory().shorten_narrations(
            base, limits, self._failure_dir(run_id)
        )
        with session_scope(self.factory) as session:
            return self._append_version(
                Repo(session), run_id, shortened, "ai", diff_storyboards(base, shortened)
            )


def _apply_crop_edit(cut: Cut, item: EditCut) -> None:
    """crop_box를 직접 적으면 후보보다 우선한다."""
    if item.shot.crop_choice is not None:
        cut.shot.crop_choice = item.shot.crop_choice
        options = cut.shot.crop_options
        if 1 <= item.shot.crop_choice <= len(options):
            cut.shot.crop_box = options[item.shot.crop_choice - 1]
    if item.shot.crop_box is not None:
        cut.shot.crop_box = item.shot.crop_box
        cut.crop_source = "human"
    elif cut.crop_source == "human":
        cut.crop_source = "rag" if cut.framing_refs else "rule"


def _parse_edit_file(text: str) -> EditFile:
    report = ValidationReport()
    try:
        data = YAML(typ="safe").load(text)
    except YAMLError as exc:
        report.error("yaml", "bad_yaml", f"YAML을 읽지 못했습니다: {exc}")
        raise StoryboardValidationError(report) from exc
    try:
        return EditFile.model_validate(data)
    except ValidationError as exc:
        for err in exc.errors():
            location = ".".join(str(part) for part in err["loc"]) or "(전체)"
            report.error(location, err["type"], err["msg"])
        raise StoryboardValidationError(report) from exc


def _flow(values: list[Any]) -> CommentedSeq:
    seq = CommentedSeq(values)
    seq.fa.set_flow_style()
    return seq


def _one_line(text: str) -> str:
    return " ".join(text.split())


def render_yaml(version: int, storyboard: Storyboard, photos: list[PhotoRecord]) -> str:
    """사람이 고칠 storyboard.yaml을 만든다. 주석으로 사진 설명과 표시를 함께 보여준다."""
    by_photo = {p.photo_id: p for p in photos}
    root = CommentedMap()
    root["base_version"] = version
    root["title"] = storyboard.title
    cuts = CommentedSeq()
    for cut in storyboard.ordered_cuts():
        shot = CommentedMap()
        shot["shot_size"] = cut.shot.shot_size
        shot["camera_move"] = cut.shot.camera_move
        shot["subject_action"] = cut.shot.subject_action
        shot["crop_choice"] = cut.shot.crop_choice
        options = cut.shot.crop_options
        chosen = (
            options[cut.shot.crop_choice - 1]
            if 1 <= cut.shot.crop_choice <= len(options)
            else None
        )
        if cut.shot.crop_box != chosen:
            shot["crop_box"] = _flow(cut.shot.crop_box)
        shot["on_screen_text"] = cut.shot.on_screen_text
        shot["transition_in"] = cut.shot.transition_in
        shot.yaml_add_eol_comment(
            f"썸네일 시트의 후보 1~{len(options)} ({cut.crop_source}). crop_box를 직접 적으면 우선", "crop_choice"
        )

        item = CommentedMap()
        item["cut_id"] = cut.cut_id
        item["position"] = cut.position
        item["photo_id"] = cut.photo_id
        item["role"] = cut.role
        item["narration"] = cut.narration
        item["target_sec"] = cut.target_sec
        item["shot"] = shot
        item["motion_prompt"] = cut.motion_prompt
        item["locked"] = _flow(list(cut.locked))

        photo = by_photo.get(cut.photo_id)
        if photo is not None and photo.analysis is not None:
            item.yaml_add_eol_comment(_one_line(photo.analysis.caption), "photo_id")
        marks = list(cut.flags)
        if cut.fit_score is not None:
            marks.append(f"적합도 {cut.fit_score}")
        if marks:
            item.yaml_add_eol_comment(", ".join(marks), "cut_id")
        cuts.append(item)
    root["cuts"] = cuts

    yaml = YAML()
    yaml.allow_unicode = True
    yaml.width = 4096
    yaml.indent(mapping=2, sequence=4, offset=2)
    buffer = io.StringIO()
    yaml.dump(root, buffer)
    return buffer.getvalue()
