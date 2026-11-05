"""[1] 전처리: HEIC 변환, EXIF 회전, 시간순 정렬, 중복·흐린 사진 표시."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import cv2
import imagehash
import numpy as np
import pillow_heif
from PIL import Image, ImageOps

from .config import PreprocessSettings
from .errors import VlogError

pillow_heif.register_heif_opener()

SUPPORTED = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}
_EXIF_IFD = 0x8769
_DATETIME_ORIGINAL = 36867
_DATETIME = 306


@dataclass
class PreparedPhoto:
    photo_id: str
    source_path: Path
    path: Path
    analysis_path: Path
    sha256: str
    width: int
    height: int
    taken_at: datetime | None
    blur_score: float
    excluded_reason: str | None = None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_taken_at(image: Image.Image) -> datetime | None:
    exif = image.getexif()
    raw = exif.get_ifd(_EXIF_IFD).get(_DATETIME_ORIGINAL) or exif.get(_DATETIME)
    if not raw:
        return None
    try:
        return datetime.strptime(str(raw).strip("\x00 "), "%Y:%m:%d %H:%M:%S")
    except ValueError:
        return None


def blur_score(image: Image.Image) -> float:
    """라플라시안 분산. 크기의 영향을 줄이려고 같은 크기로 줄여서 잰다."""
    small = image.convert("L")
    small.thumbnail((1024, 1024))
    # cv2.imread는 한글 경로를 못 읽으므로 항상 PIL에서 넘긴다.
    return float(cv2.Laplacian(np.asarray(small), cv2.CV_64F).var())


def preprocess_folder(
    source_dir: Path, work_dir: Path, settings: PreprocessSettings
) -> list[PreparedPhoto]:
    files = sorted(
        p for p in source_dir.iterdir() if p.is_file() and p.suffix.lower() in SUPPORTED
    )
    if not files:
        raise VlogError(f"사진을 찾지 못했습니다: {source_dir}")

    loaded: list[tuple[datetime, Path, Image.Image, datetime | None]] = []
    for path in files:
        with Image.open(path) as opened:
            taken_at = read_taken_at(opened)
            image = ImageOps.exif_transpose(opened).convert("RGB")
        order_key = taken_at or datetime.fromtimestamp(path.stat().st_mtime)
        loaded.append((order_key, path, image, taken_at))
    loaded.sort(key=lambda item: (item[0], item[1].name))

    photos_dir = work_dir / "photos"
    analysis_dir = work_dir / "analysis"
    photos_dir.mkdir(parents=True, exist_ok=True)
    analysis_dir.mkdir(parents=True, exist_ok=True)

    prepared: list[PreparedPhoto] = []
    hashes: list[imagehash.ImageHash] = []
    for index, (_, path, image, taken_at) in enumerate(loaded, start=1):
        photo_id = f"p{index:02d}"
        out = photos_dir / f"{photo_id}.jpg"
        image.save(out, "JPEG", quality=settings.jpeg_quality)
        analysis = image.copy()
        analysis.thumbnail((settings.max_analysis_side, settings.max_analysis_side))
        analysis_path = analysis_dir / f"{photo_id}.jpg"
        analysis.save(analysis_path, "JPEG", quality=90)
        prepared.append(
            PreparedPhoto(
                photo_id=photo_id,
                source_path=path,
                path=out,
                analysis_path=analysis_path,
                sha256=sha256_file(out),
                width=image.width,
                height=image.height,
                taken_at=taken_at.replace(tzinfo=timezone.utc) if taken_at else None,
                blur_score=blur_score(image),
            )
        )
        hashes.append(imagehash.phash(image))

    _mark_duplicates(prepared, hashes, settings.phash_distance)
    _mark_blurry(prepared, settings.blur_threshold)
    return prepared


def _mark_duplicates(
    photos: list[PreparedPhoto], hashes: list[imagehash.ImageHash], max_distance: int
) -> None:
    """유사 사진 묶음에서 가장 선명한 한 장만 남긴다."""
    for i, photo in enumerate(photos):
        if photo.excluded_reason:
            continue
        for j in range(i + 1, len(photos)):
            other = photos[j]
            if other.excluded_reason or hashes[i] - hashes[j] > max_distance:
                continue
            if other.blur_score > photo.blur_score:
                photo.excluded_reason = f"duplicate_of:{other.photo_id}"
                break
            other.excluded_reason = f"duplicate_of:{photo.photo_id}"


def _mark_blurry(photos: list[PreparedPhoto], threshold: float) -> None:
    for photo in photos:
        if not photo.excluded_reason and photo.blur_score < threshold:
            photo.excluded_reason = f"blurry:{photo.blur_score:.1f}"
    # 전부 흐리면 쓸 사진이 없어지므로 가장 선명한 한 장은 남긴다.
    if all(p.excluded_reason for p in photos):
        blurry = [p for p in photos if (p.excluded_reason or "").startswith("blurry")]
        if blurry:
            max(blurry, key=lambda p: p.blur_score).excluded_reason = None
