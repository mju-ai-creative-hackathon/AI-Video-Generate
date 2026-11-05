"""CLI 연결, Alembic 마이그레이션, 썸네일 시트, 실제 ffmpeg 합성."""

from __future__ import annotations

import shutil
import wave
from pathlib import Path

import pytest
from conftest import REPO_ROOT, noisy_image
from sqlalchemy import create_engine, inspect
from typer.testing import CliRunner

from alembic import command
from alembic.config import Config
from pipeline.app import App
from pipeline.cli import app as cli_app
from pipeline.compose import ComposeCut, FFmpegComposer, make_contact_sheet
from pipeline.config import ComposeSettings
from pipeline.db.models import Base
from pipeline.media import prepare_input_image, probe
from pipeline.sheets import make_storyboard_sheet
from pipeline.subtitles import SubtitleEvent
from pipeline.video.base import ClipRequest
from pipeline.video.kenburns import KenBurnsVideo

runner = CliRunner()
needs_ffmpeg = pytest.mark.skipif(
    not (shutil.which("ffmpeg") and shutil.which("ffprobe")), reason="ffmpeg가 PATH에 없음"
)


@pytest.fixture
def cli_config(tmp_path: Path, monkeypatch, drafted, settings):  # noqa: ANN001, ANN201
    """테스트 DB를 쓰는 config.yaml. 실행은 drafted가 만든다."""
    data = (REPO_ROOT / "data").as_posix()
    config = tmp_path / "config.yaml"
    config.write_text(
        "storyboard:\n"
        f"  formats_dir: {data}/formats\n"
        f"  style_rules: {data}/style_rules.yaml\n"
        f"  motion_presets: {data}/motion_presets.yaml\n"
        "  min_crop_height_px: 300\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("DATABASE_URL", settings.env.database_url)
    monkeypatch.chdir(tmp_path)
    return config


def invoke(*args: object):  # noqa: ANN201
    return runner.invoke(cli_app, [str(a) for a in args])


class TestCLI:
    def test_all_commands_are_registered(self):
        result = invoke("--help")
        assert result.exit_code == 0
        for name in ("plan", "render", "regen", "list", "doctor", "storyboard", "corpus"):
            assert name in result.output
        help_text = invoke("storyboard", "--help").output
        for name in ("export", "import", "regen", "diff", "approve", "revert", "versions"):
            assert name in help_text

    def test_corpus_build_is_not_in_this_scope(self):
        assert "build" not in invoke("corpus", "--help").output

    def test_render_is_refused_before_approval(self, cli_config, drafted):
        run_id = drafted(2)
        result = invoke("render", run_id, "--config", cli_config, "--video", "kenburns", "--tts", "silent")
        assert result.exit_code == 1
        assert "승인되지 않아" in result.output

    def test_edit_flow(self, cli_config, drafted, tmp_path):
        run_id = drafted(2)
        out = tmp_path / "edit" / "storyboard.yaml"
        result = invoke("storyboard", "export", run_id, "--out", out, "--config", cli_config)
        assert result.exit_code == 0, result.output
        assert out.exists()
        assert (out.parent / "storyboard_v1.jpg").exists()

        text = out.read_text(encoding="utf-8").replace("title:", "title: 고침 #", 1)
        out.write_text(text, encoding="utf-8")
        result = invoke("storyboard", "import", run_id, "--file", out, "--config", cli_config)
        assert result.exit_code == 0, result.output
        assert "v2" in result.output

        result = invoke("storyboard", "diff", run_id, "--from", 1, "--to", 2, "--config", cli_config)
        assert result.exit_code == 0
        assert "title" in result.output

        result = invoke("storyboard", "approve", run_id, "--version", 1, "--config", cli_config)
        assert result.exit_code == 1
        assert "최신 버전" in result.output

        result = invoke(
            "storyboard", "approve", run_id, "--version", 2, "--accept-warnings", "--config", cli_config
        )
        assert result.exit_code == 0, result.output

    def test_validation_errors_are_printed_without_traceback(self, cli_config, drafted, tmp_path):
        run_id = drafted(2)
        bad = tmp_path / "bad.yaml"
        bad.write_text("base_version: 1\ntitle: x\ncuts: []\n", encoding="utf-8")
        result = invoke("storyboard", "import", run_id, "--file", bad, "--config", cli_config)
        assert result.exit_code == 1
        assert "no_cuts" not in result.output or "컷이 하나도" in result.output
        assert "Traceback" not in result.output

    def test_list_and_corpus_stats(self, cli_config, drafted):
        run_id = drafted(2)
        result = invoke("list", "--config", cli_config)
        assert result.exit_code == 0
        assert run_id[:4] in result.output
        result = invoke("corpus", "stats", "--config", cli_config)
        assert result.exit_code == 0
        assert "examples" in result.output

    def test_unknown_run_is_friendly(self, cli_config):
        result = invoke("storyboard", "export", "none", "--config", cli_config)
        assert result.exit_code == 1
        assert "찾을 수 없습니다" in result.output


def test_app_builds_providers(tmp_path, monkeypatch):
    config = tmp_path / "config.yaml"
    config.write_text("tts:\n  voice_id: v\n", encoding="utf-8")
    monkeypatch.setenv("ELEVENLABS_API_KEY", "k")
    context = App.load(config)
    assert context.make_tts("silent").name == "silent"
    assert context.make_tts().name == "elevenlabs"
    assert context.make_video("kenburns").name == "kenburns"


class TestMigration:
    def test_upgrade_matches_models_and_downgrades(self, tmp_path, monkeypatch):
        url = f"sqlite:///{(tmp_path / 'm.db').as_posix()}"
        monkeypatch.chdir(REPO_ROOT)
        config = Config(str(REPO_ROOT / "alembic.ini"))
        config.set_main_option("sqlalchemy.url", url)
        command.upgrade(config, "head")

        inspector = inspect(create_engine(url))
        for table in Base.metadata.sorted_tables:
            columns = {c["name"]: c for c in inspector.get_columns(table.name)}
            assert set(columns) == {c.name for c in table.columns}, table.name
            for column in table.columns:
                if not column.primary_key:
                    assert columns[column.name]["nullable"] == column.nullable, (
                        f"{table.name}.{column.name}"
                    )

        command.downgrade(config, "base")
        assert set(inspect(create_engine(url)).get_table_names()) <= {"alembic_version"}


class TestSheet:
    def test_sheet_handles_korean_and_missing_photo(self, drafted, service, tmp_path):
        from PIL import Image

        run_id = drafted(2)
        version, board = service.get_storyboard(run_id)
        photos = service.photos(run_id)
        out = make_storyboard_sheet(board, version, photos, tmp_path / "s.jpg")
        with Image.open(out) as image:
            assert image.width > 1000
        photos[0].path.unlink()
        assert make_storyboard_sheet(board, version, photos, tmp_path / "s2.jpg").exists()


class TestPrepareImage:
    def test_crops_without_distortion(self, tmp_path):
        from PIL import Image

        source = tmp_path / "a.jpg"
        noisy_image(1).save(source)
        out = prepare_input_image(source, [0.25, 0.0, 0.75, 1.0], (480, 832), tmp_path / "o" / "b.jpg")
        with Image.open(out) as image:
            assert image.size == (480, 832)


def _make_wav(path: Path, seconds: float) -> Path:
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(48000)
        handle.writeframes(b"\x00\x00" * int(seconds * 48000))
    return path


@needs_ffmpeg
@pytest.mark.ffmpeg
class TestRealFFmpeg:
    """공백이 있는 경로까지 포함해 실제 ffmpeg로 합성한다."""

    def test_kenburns_then_compose(self, tmp_path):
        work = tmp_path / "폴더 이름 공백"
        work.mkdir()
        cfg = ComposeSettings(preset="ultrafast")
        source = work / "photo.jpg"
        noisy_image(1).save(source)
        image = prepare_input_image(source, [0.2, 0.0, 0.76, 1.0], (1080, 1920), work / "in.jpg")
        video = KenBurnsVideo((1080, 1920), 30)
        cuts = []
        for index, move in enumerate(["slow_push_in", "pan_right", "handheld"]):
            clip = work / f"clip{index}.mp4"
            video.generate(ClipRequest(image, "", 33, 16, 1, move, clip))
            info = probe(clip)
            assert (info.width, info.height) == (1080, 1920)
            assert info.duration == pytest.approx(33 / 16, abs=0.1)
            audio = _make_wav(work / f"a{index}.wav", 1.5)
            cuts.append(
                ComposeCut(
                    cut_id=f"c{index}",
                    clip_path=clip,
                    audio_path=audio,
                    duration_sec=2.5,
                    speed=1.0,
                    events=[SubtitleEvent("한글 자막 시험", 0.0, 2.5)],
                    on_screen_text="강조 문구" if index == 0 else None,
                    transition_in="fade" if index == 1 else "hard_cut",
                )
            )
        out = work / "결과 영상.mp4"
        FFmpegComposer(cfg, tmp_path).compose(cuts, work / "compose dir", out)
        info = probe(out)
        assert (info.width, info.height) == (1080, 1920)
        assert info.codec == "h264"
        assert info.pix_fmt == "yuv420p"
        assert info.fps == pytest.approx(30, abs=0.1)
        assert info.has_audio
        assert info.duration == pytest.approx(7.5, abs=0.2)

        sheet = make_contact_sheet([(c.cut_id, c.clip_path) for c in cuts], work / "contact.jpg")
        assert sheet.exists()

    def test_probe_reports_audio_duration(self, tmp_path):
        assert probe(_make_wav(tmp_path / "a.wav", 1.25)).duration == pytest.approx(1.25, abs=0.01)
