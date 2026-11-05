"""[4] 구도 계산: 상대값 → crop_box 변환, 검사, 규칙 기반 크롭.

크롭 좌표는 LLM이 쓰지 않고 이 모듈이 계산한다.
좌표는 모두 원본 사진 크기 대비 0~1의 [x1, y1, x2, y2]이다.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from ..schemas import PhotoRecord

ASPECT = 9 / 16
DEFAULT_SUBJECT = (0.3, 0.3, 0.7, 0.7)
# 나레이션 자막과 화면 문구가 놓이는 프레임 내 세로 구간 (compose 설정의 기본값과 맞춘다)
NARRATION_BAND = (0.60, 0.72)
TEXT_BAND = (0.12, 0.24)
MIN_SUBJECT_VISIBLE = 0.85

# 규칙 기반 크롭의 샷 크기별 피사체 높이 비율. None이면 가능한 가장 큰 크롭이다.
RULE_HEIGHT_RATIO: dict[str, float | None] = {
    "wide": None,
    "medium": 0.45,
    "close_up": 0.65,
    "detail": 0.9,
}


@dataclass
class PhotoGeom:
    width: int
    height: int
    subject_box: list[float] | None = None
    subject_type: str | None = None
    faces: list[list[float]] = field(default_factory=list)

    @classmethod
    def from_record(cls, record: PhotoRecord) -> "PhotoGeom":
        analysis = record.analysis
        return cls(
            width=record.width,
            height=record.height,
            subject_box=analysis.subject_box if analysis else None,
            subject_type=analysis.subject_type if analysis else None,
            faces=list(analysis.faces) if analysis else [],
        )


@dataclass
class CropCheck:
    fatal: list[str] = field(default_factory=list)
    penalty: float = 0.0
    notes: list[str] = field(default_factory=list)


@dataclass
class CropPlan:
    options: list[list[float]]
    source: str  # rag / rule
    refs: list[str] = field(default_factory=list)
    flags: list[str] = field(default_factory=list)


@dataclass
class FramingQuery:
    subject_type: str | None
    shot_size: str
    layout_vector: list[float]
    image_embedding: list[float] | None = None


@dataclass
class RetrievedExample:
    example_id: str
    framing: dict
    score: float


class FramingSearch(Protocol):
    def search(self, query: FramingQuery) -> list[RetrievedExample]: ...


def layout_vector(
    subject_box: list[float] | None, depth: tuple[float, float, float] | None = None
) -> list[float]:
    """원본 사진 안의 배치를 나타내는 벡터. 구도 데이터 구축도 이 함수를 써야 한다."""
    x1, y1, x2, y2 = subject_box or DEFAULT_SUBJECT
    near, mid, far = depth or (0.0, 0.0, 0.0)
    return [(x1 + x2) / 2, (y1 + y2) / 2, y2 - y1, x2 - x1, y1, 1 - y2, x1, 1 - x2, near, mid, far]


def max_crop_size(geom: PhotoGeom) -> tuple[float, float]:
    height = min(float(geom.height), geom.width / ASPECT)
    return height * ASPECT, height


def compute_crop(
    geom: PhotoGeom, center: tuple[float, float], height_ratio: float | None
) -> list[float]:
    """피사체가 프레임의 center 위치에, 프레임 높이의 height_ratio 크기로 오도록 자른다."""
    x1, y1, x2, y2 = geom.subject_box or DEFAULT_SUBJECT
    subject_h = (y2 - y1) * geom.height
    max_w, max_h = max_crop_size(geom)
    crop_h = max_h if not height_ratio else min(max_h, subject_h / height_ratio)
    crop_w = crop_h * ASPECT
    left = (x1 + x2) / 2 * geom.width - center[0] * crop_w
    top = (y1 + y2) / 2 * geom.height - center[1] * crop_h
    left = min(max(0.0, left), geom.width - crop_w)
    top = min(max(0.0, top), geom.height - crop_h)
    return [
        round(left / geom.width, 4),
        round(top / geom.height, 4),
        round((left + crop_w) / geom.width, 4),
        round((top + crop_h) / geom.height, 4),
    ]


def crop_box_errors(box: object) -> list[str]:
    """형식과 범위 오류. 있으면 저장할 수 없다."""
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        return ["crop_box는 [x1, y1, x2, y2] 형식이어야 합니다."]
    if not all(isinstance(v, (int, float)) for v in box):
        return ["crop_box의 값은 숫자여야 합니다."]
    x1, y1, x2, y2 = box
    if not (0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1):
        return [f"crop_box는 0~1 범위이고 x1<x2, y1<y2여야 합니다: {list(box)}"]
    return []


def visible_ratio(box: list[float], crop: list[float]) -> float:
    ix = max(0.0, min(box[2], crop[2]) - max(box[0], crop[0]))
    iy = max(0.0, min(box[3], crop[3]) - max(box[1], crop[1]))
    area = (box[2] - box[0]) * (box[3] - box[1])
    return (ix * iy) / area if area > 0 else 0.0


def iou(a: list[float], b: list[float]) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def check_crop(
    geom: PhotoGeom, crop: list[float], *, shot_size: str, min_height_px: int
) -> CropCheck:
    result = CropCheck()
    errors = crop_box_errors(crop)
    if errors:
        result.fatal.extend(errors)
        return result

    # 해상도: 사진 자체가 작아 더 키울 수 없는 경우는 탈락시키지 않는다.
    crop_h_px = (crop[3] - crop[1]) * geom.height
    if crop_h_px < min_height_px and crop_h_px < max_crop_size(geom)[1] - 1:
        result.fatal.append(f"low_resolution:{crop_h_px:.0f}px")

    # 피사체 잘림: detail은 일부만 보여주는 샷이므로 얼굴이 아니면 허용한다.
    if geom.subject_box and shot_size != "detail":
        visible = visible_ratio(geom.subject_box, crop)
        if visible < MIN_SUBJECT_VISIBLE:
            result.fatal.append(f"subject_clipped:{visible:.2f}")
    for face in geom.faces:
        if visible_ratio(face, crop) < MIN_SUBJECT_VISIBLE:
            result.fatal.append("face_clipped")
            break

    # 자막 공간: 자막이 피사체를 가릴수록 뒤로 보낸다.
    target = geom.faces[0] if geom.faces else geom.subject_box
    if target:
        height = crop[3] - crop[1]
        top = (target[1] - crop[1]) / height
        bottom = (target[3] - crop[1]) / height
        span = max(1e-6, bottom - top)
        overlap = max(0.0, min(bottom, NARRATION_BAND[1]) - max(top, NARRATION_BAND[0])) / span
        weight = 1.0 if geom.faces else 0.3
        result.penalty += overlap * weight
        if overlap > 0.5 and geom.faces:
            result.notes.append("subtitle_over_face")
    return result


def rule_based_crops(geom: PhotoGeom, shot_size: str, limit: int = 3) -> list[list[float]]:
    """검색 결과가 없을 때 쓰는 크롭. 위치를 조금씩 달리한 후보를 낸다."""
    ratio = RULE_HEIGHT_RATIO.get(shot_size)
    if geom.faces or geom.subject_type == "인물":
        centers = [(0.5, 0.38), (0.38, 0.38), (0.62, 0.38)]
    else:
        centers = [(0.5, 0.45), (0.38, 0.5), (0.62, 0.5), (0.5, 0.33)]
    crops: list[list[float]] = []
    for center in centers:
        crop = compute_crop(geom, center, ratio)
        if all(iou(crop, other) < 0.97 for other in crops):
            crops.append(crop)
    return crops[:limit]


class CropPlanner:
    def __init__(
        self,
        retriever: FramingSearch | None = None,
        *,
        options: int = 3,
        min_height_px: int = 640,
    ) -> None:
        self.retriever = retriever
        self.options = options
        self.min_height_px = min_height_px

    def plan(self, geom: PhotoGeom, shot_size: str) -> CropPlan:
        plan = self._from_examples(geom, shot_size) if self.retriever else None
        return plan or self._from_rules(geom, shot_size)

    def _from_examples(self, geom: PhotoGeom, shot_size: str) -> CropPlan | None:
        assert self.retriever is not None
        examples = self.retriever.search(
            FramingQuery(
                subject_type=geom.subject_type,
                shot_size=shot_size,
                layout_vector=layout_vector(geom.subject_box),
            )
        )
        scored: list[tuple[float, list[float], str]] = []
        for example in examples:
            framing = example.framing or {}
            center = framing.get("subject_center")
            ratio = framing.get("subject_height_ratio")
            if not center or not ratio:
                continue
            crop = compute_crop(geom, (center[0], center[1]), ratio)
            check = check_crop(geom, crop, shot_size=shot_size, min_height_px=self.min_height_px)
            if not check.fatal:
                scored.append((example.score - check.penalty, crop, example.example_id))
        scored.sort(key=lambda item: item[0], reverse=True)

        options: list[list[float]] = []
        refs: list[str] = []
        for _, crop, example_id in scored:
            if all(iou(crop, other) < 0.9 for other in options):
                options.append(crop)
                refs.append(example_id)
            if len(options) == self.options:
                break
        return CropPlan(options=options, source="rag", refs=refs) if options else None

    def _from_rules(self, geom: PhotoGeom, shot_size: str) -> CropPlan:
        crops = rule_based_crops(geom, shot_size, limit=self.options)
        checked = [
            (check_crop(geom, c, shot_size=shot_size, min_height_px=self.min_height_px), c)
            for c in crops
        ]
        passed = sorted(
            [(chk.penalty, c) for chk, c in checked if not chk.fatal], key=lambda item: item[0]
        )
        flags = ["rule_crop"]
        if passed:
            options = [c for _, c in passed]
        else:
            # 모두 검사에 실패해도 사람이 고칠 수 있도록 후보를 남기고 표시한다.
            options = crops
            flags.append("crop_check_failed")
        return CropPlan(options=options, source="rule", flags=flags)
