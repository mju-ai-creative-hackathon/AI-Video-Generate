"""API 요청·응답 형식. 프론트 문서(/docs)의 기준이 된다."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from ..schemas import Cut

TTSName = Literal["elevenlabs", "silent"]
VideoName = Literal["comfyui", "veo", "kenburns"]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MissionCreate(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    description: str = ""


class MissionOut(BaseModel):
    id: str
    title: str
    description: str
    created_at: datetime


class SubmitItem(StrictModel):
    """제출 파일 하나의 부가 정보. files와 같은 순서로 보낸다."""

    note: str | None = Field(default=None, max_length=1000)
    audio: Literal["narration", "source", "mix"] | None = None


class JobOut(BaseModel):
    id: str
    run_id: str
    kind: str
    status: Literal["queued", "running", "done", "failed", "needs_review"]
    stage: str | None = None
    message: str | None = None
    current: int | None = Field(default=None, description="진행 메시지의 현재 번호. 진행 막대용")
    total: int | None = None
    result: dict[str, Any] | None = None
    error: str | None = None
    created_at: datetime
    started_at: datetime | None = None
    finished_at: datetime | None = None


class JobAccepted(BaseModel):
    job_id: str
    run_id: str


class VideoOut(BaseModel):
    url: str = Field(description="서명된 URL. 만료되면 실행을 다시 조회한다")
    storyboard_version: int
    duration: float
    width: int
    height: int
    size_bytes: int
    created_at: datetime


class RunOut(BaseModel):
    id: str
    mission_id: str | None
    status: str
    error: str | None
    latest_version: int | None
    latest_version_status: str | None
    active_job: JobOut | None
    video: VideoOut | None
    created_at: datetime
    updated_at: datetime


class Issue(BaseModel):
    location: str
    code: str
    message: str


class CutMedia(BaseModel):
    photo_id: str
    type: Literal["image", "video"] | None
    duration: float | None
    has_audio: bool
    width: int | None
    height: int | None
    note: str | None
    caption: str | None
    mood: str | None
    suggested_segment: list[float] | None = Field(description="AI가 고른 [시작 초, 끝 초]")
    image_url: str | None = None
    video_url: str | None = None


class CutView(Cut):
    media: CutMedia


class PhotoRef(BaseModel):
    photo_id: str
    type: Literal["image", "video"]
    duration: float | None
    has_audio: bool
    image_url: str
    video_url: str | None = None


class StoryboardView(BaseModel):
    version: int
    latest_version: int
    status: Literal["draft", "approved"]
    author: Literal["ai", "human"]
    title: str
    format_id: str
    hook_type: str
    audio_mode: Literal["narration", "music"] = Field(
        default="narration", description="music이면 나레이션 없이 음악만 넣는다"
    )
    music_id: str | None = Field(default=None, description="수정 가능. null이면 음악 없음")
    music_name: str | None = None
    total_sec: float
    cuts: list[CutView]
    photos: list[PhotoRef] = Field(description="컷의 사진을 바꿀 때 고를 수 있는 자료 목록")
    errors: list[Issue]
    warnings: list[Issue]


class FieldChangeOut(BaseModel):
    cut_id: str | None
    field: str
    before: Any = None
    after: Any = None


class SaveResult(BaseModel):
    version: int
    changed: bool
    changes: list[FieldChangeOut]
    warnings: list[Issue]


class RegenRequest(StrictModel):
    cut_ids: list[str] | None = Field(default=None, description="생략하면 전체 컷")
    fields: list[str] | None = Field(default=None, description="생략하면 전체 항목")


class RevertRequest(StrictModel):
    version: int


class ApproveRequest(StrictModel):
    version: int | None = Field(default=None, description="생략하면 최신 버전")
    accept_warnings: bool = False


class ApproveResult(BaseModel):
    version: int
    warnings: list[Issue]


class RenderRequest(StrictModel):
    tts: TTSName | None = Field(default=None, description="생략하면 config.yaml의 값")
    video: VideoName | None = Field(default=None, description="생략하면 config.yaml의 값")


class RegenClipRequest(RenderRequest):
    seed: int | None = Field(default=None, ge=0, lt=2**32, description="생략하면 무작위")


class FormatOption(BaseModel):
    id: str
    name: str
    hook_type: str
    total_sec_min: float
    total_sec_max: float
    audio_mode: Literal["narration", "music"] = Field(
        default="narration", description="music이면 나레이션 없이 음악만 넣는다"
    )
    music: str | None = Field(default=None, description="템플릿의 기본 음악 id")


class MusicOption(BaseModel):
    id: str
    name: str
    moods: list[str] = Field(default_factory=list)
    bpm: float | None = None


class Options(BaseModel):
    formats: list[FormatOption]
    music: list[MusicOption] = Field(
        default_factory=list, description="파일까지 있어 고를 수 있는 배경 음악"
    )
    effects: list[str]
    shot_sizes: list[str]
    camera_moves: list[str]
    transitions: list[str]
    roles: list[str]
    audio_modes: list[str]
    regen_fields: list[str]
    lockable_fields: list[str]
    tts_providers: list[str]
    video_providers: list[str]
    image_extensions: list[str]
    video_extensions: list[str]
    max_files: int
    max_file_mb: int
    max_video_sec: float
