"""단계별 입력 해시. 값이 같으면 이전 결과를 재사용한다."""

from __future__ import annotations

import hashlib
import json
from typing import Any


def stable_hash(payload: Any) -> str:
    text = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def tts_input_hash(text: str, voice: dict[str, Any]) -> str:
    # 앞뒤 문장은 억양에만 쓰고 넣지 않는다. 컷 순서만 바꿨을 때 TTS를 다시 만들지 않기 위해서다.
    return stable_hash({"text": text, "voice": voice})


def clip_visual_key(
    *,
    provider: str,
    photo_sha256: str,
    crop_box: list[float],
    prompt: str,
    seed: int,
    camera_move: str,
    size: tuple[int, int],
    styled_image: str | None = None,
) -> str:
    """프레임 수를 뺀 영상 입력. 나레이션이 짧아졌을 때 기존 영상을 다시 쓰는 판단에 쓴다."""
    payload = {
        "provider": provider,
        "photo": photo_sha256,
        "crop": [round(v, 4) for v in crop_box],
        "prompt": prompt,
        "seed": seed,
        "move": camera_move,
        "size": list(size),
    }
    # 효과가 없는 컷은 값을 넣지 않아 기존 실행의 해시가 그대로 유지된다.
    if styled_image is not None:
        payload["styled_image"] = styled_image
    return stable_hash(payload)


def image_input_hash(
    *,
    photo_sha256: str,
    crop_box: list[float],
    prompt: str,
    provider: dict[str, Any],
    attempt: int,
) -> str:
    return stable_hash(
        {
            "photo": photo_sha256,
            "crop": [round(v, 4) for v in crop_box],
            "prompt": prompt,
            "provider": provider,
            "attempt": attempt,
        }
    )


def source_clip_hash(
    *, video_sha256: str, in_sec: float, out_sec: float, crop_box: list[float]
) -> str:
    """원본 영상 컷의 입력. 구간이나 크롭이 바뀌면 합성을 다시 한다."""
    return stable_hash(
        {
            "source_video": video_sha256,
            "in": round(in_sec, 3),
            "out": round(out_sec, 3),
            "crop": [round(v, 4) for v in crop_box],
        }
    )


def clip_input_hash(visual_key: str, frames: int) -> str:
    return stable_hash({"visual": visual_key, "frames": frames})


def compose_hash(parts: list[dict[str, Any]], settings: dict[str, Any]) -> str:
    return stable_hash({"cuts": parts, "settings": settings})
