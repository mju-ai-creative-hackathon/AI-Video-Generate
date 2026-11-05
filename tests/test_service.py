"""핵심 요구사항: 생성 전 수정과 승인."""

from __future__ import annotations

import io

import pytest
from ruamel.yaml import YAML

from pipeline.db.models import EditLog, StoryboardVersion
from pipeline.db.repo import Repo, session_scope
from pipeline.errors import (
    ApprovalRequiredError,
    ImmutableVersionError,
    StaleBaseVersionError,
    StoryboardValidationError,
    VlogError,
    WarningsNotAcceptedError,
)
from pipeline.schemas import LOCKABLE_FIELDS, REGEN_FIELDS


def load_edit(service, run_id):  # noqa: ANN001, ANN201
    _, text = service.export_yaml(run_id)
    return YAML(typ="safe").load(text)


def dump(data) -> str:  # noqa: ANN001
    yaml = YAML()
    yaml.allow_unicode = True
    buffer = io.StringIO()
    yaml.dump(data, buffer)
    return buffer.getvalue()


def approve(service, run_id):  # noqa: ANN001, ANN201
    return service.approve(run_id, accept_warnings=True)


class TestApprovalGate:
    def test_no_storyboard_refuses(self, make_run, service):
        run_id = make_run(2)
        with pytest.raises(ApprovalRequiredError):
            service.require_approved(run_id)

    def test_draft_refuses(self, drafted, service):
        run_id = drafted()
        with pytest.raises(ApprovalRequiredError, match="승인되지 않아"):
            service.require_approved(run_id)

    def test_approved_passes(self, drafted, service):
        run_id = drafted()
        version, _ = approve(service, run_id)
        approved = service.require_approved(run_id)
        assert approved.version == version

    def test_edit_after_approval_needs_reapproval(self, drafted, service):
        run_id = drafted()
        approve(service, run_id)
        data = load_edit(service, run_id)
        data["cuts"][0]["narration"] = "승인 후 고친 문장"
        result = service.import_yaml(run_id, dump(data))
        assert result.version == 2
        with pytest.raises(ApprovalRequiredError):
            service.require_approved(run_id)
        with session_scope(service.factory) as session:
            assert Repo(session).get_run(run_id).status == "storyboard_review"

    def test_regen_after_approval_needs_reapproval(self, drafted, service):
        run_id = drafted()
        approve(service, run_id)
        service.regen(run_id, fields=["narration"])
        with pytest.raises(ApprovalRequiredError):
            service.require_approved(run_id)

    def test_approved_version_cannot_be_changed(self, drafted, service):
        run_id = drafted()
        approve(service, run_id)
        with pytest.raises(ImmutableVersionError):
            with session_scope(service.factory) as session:
                row = Repo(session).get_version(run_id, 1)
                row.content = {"title": "몰래 수정"}
        _, storyboard = service.get_storyboard(run_id, 1)
        assert storyboard.title != "몰래 수정"

    def test_approved_version_cannot_be_unapproved(self, drafted, service):
        run_id = drafted()
        approve(service, run_id)
        with pytest.raises(ImmutableVersionError):
            with session_scope(service.factory) as session:
                Repo(session).get_version(run_id, 1).status = "draft"

    def test_warnings_need_explicit_acceptance_and_are_recorded(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        data["cuts"][0]["narration"] = "가" * 200
        service.import_yaml(run_id, dump(data))
        with pytest.raises(WarningsNotAcceptedError):
            service.approve(run_id)
        with pytest.raises(ApprovalRequiredError):
            service.require_approved(run_id)
        service.approve(run_id, accept_warnings=True)
        with session_scope(service.factory) as session:
            row = Repo(session).get_version(run_id, 2)
            assert any(w["code"] == "too_many_chars" for w in row.warnings_accepted)

    def test_only_latest_can_be_approved(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        data["title"] = "새 제목"
        service.import_yaml(run_id, dump(data))
        with pytest.raises(VlogError, match="최신 버전"):
            service.approve(run_id, 1, accept_warnings=True)

    def test_approving_twice_is_harmless(self, drafted, service):
        run_id = drafted()
        approve(service, run_id)
        version, _ = approve(service, run_id)
        assert version == 1


class TestEditing:
    def test_export_import_without_changes_keeps_version(self, drafted, service):
        run_id = drafted()
        _, text = service.export_yaml(run_id)
        result = service.import_yaml(run_id, text)
        assert not result.changed
        assert result.version == 1

    def test_export_shows_caption_and_keeps_korean(self, drafted, service):
        _, text = service.export_yaml(drafted())
        assert "base_version: 1" in text
        assert "사진" in text  # 사진 설명 주석
        assert "\\u" not in text

    def test_edit_creates_new_version_and_keeps_old(self, drafted, service):
        run_id = drafted()
        _, before = service.get_storyboard(run_id, 1)
        data = load_edit(service, run_id)
        data["title"] = "고친 제목"
        data["cuts"][0]["narration"] = "고친 나레이션"
        result = service.import_yaml(run_id, dump(data))
        assert result.version == 2
        assert service.get_storyboard(run_id, 1)[1].title == before.title
        latest = service.get_storyboard(run_id)[1]
        assert latest.title == "고친 제목"
        with session_scope(service.factory) as session:
            row = Repo(session).get_version(run_id, 2)
            assert row.author == "human"
            assert row.parent_version == 1

    def test_edit_log_records_before_and_after(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        cut_id = data["cuts"][0]["cut_id"]
        before = data["cuts"][0]["narration"]
        data["cuts"][0]["narration"] = "고친 나레이션"
        service.import_yaml(run_id, dump(data))
        with session_scope(service.factory) as session:
            logs = session.query(EditLog).filter_by(run_id=run_id, field="narration").all()
            assert len(logs) == 1
            assert logs[0].cut_id == cut_id
            assert logs[0].before == before
            assert logs[0].after == "고친 나레이션"
            assert (logs[0].from_version, logs[0].to_version) == (1, 2)

    def test_stale_base_version_is_refused_with_diff(self, drafted, service):
        run_id = drafted()
        stale = load_edit(service, run_id)
        fresh = load_edit(service, run_id)
        fresh["title"] = "먼저 저장"
        service.import_yaml(run_id, dump(fresh))
        stale["title"] = "늦게 저장"
        with pytest.raises(StaleBaseVersionError) as caught:
            service.import_yaml(run_id, dump(stale))
        assert any(c.field == "title" for c in caught.value.changes)
        assert service.get_storyboard(run_id)[1].title == "먼저 저장"

    def test_reorder_keeps_cut_identity(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        first, second = data["cuts"][0], data["cuts"][1]
        first_id, first_narration = first["cut_id"], first["narration"]
        first["position"], second["position"] = second["position"], first["position"]
        service.import_yaml(run_id, dump(data))
        latest = service.get_storyboard(run_id)[1]
        assert latest.cut(first_id).position == 2
        assert latest.cut(first_id).narration == first_narration
        assert sorted(c.position for c in latest.cuts) == list(range(1, len(latest.cuts) + 1))

    def test_delete_cut(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        removed = data["cuts"].pop(1)["cut_id"]
        result = service.import_yaml(run_id, dump(data))
        latest = service.get_storyboard(run_id)[1]
        assert latest.cut(removed) is None
        assert any(c.field == "cut_removed" and c.cut_id == removed for c in result.changes)
        assert [c.position for c in latest.ordered_cuts()] == list(range(1, len(latest.cuts) + 1))

    def test_add_cut_without_id(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        data["cuts"].append(
            {
                "position": 99,
                "photo_id": "p01",
                "role": "body",
                "narration": "새로 넣은 컷",
                "target_sec": 3.0,
                "shot": {"shot_size": "wide", "camera_move": "static"},
            }
        )
        service.import_yaml(run_id, dump(data))
        latest = service.get_storyboard(run_id)[1]
        added = latest.ordered_cuts()[-1]
        assert added.narration == "새로 넣은 컷"
        assert added.cut_id.startswith("c_")
        assert added.shot.crop_options  # 코드가 크롭을 계산한다
        assert added.motion_prompt.isascii()

    def test_replacing_photo_recomputes_crop_and_marks_prompt(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        cut = data["cuts"][0]
        cut["photo_id"] = "p05" if cut["photo_id"] != "p05" else "p04"
        service.import_yaml(run_id, dump(data))
        latest = service.get_storyboard(run_id)[1].cut(cut["cut_id"])
        assert latest.photo_id == cut["photo_id"]
        assert "prompt_stale" in latest.flags
        assert any(w.code == "prompt_stale" for w in service.validate(run_id).warnings)

    def test_prompt_edit_clears_stale_flag(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        data["cuts"][0]["shot"]["camera_move"] = "static"
        service.import_yaml(run_id, dump(data))
        data = load_edit(service, run_id)
        data["cuts"][0]["motion_prompt"] = "A coffee cup, camera holds still"
        service.import_yaml(run_id, dump(data))
        assert "prompt_stale" not in service.get_storyboard(run_id)[1].ordered_cuts()[0].flags

    def test_crop_choice_selects_option(self, drafted, service):
        run_id = drafted()
        board = service.get_storyboard(run_id)[1]
        target = next(c for c in board.ordered_cuts() if len(c.shot.crop_options) >= 2)
        data = load_edit(service, run_id)
        item = next(c for c in data["cuts"] if c["cut_id"] == target.cut_id)
        item["shot"]["crop_choice"] = 2
        service.import_yaml(run_id, dump(data))
        latest = service.get_storyboard(run_id)[1].cut(target.cut_id)
        assert latest.shot.crop_box == target.shot.crop_options[1]

    def test_direct_crop_box_wins_over_choice(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        item = data["cuts"][0]
        item["shot"]["crop_choice"] = 1
        item["shot"]["crop_box"] = [0.3, 0.0, 0.7, 1.0]
        service.import_yaml(run_id, dump(data))
        latest = service.get_storyboard(run_id)[1].cut(item["cut_id"])
        assert latest.shot.crop_box == [0.3, 0.0, 0.7, 1.0]
        assert latest.crop_source == "human"
        # 다시 내보내도 직접 쓴 값이 유지된다
        again = service.import_yaml(run_id, service.export_yaml(run_id)[1])
        assert not again.changed


class TestImportValidation:
    @pytest.mark.parametrize(
        ("mutate", "code"),
        [
            (lambda d: d["cuts"][0].update(photo_id="p99"), "unknown_photo"),
            (lambda d: d["cuts"][0].update(narration="   "), "empty_narration"),
            (lambda d: d["cuts"][0]["shot"].update(crop_box=[0.9, 0, 0.1, 1]), "bad_crop"),
            (lambda d: d["cuts"][0]["shot"].update(crop_choice=99), "bad_crop_choice"),
            (lambda d: d["cuts"][1].update(cut_id=d["cuts"][0]["cut_id"]), "duplicate_cut_id"),
            (lambda d: d["cuts"][1].update(position=d["cuts"][0]["position"]), "duplicate_position"),
            (lambda d: d["cuts"][0].update(cut_id="c_none"), "unknown_cut_id"),
            (lambda d: d["cuts"][0].update(locked=["없는항목"]), "unknown_lock"),
            (lambda d: d["cuts"][0].update(target_sec=0), "bad_target_sec"),
            (lambda d: d.update(title=" "), "empty_title"),
        ],
    )
    def test_errors_block_saving(self, drafted, service, mutate, code):
        run_id = drafted()
        data = load_edit(service, run_id)
        mutate(data)
        with pytest.raises(StoryboardValidationError) as caught:
            service.import_yaml(run_id, dump(data))
        assert code in {issue.code for issue in caught.value.report.errors}
        assert service.get_storyboard(run_id)[0] == 1  # 저장되지 않음

    @pytest.mark.parametrize(
        "mutate",
        [
            lambda d: d["cuts"][0]["shot"].update(camera_move="spin"),
            lambda d: d["cuts"][0].update(role="intro"),
            lambda d: d["cuts"][0].update(narattion="오타 키"),
            lambda d: d.pop("base_version"),
        ],
    )
    def test_schema_errors_show_location(self, drafted, service, mutate):
        run_id = drafted()
        data = load_edit(service, run_id)
        mutate(data)
        with pytest.raises(StoryboardValidationError) as caught:
            service.import_yaml(run_id, dump(data))
        assert caught.value.report.errors[0].location

    def test_broken_yaml_is_reported(self, drafted, service):
        with pytest.raises(StoryboardValidationError):
            service.import_yaml(drafted(), "cuts: [unclosed")

    def test_warnings_do_not_block_saving(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        data["cuts"][0]["target_sec"] = 30.0
        result = service.import_yaml(run_id, dump(data))
        assert result.changed
        codes = {w.code for w in result.warnings.warnings}
        assert {"cut_length_range"} <= codes

    def test_all_lockable_fields_are_accepted(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        data["cuts"][0]["locked"] = sorted(LOCKABLE_FIELDS)
        assert service.import_yaml(run_id, dump(data)).changed


class TestRegen:
    def test_only_selected_cut_and_field_change(self, drafted, service):
        run_id = drafted()
        before = service.get_storyboard(run_id)[1]
        target = before.ordered_cuts()[1]
        outcome = service.regen(run_id, cut_ids=[target.cut_id], fields=["narration"])
        assert outcome.version == 2
        after = service.get_storyboard(run_id)[1]
        assert after.cut(target.cut_id).narration != target.narration
        assert after.cut(target.cut_id).motion_prompt == target.motion_prompt
        assert after.cut(target.cut_id).shot.subject_action == target.shot.subject_action
        for cut in before.cuts:
            if cut.cut_id != target.cut_id:
                assert after.cut(cut.cut_id) == cut
        assert {c.field for c in outcome.changes} == {"narration"}

    def test_locked_field_is_never_regenerated(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        cut_id = data["cuts"][0]["cut_id"]
        data["cuts"][0]["narration"] = "사람이 쓴 문장"
        data["cuts"][0]["locked"] = ["narration"]
        service.import_yaml(run_id, dump(data))
        outcome = service.regen(run_id, cut_ids=[cut_id], fields=["narration", "subject_action"])
        after = service.get_storyboard(run_id)[1].cut(cut_id)
        assert after.narration == "사람이 쓴 문장"
        assert (cut_id, "narration") in outcome.skipped_locked
        assert any(c.field == "shot.subject_action" for c in outcome.changes)

    def test_all_locked_makes_no_new_version(self, drafted, service, llm):
        run_id = drafted()
        data = load_edit(service, run_id)
        cut_id = data["cuts"][0]["cut_id"]
        data["cuts"][0]["locked"] = sorted(REGEN_FIELDS)
        service.import_yaml(run_id, dump(data))
        calls = len(llm.calls)
        outcome = service.regen(run_id, cut_ids=[cut_id])
        assert outcome.version == 2
        assert not outcome.changes
        assert len(llm.calls) == calls  # LLM도 부르지 않는다

    def test_regen_author_is_ai(self, drafted, service):
        run_id = drafted()
        service.regen(run_id, fields=["motion_prompt"])
        with session_scope(service.factory) as session:
            assert Repo(session).get_version(run_id, 2).author == "ai"

    def test_regen_crop_uses_code_not_llm(self, drafted, service, llm):
        run_id = drafted()
        board = service.get_storyboard(run_id)[1]
        calls = len(llm.calls)
        service.regen(run_id, cut_ids=[board.cuts[0].cut_id], fields=["crop_box"])
        assert len(llm.calls) == calls

    @pytest.mark.parametrize("field", ["title", "position", "없는항목"])
    def test_unknown_field_is_refused(self, drafted, service, field):
        with pytest.raises(VlogError):
            service.regen(drafted(), fields=[field])

    def test_unknown_cut_is_refused(self, drafted, service):
        with pytest.raises(VlogError, match="없는 컷"):
            service.regen(drafted(), cut_ids=["c_none"])


class TestHistory:
    def test_diff_between_versions(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        data["cuts"][0]["shot"]["on_screen_text"] = "새 문구"
        service.import_yaml(run_id, dump(data))
        changes = service.diff(run_id, 1, 2)
        assert [c.field for c in changes] == ["shot.on_screen_text"]
        assert changes[0].after == "새 문구"

    def test_revert_makes_new_draft_and_keeps_history(self, drafted, service):
        run_id = drafted()
        original = service.get_storyboard(run_id, 1)[1]
        data = load_edit(service, run_id)
        data["title"] = "바뀐 제목"
        service.import_yaml(run_id, dump(data))
        version = service.revert(run_id, 1)
        assert version == 3
        assert service.get_storyboard(run_id)[1].title == original.title
        with session_scope(service.factory) as session:
            assert session.query(StoryboardVersion).filter_by(run_id=run_id).count() == 3

    def test_revert_then_approve_works(self, drafted, service):
        run_id = drafted()
        data = load_edit(service, run_id)
        data["title"] = "바뀐 제목"
        service.import_yaml(run_id, dump(data))
        version = service.revert(run_id, 1)
        approved, _ = approve(service, run_id)
        assert approved == version
