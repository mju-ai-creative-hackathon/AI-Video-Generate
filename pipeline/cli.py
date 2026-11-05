"""vlog 명령줄 도구. 스토리보드 변경은 StoryboardService만 통해 수행한다."""

from __future__ import annotations

import functools
import logging
import shutil
import sys
from pathlib import Path
from typing import Annotated, Any

import httpx
import typer
from rich.console import Console
from rich.markup import escape
from rich.table import Table

from .app import App
from .db.repo import Repo, session_scope
from .errors import StaleBaseVersionError, StoryboardValidationError, VlogError
from .sheets import make_storyboard_sheet
from .store import check_consistency
from .storyboard_service import FieldChange
from .validation import ValidationReport

app = typer.Typer(no_args_is_help=True, add_completion=False, help="사진 기반 숏폼 브이로그 파이프라인")
storyboard_app = typer.Typer(no_args_is_help=True, help="스토리보드 수정·재생성·승인")
corpus_app = typer.Typer(no_args_is_help=True, help="구도 데이터 현황")
app.add_typer(storyboard_app, name="storyboard")
app.add_typer(corpus_app, name="corpus")

console = Console()


def _progress(message: str) -> None:
    console.print(escape(message), highlight=False)


ConfigOption = Annotated[Path, typer.Option("--config", help="설정 파일 경로")]
DEFAULT_CONFIG = Path("config.yaml")


def _friendly(func):  # noqa: ANN001, ANN202
    """예상한 오류는 스택 없이 메시지만 보여준다."""

    @functools.wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        try:
            return func(*args, **kwargs)
        except StoryboardValidationError as exc:
            console.print("[red]검증 오류로 저장하지 않았습니다.[/red]")
            _print_report(exc.report)
            raise typer.Exit(1) from exc
        except StaleBaseVersionError as exc:
            console.print(f"[red]{escape(str(exc))}[/red]")
            _print_changes(exc.changes, f"v{exc.base_version} → v{exc.latest_version}의 변경")
            raise typer.Exit(1) from exc
        except VlogError as exc:
            console.print(f"[red]{escape(str(exc))}[/red]")
            raise typer.Exit(1) from exc

    return wrapper


def _print_report(report: ValidationReport) -> None:
    for issue in report.errors:
        console.print(f"  [red]오류[/red] {escape(str(issue))}", highlight=False)
    for issue in report.warnings:
        console.print(f"  [yellow]경고[/yellow] {escape(str(issue))}", highlight=False)


def _short(value: Any) -> str:
    text = "" if value is None else str(value)
    return text if len(text) <= 60 else text[:57] + "..."


def _print_changes(changes: list[FieldChange], title: str = "변경 내용") -> None:
    if not changes:
        console.print("변경 내용이 없습니다.")
        return
    table = Table(title=title)
    table.add_column("컷")
    table.add_column("항목")
    table.add_column("이전")
    table.add_column("이후")
    for change in changes:
        table.add_row(change.cut_id or "-", change.field, _short(change.before), _short(change.after))
    console.print(table)


@app.callback()
def _setup(verbose: Annotated[bool, typer.Option("--verbose", "-v")] = False) -> None:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    logging.basicConfig(
        level=logging.INFO if verbose else logging.WARNING,
        format="%(levelname)s %(name)s: %(message)s",
    )


# ── plan ──────────────────────────────────────────────────────────────────
@app.command()
@_friendly
def plan(
    photos: Annotated[Path | None, typer.Argument(help="사진 폴더")] = None,
    memo: Annotated[str | None, typer.Option(help="영상에 반영할 메모")] = None,
    format_id: Annotated[str | None, typer.Option("--format", help="포맷 직접 지정")] = None,
    resume: Annotated[str | None, typer.Option(help="중단된 run_id 이어서 실행")] = None,
    order: Annotated[
        str, typer.Option(help="time: 촬영 시각순, number: 파일 이름 번호순으로 전부 사용")
    ] = "time",
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """사진을 분석해 AI 초안을 저장하고 멈춘다. TTS와 영상 생성은 하지 않는다."""
    if photos is None and resume is None:
        raise VlogError("사진 폴더나 --resume을 지정하세요.")
    if order not in ("time", "number"):
        raise VlogError(f"--order는 time 또는 number여야 합니다: {order}")
    context = App.load(config)
    result = context.plan_pipeline(_progress).run(
        photos, memo, format_id=format_id, resume_run_id=resume, order=order
    )
    console.print(f"run_id: [bold]{result.run_id}[/bold], 초안 v{result.version} 저장")
    for photo_id, reason in result.excluded:
        console.print(f"  제외된 사진 {photo_id}: {reason}")
    for note in result.notes:
        console.print(f"  [yellow]안내[/yellow] {escape(note)}", highlight=False)
    _print_report(result.report)
    console.print(
        f"다음 단계: vlog storyboard export {result.run_id} --out storyboard.yaml", highlight=False
    )


# ── storyboard ────────────────────────────────────────────────────────────
@storyboard_app.command("export")
@_friendly
def storyboard_export(
    run_id: str,
    out: Annotated[Path, typer.Option(help="내보낼 YAML 경로")] = Path("storyboard.yaml"),
    version: Annotated[int | None, typer.Option(help="버전. 생략하면 최신")] = None,
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """수정용 YAML과 썸네일 시트를 내보낸다."""
    context = App.load(config)
    number, text = context.service.export_yaml(run_id, version)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    _, storyboard = context.service.get_storyboard(run_id, number)
    sheet = make_storyboard_sheet(
        storyboard,
        number,
        context.service.photos(run_id),
        out.parent / f"storyboard_v{number}.jpg",
        context.settings.resolve(context.settings.compose.fonts_dir),
    )
    console.print(f"v{number} 내보냄: {out}, {sheet}", highlight=False)
    _print_report(context.service.validate(run_id, number))


@storyboard_app.command("import")
@_friendly
def storyboard_import(
    run_id: str,
    file: Annotated[Path, typer.Option("--file", help="수정한 YAML")],
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """수정한 YAML을 검증해 새 버전으로 저장한다."""
    context = App.load(config)
    if not file.exists():
        raise VlogError(f"파일이 없습니다: {file}")
    result = context.service.import_yaml(run_id, file.read_text(encoding="utf-8"))
    if not result.changed:
        console.print(f"변경 내용이 없어 v{result.version}을 유지합니다.")
    else:
        console.print(f"새 버전 v{result.version} 저장")
        _print_changes(result.changes)
    _print_report(result.warnings)


@storyboard_app.command("regen")
@_friendly
def storyboard_regen(
    run_id: str,
    cut: Annotated[list[str] | None, typer.Option("--cut", help="컷 ID. 생략하면 전체")] = None,
    field: Annotated[list[str] | None, typer.Option("--field", help="항목. 생략하면 전체")] = None,
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """지정한 컷과 항목만 AI로 다시 만든다. 잠긴 항목은 건드리지 않는다."""
    outcome = App.load(config).service.regen(run_id, cut_ids=cut, fields=field)
    for cut_id, name in outcome.skipped_locked:
        console.print(f"  잠겨 있어 건너뜀: {cut_id}.{name}", highlight=False)
    console.print(f"현재 버전 v{outcome.version}")
    _print_changes(outcome.changes)
    _print_report(outcome.warnings)


@storyboard_app.command("diff")
@_friendly
def storyboard_diff(
    run_id: str,
    from_version: Annotated[int, typer.Option("--from")],
    to_version: Annotated[int, typer.Option("--to")],
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """두 버전의 차이를 보여준다."""
    changes = App.load(config).service.diff(run_id, from_version, to_version)
    _print_changes(changes, f"v{from_version} → v{to_version}")


@storyboard_app.command("revert")
@_friendly
def storyboard_revert(
    run_id: str,
    version: Annotated[int, typer.Option(help="되돌릴 버전")],
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """이전 버전의 내용을 새 초안으로 복사한다."""
    number = App.load(config).service.revert(run_id, version)
    console.print(f"v{version}의 내용으로 새 초안 v{number}을 만들었습니다.")


@storyboard_app.command("approve")
@_friendly
def storyboard_approve(
    run_id: str,
    version: Annotated[int | None, typer.Option(help="승인할 버전. 생략하면 최신")] = None,
    accept_warnings: Annotated[bool, typer.Option("--accept-warnings", help="경고를 확인함")] = False,
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """스토리보드를 승인한다. 승인된 버전만 TTS와 영상 생성에 쓰인다."""
    number, report = App.load(config).service.approve(
        run_id, version, accept_warnings=accept_warnings
    )
    console.print(f"v{number} 승인 완료. 다음 단계: vlog render {run_id}", highlight=False)
    _print_report(report)


@storyboard_app.command("versions")
@_friendly
def storyboard_versions(run_id: str, config: ConfigOption = DEFAULT_CONFIG) -> None:
    """버전 목록을 보여준다."""
    context = App.load(config)
    with session_scope(context.factory) as session:
        rows = Repo(session).list_versions(run_id)
        table = Table(title=run_id)
        for column in ("버전", "작성", "상태", "포맷", "생성", "승인"):
            table.add_column(column)
        for row in rows:
            table.add_row(
                str(row.version),
                row.author,
                row.status,
                row.format_id or "",
                f"{row.created_at:%m-%d %H:%M}",
                f"{row.approved_at:%m-%d %H:%M}" if row.approved_at else "",
            )
    console.print(table)


# ── render ────────────────────────────────────────────────────────────────
VideoOption = Annotated[str | None, typer.Option("--video", help="comfyui, veo 또는 kenburns")]
TTSOption = Annotated[str | None, typer.Option("--tts", help="elevenlabs 또는 silent")]


def _print_render(result: Any) -> None:
    console.print(f"완성본: {result.video_path}", highlight=False)
    if result.reused_video:
        console.print("  입력이 같아 기존 완성본을 재사용했습니다.")
    console.print(
        f"  이미지 생성 {len(result.images_generated)}컷, TTS 생성 {len(result.tts_generated)}컷, "
        f"영상 생성 {len(result.clips_generated)}컷"
    )
    if result.fallback_cuts:
        console.print(f"  [yellow]Ken Burns로 대체된 컷: {result.fallback_cuts}[/yellow]")
    if result.contact_sheet:
        console.print(f"  대표 프레임: {result.contact_sheet}", highlight=False)


@app.command()
@_friendly
def render(
    run_id: str,
    video: VideoOption = None,
    tts: TTSOption = None,
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """승인된 스토리보드로 TTS, 영상 생성, 합성, 저장을 실행한다."""
    pipeline = App.load(config).render_pipeline(tts=tts, video=video, progress=_progress)
    _print_render(pipeline.run(run_id))


@app.command()
@_friendly
def images(
    run_id: str,
    cut: Annotated[str | None, typer.Option("--cut", help="이 컷만. 생략하면 effect가 있는 전체")] = None,
    again: Annotated[bool, typer.Option("--again", help="--cut의 이미지를 새로 만든다")] = False,
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """승인된 스토리보드의 효과 이미지만 만든다. 영상 요금을 쓰기 전에 결과를 확인한다."""
    if again and cut is None:
        raise VlogError("--again은 --cut과 함께 쓰세요.")
    pipeline = App.load(config).render_pipeline(progress=_progress)
    results = pipeline.make_images(run_id, cut_id=cut, again=again)
    if not results:
        console.print("effect가 있는 컷이 없습니다.")
    for item, asset in results:
        state = "새로 만듦" if asset.generated else "재사용"
        console.print(f"{item.cut_id} {escape(item.effect or '')} ({state}): {asset.path}", highlight=False)


@app.command()
@_friendly
def regen(
    run_id: str,
    cut: Annotated[str, typer.Option("--cut", help="다시 만들 컷 ID")],
    seed: Annotated[int | None, typer.Option(help="시드. 생략하면 무작위")] = None,
    video: VideoOption = None,
    tts: TTSOption = None,
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """특정 컷의 영상만 다시 만들고 합성한다."""
    pipeline = App.load(config).render_pipeline(tts=tts, video=video, progress=_progress)
    _print_render(pipeline.regen_clip(run_id, cut, seed))


# ── API 서버 ──────────────────────────────────────────────────────────────
@app.command()
@_friendly
def serve(
    host: Annotated[str, typer.Option(help="LAN에 열려면 이 PC의 IP 또는 0.0.0.0")] = "127.0.0.1",
    port: Annotated[int, typer.Option()] = 8000,
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """프론트가 호출할 API 서버를 실행한다. 작업자가 하나라 프로세스도 하나만 띄운다."""
    import uvicorn

    from .api.app import create_app
    from .api.security import is_loopback

    settings = App.load(config).settings
    if not is_loopback(host) and not settings.env.api_key:
        raise VlogError(
            f"{host}로 열려면 .env에 API_KEY를 넣으세요. 인증 없이 네트워크에 열 수 없습니다."
        )
    uvicorn.run(create_app(config), host=host, port=port, workers=1)


@app.command()
@_friendly
def openapi(
    out: Annotated[Path, typer.Option(help="저장 경로")] = Path("docs/openapi.json"),
    config: ConfigOption = DEFAULT_CONFIG,
) -> None:
    """프론트 팀원에게 줄 API 명세를 저장한다. DB나 작업자는 쓰지 않는다."""
    import json

    from .api.app import create_app

    schema = create_app(config, start_worker=False).openapi()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(schema, ensure_ascii=False, indent=2), encoding="utf-8")
    console.print(f"저장: {out}", highlight=False)


# ── 조회와 점검 ───────────────────────────────────────────────────────────
@app.command("list")
@_friendly
def list_runs(config: ConfigOption = DEFAULT_CONFIG) -> None:
    """실행 목록을 보여준다."""
    context = App.load(config)
    with session_scope(context.factory) as session:
        repo = Repo(session)
        table = Table()
        for column in ("run_id", "상태", "최신 버전", "완성본", "메모", "생성"):
            table.add_column(column)
        for run in repo.list_runs():
            latest = repo.latest_version(run.id)
            table.add_row(
                run.id,
                run.status,
                f"v{latest.version} ({latest.status})" if latest else "-",
                str(len(repo.videos(run.id))),
                _short(run.memo),
                f"{run.created_at:%Y-%m-%d %H:%M}",
            )
    console.print(table)


@app.command()
@_friendly
def doctor(config: ConfigOption = DEFAULT_CONFIG) -> None:
    """도구와 서비스 상태, DB와 저장소의 일치 여부를 점검한다. 아무것도 지우지 않는다."""
    context = App.load(config)
    env = context.settings.env

    def line(ok: bool, name: str, detail: str = "") -> None:
        mark = "[green]OK[/green]" if ok else "[red]없음[/red]"
        console.print(f"{mark} {name} {detail}", highlight=False)

    for tool in ("ffmpeg", "ffprobe", "nvidia-smi"):
        path = shutil.which(tool)
        line(bool(path), tool, path or "")
    for name, url in (
        ("Ollama", f"{env.ollama_url}/api/version"),
        ("ComfyUI", f"{env.comfyui_url}/system_stats"),
    ):
        try:
            line(httpx.get(url, timeout=3).status_code == 200, name, url)
        except httpx.HTTPError:
            line(False, name, url)
    line(bool(env.elevenlabs_api_key), "ELEVENLABS_API_KEY")
    line(bool(context.settings.tts.voice_id), "tts.voice_id")
    line(bool(env.gemini_api_key), "GEMINI_API_KEY", "(effect 컷에 필요)")
    line(bool(env.google_cloud_project), "GOOGLE_CLOUD_PROJECT", "(--video veo에 필요)")
    line(bool(shutil.which("gcloud")), "gcloud", "(--video veo에 필요)")

    report = check_consistency(context.factory, context.settings)
    console.print(f"완성본 {report.checked}개 대조")
    for title, items in (
        ("파일 없음", report.missing_files),
        ("크기 불일치", report.size_mismatches),
        ("해시 불일치", report.hash_mismatches),
        ("DB에 없는 파일", report.orphan_files),
    ):
        for item in items:
            console.print(f"  [red]{title}[/red] {item}", highlight=False)
    if report.ok:
        console.print("[green]DB와 저장소가 일치합니다.[/green]")
    else:
        raise typer.Exit(1)


@corpus_app.command("stats")
@_friendly
def corpus_stats(config: ConfigOption = DEFAULT_CONFIG) -> None:
    """구도 데이터 적재 현황을 보여준다. 비어 있으면 규칙 기반 크롭을 쓴다."""
    context = App.load(config)
    with session_scope(context.factory) as session:
        stats = Repo(session).corpus_stats()
    console.print(stats)


if __name__ == "__main__":
    app()
