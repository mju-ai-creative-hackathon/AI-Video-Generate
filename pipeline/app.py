"""설정으로 파이프라인 구성 요소를 조립한다."""

from __future__ import annotations

from dataclasses import dataclass
from functools import cached_property
from pathlib import Path
from typing import Any

from ruamel.yaml import YAML
from sqlalchemy.orm import Session, sessionmaker

from .compose import FFmpegComposer
from .config import Settings, load_settings
from .db.repo import make_session_factory
from .detect import OpenCVFaceDetector
from .errors import ConfigError
from .format_match import FormatTemplate, load_formats
from .llm.base import LLMClient
from .llm.ollama import OllamaLLM
from .plan import PlanPipeline
from .rag.framing import CropPlanner
from .rag.retrieve import FramingRetriever
from .render import RenderPipeline
from .storyboard import MotionPresets, StoryboardGenerator
from .storyboard_service import StoryboardService
from .tts.base import TTSProvider
from .tts.elevenlabs import ElevenLabsTTS
from .tts.silent import SilentTTS
from .video.base import VideoProvider
from .video.comfyui import ComfyUIVideo
from .video.kenburns import KenBurnsVideo


@dataclass
class App:
    settings: Settings

    @classmethod
    def load(cls, config_path: str | Path = "config.yaml") -> "App":
        return cls(load_settings(config_path))

    @cached_property
    def factory(self) -> sessionmaker[Session]:
        return make_session_factory(self.settings.env.database_url)

    @cached_property
    def formats(self) -> dict[str, FormatTemplate]:
        return load_formats(self.settings.resolve(self.settings.storyboard.formats_dir))

    @cached_property
    def style_rules(self) -> dict[str, Any]:
        path = self.settings.resolve(self.settings.storyboard.style_rules)
        return YAML(typ="safe").load(path.read_text(encoding="utf-8")) or {}

    @cached_property
    def presets(self) -> MotionPresets:
        return MotionPresets.load(self.settings.resolve(self.settings.storyboard.motion_presets))

    @cached_property
    def planner(self) -> CropPlanner:
        rag = self.settings.rag
        retriever = FramingRetriever(self.factory, rag) if rag.enabled else None
        return CropPlanner(
            retriever,
            options=rag.options,
            min_height_px=self.settings.storyboard.min_crop_height_px,
        )

    @cached_property
    def llm(self) -> LLMClient:
        return OllamaLLM(self.settings.env.ollama_url, self.settings.llm)

    def generator(self) -> StoryboardGenerator:
        return StoryboardGenerator(
            self.llm, self.settings, self.formats, self.style_rules, self.presets, self.planner
        )

    @cached_property
    def service(self) -> StoryboardService:
        return StoryboardService(
            self.factory,
            self.settings,
            self.formats,
            self.planner,
            self.presets,
            generator_factory=self.generator,
        )

    def plan_pipeline(self) -> PlanPipeline:
        return PlanPipeline(
            self.factory,
            self.settings,
            self.llm,
            self.generator(),
            self.service,
            OpenCVFaceDetector(),
        )

    def make_tts(self, provider: str | None = None) -> TTSProvider:
        name = provider or self.settings.tts.provider
        if name == "elevenlabs":
            return ElevenLabsTTS(self.settings.env.elevenlabs_api_key, self.settings.tts)
        if name == "silent":
            return SilentTTS(self.settings.storyboard.chars_per_sec)
        raise ConfigError(f"지원하지 않는 TTS입니다: {name} (elevenlabs, silent)")

    def make_video(self, provider: str | None = None) -> VideoProvider:
        name = provider or self.settings.video.provider
        if name == "comfyui":
            return ComfyUIVideo(
                self.settings.env.comfyui_url,
                self.settings.video,
                self.settings.resolve(self.settings.video.workflow),
            )
        if name == "kenburns":
            return self.make_kenburns()
        raise ConfigError(f"지원하지 않는 영상 어댑터입니다: {name} (comfyui, kenburns)")

    def make_kenburns(self) -> VideoProvider:
        cfg = self.settings.compose
        return KenBurnsVideo((cfg.width, cfg.height), cfg.fps)

    def render_pipeline(
        self, *, tts: str | None = None, video: str | None = None
    ) -> RenderPipeline:
        # 승인 관문을 통과하기 전에는 어댑터를 만들지 않도록 팩토리로 넘긴다.
        return RenderPipeline(
            self.factory,
            self.settings,
            self.service,
            tts_factory=lambda: self.make_tts(tts),
            video_factory=lambda: self.make_video(video),
            fallback_factory=self.make_kenburns,
            composer=FFmpegComposer(self.settings.compose, self.settings.root),
        )
