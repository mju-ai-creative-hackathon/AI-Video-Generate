"""사람이 검토할 썸네일 시트를 만든다."""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

from .schemas import PhotoRecord, Storyboard

PANEL_W = 420
PREVIEW_H = 420
TEXT_H = 240
GAP = 14
COLUMNS = 4
BG = (24, 24, 28)
FG = (240, 240, 240)
MUTED = (170, 170, 178)
ACCENT = (255, 196, 0)
OPTION_COLORS = [(255, 196, 0), (64, 200, 255), (120, 230, 140)]
WARN = (255, 120, 100)


def find_font_file(fonts_dir: Path | None = None) -> Path | None:
    candidates: list[Path] = []
    if fonts_dir and fonts_dir.is_dir():
        candidates += sorted(fonts_dir.glob("Pretendard*.otf"))
        candidates += sorted(fonts_dir.glob("Pretendard*.ttf"))
    candidates += [
        Path(r"C:\Windows\Fonts\malgun.ttf"),
        Path("/usr/share/fonts/truetype/nanum/NanumGothic.ttf"),
    ]
    return next((p for p in candidates if p.exists()), None)


def _font(path: Path | None, size: int) -> ImageFont.ImageFont | ImageFont.FreeTypeFont:
    if path is None:
        return ImageFont.load_default()
    return ImageFont.truetype(str(path), size)


def _wrap(text: str, per_line: int, max_lines: int) -> list[str]:
    lines = [text[i : i + per_line] for i in range(0, len(text), per_line)] or [""]
    if len(lines) > max_lines:
        lines = lines[:max_lines]
        lines[-1] = lines[-1][:-1] + "…"
    return lines


def make_storyboard_sheet(
    storyboard: Storyboard,
    version: int,
    photos: list[PhotoRecord],
    out_path: Path,
    fonts_dir: Path | None = None,
) -> Path:
    """컷 번호, 사진, 크롭 후보, 나레이션, 화면 문구를 한 장에 보여준다."""
    cuts = storyboard.ordered_cuts()
    by_photo = {p.photo_id: p for p in photos}
    font_path = find_font_file(fonts_dir)
    title_font = _font(font_path, 34)
    body_font = _font(font_path, 22)
    small_font = _font(font_path, 18)

    rows = max(1, math.ceil(len(cuts) / COLUMNS))
    header = 76
    width = COLUMNS * PANEL_W + (COLUMNS + 1) * GAP
    height = header + rows * (PREVIEW_H + TEXT_H + GAP) + GAP
    sheet = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(sheet)
    draw.text(
        (GAP, 18),
        f"{storyboard.title}  (v{version}, {storyboard.format_id}, {len(cuts)}컷, "
        f"{sum(c.target_sec for c in cuts):.1f}초)",
        fill=FG,
        font=title_font,
    )

    for index, cut in enumerate(cuts):
        col, row = index % COLUMNS, index // COLUMNS
        x = GAP + col * (PANEL_W + GAP)
        y = header + row * (PREVIEW_H + TEXT_H + GAP)
        photo = by_photo.get(cut.photo_id)
        if photo is not None and photo.path.exists():
            _draw_preview(sheet, draw, photo.path, cut, x, y, small_font)
        else:
            draw.rectangle((x, y, x + PANEL_W, y + PREVIEW_H), outline=WARN, width=2)
            draw.text((x + 12, y + 12), f"사진 없음: {cut.photo_id}", fill=WARN, font=body_font)

        ty = y + PREVIEW_H + 8
        draw.text(
            (x, ty),
            f"#{cut.position} {cut.cut_id} · {cut.photo_id} · {cut.role} · {cut.target_sec}초",
            fill=ACCENT,
            font=small_font,
        )
        ty += 26
        draw.text(
            (x, ty),
            f"{cut.shot.shot_size} / {cut.shot.camera_move} / 크롭 {cut.shot.crop_choice}"
            f" ({cut.crop_source})",
            fill=MUTED,
            font=small_font,
        )
        ty += 28
        for line in _wrap(cut.narration, 18, 4):
            draw.text((x, ty), line, fill=FG, font=body_font)
            ty += 28
        if cut.shot.on_screen_text:
            draw.text((x, ty), f"[문구] {cut.shot.on_screen_text}", fill=ACCENT, font=small_font)
            ty += 24
        if cut.flags:
            draw.text((x, ty), ", ".join(cut.flags)[:44], fill=WARN, font=small_font)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out_path, "JPEG", quality=90)
    return out_path


def _draw_preview(
    sheet: Image.Image,
    draw: ImageDraw.ImageDraw,
    photo_path: Path,
    cut,  # noqa: ANN001
    x: int,
    y: int,
    font,  # noqa: ANN001
) -> None:
    """왼쪽은 원본과 크롭 후보, 오른쪽은 선택한 크롭의 실제 모습이다."""
    with Image.open(photo_path) as opened:
        image = opened.convert("RGB")

    crop_w = int(PREVIEW_H * 9 / 16)
    source_w = PANEL_W - crop_w - 6
    source = ImageOps.contain(image, (source_w, PREVIEW_H))
    sheet.paste(source, (x, y))
    for number, option in enumerate(cut.shot.crop_options, start=1):
        color = OPTION_COLORS[(number - 1) % len(OPTION_COLORS)]
        selected = number == cut.shot.crop_choice
        rect = (
            x + option[0] * source.width,
            y + option[1] * source.height,
            x + option[2] * source.width,
            y + option[3] * source.height,
        )
        draw.rectangle(rect, outline=color, width=4 if selected else 1)
        draw.text((rect[0] + 4, rect[1] + 2), str(number), fill=color, font=font)

    box = cut.shot.crop_box
    cropped = image.crop(
        (
            int(box[0] * image.width),
            int(box[1] * image.height),
            int(box[2] * image.width),
            int(box[3] * image.height),
        )
    )
    preview = ImageOps.fit(cropped, (crop_w, PREVIEW_H))
    sheet.paste(preview, (x + PANEL_W - crop_w, y))
