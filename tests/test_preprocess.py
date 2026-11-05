"""전처리: 회전, 정렬, 중복·흐림, 분석용 사본."""

from __future__ import annotations

from datetime import datetime

import pillow_heif
import pytest
from conftest import noisy_image
from PIL import Image, ImageFilter

from pipeline.config import PreprocessSettings
from pipeline.errors import VlogError
from pipeline.preprocess import preprocess_folder


def _save_with_exif(image: Image.Image, path, *, taken: str | None = None, orientation: int | None = None):  # noqa: ANN001, ANN202
    exif = Image.Exif()
    if orientation:
        exif[0x0112] = orientation
    if taken:
        exif.get_ifd(0x8769)[36867] = taken
    image.save(path, "JPEG", quality=95, exif=exif)


def test_sorted_by_taken_time_not_filename(tmp_path):
    source = tmp_path / "src"
    source.mkdir()
    _save_with_exif(noisy_image(1), source / "a.jpg", taken="2026:05:02 10:00:00")
    _save_with_exif(noisy_image(2), source / "b.jpg", taken="2026:05:01 10:00:00")
    photos = preprocess_folder(source, tmp_path / "work", PreprocessSettings())
    assert [p.source_path.name for p in photos] == ["b.jpg", "a.jpg"]
    assert photos[0].photo_id == "p01"
    assert photos[0].taken_at.replace(tzinfo=None) == datetime(2026, 5, 1, 10, 0)


def test_exif_rotation_is_applied(tmp_path):
    source = tmp_path / "src"
    source.mkdir()
    _save_with_exif(noisy_image(1, (1600, 1200)), source / "a.jpg", orientation=6)
    photo = preprocess_folder(source, tmp_path / "work", PreprocessSettings())[0]
    assert (photo.width, photo.height) == (1200, 1600)
    with Image.open(photo.path) as saved:
        assert saved.size == (1200, 1600)


def test_analysis_copy_is_limited(tmp_path):
    source = tmp_path / "src"
    source.mkdir()
    noisy_image(1, (3000, 2000)).save(source / "a.png")
    photo = preprocess_folder(source, tmp_path / "work", PreprocessSettings())[0]
    with Image.open(photo.analysis_path) as saved:
        assert max(saved.size) == 1568
    assert photo.width == 3000  # 원본 크기 사본은 유지


def test_heic_is_converted(tmp_path):
    source = tmp_path / "src"
    source.mkdir()
    try:
        heif = pillow_heif.from_pillow(noisy_image(1, (800, 600)))
        heif.save(source / "a.heic", quality=90)
    except (ValueError, RuntimeError) as exc:
        pytest.skip(f"HEIC 인코더 없음: {exc}")
    photo = preprocess_folder(source, tmp_path / "work", PreprocessSettings())[0]
    assert photo.path.suffix == ".jpg"
    with Image.open(photo.path) as saved:
        assert saved.format == "JPEG"


def test_duplicate_keeps_sharper(tmp_path):
    source = tmp_path / "src"
    source.mkdir()
    image = noisy_image(1)
    image.filter(ImageFilter.GaussianBlur(1)).save(source / "a_soft.png")
    image.save(source / "b_sharp.png")
    noisy_image(9).save(source / "c.png")
    photos = preprocess_folder(source, tmp_path / "work", PreprocessSettings())
    by_name = {p.source_path.name: p for p in photos}
    assert by_name["b_sharp.png"].excluded_reason is None
    assert by_name["a_soft.png"].excluded_reason == f"duplicate_of:{by_name['b_sharp.png'].photo_id}"
    assert by_name["c.png"].excluded_reason is None


def test_blurry_photo_is_marked(tmp_path):
    source = tmp_path / "src"
    source.mkdir()
    noisy_image(1).filter(ImageFilter.GaussianBlur(25)).save(source / "blur.png")
    noisy_image(2).save(source / "sharp.png")
    photos = {p.source_path.name: p for p in preprocess_folder(source, tmp_path / "w", PreprocessSettings())}
    assert photos["blur.png"].excluded_reason.startswith("blurry:")
    assert photos["sharp.png"].excluded_reason is None


def test_all_blurry_keeps_one(tmp_path):
    source = tmp_path / "src"
    source.mkdir()
    noisy_image(1).filter(ImageFilter.GaussianBlur(25)).save(source / "a.png")
    noisy_image(2).filter(ImageFilter.GaussianBlur(25)).save(source / "b.png")
    photos = preprocess_folder(source, tmp_path / "w", PreprocessSettings())
    assert sum(p.excluded_reason is None for p in photos) == 1


def test_empty_folder_is_an_error(tmp_path):
    with pytest.raises(VlogError):
        preprocess_folder(tmp_path, tmp_path / "w", PreprocessSettings())
