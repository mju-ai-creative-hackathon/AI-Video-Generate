"""포맷 매칭, 구도 계산, 구도 검색, 초안 생성."""

from __future__ import annotations

from pathlib import Path

import pytest
from conftest import analysis

from pipeline.config import RagSettings
from pipeline.db.models import DatasetSource, FramingExample
from pipeline.db.repo import session_scope
from pipeline.errors import FormatMatchError
from pipeline.format_match import (
    allocate_seconds,
    assign_photos,
    expand_slots,
    match_format,
    select_format,
    slot_fit,
)
from pipeline.rag.framing import (
    ASPECT,
    CropPlanner,
    FramingQuery,
    PhotoGeom,
    RetrievedExample,
    check_crop,
    compute_crop,
    crop_box_errors,
    layout_vector,
    rule_based_crops,
    visible_ratio,
)
from pipeline.rag.retrieve import FramingRetriever
from pipeline.schemas import PhotoAnalysis, PhotoRecord


def photo(index: int, subject: str = "음식", mood: str = "잔잔함") -> PhotoRecord:
    return PhotoRecord(
        photo_id=f"p{index:02d}",
        path=Path("x.jpg"),
        width=1600,
        height=1200,
        sha256=str(index),
        analysis=PhotoAnalysis.model_validate(analysis(subject, mood)),
    )


class TestFormatMatch:
    def test_templates_load_with_expected_fields(self, formats):
        assert {"question_hook_v1", "day_log_v1", "detail_focus_v1"} <= set(formats)
        for template in formats.values():
            assert template.slots[0].role == "hook"
            assert template.slots[-1].role == "ending"

    def test_fit_prefers_matching_subject(self, formats):
        slot = formats["detail_focus_v1"].slot("detail")
        assert slot_fit(photo(1, "음식"), slot) > slot_fit(photo(2, "풍경"), slot)

    def test_few_photos_are_reused(self, formats):
        match = match_format(formats["question_hook_v1"], [photo(1), photo(2)])
        assert len(match.assignments) > 2
        assert any(a.reused for a in match.assignments)
        assert match.reuse_ratio > 0

    def test_many_photos_grow_repeat_slots(self, formats):
        template = formats["day_log_v1"]
        few = len(expand_slots(template, 5))
        many = len(expand_slots(template, 10))
        assert many > few

    def test_extra_photos_are_dropped_by_fit(self, formats):
        template = formats["detail_focus_v1"]
        photos = [photo(i, "음식") for i in range(1, 15)] + [photo(15, "풍경")]
        match = match_format(template, photos)
        assert match.dropped_photos

    def test_assignment_keeps_time_order(self, formats):
        slots = expand_slots(formats["day_log_v1"], 8)
        chosen = assign_photos(slots, [photo(i) for i in range(1, 9)])
        assert chosen == sorted(chosen)
        assert len(set(chosen)) == len(chosen)  # 사진이 충분하면 재사용하지 않는다

    def test_length_stays_inside_format_range(self, formats):
        for template in formats.values():
            for n in (1, 3, 8, 20):
                slots = expand_slots(template, n)
                total = sum(allocate_seconds(slots, template.total_sec))
                assert total <= template.total_sec.max + 0.01
                for slot, sec in zip(slots, allocate_seconds(slots, template.total_sec)):
                    assert slot.sec.min <= sec <= slot.sec.max

    def test_required_slot_without_fit_warns(self, formats):
        match = match_format(formats["detail_focus_v1"], [photo(1, "풍경", "무서움")])
        assert any("대신 배정" in w for w in match.warnings)

    def test_low_overall_fit_suggests_other_format(self, formats):
        match = select_format(
            formats, [photo(1, "풍경", "무서움")], forced="detail_focus_v1", min_format_fit=0.9
        )
        assert any("다른 포맷" in w for w in match.warnings)

    def test_picks_best_format(self, formats):
        foods = [photo(i, "음식", "따뜻함") for i in range(1, 7)]
        assert select_format(formats, foods).template.format_id == "detail_focus_v1"

    def test_forced_unknown_format_is_error(self, formats):
        with pytest.raises(FormatMatchError):
            select_format(formats, [photo(1)], forced="nope")

    def test_no_photos_is_error(self, formats):
        with pytest.raises(FormatMatchError):
            match_format(formats["day_log_v1"], [])


class TestFraming:
    geom = PhotoGeom(1600, 1200, subject_box=[0.4, 0.4, 0.6, 0.6], subject_type="음식")

    def _aspect(self, geom, crop):  # noqa: ANN001, ANN202
        return ((crop[2] - crop[0]) * geom.width) / ((crop[3] - crop[1]) * geom.height)

    def test_relative_values_become_crop(self):
        crop = compute_crop(self.geom, (0.5, 0.5), 0.45)
        assert self._aspect(self.geom, crop) == pytest.approx(ASPECT, abs=0.01)
        # 피사체 높이 240px / 0.45 = 533px 높이의 크롭
        assert (crop[3] - crop[1]) * 1200 == pytest.approx(533, abs=2)
        # 피사체 중심이 프레임 중앙에 온다
        assert (crop[0] + crop[2]) / 2 == pytest.approx(0.5, abs=0.01)

    def test_crop_is_clamped_inside_photo(self):
        geom = PhotoGeom(1600, 1200, subject_box=[0.0, 0.0, 0.1, 0.1])
        crop = compute_crop(geom, (0.5, 0.5), 0.3)
        assert crop[0] == 0.0
        assert crop[1] == 0.0
        assert not crop_box_errors(crop)

    def test_landscape_wide_uses_full_height(self):
        crop = compute_crop(self.geom, (0.5, 0.5), None)
        assert crop[1] == 0.0
        assert crop[3] == 1.0
        assert self._aspect(self.geom, crop) == pytest.approx(ASPECT, abs=0.01)

    def test_portrait_photo_fits_width(self):
        geom = PhotoGeom(1000, 3000, subject_box=[0.4, 0.4, 0.6, 0.6])
        crop = compute_crop(geom, (0.5, 0.5), None)
        assert crop[0] == 0.0
        assert crop[2] == 1.0
        assert self._aspect(geom, crop) == pytest.approx(ASPECT, abs=0.01)

    @pytest.mark.parametrize(
        "box",
        [[0.1, 0.1, 0.5], [0.5, 0.1, 0.2, 0.9], [-0.1, 0, 0.5, 1], [0, 0, 1.2, 1], "x", None],
    )
    def test_bad_crop_boxes(self, box):
        assert crop_box_errors(box)

    def test_clipped_subject_is_fatal(self):
        check = check_crop(self.geom, [0.0, 0.0, 0.42, 1.0], shot_size="medium", min_height_px=100)
        assert any(item.startswith("subject_clipped") for item in check.fatal)

    def test_detail_shot_may_clip_subject_but_not_face(self):
        crop = [0.0, 0.0, 0.45, 1.0]
        assert not check_crop(self.geom, crop, shot_size="detail", min_height_px=100).fatal
        with_face = PhotoGeom(1600, 1200, subject_box=[0.4, 0.4, 0.6, 0.6], faces=[[0.4, 0.4, 0.6, 0.6]])
        assert "face_clipped" in check_crop(with_face, crop, shot_size="detail", min_height_px=100).fatal

    def test_low_resolution_is_fatal_unless_photo_is_small(self):
        tiny_crop = compute_crop(self.geom, (0.5, 0.5), 0.9)
        assert any(
            i.startswith("low_resolution")
            for i in check_crop(self.geom, tiny_crop, shot_size="close_up", min_height_px=640).fatal
        )
        small = PhotoGeom(400, 300, subject_box=[0.2, 0.2, 0.8, 0.8])
        full = compute_crop(small, (0.5, 0.5), None)
        assert not any(
            i.startswith("low_resolution")
            for i in check_crop(small, full, shot_size="wide", min_height_px=640).fatal
        )

    def test_subtitle_over_face_gets_penalty(self):
        face_low = PhotoGeom(1600, 1200, faces=[[0.45, 0.62, 0.55, 0.72]])
        face_high = PhotoGeom(1600, 1200, faces=[[0.45, 0.2, 0.55, 0.3]])
        full = [0.2891, 0.0, 0.7109, 1.0]
        assert (
            check_crop(face_low, full, shot_size="wide", min_height_px=100).penalty
            > check_crop(face_high, full, shot_size="wide", min_height_px=100).penalty
        )

    def test_rule_crops_are_distinct_and_valid(self):
        crops = rule_based_crops(self.geom, "medium")
        assert 1 <= len(crops) <= 3
        assert all(not crop_box_errors(c) for c in crops)
        assert all(visible_ratio(self.geom.subject_box, c) > 0.85 for c in crops)

    def test_rule_crops_work_without_subject(self):
        geom = PhotoGeom(1600, 1200)
        assert rule_based_crops(geom, "wide")

    def test_person_framing_keeps_head_higher(self):
        person = PhotoGeom(1600, 1200, subject_box=[0.4, 0.4, 0.6, 0.6], subject_type="인물")
        food = PhotoGeom(1600, 1200, subject_box=[0.4, 0.4, 0.6, 0.6], subject_type="음식")
        assert rule_based_crops(person, "medium")[0][1] > rule_based_crops(food, "medium")[0][1]

    def test_layout_vector_length_matches_db(self):
        from pipeline.db.models import LAYOUT_VECTOR_DIM

        assert len(layout_vector(None)) == LAYOUT_VECTOR_DIM
        assert len(layout_vector([0.1, 0.1, 0.2, 0.2], (0.1, 0.2, 0.7))) == LAYOUT_VECTOR_DIM


class StubRetriever:
    def __init__(self, examples):  # noqa: ANN001
        self.examples = examples
        self.queries = []

    def search(self, query):  # noqa: ANN001, ANN201
        self.queries.append(query)
        return self.examples


class TestPlanner:
    geom = PhotoGeom(1600, 1200, subject_box=[0.4, 0.4, 0.6, 0.6], subject_type="음식")

    def test_no_retriever_uses_rules_and_marks_it(self):
        plan = CropPlanner(None, min_height_px=300).plan(self.geom, "medium")
        assert plan.source == "rule"
        assert "rule_crop" in plan.flags

    def test_examples_become_candidates(self):
        retriever = StubRetriever(
            [
                RetrievedExample("e1", {"subject_center": [0.5, 0.5], "subject_height_ratio": 0.4}, 0.9),
                RetrievedExample("e2", {"subject_center": [0.33, 0.6], "subject_height_ratio": 0.3}, 0.8),
            ]
        )
        plan = CropPlanner(retriever, min_height_px=300).plan(self.geom, "medium")
        assert plan.source == "rag"
        assert plan.refs == ["e1", "e2"]
        assert len(plan.options) == 2
        assert retriever.queries[0].subject_type == "음식"
        assert retriever.queries[0].shot_size == "medium"

    def test_failed_examples_fall_back_to_rules(self):
        retriever = StubRetriever(
            [RetrievedExample("bad", {"subject_center": [0.5, 0.5], "subject_height_ratio": 0.99}, 0.9)]
        )
        plan = CropPlanner(retriever, min_height_px=1100).plan(self.geom, "wide")
        assert plan.source == "rule"
        assert "rule_crop" in plan.flags

    def test_empty_search_falls_back_to_rules(self):
        plan = CropPlanner(StubRetriever([]), min_height_px=300).plan(self.geom, "wide")
        assert plan.source == "rule"

    def test_all_checks_failing_still_gives_marked_options(self):
        # 피사체가 프레임보다 넓어 어떤 9:16 크롭으로도 다 담을 수 없다.
        geom = PhotoGeom(1600, 1200, subject_box=[0.0, 0.2, 1.0, 0.8])
        plan = CropPlanner(None, min_height_px=300).plan(geom, "wide")
        assert plan.options
        assert "crop_check_failed" in plan.flags


class TestRetriever:
    @pytest.fixture
    def seeded(self, factory):
        with session_scope(factory) as session:
            session.add(DatasetSource(id="ok", name="허용", allowed_use=True))
            session.add(DatasetSource(id="no", name="미확인", allowed_use=False))
            session.flush()

            def example(id_: str, **overrides) -> FramingExample:  # noqa: ANN003
                values = {
                    "dataset_id": "ok",
                    "image_ref": f"{id_}.jpg",
                    "split": "search",
                    "crop_box": [0, 0, 0.5, 1],
                    "crop_source": "native",
                    "crop_score": 0.8,
                    "subject": {"type": "음식"},
                    "shot": {"size": "close_up"},
                    "framing": {"subject_center": [0.5, 0.5], "subject_height_ratio": 0.5},
                    "layout_vector": layout_vector([0.4, 0.4, 0.6, 0.6]),
                    "status": "indexed",
                }
                return FramingExample(id=id_, **{**values, **overrides})

            session.add_all(
                [
                    example("good"),
                    example("eval", split="eval"),
                    example("retired", status="retired"),
                    example("draft", status="draft"),
                    example("blocked", dataset_id="no"),
                    example("other_shot", shot={"size": "wide"}),
                    example("other_subject", subject={"type": "풍경"}),
                ]
            )
        return factory

    def _query(self, **overrides):  # noqa: ANN003, ANN202
        values = {
            "subject_type": "음식",
            "shot_size": "close_up",
            "layout_vector": layout_vector([0.4, 0.4, 0.6, 0.6]),
        }
        return FramingQuery(**{**values, **overrides})

    def test_filters_keep_only_usable_records(self, seeded):
        found = FramingRetriever(seeded, RagSettings()).search(self._query())
        assert [e.example_id for e in found] == ["good"]

    def test_empty_corpus_returns_nothing(self, factory):
        assert FramingRetriever(factory, RagSettings()).search(self._query()) == []

    def test_derived_crop_scores_lower(self, seeded):
        with session_scope(seeded) as session:
            base = session.get(FramingExample, "good")
            session.add(
                FramingExample(
                    id="derived",
                    dataset_id="ok",
                    image_ref="d.jpg",
                    split="search",
                    crop_box=[0, 0, 0.5, 1],
                    crop_source="derived",
                    crop_score=base.crop_score,
                    subject=base.subject,
                    shot=base.shot,
                    framing=base.framing,
                    layout_vector=base.layout_vector,
                    status="indexed",
                )
            )
        found = {
            e.example_id: e.score for e in FramingRetriever(seeded, RagSettings()).search(self._query())
        }
        assert found["derived"] < found["good"]

    def test_mmr_prefers_varied_layouts(self, factory):
        with session_scope(factory) as session:
            session.add(DatasetSource(id="ok", name="허용", allowed_use=True))
            session.flush()
            for id_, box, score in [
                ("a", [0.4, 0.4, 0.6, 0.6], 0.9),
                ("a2", [0.4, 0.4, 0.6, 0.6], 0.89),
                ("b", [0.0, 0.0, 0.1, 0.1], 0.5),
            ]:
                session.add(
                    FramingExample(
                        id=id_,
                        dataset_id="ok",
                        image_ref="x",
                        split="search",
                        crop_box=[0, 0, 0.5, 1],
                        crop_source="native",
                        crop_score=score,
                        subject={"type": "음식"},
                        shot={"size": "close_up"},
                        framing={},
                        layout_vector=layout_vector(box),
                        status="indexed",
                    )
                )
        settings = RagSettings(top_k=2, mmr_lambda=0.3)
        found = [e.example_id for e in FramingRetriever(factory, settings).search(self._query())]
        assert "b" in found


class TestGenerate:
    def test_draft_follows_format_and_rules(self, make_run, service, generator, llm):
        run_id = make_run(6)
        draft = generator.generate(service.photos(run_id), "제주 여행").storyboard
        assert draft.format_id
        assert [c.position for c in draft.cuts] == list(range(1, len(draft.cuts) + 1))
        assert len({c.cut_id for c in draft.cuts}) == len(draft.cuts)
        assert draft.cuts[0].role == "hook"
        assert draft.cuts[-1].role == "ending"
        for cut in draft.cuts:
            assert cut.shot.crop_box == cut.shot.crop_options[cut.shot.crop_choice - 1]
            assert cut.motion_prompt.isascii()
            assert "rule_crop" in cut.flags  # 코퍼스가 비어 있으므로
        assert "제주 여행" in llm.calls[0]["prompt"]

    def test_llm_never_chooses_crop_coordinates(self, make_run, service, generator, llm):
        generator.generate(service.photos(make_run(3)), None)
        for call in llm.calls:
            assert "crop" not in str(call["schema"])

    def test_reused_photo_changes_shot_size(self, make_run, service, generator):
        draft = generator.generate(service.photos(make_run(1)), None).storyboard
        reused = [c for c in draft.cuts if "reused_photo" in c.flags]
        assert reused
        sizes = [c.shot.shot_size for c in draft.cuts[:3]]
        assert len(set(sizes)) > 1

    def test_too_long_narration_is_retried(self, make_run, service, generator, llm):
        import json

        run_id = make_run(3)
        photos = service.photos(run_id)
        long_cut = {"narration": "가" * 200, "on_screen_text": None, "subject_action": "x"}
        # 컷 수를 먼저 알아내기 위해 한 번 생성한다.
        count = len(generator.generate(photos, None, format_id="day_log_v1").storyboard.cuts)
        llm.calls.clear()
        llm.queue = [
            json.dumps(
                {"title": "t", "cuts": [{"cut_index": i + 1, **long_cut} for i in range(count)]},
                ensure_ascii=False,
            )
        ]
        generator.generate(photos, None, format_id="day_log_v1")
        script_calls = [c for c in llm.calls if c["schema"].get("title") == "ScriptOut"]
        assert len(script_calls) == 2
        assert "너무 깁니다" in script_calls[1]["prompt"]

    def test_excluded_photos_are_not_used(self, make_run, service, generator, factory):
        from pipeline.db.models import Photo

        run_id = make_run(4)
        with session_scope(factory) as session:
            session.query(Photo).filter(Photo.photo_id == "p02").update({"excluded_reason": "blurry:1"})
        draft = generator.generate(service.photos(run_id), None).storyboard
        assert "p02" not in {c.photo_id for c in draft.cuts}
