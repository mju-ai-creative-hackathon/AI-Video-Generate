"""`vlog render`: 승인된 스토리보드로 TTS → 컷 영상 → 합성 → 저장을 실행한다.

- 시작 전에 승인 관문을 통과해야 한다. 통과 전에는 TTS와 영상 어댑터를 만들지도 않는다.
- 음성을 먼저 만들어 실제 길이에 맞는 프레임만 생성한다.
- 단계별 입력 해시를 비교해 바뀐 컷만 다시 만들고, 중단돼도 이어서 실행한다.
"""

from __future__ import annotations

import hashlib
import logging
import random
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from sqlalchemy.orm import Session, sessionmaker

from .compose import ComposeCut, Composer, frame_exact, make_contact_sheet
from .config import Settings
from .db.models import (
    ASSET_AUDIO,
    ASSET_CLIP,
    ASSET_DONE,
    ASSET_FAILED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_NARRATED,
    RUN_RENDERED,
    CutAsset,
)
from .db.repo import Repo, session_scope
from .errors import ReapprovalRequiredError, VideoError, VlogError
from .frames import CutTiming, allowed_audio_sec, plan_timing
from .gpu import ensure_free_vram
from .hashing import clip_input_hash, clip_visual_key, compose_hash, tts_input_hash
from .media import MediaInfo, prepare_input_image, probe, probe_duration
from .schemas import Cut, PhotoRecord, Storyboard
from .store import LocalVideoStore, VideoStore, relative_to_root, save_final
from .storyboard_service import ApprovedStoryboard, StoryboardService
from .subtitles import SubtitleEvent, WordTiming, chunk_words, proportional_words
from .tts.base import TTSProvider, TTSRequest
from .validation import spoken_chars
from .video.base import ClipRequest, VideoProvider

log = logging.getLogger(__name__)


@dataclass
class AudioAsset:
    input_hash: str
    path: Path
    audio_sec: float
    words: list[WordTiming]


@dataclass
class ClipAsset:
    input_hash: str
    path: Path
    fallback: bool = False


@dataclass
class RenderResult:
    run_id: str
    version: int
    video_path: Path
    sha256: str
    reused_video: bool = False
    tts_generated: list[str] = field(default_factory=list)
    clips_generated: list[str] = field(default_factory=list)
    fallback_cuts: list[str] = field(default_factory=list)
    contact_sheet: Path | None = None


class RenderPipeline:
    def __init__(
        self,
        factory: sessionmaker[Session],
        settings: Settings,
        service: StoryboardService,
        *,
        tts_factory: Callable[[], TTSProvider],
        video_factory: Callable[[], VideoProvider],
        fallback_factory: Callable[[], VideoProvider] | None,
        composer: Composer,
        store: VideoStore | None = None,
        duration_probe: Callable[[Path], float] = probe_duration,
        video_probe: Callable[[Path], MediaInfo] = probe,
        contact_sheet: Callable[[list[tuple[str, Path]], Path], Path] | None = make_contact_sheet,
    ) -> None:
        self.factory = factory
        self.settings = settings
        self.service = service
        self.tts_factory = tts_factory
        self.video_factory = video_factory
        self.fallback_factory = fallback_factory
        self.composer = composer
        self.store = store or LocalVideoStore(settings.storage_path)
        self.duration_probe = duration_probe
        self.video_probe = video_probe
        self.contact_sheet = contact_sheet

    # ── 입구 ──────────────────────────────────────────────────────────────
    def run(self, run_id: str) -> RenderResult:
        approved = self.service.require_approved(run_id)  # 승인 관문
        try:
            return self._run(approved)
        except ReapprovalRequiredError:
            raise
        except Exception as exc:
            self._set_status(run_id, RUN_FAILED, str(exc)[:2000])
            raise

    def regen_clip(self, run_id: str, cut_id: str, seed: int | None = None) -> RenderResult:
        """특정 컷만 새 시드로 다시 만들고 합성한다. 다른 컷은 해시가 같아 재사용된다."""
        approved = self.service.require_approved(run_id)
        if approved.storyboard.cut(cut_id) is None:
            raise VlogError(f"없는 컷입니다: {cut_id}")
        chosen = seed if seed is not None else random.randrange(2**32)
        with session_scope(self.factory) as session:
            run = Repo(session).get_run(run_id)
            config = dict(run.config or {})
            config["seeds"] = {**config.get("seeds", {}), cut_id: chosen}
            run.config = config
        return self.run(run_id)

    # ── 경로와 상태 ───────────────────────────────────────────────────────
    def _work_dir(self, run_id: str) -> Path:
        return self.settings.storage_path / "runs" / run_id

    def _rel(self, path: Path) -> str:
        return relative_to_root(path, self.settings.root)

    def _set_status(self, run_id: str, status: str, error: str | None = None) -> None:
        with session_scope(self.factory) as session:
            run = Repo(session).get_run(run_id)
            run.status = status
            run.error = error

    def _seed_for(self, run_id: str, cut_id: str) -> int:
        with session_scope(self.factory) as session:
            config = Repo(session).get_run(run_id).config or {}
        chosen = config.get("seeds", {}).get(cut_id)
        if chosen is not None:
            return int(chosen)
        digest = hashlib.sha256(cut_id.encode()).hexdigest()
        return (self.settings.video.base_seed + int(digest[:8], 16)) % (2**32)

    # ── 전체 흐름 ─────────────────────────────────────────────────────────
    def _run(self, approved: ApprovedStoryboard) -> RenderResult:
        run_id = approved.run_id
        cuts = approved.storyboard.ordered_cuts()
        result_generated: list[str] = []

        tts = self.tts_factory()
        audio = self._narrate(run_id, cuts, tts, result_generated)
        timings = {c.cut_id: plan_timing(audio[c.cut_id].audio_sec, self.settings.timing) for c in cuts}
        self._handle_too_long(run_id, approved.storyboard, audio, timings)
        self._set_status(run_id, RUN_NARRATED)

        photos = {p.photo_id: p for p in self.service.photos(run_id)}
        clips, clips_generated, fallback_cuts = self._render_clips(run_id, cuts, photos, timings)
        self._set_status(run_id, RUN_RENDERED)

        sheet = self._make_contact_sheet(run_id, cuts, clips)
        path, digest, reused = self._compose_and_store(approved, cuts, audio, clips, timings)
        return RenderResult(
            run_id=run_id,
            version=approved.version,
            video_path=path,
            sha256=digest,
            reused_video=reused,
            tts_generated=result_generated,
            clips_generated=clips_generated,
            fallback_cuts=fallback_cuts,
            contact_sheet=sheet,
        )

    # ── [5] 나레이션 ──────────────────────────────────────────────────────
    def _narrate(
        self, run_id: str, cuts: list[Cut], tts: TTSProvider, generated: list[str]
    ) -> dict[str, AudioAsset]:
        signature = tts.signature()
        assets: dict[str, AudioAsset] = {}
        for index, cut in enumerate(cuts):
            input_hash = tts_input_hash(cut.narration, signature)
            existing = self._find_audio(run_id, cut.cut_id, input_hash)
            if existing is not None:
                assets[cut.cut_id] = existing
                continue
            out_path = (
                self._work_dir(run_id) / "audio" / f"{cut.cut_id}_{input_hash[:10]}{tts.extension}"
            )
            request = TTSRequest(
                text=cut.narration,
                previous_text=cuts[index - 1].narration if index > 0 else None,
                next_text=cuts[index + 1].narration if index + 1 < len(cuts) else None,
            )
            result = tts.synthesize(request, out_path)
            audio_sec = self.duration_probe(out_path)
            words = result.words or proportional_words(cut.narration, audio_sec)
            with session_scope(self.factory) as session:
                session.add(
                    CutAsset(
                        run_id=run_id,
                        cut_id=cut.cut_id,
                        kind=ASSET_AUDIO,
                        input_hash=input_hash,
                        path=self._rel(out_path),
                        audio_sec=audio_sec,
                        status=ASSET_DONE,
                        meta={
                            "words": [w.to_dict() for w in words],
                            "has_timestamps": bool(result.words),
                        },
                    )
                )
            generated.append(cut.cut_id)
            assets[cut.cut_id] = AudioAsset(input_hash, out_path, audio_sec, words)
        return assets

    def _find_audio(self, run_id: str, cut_id: str, input_hash: str) -> AudioAsset | None:
        with session_scope(self.factory) as session:
            row = Repo(session).find_asset(run_id, cut_id, ASSET_AUDIO, input_hash)
            if row is None or not row.path:
                return None
            path = self.settings.resolve(row.path)
            if not path.exists():
                return None
            words = [WordTiming(**w) for w in (row.meta or {}).get("words", [])]
            return AudioAsset(input_hash, path, row.audio_sec or 0.0, words)

    def _handle_too_long(
        self,
        run_id: str,
        storyboard: Storyboard,
        audio: dict[str, AudioAsset],
        timings: dict[str, CutTiming],
    ) -> None:
        """나레이션이 너무 길면 줄인 초안을 만들고 멈춘다.

        승인되지 않은 문장으로 음성을 만들 수 없으므로 자동으로 재합성하지 않는다.
        이미 만든 음성은 해시로 재사용되어, 다시 승인하면 바뀐 컷만 재합성한다.
        """
        too_long = [c for c in storyboard.ordered_cuts() if timings[c.cut_id].too_long]
        if not too_long:
            return
        allowed = allowed_audio_sec(self.settings.timing)
        limits: dict[str, int] = {}
        locked: list[str] = []
        for cut in too_long:
            if "narration" in cut.locked:
                locked.append(cut.cut_id)
                continue
            ratio = allowed / audio[cut.cut_id].audio_sec
            limits[cut.cut_id] = max(1, int(spoken_chars(cut.narration) * ratio * 0.95))
        detail = ", ".join(f"{c.cut_id}({audio[c.cut_id].audio_sec:.1f}초)" for c in too_long)
        message = f"나레이션이 영상 최대 길이를 넘습니다: {detail}."
        if locked:
            message += f" 잠긴 컷 {locked}은 직접 줄이세요."
        if limits:
            version = self.service.propose_shortened(run_id, limits)
            message += (
                f" 줄인 초안 v{version}을 저장했습니다. 검토 후 "
                f"`vlog storyboard approve {run_id} --version {version}`을 실행하세요."
            )
        raise ReapprovalRequiredError(message)

    # ── [6] 컷 영상 ───────────────────────────────────────────────────────
    def _render_clips(
        self,
        run_id: str,
        cuts: list[Cut],
        photos: dict[str, PhotoRecord],
        timings: dict[str, CutTiming],
    ) -> tuple[dict[str, ClipAsset], list[str], list[str]]:
        video = self.video_factory()
        clips: dict[str, ClipAsset] = {}
        generated: list[str] = []
        fallbacks: list[str] = []
        try:
            for cut in cuts:
                photo = photos.get(cut.photo_id)
                if photo is None:
                    raise VlogError(f"사진을 찾을 수 없습니다: {cut.photo_id}")
                seed = self._seed_for(run_id, cut.cut_id)
                visual = clip_visual_key(
                    provider=video.name,
                    photo_sha256=photo.sha256,
                    crop_box=cut.shot.crop_box,
                    prompt=cut.motion_prompt,
                    seed=seed,
                    camera_move=cut.shot.camera_move,
                    size=video.input_size,
                )
                timing = timings[cut.cut_id]
                input_hash = clip_input_hash(visual, timing.frames)
                existing = self._find_clip(run_id, cut.cut_id, input_hash, visual, timing.frames)
                if existing is not None:
                    clips[cut.cut_id] = existing
                    continue
                asset = self._generate_clip(
                    run_id, cut, photo, timing, video, seed, visual, input_hash
                )
                clips[cut.cut_id] = asset
                generated.append(cut.cut_id)
                if asset.fallback:
                    fallbacks.append(cut.cut_id)
        finally:
            video.release()
        return clips, generated, fallbacks

    def _find_clip(
        self, run_id: str, cut_id: str, input_hash: str, visual: str, frames: int
    ) -> ClipAsset | None:
        with session_scope(self.factory) as session:
            repo = Repo(session)
            row = repo.find_asset(run_id, cut_id, ASSET_CLIP, input_hash)
            if row is None:
                # 나레이션이 짧아졌다면 같은 입력의 더 긴 클립을 그대로 쓴다.
                row = next(
                    (
                        r
                        for r in repo.done_assets(run_id, cut_id, ASSET_CLIP)
                        if (r.meta or {}).get("visual_key") == visual
                        and (r.meta or {}).get("frames", 0) >= frames
                    ),
                    None,
                )
            if row is None or not row.path:
                return None
            path = self.settings.resolve(row.path)
            if not path.exists():
                return None
            return ClipAsset(row.input_hash, path, bool((row.meta or {}).get("fallback")))

    def _generate_clip(
        self,
        run_id: str,
        cut: Cut,
        photo: PhotoRecord,
        timing: CutTiming,
        video: VideoProvider,
        seed: int,
        visual: str,
        input_hash: str,
    ) -> ClipAsset:
        work = self._work_dir(run_id)
        out_path = work / "clips" / f"{cut.cut_id}_{input_hash[:10]}.mp4"
        image = prepare_input_image(
            photo.path,
            cut.shot.crop_box,
            video.input_size,
            work / "inputs" / f"{cut.cut_id}_{input_hash[:10]}.jpg",
        )
        last_error = ""
        attempts = self.settings.video.max_retries + 1
        for attempt in range(attempts):
            used_seed = (seed + attempt) % (2**32)
            try:
                if video.requires_gpu:
                    ensure_free_vram(self.settings.video.min_free_vram_mb)
                video.generate(
                    ClipRequest(
                        image_path=image,
                        prompt=cut.motion_prompt,
                        frames=timing.frames,
                        fps=self.settings.timing.source_fps,
                        seed=used_seed,
                        camera_move=cut.shot.camera_move,
                        out_path=out_path,
                    )
                )
                self._record_clip(
                    run_id, cut, input_hash, out_path, used_seed,
                    {"visual_key": visual, "frames": timing.frames, "provider": video.name},
                )
                return ClipAsset(input_hash, out_path)
            except VlogError as exc:
                last_error = str(exc)
                log.warning(
                    "컷 %s 생성 실패 (%d/%d): %s", cut.cut_id, attempt + 1, attempts, last_error
                )
        self._record_failure(run_id, cut, input_hash, seed, last_error)
        return self._fallback_clip(run_id, cut, photo, timing, seed, visual, input_hash, last_error)

    def _fallback_clip(
        self,
        run_id: str,
        cut: Cut,
        photo: PhotoRecord,
        timing: CutTiming,
        seed: int,
        visual: str,
        input_hash: str,
        reason: str,
    ) -> ClipAsset:
        """재시도 후에도 실패한 컷은 카메라 움직임을 따르는 Ken Burns로 대체하고 기록한다."""
        if self.fallback_factory is None:
            raise VideoError(f"컷 {cut.cut_id} 생성 실패: {reason}")
        fallback = self.fallback_factory()
        work = self._work_dir(run_id)
        image = prepare_input_image(
            photo.path,
            cut.shot.crop_box,
            fallback.input_size,
            work / "inputs" / f"{cut.cut_id}_{input_hash[:10]}_fallback.jpg",
        )
        out_path = work / "clips" / f"{cut.cut_id}_{input_hash[:10]}_fallback.mp4"
        fallback.generate(
            ClipRequest(
                image_path=image,
                prompt=cut.motion_prompt,
                frames=timing.frames,
                fps=self.settings.timing.source_fps,
                seed=seed,
                camera_move=cut.shot.camera_move,
                out_path=out_path,
            )
        )
        self._record_clip(
            run_id, cut, input_hash, out_path, seed,
            {
                "visual_key": visual,
                "frames": timing.frames,
                "provider": fallback.name,
                "fallback": True,
                "fallback_reason": reason[:500],
            },
        )
        return ClipAsset(input_hash, out_path, fallback=True)

    def _record_clip(
        self,
        run_id: str,
        cut: Cut,
        input_hash: str,
        path: Path,
        seed: int,
        meta: dict[str, Any],
    ) -> None:
        with session_scope(self.factory) as session:
            session.add(
                CutAsset(
                    run_id=run_id,
                    cut_id=cut.cut_id,
                    kind=ASSET_CLIP,
                    input_hash=input_hash,
                    path=self._rel(path),
                    seed=seed,
                    status=ASSET_DONE,
                    meta=meta,
                )
            )

    def _record_failure(
        self, run_id: str, cut: Cut, input_hash: str, seed: int, error: str
    ) -> None:
        with session_scope(self.factory) as session:
            session.add(
                CutAsset(
                    run_id=run_id,
                    cut_id=cut.cut_id,
                    kind=ASSET_CLIP,
                    input_hash=input_hash,
                    seed=seed,
                    status=ASSET_FAILED,
                    error=error[:2000],
                    meta={},
                )
            )

    def _make_contact_sheet(
        self, run_id: str, cuts: list[Cut], clips: dict[str, ClipAsset]
    ) -> Path | None:
        if self.contact_sheet is None:
            return None
        out = self._work_dir(run_id) / "contact_sheet.jpg"
        try:
            return self.contact_sheet([(c.cut_id, clips[c.cut_id].path) for c in cuts], out)
        except VlogError as exc:  # 대표 프레임 시트는 보조 기능이므로 합성을 막지 않는다
            log.warning("대표 프레임 시트를 만들지 못했습니다: %s", exc)
            return None

    # ── [7~8] 합성과 저장 ─────────────────────────────────────────────────
    def _compose_and_store(
        self,
        approved: ApprovedStoryboard,
        cuts: list[Cut],
        audio: dict[str, AudioAsset],
        clips: dict[str, ClipAsset],
        timings: dict[str, CutTiming],
    ) -> tuple[Path, str, bool]:
        run_id = approved.run_id
        cfg = self.settings.compose
        parts = [
            {
                "audio": audio[c.cut_id].input_hash,
                "clip": clips[c.cut_id].input_hash,
                "narration": c.narration,
                "text": c.shot.on_screen_text,
                "transition": c.shot.transition_in,
                "duration": timings[c.cut_id].duration_sec,
                "speed": timings[c.cut_id].speed,
            }
            for c in cuts
        ]
        settings_part = cfg.model_dump()
        digest = compose_hash(parts, settings_part)

        with session_scope(self.factory) as session:
            existing = Repo(session).find_video(run_id, digest)
            if existing is not None and self.settings.resolve(existing.path).exists():
                run = Repo(session).get_run(run_id)
                run.status = RUN_COMPLETED
                run.error = None
                return self.settings.resolve(existing.path), existing.sha256, True

        compose_cuts = [
            ComposeCut(
                cut_id=c.cut_id,
                clip_path=clips[c.cut_id].path,
                audio_path=audio[c.cut_id].path,
                duration_sec=timings[c.cut_id].duration_sec,
                speed=timings[c.cut_id].speed,
                events=_events_for(audio[c.cut_id], timings[c.cut_id], cfg.max_chars_per_line),
                on_screen_text=c.shot.on_screen_text,
                transition_in=c.shot.transition_in,
            )
            for c in cuts
        ]
        work_dir = self._work_dir(run_id) / "compose"
        temp = work_dir / f"final_{digest[:12]}.part.mp4"
        self.composer.compose(compose_cuts, work_dir, temp)
        video = save_final(
            self.factory,
            self.settings,
            self.store,
            temp,
            run_id=run_id,
            version=approved.version,
            compose_hash=digest,
            expected_duration=sum(frame_exact(t.duration_sec, cfg.fps) for t in timings.values()),
            probe_fn=self.video_probe,
        )
        return self.settings.resolve(video.path), video.sha256, False


def _events_for(asset: AudioAsset, timing: CutTiming, max_chars: int) -> list[SubtitleEvent]:
    return chunk_words(asset.words, max_chars, timing.duration_sec)
