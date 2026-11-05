"""[2] 사진 분석: 비전 모델로 사진 한 장씩 분석한다."""

from __future__ import annotations

from pathlib import Path

from .config import LLMSettings
from .detect import SubjectDetector, union_box
from .llm.base import LLMClient, structured_call
from .schemas import LLMPhotoAnalysis, PhotoAnalysis

SYSTEM = (
    "당신은 사진을 객관적으로 설명하는 분석가입니다. 사진에 실제로 보이는 것만 씁니다. "
    "장소 이름, 맛, 동행인처럼 사진만으로 알 수 없는 사실은 추측하지 않습니다. "
    "반드시 주어진 JSON 스키마로만 답합니다."
)

PROMPT = """이 사진을 분석해 JSON으로 답하세요.

- caption: 보이는 것을 한국어 한두 문장으로 설명
- mood: 분위기를 나타내는 한국어 한 단어 (예: 잔잔함, 설렘, 활기, 따뜻함)
- subject_type: 주 피사체의 종류. 인물, 음식, 사물, 풍경, 실내 중 하나
- subject_box: 주 피사체를 감싸는 [x1, y1, x2, y2]. 이미지 너비·높이 대비 0~1 값. 모르면 null
- motion_ideas: 이 사진이 짧은 영상으로 움직인다면 자연스러울 작은 움직임 1~3개"""


def analyze_photo(
    llm: LLMClient,
    settings: LLMSettings,
    image_path: Path,
    detector: SubjectDetector | None = None,
    failure_dir: Path | None = None,
) -> PhotoAnalysis:
    result = structured_call(
        llm,
        LLMPhotoAnalysis,
        model=settings.vision_model,
        system=SYSTEM,
        prompt=PROMPT,
        images=[image_path],
        max_retries=settings.max_retries,
        failure_dir=failure_dir,
        label=f"analyze_{image_path.stem}",
    )
    return apply_detection(result, detector.detect_faces(image_path) if detector else [])


def apply_detection(result: LLMPhotoAnalysis, faces: list[list[float]]) -> PhotoAnalysis:
    """모델이 준 좌표는 부정확할 수 있으므로 검출 결과가 있으면 그것을 우선한다."""
    data = result.model_dump()
    data["faces"] = faces
    # 얼굴 검출은 음식·사물 사진에서 오검출이 날 수 있어, 인물 사진이거나 모델 좌표가 없을 때만 쓴다.
    if faces and (result.subject_type == "인물" or result.subject_box is None):
        data["subject_box"] = union_box(faces)
        data["subject_box_source"] = "detector"
    elif result.subject_box is not None:
        data["subject_box_source"] = "llm"
    else:
        data["subject_box_source"] = "none"
    return PhotoAnalysis.model_validate(data)
