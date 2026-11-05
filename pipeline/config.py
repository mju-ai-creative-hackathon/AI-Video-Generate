"""config.yaml과 .env를 읽어 설정 객체를 만든다."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings, SettingsConfigDict
from ruamel.yaml import YAML

from .errors import ConfigError


class LLMSettings(BaseModel):
    vision_model: str = "gemma4:latest"
    text_model: str = "gemma4:latest"
    temperature: float = 0.7
    num_ctx: int = 8192
    timeout_sec: float = 600
    max_retries: int = 2
    think: bool | None = None
    keep_alive: int | str = 0


class PreprocessSettings(BaseModel):
    max_analysis_side: int = 1568
    phash_distance: int = 5
    blur_threshold: float = 60.0
    jpeg_quality: int = 95


class StoryboardSettings(BaseModel):
    formats_dir: str = "data/formats"
    style_rules: str = "data/style_rules.yaml"
    motion_presets: str = "data/motion_presets.yaml"
    min_format_fit: float = 0.45
    low_slot_fit: float = 0.35
    chars_per_sec: float = 5.5
    chars_tolerance: float = 1.15
    max_script_retries: int = 2
    min_crop_height_px: int = 640


class RagSettings(BaseModel):
    enabled: bool = True
    candidates: int = 200
    top_k: int = 15
    options: int = 3
    mmr_lambda: float = 0.7
    weight_image: float = 0.5
    weight_layout: float = 0.35
    weight_score: float = 0.15
    derived_weight: float = 0.8


class TTSSettings(BaseModel):
    provider: str = "elevenlabs"
    voice_id: str = ""
    model_id: str = "eleven_multilingual_v2"
    output_format: str = "mp3_44100_128"
    stability: float = 0.5
    similarity_boost: float = 0.75
    style: float = 0.0
    speed: float = 1.0
    max_retries: int = 5
    timeout_sec: float = 120


class VideoSettings(BaseModel):
    provider: str = "comfyui"
    workflow: str = "workflows/wan22_i2v.json"
    width: int = 480
    height: int = 832
    fps: int = 16
    steps: int = 6
    base_seed: int = 20261105
    max_retries: int = 2
    timeout_sec: float = 1800
    min_free_vram_mb: int = 8000
    models: dict[str, str] = Field(default_factory=dict)
    negative_prompt: str = ""


class TimingSettings(BaseModel):
    pad_sec: float = 0.25
    source_fps: int = 16
    max_frames: int = 81
    min_frames: int = 33
    max_slowdown: float = 1.2
    min_cut_sec: float = 2.0


class ComposeSettings(BaseModel):
    width: int = 1080
    height: int = 1920
    fps: int = 30
    crf: int = 18
    preset: str = "medium"
    audio_bitrate: str = "192k"
    loudness_lufs: float = -14.0
    bgm_path: str | None = None
    bgm_volume: float = 0.25
    fade_sec: float = 0.3
    font_name: str = "Pretendard"
    fonts_dir: str = "assets/fonts"
    narration_font_size: int = 64
    emphasis_font_size: int = 88
    narration_margin_ratio: float = 0.30
    emphasis_margin_ratio: float = 0.14
    max_chars_per_line: int = 14


class Env(BaseSettings):
    """비밀 값과 접속 주소. 환경 변수가 .env보다 우선한다."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    elevenlabs_api_key: str = ""
    database_url: str = "postgresql+psycopg://vlog:vlog@127.0.0.1:5432/vlog"
    ollama_url: str = "http://127.0.0.1:11434"
    comfyui_url: str = "http://127.0.0.1:8188"


class Settings(BaseModel):
    root: Path = Path(".")
    storage_dir: str = "storage"
    llm: LLMSettings = Field(default_factory=LLMSettings)
    preprocess: PreprocessSettings = Field(default_factory=PreprocessSettings)
    storyboard: StoryboardSettings = Field(default_factory=StoryboardSettings)
    rag: RagSettings = Field(default_factory=RagSettings)
    tts: TTSSettings = Field(default_factory=TTSSettings)
    video: VideoSettings = Field(default_factory=VideoSettings)
    timing: TimingSettings = Field(default_factory=TimingSettings)
    compose: ComposeSettings = Field(default_factory=ComposeSettings)
    env: Env = Field(default_factory=Env)

    def resolve(self, relative: str | Path) -> Path:
        path = Path(relative)
        return path if path.is_absolute() else self.root / path

    @property
    def storage_path(self) -> Path:
        return self.resolve(self.storage_dir)


def load_settings(path: str | Path = "config.yaml") -> Settings:
    config_path = Path(path).resolve()
    if not config_path.exists():
        raise ConfigError(f"설정 파일을 찾을 수 없습니다: {config_path}")
    raw: dict[str, Any] = YAML(typ="safe").load(config_path.read_text(encoding="utf-8")) or {}
    env = Env(_env_file=config_path.parent / ".env")  # type: ignore[call-arg]
    return Settings(root=config_path.parent, env=env, **raw)
