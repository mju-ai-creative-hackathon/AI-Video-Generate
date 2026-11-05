"""[3~4] AI 스토리보드 초안 생성과 부분 재생성."""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from pydantic import BaseModel
from ruamel.yaml import YAML

from .config import Settings
from .errors import FormatMatchError
from .format_match import Assignment, FormatTemplate, ShotOption, select_format
from .llm.base import LLMClient, structured_call
from .rag.framing import CropPlanner, PhotoGeom, check_crop, rule_based_crops
from .schemas import (
    Cut,
    PhotoRecord,
    ShotDesign,
    Storyboard,
    default_source_clip,
    new_cut_id,
)
from .validation import max_chars, spoken_chars

SYSTEM = (
    "당신은 숏폼 브이로그 대본 작가입니다. 주어진 사진·영상 설명과 메모, 미션에 있는 사실만 씁니다. "
    "반드시 주어진 JSON 스키마로만 답합니다."
)
TRANSLATE_SYSTEM = (
    "You convert Korean shot notes into short English phrases for a video generation model. "
    "Answer only with JSON that matches the schema."
)


class ScriptCut(BaseModel):
    cut_index: int
    narration: str
    on_screen_text: str | None = None
    subject_action: str


class ScriptOut(BaseModel):
    title: str
    cuts: list[ScriptCut]


class MotionCut(BaseModel):
    cut_index: int
    subject: str
    action: str


class MotionOut(BaseModel):
    cuts: list[MotionCut]


class ShortenCut(BaseModel):
    cut_index: int
    narration: str


class ShortenOut(BaseModel):
    cuts: list[ShortenCut]


FACE_HIDDEN_NOTE = (
    "주의: 사진에서 얼굴이 보이지 않음. subject_action에 돌아보기, 고개 돌리기, 표정처럼 "
    "얼굴이 드러나는 행동을 쓰지 말 것"
)


def _face_hidden(photo: PhotoRecord) -> bool:
    return bool(photo.analysis and photo.analysis.face_hidden)


def _load_yaml(path: Path) -> dict[str, Any]:
    return YAML(typ="safe").load(path.read_text(encoding="utf-8")) or {}


class MotionPresets:
    def __init__(self, data: dict[str, Any]) -> None:
        self.data = data

    @classmethod
    def load(cls, path: Path) -> "MotionPresets":
        return cls(_load_yaml(path))

    def render(
        self,
        shot_size: str,
        camera_move: str,
        *,
        subject: str | None = None,
        action: str | None = None,
        is_person: bool = False,
        face_hidden: bool = False,
    ) -> str:
        prompt = self.data["template"].format(
            shot=self.data["shots"][shot_size],
            subject=subject or self.data["default_subject"],
            action=action or self.data["default_action"],
            move=self.data["moves"][camera_move],
        )
        if face_hidden:
            return prompt + self.data.get("no_face_suffix", "")
        return prompt + (self.data.get("person_suffix", "") if is_person else "")


@dataclass
class GeneratedDraft:
    storyboard: Storyboard
    notes: list[str] = field(default_factory=list)


@dataclass
class RegenResult:
    storyboard: Storyboard
    skipped_locked: list[tuple[str, str]] = field(default_factory=list)


def choose_shot(
    options: list[ShotOption],
    geom: PhotoGeom,
    used_sizes: set[str],
    *,
    min_height_px: int,
) -> tuple[ShotOption, list[str]]:
    """사진에서 가능한 샷만 남긴다. 재사용 사진은 이전과 다른 샷 크기를 고른다."""

    def feasible(size: str) -> bool:
        if size == "detail" and geom.subject_box is None:
            return False
        crops = rule_based_crops(geom, size, limit=1)
        if not crops:
            return False
        check = check_crop(geom, crops[0], shot_size=size, min_height_px=min_height_px)
        return not any(item.startswith("low_resolution") for item in check.fatal)

    possible = [o for o in options if feasible(o.size)]
    fresh = [o for o in possible if o.size not in used_sizes]
    if fresh:
        return fresh[0], []
    # 사진을 다시 쓸 때는 템플릿 후보 밖이라도 다른 크기로 잘라 숏폼의 리듬을 만든다.
    if used_sizes:
        move = options[0].move
        for size in ("wide", "close_up", "medium", "detail"):
            if size not in used_sizes and feasible(size):
                return ShotOption(size=size, move=move), ["shot_outside_format"]
    if possible:
        return possible[0], []
    return ShotOption(size="wide", move=options[0].move), ["shot_fallback"]


class StoryboardGenerator:
    def __init__(
        self,
        llm: LLMClient,
        settings: Settings,
        formats: dict[str, FormatTemplate],
        style_rules: dict[str, Any],
        presets: MotionPresets,
        planner: CropPlanner,
    ) -> None:
        self.llm = llm
        self.settings = settings
        self.formats = formats
        self.style_rules = style_rules
        self.presets = presets
        self.planner = planner

    # ── 전체 초안 ─────────────────────────────────────────────────────────
    def generate(
        self,
        photos: list[PhotoRecord],
        memo: str | None,
        *,
        format_id: str | None = None,
        failure_dir: Path | None = None,
        progress: Callable[[str], None] | None = None,
        keep_order: bool = False,
        one_per_cut: bool = False,
        music_id: str | None = None,
    ) -> GeneratedDraft:
        notify = progress or (lambda _message: None)
        usable = [p for p in photos if not p.excluded_reason and p.analysis is not None]
        if not usable:
            raise FormatMatchError("분석을 마친 사용 가능한 사진이 없습니다.")
        if keep_order or one_per_cut:
            # DB 조회는 문자열 순서라 p100이 p99보다 앞에 온다. 번호 순서로 다시 맞춘다.
            usable = sorted(usable, key=lambda p: int(p.photo_id[1:]))
        cfg = self.settings.storyboard
        match = select_format(
            self.formats,
            usable,
            forced=format_id,
            min_format_fit=cfg.min_format_fit,
            low_slot_fit=cfg.low_slot_fit,
            keep_order=keep_order,
            one_per_cut=one_per_cut,
        )
        notes = list(match.warnings)
        if match.dropped_photos:
            notes.append(f"포맷에 맞추느라 쓰지 않은 사진: {', '.join(match.dropped_photos)}")

        music_only = match.template.music_only
        notify(f"포맷 {match.template.format_id}, {len(match.assignments)}컷 대본 작성 중")
        script = self._write_script(match.assignments, match.template, memo, failure_dir)
        by_index = {c.cut_index: c for c in script.cuts}

        used: dict[str, set[str]] = defaultdict(set)
        shots: list[tuple[ShotOption, list[str]]] = []
        for assignment in match.assignments:
            geom = PhotoGeom.from_record(assignment.photo)
            option, flags = choose_shot(
                assignment.slot.shot_options,
                geom,
                used[assignment.photo.photo_id],
                min_height_px=cfg.min_crop_height_px,
            )
            used[assignment.photo.photo_id].add(option.size)
            shots.append((option, flags))

        # 원본 영상을 쓰는 컷은 AI 영상을 만들지 않으므로 프롬프트가 필요 없다.
        motion_items = [
            (i, a.photo, by_index[i].subject_action)
            for i, a in enumerate(match.assignments, start=1)
            if not a.photo.is_video
        ]
        if motion_items:
            notify("영상 프롬프트 작성 중")
        motions = self._translate_motion(motion_items, failure_dir)

        cuts: list[Cut] = []
        existing: set[str] = set()
        for index, (assignment, (option, shot_flags)) in enumerate(
            zip(match.assignments, shots), start=1
        ):
            item = by_index[index]
            geom = PhotoGeom.from_record(assignment.photo)
            plan = self.planner.plan(geom, option.size)
            cut_id = new_cut_id(existing)
            existing.add(cut_id)
            flags = list(shot_flags) + list(plan.flags)
            if assignment.reused:
                flags.append("reused_photo")
            if assignment.fit < cfg.low_slot_fit:
                flags.append("low_fit")
            text = item.on_screen_text if assignment.slot.text.use != "none" else None
            if assignment.slot.text.use == "required" and not (text or "").strip():
                flags.append("missing_text")
            motion = motions.get(index)
            is_video = assignment.photo.is_video
            clip = (
                default_source_clip(assignment.photo, self.settings.video_input.max_cut_sec)
                if is_video
                else None
            )
            if clip is not None and music_only:
                # 음악 전용 템플릿은 컷 길이가 리듬이므로 원본 영상 구간도 슬롯 길이로 자른다.
                end = min(clip.out_sec, round(clip.in_sec + assignment.target_sec, 3))
                clip = clip.model_copy(update={"out_sec": end})
            cuts.append(
                Cut(
                    cut_id=cut_id,
                    position=index,
                    photo_id=assignment.photo.photo_id,
                    role=assignment.slot.role,
                    # 원본 소리만 쓰는 컷과 음악 전용 템플릿은 나레이션을 합성하지 않는다.
                    narration=""
                    if music_only or (clip and clip.audio == "source")
                    else item.narration.strip(),
                    target_sec=clip.length if clip else assignment.target_sec,
                    source_clip=clip,
                    shot=ShotDesign(
                        shot_size=option.size,
                        camera_move="static" if is_video else option.move,
                        subject_action=item.subject_action.strip(),
                        crop_options=plan.options,
                        crop_choice=1,
                        crop_box=plan.options[0],
                        on_screen_text=(text or "").strip() or None,
                        transition_in="hard_cut",
                    ),
                    framing_refs=plan.refs,
                    motion_prompt=""
                    if is_video
                    else self.presets.render(
                        option.size,
                        option.move,
                        subject=motion.subject if motion else None,
                        action=motion.action if motion else None,
                        is_person=geom.subject_type == "인물",
                        face_hidden=_face_hidden(assignment.photo),
                    ),
                    slot=assignment.slot.slot,
                    fit_score=assignment.fit,
                    crop_source="rag" if plan.source == "rag" else "rule",
                    flags=flags,
                )
            )
        chosen_music = music_id or match.template.audio.music
        if music_only and not chosen_music:
            notes.append("음악 전용 템플릿인데 고른 음악이 없습니다. 승인 전에 music_id를 정하세요.")
        storyboard = Storyboard(
            title=script.title.strip(),
            format_id=match.template.format_id,
            hook_type=match.template.hook_type,
            style_notes=str(self.style_rules.get("tone", "")),
            audio_mode=match.template.audio.mode,
            music_id=chosen_music,
            cuts=cuts,
        )
        return GeneratedDraft(storyboard=storyboard, notes=notes)

    # ── 대본 ──────────────────────────────────────────────────────────────
    def _style_block(self) -> str:
        rules = "\n".join(f"- {r}" for r in self.style_rules.get("rules", []))
        forbidden = "\n".join(f"- {r}" for r in self.style_rules.get("forbidden", []))
        return f"말투: {self.style_rules.get('tone', '')}\n규칙:\n{rules}\n금지:\n{forbidden}"

    def _chars_per_sec(self, rate: float | None) -> float:
        return rate or self.settings.storyboard.chars_per_sec

    def _write_script(
        self,
        assignments: list[Assignment],
        template: FormatTemplate,
        memo: str | None,
        failure_dir: Path | None,
    ) -> ScriptOut:
        lines = []
        limits: dict[int, int] = {}
        music_only = template.music_only
        for index, a in enumerate(assignments, start=1):
            limit = max_chars(a.target_sec, self._chars_per_sec(a.slot.narration.chars_per_sec))
            limits[index] = limit
            analysis = a.photo.analysis
            assert analysis is not None
            text_rule = {"required": "필수", "optional": "선택", "none": "쓰지 않음(null)"}[
                a.slot.text.use
            ]
            kind = "영상 설명" if a.photo.is_video else "사진 설명"
            narration_rule = (
                "나레이션: 쓰지 않음(빈 문자열)"
                if music_only
                else f"목표: {a.slot.narration.goal} | 나레이션 최대 {limit}자(공백 제외)"
            )
            lines.append(
                f"컷 {index} | 역할: {a.slot.role} | {narration_rule} | "
                f"길이: {a.target_sec}초 | 화면 문구: {text_rule}\n"
                f"  {kind}: {analysis.caption}\n"
                f"  분위기: {analysis.mood}\n"
                f"  움직임 아이디어: {', '.join(analysis.motion_ideas) or '없음'}"
            )
            if a.photo.note:
                lines[-1] += f"\n  제출자 메모: {a.photo.note}"
            if analysis.face_hidden:
                lines[-1] += f"\n  {FACE_HIDDEN_NOTE}"
        task = (
            "음악만 쓰는 영상입니다. 나레이션은 모두 빈 문자열로 두고, 화면 문구와 "
            "피사체 행동(subject_action), 영상 전체의 제목을 쓰세요."
            if music_only
            else "아래 컷마다 나레이션, 화면 문구, 피사체 행동(subject_action)을 쓰고 "
            "영상 전체의 제목도 쓰세요."
        )
        prompt = (
            f"포맷: {template.name or template.format_id} (훅 유형: {template.hook_type})\n"
            f"메모: {memo or '없음'}\n\n{self._style_block()}\n\n"
            f"{task} cut_index는 컷 번호와 같아야 합니다.\n\n" + "\n".join(lines)
        )
        expected = set(limits)

        def check(result: ScriptOut) -> None:
            indexes = {c.cut_index for c in result.cuts}
            if indexes != expected or len(result.cuts) != len(expected):
                raise ValueError(f"cut_index는 {sorted(expected)}이어야 합니다: {sorted(indexes)}")
            empty = [c.cut_index for c in result.cuts if not c.narration.strip()]
            if empty and not music_only:
                raise ValueError(f"나레이션이 비어 있는 컷: {empty}")

        llm_cfg = self.settings.llm
        call = {
            "model": llm_cfg.text_model,
            "system": SYSTEM,
            "max_retries": llm_cfg.max_retries,
            "check": check,
            "failure_dir": failure_dir,
            "label": "script",
        }
        result = structured_call(self.llm, ScriptOut, prompt=prompt, **call)

        if music_only:
            return result  # 나레이션을 쓰지 않으므로 글자 수를 다시 요청하지 않는다
        # 글자 수 초과는 다시 요청하되, 끝내 넘으면 경고로 남겨 사람이 판단하게 한다.
        tolerance = self.settings.storyboard.chars_tolerance
        for _ in range(self.settings.storyboard.max_script_retries):
            over = {
                c.cut_index: limits[c.cut_index]
                for c in result.cuts
                if spoken_chars(c.narration) > limits[c.cut_index] * tolerance
            }
            if not over:
                break
            feedback = ", ".join(f"컷 {i}은 {n}자 이내" for i, n in sorted(over.items()))
            retry = f"{prompt}\n\n이전 답변이 너무 깁니다. {feedback}로 줄여 전체를 다시 쓰세요."
            result = structured_call(self.llm, ScriptOut, prompt=retry, **call)
        return result

    def _translate_motion(
        self,
        items: list[tuple[int, PhotoRecord, str]],
        failure_dir: Path | None,
    ) -> dict[int, MotionCut]:
        if not items:
            return {}
        lines = "\n".join(
            f"{index}. photo: {photo.analysis.caption if photo.analysis else ''} / action: {action}"
            + (
                " / the face is hidden: never describe turning around, looking back or showing the face"
                if _face_hidden(photo)
                else ""
            )
            for index, photo, action in items
        )
        prompt = (
            "For each numbered item write `subject` (a short English noun phrase for the main "
            "subject, no leading article needed) and `action` (a short English phrase for its "
            "gentle movement). cut_index must equal the item number.\n\n" + lines
        )
        expected = {index for index, _, _ in items}

        def check(result: MotionOut) -> None:
            if {c.cut_index for c in result.cuts} != expected:
                raise ValueError(f"cut_index must be exactly {sorted(expected)}")

        result = structured_call(
            self.llm,
            MotionOut,
            model=self.settings.llm.text_model,
            system=TRANSLATE_SYSTEM,
            prompt=prompt,
            max_retries=self.settings.llm.max_retries,
            check=check,
            failure_dir=failure_dir,
            label="motion",
        )
        return {c.cut_index: c for c in result.cuts}

    # ── 부분 재생성 ───────────────────────────────────────────────────────
    def regenerate(
        self,
        storyboard: Storyboard,
        photos: list[PhotoRecord],
        memo: str | None,
        *,
        cut_ids: list[str],
        fields: list[str],
        failure_dir: Path | None = None,
    ) -> RegenResult:
        """지정한 컷과 항목만 다시 만든다. 사람이 잠근 항목은 건드리지 않는다."""
        result = storyboard.model_copy(deep=True)
        by_photo = {p.photo_id: p for p in photos}
        template = self.formats.get(storyboard.format_id)
        ordered = result.ordered_cuts()
        skipped: list[tuple[str, str]] = []

        # (컷, 다시 만들 항목들)
        targets: list[tuple[Cut, set[str]]] = []
        for cut in ordered:
            if cut.cut_id not in cut_ids:
                continue
            allowed = set()
            for name in fields:
                if name in cut.locked:
                    skipped.append((cut.cut_id, name))
                elif not _applies_to(cut, name) or (name == "narration" and result.music_only):
                    continue
                else:
                    allowed.add(name)
            if allowed:
                targets.append((cut, allowed))

        text_targets = [
            (cut, names)
            for cut, names in targets
            if names & {"narration", "on_screen_text", "subject_action"}
        ]
        if text_targets:
            self._regen_text(text_targets, ordered, by_photo, template, memo, failure_dir)

        motion_targets = [(cut, names) for cut, names in targets if "motion_prompt" in names]
        if motion_targets:
            motions = self._translate_motion(
                [
                    (i, by_photo[cut.photo_id], cut.shot.subject_action)
                    for i, (cut, _) in enumerate(motion_targets, start=1)
                ],
                failure_dir,
            )
            for i, (cut, _) in enumerate(motion_targets, start=1):
                photo = by_photo[cut.photo_id]
                cut.motion_prompt = self.presets.render(
                    cut.shot.shot_size,
                    cut.shot.camera_move,
                    subject=motions[i].subject,
                    action=motions[i].action,
                    is_person=bool(photo.analysis and photo.analysis.subject_type == "인물"),
                    face_hidden=_face_hidden(photo),
                )

        for cut, names in targets:
            if "crop_box" in names:
                self.replan_crop(cut, by_photo[cut.photo_id])
        return RegenResult(storyboard=result, skipped_locked=skipped)

    def replan_crop(self, cut: Cut, photo: PhotoRecord) -> None:
        plan = self.planner.plan(PhotoGeom.from_record(photo), cut.shot.shot_size)
        apply_crop_plan(cut, plan)

    def _regen_text(
        self,
        targets: list[tuple[Cut, set[str]]],
        ordered: list[Cut],
        by_photo: dict[str, PhotoRecord],
        template: FormatTemplate | None,
        memo: str | None,
        failure_dir: Path | None,
    ) -> None:
        lines = []
        for index, (cut, names) in enumerate(targets, start=1):
            photo = by_photo[cut.photo_id]
            slot = template.slot(cut.slot) if template else None
            rate = self._chars_per_sec(slot.narration.chars_per_sec if slot else None)
            neighbor = {c.position: c for c in ordered}
            previous = neighbor.get(cut.position - 1)
            following = neighbor.get(cut.position + 1)
            lines.append(
                f"항목 {index} | 역할: {cut.role} | 목표: {slot.narration.goal if slot else '자연스러운 연결'} | "
                f"나레이션 최대 {max_chars(cut.target_sec, rate)}자\n"
                f"  다시 쓸 항목: {', '.join(sorted(names))}\n"
                f"  {'영상' if photo.is_video else '사진'} 설명: "
                f"{photo.analysis.caption if photo.analysis else ''}\n"
                f"  앞 컷 나레이션: {previous.narration if previous else '없음'}\n"
                f"  현재 나레이션: {cut.narration}\n"
                f"  뒤 컷 나레이션: {following.narration if following else '없음'}"
            )
            if photo.note:
                lines[-1] += f"\n  제출자 메모: {photo.note}"
            if _face_hidden(photo):
                lines[-1] += f"\n  {FACE_HIDDEN_NOTE}"
        prompt = (
            f"메모: {memo or '없음'}\n\n{self._style_block()}\n\n"
            "아래 항목을 새로 쓰세요. 앞뒤 컷과 자연스럽게 이어져야 합니다. "
            "title은 빈 문자열로 두고, cut_index는 항목 번호와 같아야 합니다. "
            "다시 쓸 항목이 아닌 값은 현재 값을 그대로 적으세요.\n\n" + "\n".join(lines)
        )
        expected = set(range(1, len(targets) + 1))

        def check(result: ScriptOut) -> None:
            if {c.cut_index for c in result.cuts} != expected:
                raise ValueError(f"cut_index는 {sorted(expected)}이어야 합니다.")

        result = structured_call(
            self.llm,
            ScriptOut,
            model=self.settings.llm.text_model,
            system=SYSTEM,
            prompt=prompt,
            max_retries=self.settings.llm.max_retries,
            check=check,
            failure_dir=failure_dir,
            label="regen",
        )
        by_index = {c.cut_index: c for c in result.cuts}
        for index, (cut, names) in enumerate(targets, start=1):
            item = by_index[index]
            if "narration" in names and item.narration.strip():
                cut.narration = item.narration.strip()
            if "on_screen_text" in names:
                cut.shot.on_screen_text = (item.on_screen_text or "").strip() or None
            if "subject_action" in names and item.subject_action.strip():
                cut.shot.subject_action = item.subject_action.strip()

    # ── 길이 초과 나레이션 줄이기 ─────────────────────────────────────────
    def shorten_narrations(
        self,
        storyboard: Storyboard,
        limits: dict[str, int],
        failure_dir: Path | None = None,
    ) -> Storyboard:
        """limits의 컷 나레이션을 줄인 사본을 돌려준다. 잠긴 컷은 호출 쪽에서 제외한다."""
        result = storyboard.model_copy(deep=True)
        cuts = [c for c in result.ordered_cuts() if c.cut_id in limits]
        lines = "\n".join(
            f"항목 {i}: 최대 {limits[c.cut_id]}자 | {c.narration}" for i, c in enumerate(cuts, start=1)
        )
        prompt = (
            f"{self._style_block()}\n\n아래 나레이션을 뜻을 유지하며 글자 수(공백 제외) 안으로 줄이세요. "
            f"cut_index는 항목 번호와 같아야 합니다.\n\n{lines}"
        )
        expected = set(range(1, len(cuts) + 1))

        def check(out: ShortenOut) -> None:
            if {c.cut_index for c in out.cuts} != expected:
                raise ValueError(f"cut_index는 {sorted(expected)}이어야 합니다.")

        out = structured_call(
            self.llm,
            ShortenOut,
            model=self.settings.llm.text_model,
            system=SYSTEM,
            prompt=prompt,
            max_retries=self.settings.llm.max_retries,
            check=check,
            failure_dir=failure_dir,
            label="shorten",
        )
        by_index = {c.cut_index: c for c in out.cuts}
        for i, cut in enumerate(cuts, start=1):
            if by_index[i].narration.strip():
                cut.narration = by_index[i].narration.strip()
        return result


def _applies_to(cut: Cut, field_name: str) -> bool:
    """원본 영상 컷에는 영상 프롬프트가 없고, 원본 소리만 쓰면 나레이션도 없다."""
    if cut.source_clip is None:
        return True
    if field_name == "motion_prompt":
        return False
    return not (field_name == "narration" and cut.source_clip.audio == "source")


def apply_crop_plan(cut: Cut, plan: Any) -> None:
    cut.shot.crop_options = plan.options
    cut.shot.crop_choice = 1
    cut.shot.crop_box = plan.options[0]
    cut.framing_refs = plan.refs
    cut.crop_source = "rag" if plan.source == "rag" else "rule"
    keep = [f for f in cut.flags if f not in {"rule_crop", "crop_check_failed"}]
    cut.flags = keep + list(plan.flags)
