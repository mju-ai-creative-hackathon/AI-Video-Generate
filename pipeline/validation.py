"""스토리보드 검증. 오류는 저장 불가, 경고는 승인 시 확인 후 기록한다."""

from __future__ import annotations

import re
from collections import Counter
from dataclasses import asdict, dataclass, field

from .config import StoryboardSettings
from .format_match import FormatTemplate
from .music import MusicLibrary
from .rag.framing import ASPECT, crop_box_errors, visible_ratio
from .schemas import LOCKABLE_FIELDS, PhotoRecord, Storyboard

HANGUL = re.compile(r"[가-힣]")
FACE_REVEALING = re.compile(
    r"\b(turn(s|ing)?\s+(around|back|(the\s+|her\s+|his\s+|their\s+)?head)|look(s|ing)?\s+(at|back|up|over)"
    r"|glanc\w*|smil\w*|facing the camera)\b",
    re.IGNORECASE,
)


@dataclass
class Issue:
    location: str
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.location}: {self.message}"


@dataclass
class ValidationReport:
    errors: list[Issue] = field(default_factory=list)
    warnings: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors

    def error(self, location: str, code: str, message: str) -> None:
        self.errors.append(Issue(location, code, message))

    def warn(self, location: str, code: str, message: str) -> None:
        self.warnings.append(Issue(location, code, message))

    def format_errors(self) -> str:
        return "\n".join(f"- {issue}" for issue in self.errors)

    def format_warnings(self) -> str:
        return "\n".join(f"- {issue}" for issue in self.warnings)

    def warnings_as_dicts(self) -> list[dict[str, str]]:
        return [asdict(issue) for issue in self.warnings]


def spoken_chars(text: str) -> int:
    return len(re.sub(r"\s+", "", text))


def max_chars(target_sec: float, chars_per_sec: float) -> int:
    return int(target_sec * chars_per_sec)


def validate_storyboard(
    storyboard: Storyboard,
    photos: list[PhotoRecord],
    template: FormatTemplate | None,
    settings: StoryboardSettings,
    music: MusicLibrary | None = None,
) -> ValidationReport:
    report = ValidationReport()
    by_id = {p.photo_id: p for p in photos}
    music_only = storyboard.music_only

    if not storyboard.title.strip():
        report.error("title", "empty_title", "제목이 비어 있습니다.")
    _check_music(report, storyboard, music)
    if not storyboard.cuts:
        report.error("cuts", "no_cuts", "컷이 하나도 없습니다.")
        return report

    ids = Counter(c.cut_id for c in storyboard.cuts)
    positions = Counter(c.position for c in storyboard.cuts)

    for index, cut in enumerate(storyboard.cuts):
        where = f"cuts[{index}]({cut.cut_id})"
        if ids[cut.cut_id] > 1:
            report.error(where, "duplicate_cut_id", f"cut_id가 중복됩니다: {cut.cut_id}")
        if positions[cut.position] > 1:
            report.error(where, "duplicate_position", f"position이 중복됩니다: {cut.position}")
        clip = cut.source_clip
        needs_narration = not music_only and (clip is None or clip.audio != "source")
        if needs_narration and not cut.narration.strip():
            report.error(f"{where}.narration", "empty_narration", "나레이션이 비어 있습니다.")
        if cut.target_sec <= 0:
            report.error(f"{where}.target_sec", "bad_target_sec", "목표 길이는 0보다 커야 합니다.")
        if music_only and cut.narration.strip():
            report.warn(
                f"{where}.narration",
                "narration_unused",
                "음악 전용 템플릿이라 나레이션은 영상에 들어가지 않습니다.",
            )
        if clip is not None:
            pass  # 원본 영상을 쓰므로 영상 프롬프트가 필요 없다
        elif not cut.motion_prompt.strip():
            report.error(f"{where}.motion_prompt", "empty_prompt", "영상 프롬프트가 비어 있습니다.")
        elif HANGUL.search(cut.motion_prompt):
            report.warn(
                f"{where}.motion_prompt", "prompt_not_english", "영상 프롬프트에 한글이 있습니다."
            )
        unknown_locks = sorted(set(cut.locked) - LOCKABLE_FIELDS)
        if unknown_locks:
            report.error(
                f"{where}.locked",
                "unknown_lock",
                f"잠글 수 없는 항목입니다: {unknown_locks}. 가능: {sorted(LOCKABLE_FIELDS)}",
            )

        photo = by_id.get(cut.photo_id)
        if photo is None:
            report.error(f"{where}.photo_id", "unknown_photo", f"없는 사진입니다: {cut.photo_id}")
        elif photo.excluded_reason:
            report.warn(
                f"{where}.photo_id",
                "excluded_photo",
                f"전처리에서 제외된 사진입니다: {cut.photo_id} ({photo.excluded_reason})",
            )
        if photo is not None:
            _check_source_clip(report, where, cut, photo)
        if photo is not None and photo.analysis and photo.analysis.face_hidden:
            if FACE_REVEALING.search(cut.motion_prompt):
                report.warn(
                    f"{where}.motion_prompt",
                    "face_revealing_prompt",
                    "얼굴이 안 보이는 사진인데 프롬프트에 얼굴이 드러날 수 있는 동작이 있습니다.",
                )

        crop_errors = crop_box_errors(cut.shot.crop_box)
        for message in crop_errors:
            report.error(f"{where}.shot.crop_box", "bad_crop", message)
        options = cut.shot.crop_options
        if options and not 1 <= cut.shot.crop_choice <= len(options):
            report.error(
                f"{where}.shot.crop_choice",
                "bad_crop_choice",
                f"crop_choice는 1~{len(options)} 범위여야 합니다: {cut.shot.crop_choice}",
            )
        if photo is not None and not crop_errors:
            _check_crop_quality(report, where, cut.shot.crop_box, cut.shot.shot_size, photo, settings)

        slot = template.slot(cut.slot) if template else None
        # 원본 영상 컷의 길이는 고른 구간이 정하므로 슬롯 범위와 비교하지 않는다.
        if clip is None and slot is not None and not slot.sec.min <= cut.target_sec <= slot.sec.max:
            report.warn(
                f"{where}.target_sec",
                "cut_length_range",
                f"컷 길이 {cut.target_sec}초가 슬롯 범위({slot.sec.min}~{slot.sec.max}초)를 벗어납니다.",
            )
        rate = (slot.narration.chars_per_sec if slot else None) or settings.chars_per_sec
        limit = max_chars(cut.target_sec, rate)
        chars = spoken_chars(cut.narration)
        if needs_narration and cut.target_sec > 0 and chars > limit * settings.chars_tolerance:
            report.warn(
                f"{where}.narration",
                "too_many_chars",
                f"{chars}자로 {cut.target_sec}초의 권장 글자 수({limit}자)를 넘습니다.",
            )
        if cut.fit_score is not None and cut.fit_score < settings.low_slot_fit:
            report.warn(
                f"{where}.photo_id",
                "low_slot_fit",
                f"슬롯 적합도가 낮습니다({cut.fit_score}).",
            )

    if template is not None:
        total = sum(c.target_sec for c in storyboard.cuts)
        if not template.total_sec.min <= total <= template.total_sec.max:
            report.warn(
                "cuts",
                "total_length_range",
                f"전체 길이 {total:.1f}초가 포맷 범위"
                f"({template.total_sec.min}~{template.total_sec.max}초)를 벗어납니다.",
            )
    return report


def music_problem(storyboard: Storyboard, music: MusicLibrary | None) -> tuple[str, str] | None:
    """(코드, 메시지). 승인과 생성은 이 문제가 있으면 멈춘다. 목록을 모르면 파일 확인을 건너뛴다."""
    music_id = storyboard.music_id
    if music_id is None:
        if storyboard.music_only:
            return "missing_music", "음악 전용 템플릿입니다. 사용할 음악(music_id)을 고르세요."
        return None
    if music is None:
        return None
    track = music.get(music_id)
    if track is None:
        choices = ", ".join(t.id for t in music.available()) or "없음(data/music.yaml에 곡 추가 필요)"
        return "unknown_music", f"없는 음악입니다: {music_id}. 가능: {choices}"
    if music.path(music_id) is None:
        return "music_file_missing", f"음악 파일이 없습니다: {track.file}"
    return None


def _check_music(
    report: ValidationReport, storyboard: Storyboard, music: MusicLibrary | None
) -> None:
    # AI 초안 저장을 막지 않도록 경고로 남기고, 승인과 생성에서 music_problem으로 막는다.
    problem = music_problem(storyboard, music)
    if problem is not None:
        report.warn("music_id", *problem)


def _check_source_clip(report: ValidationReport, where: str, cut, photo: PhotoRecord) -> None:  # noqa: ANN001
    location = f"{where}.source_clip"
    clip = cut.source_clip
    if not photo.is_video:
        if clip is not None:
            report.error(location, "source_clip_on_photo", "사진 컷에는 source_clip을 쓸 수 없습니다.")
        return
    if clip is None:
        report.error(location, "missing_source_clip", "영상 컷에는 source_clip이 필요합니다.")
        return
    duration = photo.media_duration or 0.0
    if clip.in_sec < 0 or clip.in_sec >= clip.out_sec or clip.out_sec > duration + 0.05:
        report.error(
            location,
            "bad_source_clip",
            f"구간이 잘못됐습니다: {clip.in_sec}~{clip.out_sec}초 (영상 길이 {duration:.2f}초)",
        )
    if clip.audio == "source" and not photo.has_audio:
        report.error(
            f"{location}.audio",
            "no_source_audio",
            "소리가 없는 영상입니다. audio를 narration으로 바꾸세요.",
        )
    if cut.effect:
        report.error(f"{where}.effect", "effect_on_video", "영상 컷에는 effect를 쓸 수 없습니다.")


def _check_crop_quality(
    report: ValidationReport,
    where: str,
    crop: list[float],
    shot_size: str,
    photo: PhotoRecord,
    settings: StoryboardSettings,
) -> None:
    location = f"{where}.shot.crop_box"
    width_px = (crop[2] - crop[0]) * photo.width
    height_px = (crop[3] - crop[1]) * photo.height
    if abs(width_px / height_px - ASPECT) / ASPECT > 0.03:
        report.warn(location, "crop_aspect", "크롭 비율이 9:16과 다릅니다. 합성 때 가장자리가 잘립니다.")
    if height_px < settings.min_crop_height_px:
        report.warn(location, "low_resolution", f"크롭 높이가 {height_px:.0f}px로 낮습니다.")
    analysis = photo.analysis
    if analysis is None:
        return
    if (
        analysis.subject_box
        and shot_size != "detail"
        and visible_ratio(analysis.subject_box, crop) < 0.85
    ):
        report.warn(location, "subject_clipped", "주 피사체가 크롭에서 잘립니다.")
    if any(visible_ratio(face, crop) < 0.85 for face in analysis.faces):
        report.warn(location, "face_clipped", "얼굴이 크롭에서 잘립니다.")
