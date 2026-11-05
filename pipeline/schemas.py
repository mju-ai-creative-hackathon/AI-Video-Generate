"""스토리보드와 사진 분석의 데이터 구조."""

from __future__ import annotations

import secrets
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

ShotSize = Literal["wide", "medium", "close_up", "detail"]
CameraMove = Literal[
    "static", "slow_push_in", "pull_out", "pan_left", "pan_right", "tilt_up", "handheld"
]
Role = Literal["hook", "body", "ending"]
Transition = Literal["hard_cut", "fade"]
SubjectType = Literal["인물", "음식", "사물", "풍경", "실내"]

# 사람이 잠글 수 있는 항목. 잠긴 항목은 AI 재생성에서 제외한다.
LOCKABLE_FIELDS = frozenset(
    {
        "narration",
        "target_sec",
        "photo_id",
        "role",
        "motion_prompt",
        "shot_size",
        "camera_move",
        "subject_action",
        "crop_box",
        "on_screen_text",
        "transition_in",
    }
)
# `vlog storyboard regen --field`로 AI가 다시 만들 수 있는 항목.
REGEN_FIELDS = frozenset(
    {"narration", "on_screen_text", "subject_action", "motion_prompt", "crop_box"}
)


def new_cut_id(existing: set[str] | None = None) -> str:
    existing = existing or set()
    while True:
        cut_id = f"c_{secrets.token_hex(2)}"
        if cut_id not in existing:
            return cut_id


def normalize_box(value: object) -> list[float] | None:
    """모델이 준 0~1 좌표를 검사한다. 부정확할 수 있으므로 잘못되면 버린다."""
    if not isinstance(value, (list, tuple)) or len(value) != 4:
        return None
    try:
        box = [float(v) for v in value]
    except (TypeError, ValueError):
        return None
    x1, y1, x2, y2 = box
    if not (0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1):
        return None
    return box


class LLMPhotoAnalysis(BaseModel):
    """비전 모델의 응답 스키마."""

    caption: str = Field(description="사진에 실제로 보이는 것만 한국어 한두 문장으로 설명")
    mood: str = Field(description="분위기 한 단어. 예: 잔잔함, 설렘, 활기")
    subject_type: SubjectType
    subject_box: list[float] | None = Field(
        default=None, description="주 피사체 위치 [x1, y1, x2, y2], 이미지 크기 대비 0~1"
    )
    motion_ideas: list[str] = Field(
        default_factory=list, description="사진 속 피사체에 자연스러운 작은 움직임 1~3개"
    )

    @field_validator("subject_box", mode="before")
    @classmethod
    def _clean_box(cls, value: object) -> list[float] | None:
        return normalize_box(value)


class PhotoAnalysis(LLMPhotoAnalysis):
    subject_box_source: Literal["detector", "llm", "none"] = "none"
    faces: list[list[float]] = Field(default_factory=list)


@dataclass
class PhotoRecord:
    photo_id: str
    path: Path
    width: int
    height: int
    sha256: str
    analysis: PhotoAnalysis | None = None
    excluded_reason: str | None = None
    analysis_path: Path | None = None


class ShotDesign(BaseModel):
    model_config = ConfigDict(extra="forbid")

    shot_size: ShotSize
    camera_move: CameraMove
    subject_action: str = ""
    crop_options: list[list[float]] = Field(default_factory=list)
    crop_choice: int = 1  # 썸네일 시트와 같은 1부터 시작하는 번호
    crop_box: list[float]
    on_screen_text: str | None = None
    transition_in: Transition = "hard_cut"


class Cut(BaseModel):
    model_config = ConfigDict(extra="forbid")

    cut_id: str
    position: int
    locked: list[str] = Field(default_factory=list)
    photo_id: str
    role: Role
    narration: str
    target_sec: float
    shot: ShotDesign
    framing_refs: list[str] = Field(default_factory=list)
    motion_prompt: str
    # 사람이 바로 볼 수 있도록 표시하는 정보. 수정 대상이 아니다.
    slot: str | None = None
    fit_score: float | None = None
    crop_source: Literal["rag", "rule", "human"] = "rule"
    flags: list[str] = Field(default_factory=list)


class Storyboard(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str
    format_id: str
    hook_type: str
    style_notes: str = ""
    cuts: list[Cut]

    def ordered_cuts(self) -> list[Cut]:
        return sorted(self.cuts, key=lambda c: c.position)

    def cut(self, cut_id: str) -> Cut | None:
        return next((c for c in self.cuts if c.cut_id == cut_id), None)


# ── storyboard.yaml 가져오기 형식 ─────────────────────────────────────────────
# 오타가 조용히 무시되지 않도록 모르는 키는 거부한다.
class EditShot(BaseModel):
    model_config = ConfigDict(extra="forbid")

    shot_size: ShotSize
    camera_move: CameraMove
    subject_action: str = ""
    crop_choice: int | None = None
    crop_box: list[float] | None = None
    on_screen_text: str | None = None
    transition_in: Transition = "hard_cut"


class EditCut(BaseModel):
    model_config = ConfigDict(extra="forbid")

    cut_id: str | None = None  # 비우면 새 컷으로 보고 ID를 만든다
    position: int
    photo_id: str
    role: Role
    narration: str
    target_sec: float
    shot: EditShot
    motion_prompt: str | None = None
    locked: list[str] = Field(default_factory=list)


class EditFile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    base_version: int
    title: str
    cuts: list[EditCut]
