"""[2] 사진 분석: 비전 모델로 사진 한 장씩 분석한다."""

from __future__ import annotations

import math
from pathlib import Path

from .config import LLMSettings, PreprocessSettings, VideoInputSettings
from .detect import SubjectDetector, union_box
from .llm.base import LLMClient, structured_call
from .media import extract_frame
from .schemas import LLMPhotoAnalysis, LLMVideoAnalysis, PhotoAnalysis, PhotoRecord

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
- motion_ideas: 이 사진이 짧은 영상으로 움직인다면 자연스러울 작은 움직임 1~3개
- face_visible: 사람의 얼굴(눈·코·입)이 실제로 보이면 true. 뒷모습이거나 머리카락·모자·물건에 가려졌거나 사람이 없으면 false"""


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


VIDEO_PROMPT = """이 이미지들은 한 영상에서 시간순으로 뽑은 프레임 {count}장입니다. 약 {spacing:.1f}초 간격이며 순서대로 1번부터 {count}번입니다.
{context}

JSON으로 답하세요.

- start_frame, end_frame: 숏폼에 쓰기 가장 좋은 연속 구간의 첫 프레임과 마지막 프레임 번호. 위 내용과 가장 관련 있고 흔들림이나 가림이 적은 구간을 고르세요. 약 {max_sec:.0f}초 이내가 좋습니다.
- caption: 고른 구간에 보이는 것을 한국어 한두 문장으로 설명
- mood: 분위기를 나타내는 한국어 한 단어 (예: 잔잔함, 설렘, 활기, 따뜻함)
- subject_type: 고른 구간의 주 피사체 종류. 인물, 음식, 사물, 풍경, 실내 중 하나
- subject_box: 고른 구간의 가운데 프레임에서 주 피사체를 감싸는 [x1, y1, x2, y2]. 이미지 너비·높이 대비 0~1 값. 모르면 null
- motion_ideas: 구간에서 일어나는 움직임 1~3개
- face_visible: 사람의 얼굴(눈·코·입)이 실제로 보이면 true. 사람이 없거나 가려졌으면 false"""


def frame_grid(duration: float, every_sec: float, max_frames: int) -> tuple[int, float]:
    """(프레임 수, 프레임 간격)을 정한다. 프레임 i는 구간 [(i-1)·간격, i·간격]의 가운데다."""
    count = max(1, min(max_frames, math.ceil(duration / max(every_sec, 0.1))))
    return count, duration / count


def segment_from_frames(
    start_frame: int,
    end_frame: int,
    count: int,
    duration: float,
    *,
    max_sec: float,
    min_sec: float,
) -> list[float]:
    """프레임 번호를 초로 바꾸고 길이를 제한한다."""
    spacing = duration / count
    start = (start_frame - 1) * spacing
    end = end_frame * spacing
    if end - start > max_sec:
        end = start + max_sec
    if end - start < min_sec:
        end = min(duration, start + min_sec)
        start = max(0.0, end - min_sec)
    return [round(start, 3), round(min(end, duration), 3)]


def analyze_video(
    llm: LLMClient,
    settings: LLMSettings,
    video_settings: VideoInputSettings,
    preprocess_settings: PreprocessSettings,
    record: PhotoRecord,
    mission: str | None,
    min_cut_sec: float,
    work_dir: Path,
    detector: SubjectDetector | None = None,
    failure_dir: Path | None = None,
) -> PhotoAnalysis:
    """프레임 여러 장으로 영상을 분석해 쓸 구간을 고르고, 그 구간의 대표 프레임을 저장한다."""
    assert record.video_path is not None
    duration = record.media_duration or 0.0
    count, spacing = frame_grid(
        duration, video_settings.sample_every_sec, video_settings.max_frames
    )
    frames: list[Path] = []
    frames_dir = work_dir / "frames" / record.photo_id
    for index in range(count):
        path = frames_dir / f"{index + 1:03d}.jpg"
        image = extract_frame(record.video_path, (index + 0.5) * spacing, path)
        image.thumbnail((video_settings.frame_side, video_settings.frame_side))
        image.save(path, "JPEG", quality=85)
        frames.append(path)

    context = "\n".join(
        line
        for line in (
            mission and f"[미션]\n{mission}",
            record.note and f"[제출자 메모] {record.note}",
        )
        if line
    )

    def check(result: LLMVideoAnalysis) -> None:
        if not 1 <= result.start_frame <= result.end_frame <= count:
            raise ValueError(
                f"1 <= start_frame <= end_frame <= {count}이어야 합니다: "
                f"{result.start_frame}, {result.end_frame}"
            )

    result = structured_call(
        llm,
        LLMVideoAnalysis,
        model=settings.vision_model,
        system=SYSTEM,
        prompt=VIDEO_PROMPT.format(
            count=count, spacing=spacing, context=context, max_sec=video_settings.max_cut_sec
        ),
        images=frames,
        max_retries=settings.max_retries,
        check=check,
        failure_dir=failure_dir,
        label=f"analyze_{record.photo_id}",
    )
    segment = segment_from_frames(
        result.start_frame,
        result.end_frame,
        count,
        duration,
        max_sec=video_settings.max_cut_sec,
        min_sec=min_cut_sec,
    )
    # 크롭 계산과 썸네일이 고른 구간을 보도록 대표 프레임을 바꾼다.
    poster = extract_frame(record.video_path, sum(segment) / 2, record.path)
    if record.analysis_path is not None:
        poster.thumbnail(
            (preprocess_settings.max_analysis_side, preprocess_settings.max_analysis_side)
        )
        poster.save(record.analysis_path, "JPEG", quality=90)
    faces = detector.detect_faces(record.path) if detector else []
    analysis = apply_detection(result, faces)
    return analysis.model_copy(update={"segment": segment})


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
