"""배경 음악 목록. 템플릿의 기본 음악과 제작 요청의 musicId가 이 목록의 id를 가리킨다.

라이선스를 확보한 곡만 넣는다. 파일은 저장소에 올리지 않는 assets/music/에 둔다.
"""

from __future__ import annotations

from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field
from ruamel.yaml import YAML

from .errors import ConfigError


class MusicTrack(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")
    name: str
    file: str = Field(description="저장소 루트 기준 경로")
    license: str = Field(description="출처와 사용 조건. 확인한 사람이 적는다")
    moods: list[str] = Field(default_factory=list)
    bpm: float | None = None


class MusicLibrary:
    def __init__(self, tracks: list[MusicTrack], root: Path) -> None:
        self.root = root
        self.tracks = {t.id: t for t in tracks}
        if len(self.tracks) != len(tracks):
            raise ConfigError("음악 id가 중복됩니다.")

    @classmethod
    def load(cls, path: Path, root: Path) -> "MusicLibrary":
        if not path.exists():
            return cls([], root)
        data = YAML(typ="safe").load(path.read_text(encoding="utf-8")) or {}
        tracks = [MusicTrack.model_validate(t) for t in data.get("tracks") or []]
        return cls(tracks, root)

    @property
    def ids(self) -> list[str]:
        return sorted(self.tracks)

    def get(self, music_id: str | None) -> MusicTrack | None:
        return self.tracks.get(music_id) if music_id else None

    def path(self, music_id: str) -> Path | None:
        """파일이 있으면 경로를, 목록에 없거나 파일이 없으면 None을 돌려준다."""
        track = self.get(music_id)
        if track is None:
            return None
        path = (self.root / track.file).resolve()
        return path if path.is_file() else None

    def available(self) -> list[MusicTrack]:
        """파일까지 있는 곡만. 프론트에 보여줄 선택지다."""
        return [t for t in self.tracks.values() if self.path(t.id) is not None]
