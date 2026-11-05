"""컷 이미지 효과 목록과 프롬프트 조립."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ruamel.yaml import YAML


@dataclass
class ImageEffects:
    data: dict[str, Any]

    @classmethod
    def load(cls, path: Path) -> "ImageEffects":
        return cls(YAML(typ="safe").load(path.read_text(encoding="utf-8")) or {})

    @property
    def names(self) -> list[str]:
        return sorted(self.data.get("effects", {}))

    def is_valid(self, effect: str) -> bool:
        """목록의 이름이거나 공백이 있는 직접 쓴 문장이어야 한다. 이름 오타로 요금이 나가는 일을 막는다."""
        text = effect.strip()
        return text in self.data.get("effects", {}) or len(text.split()) > 1

    def image_prompt(self, effect: str, *, is_person: bool, face_hidden: bool) -> str:
        preset = self.data.get("effects", {}).get(effect.strip())
        parts = [self.data.get("base", ""), preset["image"] if preset else effect.strip()]
        if face_hidden:
            parts.append(self.data.get("face_hidden", ""))
        elif is_person:
            parts.append(self.data.get("person_face", ""))
        return " ".join(part for part in parts if part)

    def video_suffix(self, effect: str) -> str:
        preset = self.data.get("effects", {}).get(effect.strip())
        return preset.get("video", "") if preset else ""
