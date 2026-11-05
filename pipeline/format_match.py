"""[3] 포맷 템플릿 로드, 적합도 계산, 사진 배정."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from ruamel.yaml import YAML

from .errors import ConfigError, FormatMatchError
from .schemas import CameraMove, PhotoRecord, Role, ShotSize, SubjectType

REUSE_PENALTY = 0.6


class SecRange(BaseModel):
    min: float
    max: float


class RepeatRange(BaseModel):
    min: int = 1
    max: int = 1


class PhotoNeed(BaseModel):
    prefer: list[SubjectType] = Field(default_factory=list)
    moods: list[str] = Field(default_factory=list)


class ShotOption(BaseModel):
    size: ShotSize
    move: CameraMove


class NarrationGoal(BaseModel):
    goal: str = ""  # 음악 전용 템플릿은 비워도 된다
    chars_per_sec: float | None = None


class AudioRule(BaseModel):
    """템플릿의 소리 구성. music이면 나레이션·TTS 없이 음악만 넣고 컷 길이는 템플릿을 따른다."""

    model_config = ConfigDict(extra="forbid")

    mode: Literal["narration", "music"] = "narration"
    music: str | None = None  # 기본 음악 id(data/music.yaml). 제작 요청에서 바꿀 수 있다


class TextRule(BaseModel):
    use: Literal["required", "optional", "none"] = "optional"
    position: Literal["top", "bottom"] = "top"


class Slot(BaseModel):
    model_config = ConfigDict(extra="forbid")

    slot: str
    role: Role
    required: bool = True
    repeat: RepeatRange = Field(default_factory=RepeatRange)
    sec: SecRange
    photo_need: PhotoNeed = Field(default_factory=PhotoNeed)
    shot_options: list[ShotOption]
    narration: NarrationGoal = Field(default_factory=NarrationGoal)
    text: TextRule = Field(default_factory=TextRule)


class FormatTemplate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    format_id: str
    name: str = ""
    hook_type: str
    total_sec: SecRange
    audio: AudioRule = Field(default_factory=AudioRule)
    slots: list[Slot]

    def slot(self, name: str | None) -> Slot | None:
        return next((s for s in self.slots if s.slot == name), None)

    @property
    def music_only(self) -> bool:
        return self.audio.mode == "music"


def load_formats(directory: Path) -> dict[str, FormatTemplate]:
    if not directory.is_dir():
        raise ConfigError(f"포맷 폴더를 찾을 수 없습니다: {directory}")
    formats: dict[str, FormatTemplate] = {}
    for path in sorted(directory.glob("*.yaml")):
        data = YAML(typ="safe").load(path.read_text(encoding="utf-8"))
        template = FormatTemplate.model_validate(data)
        if template.format_id in formats:
            raise ConfigError(f"format_id가 중복됩니다: {template.format_id}")
        formats[template.format_id] = template
    if not formats:
        raise ConfigError(f"포맷 템플릿이 없습니다: {directory}")
    return formats


def slot_fit(photo: PhotoRecord, slot: Slot) -> float:
    """사진이 슬롯에 얼마나 맞는지 0~1로 계산한다."""
    analysis = photo.analysis
    if analysis is None:
        return 0.0
    prefer = slot.photo_need.prefer
    if not prefer:
        type_score = 0.7
    elif analysis.subject_type in prefer:
        type_score = 1.0 - 0.15 * prefer.index(analysis.subject_type)
    else:
        type_score = 0.0
    moods = slot.photo_need.moods
    if not moods:
        mood_score = 0.5
    else:
        mood_score = 1.0 if any(m in analysis.mood for m in moods) else 0.3
    return round(0.7 * type_score + 0.3 * mood_score, 3)


@dataclass
class Assignment:
    slot: Slot
    photo: PhotoRecord
    fit: float
    target_sec: float
    reused: bool = False


@dataclass
class FormatMatch:
    template: FormatTemplate
    assignments: list[Assignment]
    mean_fit: float
    dropped_photos: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def reuse_ratio(self) -> float:
        return sum(a.reused for a in self.assignments) / max(1, len(self.assignments))


def expand_slots(
    template: FormatTemplate, n_photos: int, *, keep_order: bool = False
) -> list[Slot]:
    """사진 수와 전체 길이 범위에 맞춰 반복 슬롯의 개수를 정한다.

    keep_order면 사진을 빼지 않도록 포맷의 범위를 넘어서도 컷을 늘린다.
    """
    counts = [s.repeat.min for s in template.slots]

    def total(attr: str) -> float:
        return sum(c * getattr(s.sec, attr) for c, s in zip(counts, template.slots))

    while True:
        needs_cuts = sum(counts) < n_photos
        needs_time = total("max") < template.total_sec.min
        if not (needs_cuts or needs_time):
            break
        candidates = [
            i
            for i, s in enumerate(template.slots)
            if counts[i] < s.repeat.max and total("min") + s.sec.min <= template.total_sec.max
        ]
        if not candidates:
            break
        # 가장 덜 채워진 반복 슬롯부터 늘린다.
        index = min(candidates, key=lambda i: counts[i] / template.slots[i].repeat.max)
        counts[index] += 1
    if keep_order and sum(counts) < n_photos:
        body = [i for i, s in enumerate(template.slots) if s.role == "body"]
        growable = body or list(range(len(template.slots)))
        while sum(counts) < n_photos:
            counts[min(growable, key=lambda i: counts[i])] += 1
    return [s for c, s in zip(counts, template.slots) for _ in range(c)]


def allocate_seconds(slots: list[Slot], total_sec: SecRange) -> list[float]:
    """모든 컷의 길이를 슬롯 범위 안에서 같은 비율로 조정해 전체 범위에 맞춘다."""
    low = sum(s.sec.min for s in slots)
    high = sum(s.sec.max for s in slots)
    ratio = 0.5
    if high > low:
        mid = low + (high - low) * ratio
        if mid < total_sec.min:
            ratio = min(1.0, (total_sec.min - low) / (high - low))
        elif mid > total_sec.max:
            ratio = max(0.0, (total_sec.max - low) / (high - low))
    return [round(s.sec.min + (s.sec.max - s.sec.min) * ratio, 2) for s in slots]


def assign_photos(slots: list[Slot], photos: list[PhotoRecord]) -> list[int]:
    """슬롯별 사진 번호를 고른다. 시간순을 지키고, 재사용은 사진이 모자랄 때만 일어난다."""
    m, n = len(slots), len(photos)
    fits = [[slot_fit(p, s) for p in photos] for s in slots]
    best = [[float("-inf")] * n for _ in range(m)]
    came_from = [[0] * n for _ in range(m)]
    for j in range(n):
        best[0][j] = fits[0][j]
    for i in range(1, m):
        running_max, running_arg = float("-inf"), 0
        for j in range(n):
            reuse = best[i - 1][j] - REUSE_PENALTY
            value, source = (
                (running_max, running_arg) if running_max >= reuse else (reuse, j)
            )
            best[i][j] = fits[i][j] + value
            came_from[i][j] = source
            if best[i - 1][j] > running_max:
                running_max, running_arg = best[i - 1][j], j
    j = max(range(n), key=lambda k: best[m - 1][k])
    chosen = [0] * m
    for i in range(m - 1, -1, -1):
        chosen[i] = j
        j = came_from[i][j]
    return chosen


def assign_photos_in_order(slots: list[Slot], photos: list[PhotoRecord]) -> list[int]:
    """사진을 주어진 순서대로 빠짐없이 쓴다. 컷이 더 많으면 적합도가 높은 사진을 이어서 다시 쓴다."""
    m, n = len(slots), len(photos)
    fits = [[slot_fit(p, s) for p in photos] for s in slots]
    best = [[float("-inf")] * n for _ in range(m)]
    came_from = [[0] * n for _ in range(m)]
    best[0][0] = fits[0][0]
    for i in range(1, m):
        for j in range(n):
            stay = best[i - 1][j]
            advance = best[i - 1][j - 1] if j > 0 else float("-inf")
            source = j if stay >= advance else j - 1
            if max(stay, advance) > float("-inf"):
                best[i][j] = fits[i][j] + max(stay, advance)
                came_from[i][j] = source
    chosen = [0] * m
    j = n - 1
    for i in range(m - 1, -1, -1):
        chosen[i] = j
        j = came_from[i][j]
    return chosen


def match_format(
    template: FormatTemplate,
    photos: list[PhotoRecord],
    *,
    low_slot_fit: float = 0.35,
    keep_order: bool = False,
) -> FormatMatch:
    if not photos:
        raise FormatMatchError("사용할 수 있는 사진이 없습니다.")
    slots = expand_slots(template, len(photos), keep_order=keep_order)
    if not slots:
        raise FormatMatchError(f"포맷에 슬롯이 없습니다: {template.format_id}")
    seconds = allocate_seconds(slots, template.total_sec)
    chosen = (
        assign_photos_in_order(slots, photos) if keep_order else assign_photos(slots, photos)
    )

    assignments: list[Assignment] = []
    warnings: list[str] = []
    seen: set[int] = set()
    for index, (slot, photo_index, sec) in enumerate(zip(slots, chosen, seconds), start=1):
        photo = photos[photo_index]
        fit = slot_fit(photo, slot)
        assignments.append(
            Assignment(
                slot=slot, photo=photo, fit=fit, target_sec=sec, reused=photo_index in seen
            )
        )
        seen.add(photo_index)
        if slot.required and fit < low_slot_fit:
            warnings.append(
                f"{index}번 컷({slot.slot})에 맞는 사진이 없어 {photo.photo_id}를 대신 배정했습니다."
            )
    dropped = [p.photo_id for i, p in enumerate(photos) if i not in seen]
    mean_fit = round(sum(a.fit for a in assignments) / len(assignments), 3)
    return FormatMatch(template, assignments, mean_fit, dropped, warnings)


def slots_one_per_cut(template: FormatTemplate, count: int) -> list[Slot]:
    """컷 수만큼 슬롯을 대응시킨다. 첫 컷은 hook, 마지막 컷은 ending, 나머지는 body를 고르게 쓴다."""
    if not template.slots:
        raise FormatMatchError(f"포맷에 슬롯이 없습니다: {template.format_id}")
    hook = next((s for s in template.slots if s.role == "hook"), template.slots[0])
    ending = next((s for s in reversed(template.slots) if s.role == "ending"), template.slots[-1])
    if count == 1:
        return [hook]
    middle = count - 2
    body = [s for s in template.slots if s.role == "body"] or [hook]
    picked = [body[i * len(body) // middle] for i in range(middle)] if middle > 0 else []
    return [hook, *picked, ending]


def match_one_per_cut(
    template: FormatTemplate,
    photos: list[PhotoRecord],
    *,
    low_slot_fit: float = 0.35,
) -> FormatMatch:
    """제출된 자료를 순서 그대로 한 컷씩 쓴다. 템플릿은 역할, 말투, 길이, 샷 후보만 제공한다."""
    if not photos:
        raise FormatMatchError("사용할 수 있는 자료가 없습니다.")
    slots = slots_one_per_cut(template, len(photos))
    seconds = allocate_seconds(slots, template.total_sec)
    assignments: list[Assignment] = []
    warnings: list[str] = []
    for index, (slot, photo, sec) in enumerate(zip(slots, photos, seconds), start=1):
        fit = slot_fit(photo, slot)
        # 음악 전용 템플릿은 컷 길이가 리듬이므로 영상도 슬롯 길이를 따른다(구간은 생성 때 자른다).
        if photo.is_video and not template.music_only:
            segment = photo.analysis.segment if photo.analysis else None
            sec = (
                round(segment[1] - segment[0], 2)
                if segment
                else min(photo.media_duration or sec, slot.sec.max)
            )
        assignments.append(Assignment(slot=slot, photo=photo, fit=fit, target_sec=sec))
        if slot.required and fit < low_slot_fit:
            warnings.append(
                f"{index}번 컷({slot.slot})의 슬롯 적합도가 낮습니다: {photo.photo_id}"
            )
    mean_fit = round(sum(a.fit for a in assignments) / len(assignments), 3)
    return FormatMatch(template, assignments, mean_fit, [], warnings)


def select_format(
    templates: dict[str, FormatTemplate],
    photos: list[PhotoRecord],
    *,
    forced: str | None = None,
    min_format_fit: float = 0.45,
    low_slot_fit: float = 0.35,
    keep_order: bool = False,
    one_per_cut: bool = False,
) -> FormatMatch:
    """가장 잘 맞는 포맷을 고른다. 사용자가 직접 지정할 수도 있다."""

    def matched(template: FormatTemplate) -> FormatMatch:
        if one_per_cut:
            return match_one_per_cut(template, photos, low_slot_fit=low_slot_fit)
        return match_format(template, photos, low_slot_fit=low_slot_fit, keep_order=keep_order)

    if forced is not None:
        if forced not in templates:
            raise FormatMatchError(
                f"없는 포맷입니다: {forced}. 사용 가능: {', '.join(sorted(templates))}"
            )
        match = matched(templates[forced])
    else:
        matches = [matched(t) for t in templates.values()]
        match = max(matches, key=lambda m: m.mean_fit - 0.1 * m.reuse_ratio)
    if match.mean_fit < min_format_fit:
        others = ", ".join(t for t in sorted(templates) if t != match.template.format_id)
        match.warnings.append(
            f"포맷 {match.template.format_id}의 적합도가 낮습니다({match.mean_fit}). "
            f"다른 포맷을 고려하세요: {others or '없음'}"
        )
    return match
