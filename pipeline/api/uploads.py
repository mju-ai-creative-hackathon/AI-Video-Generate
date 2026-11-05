"""업로드 저장과 검증. 저장 이름은 서버가 만들고, 실제 내용까지 확인한다."""

from __future__ import annotations

from pathlib import Path

from fastapi import UploadFile
from PIL import Image

from ..config import ApiSettings
from ..errors import MediaError, UploadRejectedError, UploadTooLargeError
from ..media import probe
from ..preprocess import SUPPORTED, VIDEO_SUPPORTED

_CHUNK = 1024 * 1024


def _extension(upload: UploadFile) -> str:
    name = upload.filename or ""
    suffix = Path(name).suffix.lower()
    if suffix not in SUPPORTED | VIDEO_SUPPORTED:
        raise UploadRejectedError(f"지원하지 않는 파일입니다: {name or '(이름 없음)'}")
    return suffix


async def save_upload(upload: UploadFile, target: Path, cfg: ApiSettings) -> None:
    limit = cfg.max_file_mb * 1024 * 1024
    written = 0
    with target.open("wb") as handle:
        while chunk := await upload.read(_CHUNK):
            written += len(chunk)
            if written > limit:
                raise UploadTooLargeError(
                    f"파일이 {cfg.max_file_mb}MB를 넘습니다: {upload.filename}"
                )
            handle.write(chunk)
    if written == 0:
        raise UploadRejectedError(f"빈 파일입니다: {upload.filename}")


def verify_media(path: Path, display_name: str, cfg: ApiSettings) -> None:
    """확장자만 믿지 않고 실제로 열어 본다."""
    if path.suffix.lower() in VIDEO_SUPPORTED:
        try:
            info = probe(path)
        except (MediaError, ValueError) as exc:
            raise UploadRejectedError(f"영상을 읽을 수 없습니다: {display_name}") from exc
        if not info.has_video or info.duration <= 0:
            raise UploadRejectedError(f"영상을 읽을 수 없습니다: {display_name}")
        if info.duration > cfg.max_video_sec:
            raise UploadTooLargeError(
                f"영상이 {cfg.max_video_sec:.0f}초를 넘습니다: {display_name}"
            )
        return
    try:
        with Image.open(path) as image:
            image.verify()
    except Exception as exc:  # noqa: BLE001 - Pillow는 형식마다 다른 예외를 던진다
        raise UploadRejectedError(f"이미지를 읽을 수 없습니다: {display_name}") from exc


async def store_uploads(
    uploads: list[UploadFile], directory: Path, cfg: ApiSettings
) -> list[Path]:
    """순서대로 저장하고 경로를 돌려준다. 원본 파일 이름은 경로에 쓰지 않는다."""
    if not uploads:
        raise UploadRejectedError("파일이 없습니다.")
    if len(uploads) > cfg.max_files:
        raise UploadTooLargeError(f"파일은 {cfg.max_files}개까지 제출할 수 있습니다.")
    suffixes = [_extension(upload) for upload in uploads]
    directory.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for index, (upload, suffix) in enumerate(zip(uploads, suffixes), start=1):
        target = directory / f"{index:03d}{suffix}"
        await save_upload(upload, target, cfg)
        verify_media(target, upload.filename or target.name, cfg)
        paths.append(target)
    return paths
