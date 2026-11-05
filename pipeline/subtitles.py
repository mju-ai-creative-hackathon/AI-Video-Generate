"""타임스탬프를 어절로 묶어 ASS 자막을 만든다."""

from __future__ import annotations

from dataclasses import asdict, dataclass

from .config import ComposeSettings


@dataclass
class WordTiming:
    text: str
    start: float
    end: float

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class SubtitleEvent:
    text: str
    start: float
    end: float


def words_from_alignment(
    characters: list[str], starts: list[float], ends: list[float]
) -> list[WordTiming]:
    """글자별 시각을 공백 기준의 어절별 시각으로 묶는다."""
    words: list[WordTiming] = []
    buffer = ""
    start = 0.0
    end = 0.0
    for char, char_start, char_end in zip(characters, starts, ends):
        if char.isspace():
            if buffer:
                words.append(WordTiming(buffer, start, end))
                buffer = ""
            continue
        if not buffer:
            start = char_start
        buffer += char
        end = char_end
    if buffer:
        words.append(WordTiming(buffer, start, end))
    return words


def proportional_words(text: str, duration: float) -> list[WordTiming]:
    """타임스탬프가 없을 때 글자 수에 비례해 배분한다."""
    tokens = text.split()
    total = sum(len(t) for t in tokens)
    if not tokens or total == 0:
        return []
    words: list[WordTiming] = []
    cursor = 0.0
    for token in tokens:
        span = duration * len(token) / total
        words.append(WordTiming(token, round(cursor, 3), round(cursor + span, 3)))
        cursor += span
    return words


def chunk_words(words: list[WordTiming], max_chars: int, end_time: float) -> list[SubtitleEvent]:
    """어절을 한 번에 보여줄 묶음으로 나눈다. 각 묶음은 다음 묶음이 시작할 때까지 보인다."""
    groups: list[list[WordTiming]] = []
    current: list[WordTiming] = []
    length = 0
    for word in words:
        added = len(word.text) + (1 if current else 0)
        if current and length + added > max_chars:
            groups.append(current)
            current, length = [], 0
            added = len(word.text)
        current.append(word)
        length += added
    if current:
        groups.append(current)

    events: list[SubtitleEvent] = []
    for index, group in enumerate(groups):
        start = 0.0 if index == 0 else group[0].start
        end = groups[index + 1][0].start if index + 1 < len(groups) else end_time
        events.append(SubtitleEvent(" ".join(w.text for w in group), start, max(end, start + 0.05)))
    return events


def _timestamp(seconds: float) -> str:
    centis = int(round(max(seconds, 0.0) * 100))
    hours, centis = divmod(centis, 360000)
    minutes, centis = divmod(centis, 6000)
    secs, centis = divmod(centis, 100)
    return f"{hours}:{minutes:02d}:{secs:02d}.{centis:02d}"


def _escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("{", r"\{").replace("}", r"\}").replace("\n", r"\N")


def build_ass(
    events: list[SubtitleEvent],
    on_screen_text: str | None,
    duration: float,
    cfg: ComposeSettings,
    font_name: str,
) -> str:
    """나레이션은 플랫폼 UI를 피해 하단 위쪽에, 화면 문구는 강조 스타일로 상단에 둔다."""
    narration_margin = int(cfg.height * cfg.narration_margin_ratio)
    emphasis_margin = int(cfg.height * cfg.emphasis_margin_ratio)
    style_format = (
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
        "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding"
    )
    lines = [
        "[Script Info]",
        "ScriptType: v4.00+",
        f"PlayResX: {cfg.width}",
        f"PlayResY: {cfg.height}",
        "WrapStyle: 0",
        "ScaledBorderAndShadow: yes",
        "",
        "[V4+ Styles]",
        style_format,
        f"Style: Narration,{font_name},{cfg.narration_font_size},&H00FFFFFF,&H00FFFFFF,&H00000000,"
        f"&H64000000,-1,0,0,0,100,100,0,0,1,5,1,2,80,80,{narration_margin},1",
        f"Style: Emphasis,{font_name},{cfg.emphasis_font_size},&H0000D8FF,&H0000D8FF,&H00000000,"
        f"&H64000000,-1,0,0,0,100,100,0,0,1,6,1,8,70,70,{emphasis_margin},1",
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    if on_screen_text:
        lines.append(
            f"Dialogue: 1,{_timestamp(0)},{_timestamp(duration)},Emphasis,,0,0,0,,"
            f"{_escape(on_screen_text)}"
        )
    for event in events:
        lines.append(
            f"Dialogue: 0,{_timestamp(event.start)},{_timestamp(min(event.end, duration))},"
            f"Narration,,0,0,0,,{_escape(event.text)}"
        )
    return "\n".join(lines) + "\n"
