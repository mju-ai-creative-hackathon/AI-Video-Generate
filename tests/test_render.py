"""렌더링: 승인 관문, 재실행 범위, 이어서 실행, 대체, 저장."""

from __future__ import annotations

import io
from pathlib import Path

import pytest
from conftest import FakeComposer, FakeTTS, FakeVideo, fake_video_info
from ruamel.yaml import YAML

from pipeline.config import ComposeSettings
from pipeline.db.models import CutAsset, Run, Video
from pipeline.db.repo import Repo, session_scope
from pipeline.errors import (
    ApprovalRequiredError,
    ReapprovalRequiredError,
    StoreError,
    VideoError,
    VlogError,
)
from pipeline.media import MediaInfo
from pipeline.store import LocalVideoStore, check_consistency, save_final, verify_video


def edit(service, run_id, mutate):  # noqa: ANN001, ANN201
    data = YAML(typ="safe").load(service.export_yaml(run_id)[1])
    mutate(data)
    yaml = YAML()
    yaml.allow_unicode = True
    buffer = io.StringIO()
    yaml.dump(data, buffer)
    return service.import_yaml(run_id, buffer.getvalue())


def approve(service, run_id):  # noqa: ANN001, ANN201
    return service.approve(run_id, accept_warnings=True)


@pytest.fixture
def rendered(drafted, service, renderer):
    """한 번 렌더링까지 마친 상태."""

    def _make(n_photos: int = 3):  # noqa: ANN202
        run_id = drafted(n_photos)
        approve(service, run_id)
        pipeline, tts, video, composer = renderer()
        result = pipeline.run(run_id)
        n_cuts = len(service.get_storyboard(run_id)[1].cuts)
        assert len(tts.calls) == n_cuts
        assert len(video.calls) == n_cuts
        tts.calls.clear()
        video.calls.clear()
        composer.calls.clear()
        return run_id, pipeline, tts, video, composer, result

    return _make


class TestGate:
    def test_render_before_approval_calls_nothing(self, drafted, service, renderer):
        run_id = drafted(3)
        pipeline, tts, video, composer = renderer()
        with pytest.raises(ApprovalRequiredError):
            pipeline.run(run_id)
        assert tts.calls == []
        assert video.calls == []
        assert composer.calls == []

    def test_adapters_are_not_even_created_before_approval(
        self, drafted, service, factory, settings
    ):
        from pipeline.render import RenderPipeline

        run_id = drafted(2)

        def forbidden():  # noqa: ANN202
            raise AssertionError("승인 전에 어댑터를 만들면 안 됩니다")

        pipeline = RenderPipeline(
            factory,
            settings,
            service,
            tts_factory=forbidden,
            video_factory=forbidden,
            fallback_factory=forbidden,
            composer=FakeComposer(),
        )
        with pytest.raises(ApprovalRequiredError):
            pipeline.run(run_id)
        with pytest.raises(ApprovalRequiredError):
            pipeline.regen_clip(run_id, "c_any")

    def test_edit_after_approval_blocks_render_until_reapproved(self, rendered, service):
        run_id, pipeline, tts, video, _, _ = rendered()
        edit(service, run_id, lambda d: d["cuts"][0].update(narration="고친 문장"))
        with pytest.raises(ApprovalRequiredError):
            pipeline.run(run_id)
        assert tts.calls == []
        assert video.calls == []
        approve(service, run_id)
        pipeline.run(run_id)
        assert len(tts.calls) == 1

    def test_plan_stage_makes_no_tts_or_video_calls(self, tmp_path, settings, factory, service, generator, llm):
        from conftest import noisy_image

        from pipeline.plan import PlanPipeline

        source = tmp_path / "photos"
        source.mkdir()
        for index in range(3):
            noisy_image(index).save(source / f"{index}.png")
        result = PlanPipeline(factory, settings, llm, generator, service).run(source, "메모")
        assert result.version == 1
        with session_scope(factory) as session:
            assert session.query(CutAsset).count() == 0
            assert Repo(session).get_run(result.run_id).status == "storyboard_review"


class TestFirstRender:
    def test_completes_and_registers_video(self, rendered, factory, settings):
        run_id, _, _, _, _, result = rendered()
        assert result.video_path.exists()
        assert result.video_path.name == "final_v1.mp4"
        with session_scope(factory) as session:
            repo = Repo(session)
            assert repo.get_run(run_id).status == "completed"
            video = repo.videos(run_id)[0]
            assert video.storyboard_version == 1
            assert video.sha256 == result.sha256
            assert video.width == 1080
            assert not Path(video.path).is_absolute()

    def test_audio_is_made_before_video_with_measured_frames(self, drafted, service, renderer):
        run_id = drafted(2)
        approve(service, run_id)
        pipeline, tts, video, _ = renderer(tts=FakeTTS(sec_per_char=0.05))
        pipeline.run(run_id)
        board = service.get_storyboard(run_id)[1]
        for request, cut in zip(video.calls, board.ordered_cuts()):
            assert (request.frames - 1) % 4 == 0
            assert 33 <= request.frames <= 81
            assert request.prompt == cut.motion_prompt
            assert request.camera_move == cut.shot.camera_move

    def test_tts_gets_neighbouring_sentences(self, drafted, service, renderer):
        run_id = drafted(3)
        approve(service, run_id)
        pipeline, tts, _, _ = renderer()
        pipeline.run(run_id)
        cuts = service.get_storyboard(run_id)[1].ordered_cuts()
        assert tts.calls[0].previous_text is None
        assert tts.calls[0].next_text == cuts[1].narration
        assert tts.calls[1].previous_text == cuts[0].narration

    def test_gpu_is_released_after_clips(self, rendered):
        _, pipeline, _, video, _, _ = rendered()
        pipeline.run(pipeline.service.factory and "run1")
        assert video.released >= 2

    def test_input_image_is_cropped_to_provider_size(self, drafted, service, renderer):
        from PIL import Image

        run_id = drafted(2)
        approve(service, run_id)
        pipeline, _, video, _ = renderer()
        pipeline.run(run_id)
        with Image.open(video.calls[0].image_path) as image:
            assert image.size == video.input_size


class TestRerunScope:
    """승인 후 수정 시 다시 실행하는 범위 (계획의 표와 같다)."""

    def _rerender(self, service, pipeline, run_id, mutate):  # noqa: ANN001, ANN202
        edit(service, run_id, mutate)
        approve(service, run_id)
        return pipeline.run(run_id)

    def test_nothing_changed_reuses_everything(self, rendered):
        run_id, pipeline, tts, video, composer, first = rendered()
        result = pipeline.run(run_id)
        assert tts.calls == []
        assert video.calls == []
        assert composer.calls == []
        assert result.reused_video
        assert result.video_path == first.video_path

    def test_narration_change_redoes_tts_and_compose_only_that_cut(self, rendered, service):
        run_id, pipeline, tts, video, composer, _ = rendered()
        # 같은 길이의 문장이면 프레임 수가 같아 영상은 유지된다.
        board = service.get_storyboard(run_id)[1].ordered_cuts()[0]
        new_text = "다" * len(board.narration)
        self._rerender(service, pipeline, run_id, lambda d: d["cuts"][0].update(narration=new_text))
        assert [c.text for c in tts.calls] == [new_text]
        assert video.calls == []
        assert len(composer.calls) == 1

    def test_longer_narration_regenerates_that_clip(self, rendered, service):
        run_id, pipeline, tts, video, _, _ = rendered()
        new_text = "다" * 20  # 0.2초 × 20자 = 4초
        self._rerender(service, pipeline, run_id, lambda d: d["cuts"][0].update(narration=new_text))
        assert len(tts.calls) == 1
        assert len(video.calls) == 1

    def test_shorter_narration_keeps_existing_clip(self, rendered, service):
        run_id, pipeline, _, video, composer, _ = rendered()
        self._rerender(service, pipeline, run_id, lambda d: d["cuts"][0].update(narration="다" * 20))
        video.calls.clear()
        composer.calls.clear()
        self._rerender(service, pipeline, run_id, lambda d: d["cuts"][0].update(narration="라" * 12))
        assert video.calls == []
        assert len(composer.calls) == 1

    @pytest.mark.parametrize(
        "mutate",
        [
            lambda d: d["cuts"][0]["shot"].update(on_screen_text="새 문구"),
            lambda d: d["cuts"][0]["shot"].update(transition_in="fade"),
        ],
    )
    def test_text_or_transition_only_recomposes(self, rendered, service, mutate):
        run_id, pipeline, tts, video, composer, first = rendered()
        result = self._rerender(service, pipeline, run_id, mutate)
        assert tts.calls == []
        assert video.calls == []
        assert len(composer.calls) == 1
        assert result.video_path != first.video_path
        assert result.video_path.name == "final_v2.mp4"

    @pytest.mark.parametrize(
        "mutate",
        [
            lambda d: d["cuts"][0]["shot"].update(camera_move="tilt_up"),
            lambda d: d["cuts"][0].update(motion_prompt="A new prompt"),
            lambda d: d["cuts"][0]["shot"].update(crop_box=[0.3, 0.0, 0.7, 1.0]),
            lambda d: d["cuts"][0].update(photo_id="p03" if d["cuts"][0]["photo_id"] != "p03" else "p02"),
        ],
    )
    def test_visual_change_regenerates_only_that_clip(self, rendered, service, mutate):
        run_id, pipeline, tts, video, composer, _ = rendered()
        self._rerender(service, pipeline, run_id, mutate)
        assert tts.calls == []
        assert len(video.calls) == 1
        assert len(composer.calls) == 1

    def test_reorder_recomposes_without_tts_or_video(self, rendered, service):
        run_id, pipeline, tts, video, composer, _ = rendered()

        def swap(d):  # noqa: ANN001, ANN202
            a, b = d["cuts"][0], d["cuts"][1]
            a["position"], b["position"] = b["position"], a["position"]

        self._rerender(service, pipeline, run_id, swap)
        assert tts.calls == []
        assert video.calls == []
        assert len(composer.calls) == 1

    def test_regen_clip_redoes_only_that_cut_with_seed(self, rendered, service, factory):
        run_id, pipeline, tts, video, composer, _ = rendered()
        cut = service.get_storyboard(run_id)[1].ordered_cuts()[1]
        pipeline.regen_clip(run_id, cut.cut_id, seed=42)
        assert tts.calls == []
        assert [c.seed for c in video.calls] == [42]
        assert len(composer.calls) == 1
        # 다음 렌더링에서도 이 시드가 유지되어 다시 만들지 않는다.
        video.calls.clear()
        composer.calls.clear()
        pipeline.run(run_id)
        assert video.calls == []
        assert composer.calls == []

    def test_regen_unknown_cut_is_refused(self, rendered):
        run_id, pipeline, *_ = rendered()
        with pytest.raises(VlogError, match="없는 컷"):
            pipeline.regen_clip(run_id, "c_none")


class TestResume:
    def test_interrupted_render_continues_where_it_stopped(self, drafted, service, renderer, factory):
        run_id = drafted(3)
        approve(service, run_id)
        n_cuts = len(service.get_storyboard(run_id)[1].cuts)

        class StopAfterTwo(FakeVideo):
            def generate(self, request):  # noqa: ANN001, ANN202
                if len(self.calls) == 2:
                    raise KeyboardInterrupt
                return super().generate(request)

        pipeline, tts, video, _ = renderer(video=StopAfterTwo())
        with pytest.raises(KeyboardInterrupt):
            pipeline.run(run_id)

        pipeline2, tts2, video2, _ = renderer(tts=tts, video=FakeVideo())
        tts.calls.clear()
        pipeline2.run(run_id)
        assert tts.calls == []  # 음성은 재사용
        assert len(video2.calls) == n_cuts - 2  # 남은 컷만
        with session_scope(factory) as session:
            assert Repo(session).get_run(run_id).status == "completed"

    def test_missing_file_is_regenerated(self, rendered, service, factory, settings):
        run_id, pipeline, _, video, _, _ = rendered()
        with session_scope(factory) as session:
            row = session.query(CutAsset).filter_by(kind="clip").first()
            settings.resolve(row.path).unlink()
        with session_scope(factory) as session:
            for row in session.query(Video).all():
                settings.resolve(row.path).unlink()
                session.delete(row)
        pipeline.run(run_id)
        assert len(video.calls) == 1


class TestTooLongNarration:
    def test_stops_and_asks_for_reapproval_instead_of_using_unapproved_text(
        self, drafted, service, renderer, factory
    ):
        run_id = drafted(3)
        approve(service, run_id)
        pipeline, tts, video, composer = renderer(tts=FakeTTS(sec_per_char=1.0))
        with pytest.raises(ReapprovalRequiredError, match="줄인 초안 v2"):
            pipeline.run(run_id)
        assert video.calls == []
        assert composer.calls == []
        with session_scope(factory) as session:
            repo = Repo(session)
            assert repo.get_run(run_id).status == "storyboard_review"
            assert repo.latest_version(run_id).status == "draft"
            assert repo.latest_version(run_id).author == "ai"
        assert service.get_storyboard(run_id, 1)[1].ordered_cuts()[0].narration != "짧은 문장"
        with pytest.raises(ApprovalRequiredError):
            service.require_approved(run_id)

    def test_locked_narration_is_not_shortened(self, drafted, service, renderer):
        run_id = drafted(1)
        edit(
            service,
            run_id,
            lambda d: [c.update(locked=["narration"]) for c in d["cuts"]],
        )
        approve(service, run_id)
        pipeline, *_ = renderer(tts=FakeTTS(sec_per_char=1.0))
        with pytest.raises(ReapprovalRequiredError, match="직접 줄이세요"):
            pipeline.run(run_id)
        assert service.get_storyboard(run_id)[0] == 2  # 새 초안이 생기지 않음


class TestFallback:
    def test_failed_clip_retries_with_new_seed_then_falls_back(self, drafted, service, renderer, factory):
        run_id = drafted(2)
        approve(service, run_id)
        fallback = FakeVideo()
        fallback.name = "kenburns"
        pipeline, _, video, _ = renderer(video=FakeVideo(fail_times=3), fallback=fallback)
        result = pipeline.run(run_id)
        first_seeds = [c.seed for c in video.calls[:3]]
        assert first_seeds == [first_seeds[0] + i for i in range(3)]
        assert len(fallback.calls) == 1
        assert len(result.fallback_cuts) == 1
        with session_scope(factory) as session:
            failed = session.query(CutAsset).filter_by(status="failed").one()
            assert "가짜 실패" in failed.error
            done = session.query(CutAsset).filter_by(cut_id=failed.cut_id, status="done", kind="clip").one()
            assert done.meta["fallback"] is True
            assert "가짜 실패" in done.meta["fallback_reason"]
            assert Repo(session).get_run(run_id).status == "completed"

    def test_retry_succeeds_without_fallback(self, drafted, service, renderer):
        run_id = drafted(2)
        approve(service, run_id)
        fallback = FakeVideo()
        pipeline, _, video, _ = renderer(video=FakeVideo(fail_times=1), fallback=fallback)
        result = pipeline.run(run_id)
        assert fallback.calls == []
        assert result.fallback_cuts == []

    def test_no_fallback_marks_run_failed(self, drafted, service, renderer, factory):
        run_id = drafted(2)
        approve(service, run_id)
        pipeline, *_ = renderer(video=FakeVideo(fail_times=99))
        with pytest.raises(VideoError):
            pipeline.run(run_id)
        with session_scope(factory) as session:
            run = Repo(session).get_run(run_id)
            assert run.status == "failed"
            assert "가짜 실패" in run.error

    def test_failed_run_can_render_again(self, drafted, service, renderer, factory):
        run_id = drafted(2)
        approve(service, run_id)
        pipeline, *_ = renderer(video=FakeVideo(fail_times=99))
        with pytest.raises(VideoError):
            pipeline.run(run_id)
        pipeline2, *_ = renderer()
        pipeline2.run(run_id)
        with session_scope(factory) as session:
            run = Repo(session).get_run(run_id)
            assert run.status == "completed"
            assert run.error is None


class TestStore:
    def _info(self, **overrides):  # noqa: ANN003, ANN202
        values = {
            "duration": 10.0,
            "width": 1080,
            "height": 1920,
            "fps": 30.0,
            "codec": "h264",
            "pix_fmt": "yuv420p",
            "has_audio": True,
        }
        return MediaInfo(**{**values, **overrides})

    def test_valid_video_passes(self):
        verify_video(self._info(), ComposeSettings(), 10.1)

    @pytest.mark.parametrize(
        "override",
        [
            {"width": 720},
            {"fps": 24.0},
            {"codec": "hevc"},
            {"pix_fmt": "yuv444p"},
            {"has_audio": False},
            {"duration": 0.0},
            {"duration": 20.0},
        ],
    )
    def test_bad_video_is_rejected(self, override):
        with pytest.raises(StoreError):
            verify_video(self._info(**override), ComposeSettings(), 10.0)

    def _run(self, factory, run_id="r1"):  # noqa: ANN001, ANN202
        with session_scope(factory) as session:
            session.add(Run(id=run_id, status="rendered", config={}))

    def _temp(self, settings, content=b"video"):  # noqa: ANN001, ANN202
        temp = settings.root / "temp.mp4"
        temp.write_bytes(content)
        return temp

    def test_saved_row_matches_file(self, factory, settings):
        self._run(factory)
        store = LocalVideoStore(settings.storage_path)
        video = save_final(
            factory, settings, store, self._temp(settings),
            run_id="r1", version=2, compose_hash="h", probe_fn=fake_video_info,
        )
        path = settings.resolve(video.path)
        assert path.name == "final_v2.mp4"
        assert path.read_bytes() == b"video"
        assert video.size_bytes == 5
        report = check_consistency(factory, settings)
        assert report.ok
        assert report.checked == 1

    def test_invalid_video_is_not_moved(self, factory, settings):
        self._run(factory)
        temp = self._temp(settings)
        with pytest.raises(StoreError):
            save_final(
                factory, settings, LocalVideoStore(settings.storage_path), temp,
                run_id="r1", version=1, compose_hash="h",
                probe_fn=lambda _p: self._info(codec="hevc"),
            )
        assert temp.exists()
        with session_scope(factory) as session:
            assert session.query(Video).count() == 0

    def test_db_failure_leaves_orphan_that_doctor_reports(self, factory, settings):
        store = LocalVideoStore(settings.storage_path)
        with pytest.raises(StoreError, match="vlog doctor"):
            # 없는 run_id라 트랜잭션이 실패한다.
            save_final(
                factory, settings, store, self._temp(settings),
                run_id="없음", version=1, compose_hash="h", probe_fn=fake_video_info,
            )
        report = check_consistency(factory, settings)
        assert len(report.orphan_files) == 1
        files = list((settings.storage_path / "videos").rglob("*.mp4"))
        assert len(files) == 1  # 자동으로 지우지 않는다

    def test_doctor_finds_missing_and_changed_files(self, factory, settings):
        self._run(factory)
        store = LocalVideoStore(settings.storage_path)
        video = save_final(
            factory, settings, store, self._temp(settings),
            run_id="r1", version=1, compose_hash="a", probe_fn=fake_video_info,
        )
        settings.resolve(video.path).write_bytes(b"changed!")
        report = check_consistency(factory, settings)
        assert report.hash_mismatches
        assert report.size_mismatches
        settings.resolve(video.path).unlink()
        assert check_consistency(factory, settings).missing_files

    def test_existing_file_is_not_overwritten(self, settings):
        store = LocalVideoStore(settings.storage_path)
        first = store.put(self._temp(settings, b"one"), "r", 1)
        second = store.put(self._temp(settings, b"two"), "r", 1)
        assert first.read_bytes() == b"one"
        assert second.read_bytes() == b"two"
        assert first != second
