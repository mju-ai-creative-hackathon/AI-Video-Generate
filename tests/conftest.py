"""테스트 공통 도구. 외부 API, GPU, ffmpeg, PostgreSQL 없이 돈다."""

from __future__ import annotations

import json
import re
import wave
from pathlib import Path
from typing import Any

import numpy as np
import pytest
from PIL import Image
from ruamel.yaml import YAML
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from pipeline.config import Env, Settings, StoryboardSettings
from pipeline.db.models import Base, Photo, Run
from pipeline.db.repo import session_scope
from pipeline.format_match import load_formats
from pipeline.llm.base import LLMClient
from pipeline.media import MediaInfo
from pipeline.rag.framing import CropPlanner
from pipeline.render import RenderPipeline
from pipeline.schemas import PhotoAnalysis
from pipeline.storyboard import MotionPresets, StoryboardGenerator
from pipeline.storyboard_service import StoryboardService
from pipeline.tts.base import TTSProvider, TTSRequest, TTSResult
from pipeline.video.base import ClipRequest, VideoProvider

REPO_ROOT = Path(__file__).resolve().parents[1]


class FakeLLM(LLMClient):
    """스키마 이름을 보고 형식에 맞는 응답을 만든다. queue에 넣은 응답이 있으면 먼저 쓴다."""

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []
        self.queue: list[str] = []
        self.counter = 0

    def chat_json(self, *, model, system, prompt, schema, images=None) -> str:  # noqa: ANN001
        self.calls.append({"model": model, "prompt": prompt, "schema": schema, "images": images})
        if self.queue:
            return self.queue.pop(0)
        self.counter += 1
        title = schema.get("title")
        if title == "LLMPhotoAnalysis":
            return json.dumps(
                {
                    "caption": "창가 테이블 위 커피잔",
                    "mood": "잔잔함",
                    "subject_type": "음식",
                    "subject_box": [0.35, 0.35, 0.65, 0.65],
                    "motion_ideas": ["김이 피어오름"],
                    "face_visible": False,
                },
                ensure_ascii=False,
            )
        if title == "ScriptOut":
            indexes = [int(n) for n in re.findall(r"^(?:컷|항목) (\d+) \|", prompt, re.M)]
            return json.dumps(
                {
                    "title": "혼자 떠난 제주",
                    "cuts": [
                        {
                            "cut_index": i,
                            "narration": f"새 문장 {self.counter}의 {i}번",
                            "on_screen_text": f"문구{i}",
                            "subject_action": f"움직임 {self.counter}",
                        }
                        for i in indexes
                    ],
                },
                ensure_ascii=False,
            )
        if title == "MotionOut":
            indexes = [int(n) for n in re.findall(r"^(\d+)\. photo:", prompt, re.M)]
            return json.dumps(
                {
                    "cuts": [
                        {"cut_index": i, "subject": f"coffee cup {self.counter}", "action": "steam rising"}
                        for i in indexes
                    ]
                }
            )
        if title == "ShortenOut":
            indexes = [int(n) for n in re.findall(r"^항목 (\d+):", prompt, re.M)]
            return json.dumps({"cuts": [{"cut_index": i, "narration": "짧은 문장"} for i in indexes]}, ensure_ascii=False)
        raise AssertionError(f"모르는 스키마: {title}")


class FakeTTS(TTSProvider):
    """글자당 길이로 무음 wav를 만든다."""

    name = "fake"
    extension = ".wav"

    def __init__(self, sec_per_char: float = 0.2) -> None:
        self.sec_per_char = sec_per_char
        self.calls: list[TTSRequest] = []

    def signature(self) -> dict[str, Any]:
        return {"provider": self.name}

    def synthesize(self, request: TTSRequest, out_path: Path) -> TTSResult:
        self.calls.append(request)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(out_path), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(8000)
            handle.writeframes(b"\x00\x00" * int(len(request.text) * self.sec_per_char * 8000))
        return TTSResult()


def wav_duration(path: Path) -> float:
    with wave.open(str(path), "rb") as handle:
        return handle.getnframes() / handle.getframerate()


class FakeVideo(VideoProvider):
    name = "fakevideo"

    def __init__(self, fail_times: int = 0) -> None:
        self.calls: list[ClipRequest] = []
        self.fail_times = fail_times
        self.released = 0

    @property
    def input_size(self) -> tuple[int, int]:
        return (48, 83)

    def generate(self, request: ClipRequest) -> Path:
        from pipeline.errors import VideoError

        self.calls.append(request)
        if self.fail_times > 0:
            self.fail_times -= 1
            raise VideoError("가짜 실패")
        request.out_path.parent.mkdir(parents=True, exist_ok=True)
        request.out_path.write_bytes(b"clip")
        return request.out_path

    def release(self) -> None:
        self.released += 1


class FakeComposer:
    def __init__(self) -> None:
        self.calls: list[list[Any]] = []

    def compose(self, cuts, work_dir: Path, out_path: Path, *, music=None) -> Path:  # noqa: ANN001
        self.calls.append(cuts)
        self.music = music
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_bytes(b"final-" + str(len(self.calls)).encode())
        return out_path


def fake_video_info(_path: Path) -> MediaInfo:
    return MediaInfo(
        duration=0.0, width=1080, height=1920, fps=30.0, codec="h264", pix_fmt="yuv420p", has_audio=True
    )


def noisy_image(seed: int, size: tuple[int, int] = (1600, 1200)) -> Image.Image:
    """서로 다른 큰 무늬를 가진 선명한 이미지. 지각 해시가 겹치지 않는다."""
    rng = np.random.default_rng(seed)
    coarse = rng.integers(0, 255, (12, 16, 3), dtype=np.uint8)
    image = Image.fromarray(coarse).resize(size, Image.Resampling.NEAREST)
    array = np.asarray(image).astype(int) + rng.integers(-20, 20, (size[1], size[0], 3))
    return Image.fromarray(np.clip(array, 0, 255).astype(np.uint8))


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    data = REPO_ROOT / "data"
    env = Env(_env_file=None, database_url=f"sqlite:///{(tmp_path / 'test.db').as_posix()}")
    return Settings(
        root=tmp_path,
        env=env,
        storyboard=StoryboardSettings(
            formats_dir=str(data / "formats"),
            style_rules=str(data / "style_rules.yaml"),
            motion_presets=str(data / "motion_presets.yaml"),
        ),
    )


@pytest.fixture
def factory(settings: Settings) -> sessionmaker:
    engine = create_engine(settings.env.database_url)
    Base.metadata.create_all(engine)
    return sessionmaker(engine, expire_on_commit=False)


@pytest.fixture
def formats(settings: Settings):
    return load_formats(settings.resolve(settings.storyboard.formats_dir))


@pytest.fixture
def presets(settings: Settings) -> MotionPresets:
    return MotionPresets.load(settings.resolve(settings.storyboard.motion_presets))


@pytest.fixture
def style_rules(settings: Settings) -> dict:
    path = settings.resolve(settings.storyboard.style_rules)
    return YAML(typ="safe").load(path.read_text(encoding="utf-8"))


@pytest.fixture
def planner() -> CropPlanner:
    return CropPlanner(None, options=3, min_height_px=300)


@pytest.fixture
def llm() -> FakeLLM:
    return FakeLLM()


@pytest.fixture
def generator(llm, settings, formats, style_rules, presets, planner) -> StoryboardGenerator:
    return StoryboardGenerator(llm, settings, formats, style_rules, presets, planner)


@pytest.fixture
def service(factory, settings, formats, planner, presets, generator) -> StoryboardService:
    return StoryboardService(
        factory, settings, formats, planner, presets, generator_factory=lambda: generator
    )


def analysis(subject_type: str = "음식", mood: str = "잔잔함") -> dict:
    return PhotoAnalysis(
        caption=f"{subject_type} 사진",
        mood=mood,
        subject_type=subject_type,  # type: ignore[arg-type]
        subject_box=[0.35, 0.35, 0.65, 0.65],
        motion_ideas=["작은 움직임"],
        subject_box_source="llm",
    ).model_dump(mode="json")


@pytest.fixture
def make_run(factory, settings):
    """분석까지 끝난 사진이 있는 실행을 만든다."""

    def _make(n_photos: int = 6, run_id: str = "run1", memo: str = "제주 여행") -> str:
        photo_dir = settings.storage_path / "runs" / run_id / "photos"
        photo_dir.mkdir(parents=True, exist_ok=True)
        types = ["풍경", "음식", "사물", "인물", "실내", "풍경"]
        with session_scope(factory) as session:
            session.add(Run(id=run_id, status="planning", memo=memo, config={"seeds": {}}))
            for index in range(n_photos):
                photo_id = f"p{index + 1:02d}"
                path = photo_dir / f"{photo_id}.jpg"
                noisy_image(index).save(path, "JPEG", quality=90)
                session.add(
                    Photo(
                        run_id=run_id,
                        photo_id=photo_id,
                        source_path=str(path),
                        path=path.relative_to(settings.root).as_posix(),
                        sha256=f"sha{index}",
                        width=1600,
                        height=1200,
                        analysis=analysis(types[index % len(types)]),
                    )
                )
        return run_id

    return _make


@pytest.fixture
def drafted(make_run, service, generator):
    """AI 초안 v1까지 저장한 실행."""

    def _make(n_photos: int = 6, run_id: str = "run1") -> str:
        make_run(n_photos, run_id)
        draft = generator.generate(service.photos(run_id), "제주 여행")
        service.save_ai_draft(run_id, draft.storyboard)
        return run_id

    return _make


@pytest.fixture
def renderer(factory, settings, service):
    """가짜 어댑터로 RenderPipeline을 만든다."""

    def _make(tts=None, video=None, composer=None, fallback=None):  # noqa: ANN001, ANN202
        tts = tts or FakeTTS()
        video = video or FakeVideo()
        composer = composer or FakeComposer()
        pipeline = RenderPipeline(
            factory,
            settings,
            service,
            tts_factory=lambda: tts,
            video_factory=lambda: video,
            fallback_factory=(lambda: fallback) if fallback else None,
            composer=composer,
            duration_probe=wav_duration,
            video_probe=fake_video_info,
            contact_sheet=None,
        )
        return pipeline, tts, video, composer

    return _make
