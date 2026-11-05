"""[1] 전처리: HEIC 변환, EXIF 회전, 시간순 정렬, 중복·흐린 사진 표시."""

from __future__ import annotations

import hashlib
import re
import shutil
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import cv2
import imagehash
import numpy as np
import pillow_heif
from PIL import Image, ImageOps

from .config import PreprocessSettings
from .errors import MediaError, VlogError
from .media import extract_frame, probe

pillow_heif.register_heif_opener()

SUPPORTED = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}
VIDEO_SUPPORTED = {".mp4", ".mov", ".m4v", ".webm", ".mkv"}
NUMBER_PREFIX = re.compile(r"^(\d+)")
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
    media_type: str = "image"
    video_path: Path | None = None
    media_duration: float | None = None
    has_audio: bool = False
    note: str | None = None
    audio_mode: str | None = None


@dataclass
class MediaItem:
    """제출된 사진 또는 영상 하나. 목록의 순서가 곧 컷 순서다."""

    source_path: Path
    note: str | None = None
    audio_mode: str | None = None


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


def list_photos(source_dir: Path) -> list[Path]:
    files = sorted(
        p for p in source_dir.iterdir() if p.is_file() and p.suffix.lower() in SUPPORTED
    )
    if not files:
        raise VlogError(f"사진을 찾지 못했습니다: {source_dir}")
    return files


def numbered_photos(source_dir: Path) -> dict[Path, int]:
    """파일 이름 앞의 숫자를 읽는다. 번호가 없거나 겹치면 어떤 파일인지 알려주고 멈춘다."""
    numbers: dict[Path, int] = {}
    missing: list[str] = []
    owners: dict[int, list[str]] = {}
    for path in list_photos(source_dir):
        match = NUMBER_PREFIX.match(path.name)
        if match is None:
            missing.append(path.name)
            continue
        number = int(match.group(1))
        numbers[path] = number
        owners.setdefault(number, []).append(path.name)
    problems = []
    if missing:
        problems.append(f"번호가 없는 파일: {', '.join(missing)}")
    for number, names in sorted(owners.items()):
        if len(names) > 1:
            problems.append(f"번호 {number}이 겹치는 파일: {', '.join(names)}")
    if problems:
        raise VlogError(
            "--order number를 쓰려면 파일 이름이 서로 다른 숫자로 시작해야 합니다 "
            "(예: 1.jpg, 2_카페.png). " + " / ".join(problems)
        )
    return numbers


def preprocess_folder(
    source_dir: Path, work_dir: Path, settings: PreprocessSettings, *, order: str = "time"
) -> list[PreparedPhoto]:
    """order="number"면 파일 이름 번호로 정렬하고, 사람이 고른 사진이므로 자동 제외하지 않는다."""
    files = list_photos(source_dir)
    numbers = numbered_photos(source_dir) if order == "number" else None

    loaded: list[tuple[datetime, Path, Image.Image, datetime | None]] = []
    for path in files:
        with Image.open(path) as opened:
            taken_at = read_taken_at(opened)
            image = ImageOps.exif_transpose(opened).convert("RGB")
        order_key = taken_at or datetime.fromtimestamp(path.stat().st_mtime)
        loaded.append((order_key, path, image, taken_at))
    loaded.sort(key=lambda item: (item[0], item[1].name))
    if numbers is not None:
        loaded.sort(key=lambda item: numbers[item[1]])

    photos_dir = work_dir / "photos"
    analysis_dir = work_dir / "analysis"
    photos_dir.mkdir(parents=True, exist_ok=True)
    analysis_dir.mkdir(parents=True, exist_ok=True)

    prepared: list[PreparedPhoto] = []
    hashes: list[imagehash.ImageHash] = []
    for index, (_, path, image, taken_at) in enumerate(loaded, start=1):
        # 번호 순서면 파일 번호를 그대로 써서 스토리보드에서 바로 알아볼 수 있게 한다.
        photo_id = f"p{numbers[path]:02d}" if numbers is not None else f"p{index:02d}"
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

    if numbers is None:
        _mark_duplicates(prepared, hashes, settings.phash_distance)
        _mark_blurry(prepared, settings.blur_threshold)
    return prepared


def prepare_media(
    items: list[MediaItem], work_dir: Path, settings: PreprocessSettings
) -> list[PreparedPhoto]:
    """제출된 순서대로 p01, p02를 붙인다. 사람이 고른 자료이므로 자동으로 빼지 않는다."""
    photos_dir = work_dir / "photos"
    analysis_dir = work_dir / "analysis"
    photos_dir.mkdir(parents=True, exist_ok=True)
    analysis_dir.mkdir(parents=True, exist_ok=True)
    prepared: list[PreparedPhoto] = []
    for index, item in enumerate(items, start=1):
        photo_id = f"p{index:02d}"
        if item.source_path.suffix.lower() in VIDEO_SUPPORTED:
            prepared.append(_prepare_video(item, photo_id, work_dir, settings))
        else:
            prepared.append(_prepare_image(item, photo_id, work_dir, settings))
    return prepared


def _save_analysis_copy(image: Image.Image, path: Path, settings: PreprocessSettings) -> None:
    copy = image.copy()
    copy.thumbnail((settings.max_analysis_side, settings.max_analysis_side))
    copy.save(path, "JPEG", quality=90)


def _prepare_image(
    item: MediaItem, photo_id: str, work_dir: Path, settings: PreprocessSettings
) -> PreparedPhoto:
    with Image.open(item.source_path) as opened:
        taken_at = read_taken_at(opened)
        image = ImageOps.exif_transpose(opened).convert("RGB")
    out = work_dir / "photos" / f"{photo_id}.jpg"
    image.save(out, "JPEG", quality=settings.jpeg_quality)
    analysis_path = work_dir / "analysis" / f"{photo_id}.jpg"
    _save_analysis_copy(image, analysis_path, settings)
    return PreparedPhoto(
        photo_id=photo_id,
        source_path=item.source_path,
        path=out,
        analysis_path=analysis_path,
        sha256=sha256_file(out),
        width=image.width,
        height=image.height,
        taken_at=taken_at.replace(tzinfo=timezone.utc) if taken_at else None,
        blur_score=blur_score(image),
        note=item.note,
        audio_mode=item.audio_mode,
    )


def _prepare_video(
    item: MediaItem, photo_id: str, work_dir: Path, settings: PreprocessSettings
) -> PreparedPhoto:
    info = probe(item.source_path)
    if not info.has_video or info.duration <= 0:
        raise MediaError(f"영상을 읽을 수 없습니다: {item.source_path.name}")
    # 작업 폴더 안에 있는 업로드 파일은 다시 복사하지 않는다.
    video_path = item.source_path
    if not item.source_path.resolve().is_relative_to(work_dir.resolve()):
        video_dir = work_dir / "videos"
        video_dir.mkdir(parents=True, exist_ok=True)
        video_path = video_dir / f"{photo_id}{item.source_path.suffix.lower()}"
        shutil.copy2(item.source_path, video_path)
    # 대표 프레임은 AI가 구간을 고른 뒤 그 구간의 가운데 프레임으로 바뀐다.
    out = work_dir / "photos" / f"{photo_id}.jpg"
    image = extract_frame(video_path, info.duration / 2, out)
    analysis_path = work_dir / "analysis" / f"{photo_id}.jpg"
    _save_analysis_copy(image, analysis_path, settings)
    return PreparedPhoto(
        photo_id=photo_id,
        source_path=item.source_path,
        path=out,
        analysis_path=analysis_path,
        sha256=sha256_file(video_path),
        width=image.width,  # 회전이 적용된 크기
        height=image.height,
        taken_at=None,
        blur_score=0.0,
        media_type="video",
        video_path=video_path,
        media_duration=info.duration,
        has_audio=info.has_audio,
        note=item.note,
        audio_mode=item.audio_mode,
    )


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
