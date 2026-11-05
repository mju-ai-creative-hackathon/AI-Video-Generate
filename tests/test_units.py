"""프레임 계산, 입력 해시, 자막, 합성 필터 단위 테스트."""

from __future__ import annotations

import pytest

from pipeline.compose import ComposeCut, build_cut_filter, build_final_audio_filter, frame_exact
from pipeline.config import ComposeSettings, TimingSettings
from pipeline.frames import plan_timing, round_up_frames
from pipeline.hashing import clip_input_hash, clip_visual_key, compose_hash, tts_input_hash
from pipeline.media import filter_path
from pipeline.subtitles import (
    SubtitleEvent,
    WordTiming,
    build_ass,
    chunk_words,
    proportional_words,
    words_from_alignment,
)


@pytest.mark.parametrize(
    ("frames", "expected"), [(33, 33), (34, 37), (36, 37), (37, 37), (80, 81), (81, 81), (1, 1)]
)
def test_round_up_frames_follows_4n_plus_1(frames, expected):
    assert round_up_frames(frames) == expected


class TestPlanTiming:
    cfg = TimingSettings()

    def test_short_audio_uses_minimum_frames(self):
        timing = plan_timing(0.5, self.cfg)
        assert timing.frames == 33
        assert timing.speed == 1.0
        assert timing.duration_sec == 2.0  # 최소 컷 길이

    def test_generates_only_needed_frames(self):
        timing = plan_timing(3.0, self.cfg)  # 3.25초 × 16fps = 52 → 53
        assert timing.frames == 53
        assert (timing.frames - 1) % 4 == 0
        assert timing.duration_sec == 3.25
        assert not timing.too_long

    def test_up_to_five_seconds_is_not_slowed(self):
        timing = plan_timing(4.75, self.cfg)
        assert timing.frames == 81
        assert timing.speed == 1.0

    def test_between_five_and_six_seconds_is_slowed(self):
        timing = plan_timing(5.5, self.cfg)  # 5.75초 / 5.0625초
        assert timing.frames == 81
        assert 1.0 < timing.speed <= 1.2
        assert not timing.too_long

    def test_over_six_seconds_needs_shortening(self):
        assert plan_timing(6.0, self.cfg).too_long


class TestHashes:
    voice = {"provider": "x", "voice_id": "a"}

    def _visual(self, **overrides):
        values = {
            "provider": "comfyui",
            "photo_sha256": "abc",
            "crop_box": [0.1, 0.1, 0.5, 0.9],
            "prompt": "push in",
            "seed": 1,
            "camera_move": "slow_push_in",
            "size": (480, 832),
        }
        return clip_visual_key(**{**values, **overrides})

    def test_tts_hash_changes_with_text_and_voice(self):
        base = tts_input_hash("문장", self.voice)
        assert base == tts_input_hash("문장", dict(self.voice))
        assert base != tts_input_hash("다른 문장", self.voice)
        assert base != tts_input_hash("문장", {**self.voice, "voice_id": "b"})

    @pytest.mark.parametrize(
        "override",
        [
            {"photo_sha256": "zzz"},
            {"crop_box": [0.2, 0.1, 0.5, 0.9]},
            {"prompt": "pan"},
            {"seed": 2},
            {"camera_move": "static"},
            {"provider": "kenburns"},
        ],
    )
    def test_clip_key_changes_with_each_input(self, override):
        assert self._visual() != self._visual(**override)

    def test_clip_hash_changes_with_frames(self):
        visual = self._visual()
        assert clip_input_hash(visual, 33) != clip_input_hash(visual, 37)

    def test_compose_hash_changes_with_order(self):
        a, b = {"clip": "a"}, {"clip": "b"}
        assert compose_hash([a, b], {}) != compose_hash([b, a], {})


class TestSubtitles:
    def test_alignment_groups_characters_into_words(self):
        chars = list("혼자 떠난")
        starts = [0.0, 0.1, 0.2, 0.3, 0.4]
        ends = [0.1, 0.2, 0.3, 0.4, 0.5]
        words = words_from_alignment(chars, starts, ends)
        assert [w.text for w in words] == ["혼자", "떠난"]
        assert words[1].start == 0.3
        assert words[1].end == 0.5

    def test_proportional_fallback_spans_duration(self):
        words = proportional_words("하나 둘셋", 3.0)
        assert words[0].end == pytest.approx(1.2)
        assert words[-1].end == pytest.approx(3.0)

    def test_chunks_cover_whole_cut_without_gaps(self):
        words = [WordTiming("가" * 6, i * 0.5, i * 0.5 + 0.5) for i in range(4)]
        events = chunk_words(words, 14, 3.0)
        assert len(events) == 2
        assert events[0].start == 0.0
        assert events[0].end == events[1].start
        assert events[-1].end == 3.0

    def test_long_single_word_is_kept(self):
        events = chunk_words([WordTiming("가" * 30, 0, 1)], 14, 1.0)
        assert len(events) == 1

    def test_ass_positions_and_escapes(self):
        cfg = ComposeSettings()
        text = build_ass([SubtitleEvent("{위험}", 0.0, 1.0)], "제목", 2.0, cfg, "Pretendard")
        assert r"\{위험\}" in text
        assert ",2,80,80,576,1" in text  # 나레이션: 하단에서 30% 위
        assert ",8,70,70,268,1" in text  # 화면 문구: 상단
        assert "Emphasis,,0,0,0,,제목" in text


class TestComposeFilters:
    cfg = ComposeSettings()

    def _cut(self, **overrides):
        values = {
            "cut_id": "c1",
            "clip_path": "a.mp4",
            "audio_path": "a.wav",
            "duration_sec": 3.0,
            "speed": 1.0,
            "events": [],
            "on_screen_text": None,
            "transition_in": "hard_cut",
        }
        return ComposeCut(**{**values, **overrides})

    def test_frame_exact_rounds_up(self):
        assert frame_exact(3.01, 30) == pytest.approx(91 / 30)

    def test_filter_does_not_stretch_and_keeps_length(self):
        graph, duration = build_cut_filter(self._cut(), self.cfg, "c.ass", None)
        assert "force_original_aspect_ratio=increase" in graph
        assert "crop=1080:1920" in graph
        assert "trim=duration=3.000" in graph
        assert f"apad=whole_dur={duration:.3f}" in graph
        assert "fade" not in graph

    def test_fade_does_not_shorten_cut(self):
        graph, duration = build_cut_filter(
            self._cut(transition_in="fade"), self.cfg, "c.ass", None
        )
        assert "fade=t=in:st=0:d=0.300" in graph
        assert duration == 3.0

    def test_slowdown_is_applied(self):
        graph, _ = build_cut_filter(self._cut(speed=1.15), self.cfg, "c.ass", None)
        assert "setpts=1.1500*PTS" in graph

    def test_bgm_ducks_under_narration(self):
        assert "sidechaincompress" not in build_final_audio_filter(self.cfg, False)
        graph = build_final_audio_filter(self.cfg, True)
        assert "sidechaincompress" in graph
        assert "loudnorm=I=-14.0" in graph

    def test_filter_path_escapes_windows_drive(self):
        assert filter_path("F:\\Code\\a b\\x.ass") == r"F\:/Code/a b/x.ass"
