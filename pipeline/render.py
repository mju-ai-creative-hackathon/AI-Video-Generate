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

from .compose import ComposeCut, Composer, MusicInput, frame_exact, make_contact_sheet
from .config import Settings
from .db.models import (
    ASSET_AUDIO,
    ASSET_CLIP,
    ASSET_DONE,
    ASSET_FAILED,
    ASSET_IMAGE,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_NARRATED,
    RUN_RENDERED,
    CutAsset,
)
from .db.repo import Repo, session_scope
from .effects import ImageEffects
from .errors import ConfigError, ReapprovalRequiredError, VideoError, VlogError
from .frames import (
    CutTiming,
    allowed_audio_sec,
    plan_fixed_timing,
    plan_source_timing,
    plan_timing,
)
from .gpu import wait_for_free_vram
from .hashing import (
    clip_input_hash,
    clip_visual_key,
    compose_hash,
    image_input_hash,
    source_clip_hash,
    tts_input_hash,
)
from .image.base import ImageProvider, ImageRequest
from .media import MediaInfo, prepare_input_image, probe, probe_duration
from .schemas import Cut, PhotoRecord, Storyboard
from .store import LocalVideoStore, VideoStore, relative_to_root, save_final
from .storyboard_service import ApprovedStoryboard, StoryboardService
from .subtitles import SubtitleEvent, WordTiming, chunk_words, proportional_words
from .tts.base import TTSProvider, TTSRequest
from .music import MusicLibrary
from .validation import music_problem, spoken_chars
from .video.base import ClipRequest, VideoProvider

log = logging.getLogger(__name__)
FULL_FRAME = [0.0, 0.0, 1.0, 1.0]


@dataclass
class ImageAsset:
    input_hash: str
    path: Path
    generated: bool = False


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
    images_generated: list[str] = field(default_factory=list)
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
        progress: Callable[[str], None] | None = None,
        image_factory: Callable[[], ImageProvider] | None = None,
        effects: ImageEffects | None = None,
        music: MusicLibrary | None = None,
    ) -> None:
        self.image_factory = image_factory
        self.effects = effects
        self.music = music
        self._gpu_ready = False
        self.progress = progress or (lambda _message: None)
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
        cut = approved.storyboard.cut(cut_id)
        if cut is None:
            raise VlogError(f"없는 컷입니다: {cut_id}")
        if cut.source_clip is not None:
            raise VlogError(f"컷 {cut_id}은 원본 영상을 쓰므로 다시 생성할 영상이 없습니다.")
        chosen = seed if seed is not None else random.randrange(2**32)
        with session_scope(self.factory) as session:
            run = Repo(session).get_run(run_id)
            config = dict(run.config or {})
            config["seeds"] = {**config.get("seeds", {}), cut_id: chosen}
            run.config = config
        return self.run(run_id)

    def make_images(
        self, run_id: str, *, cut_id: str | None = None, again: bool = False
    ) -> list[tuple[Cut, ImageAsset]]:
        """효과 이미지만 만든다. 영상 요금을 쓰기 전에 결과를 보려는 용도이며 승인이 필요하다."""
        approved = self.service.require_approved(run_id)
        cuts = approved.storyboard.ordered_cuts()
        if cut_id is not None:
            cut = approved.storyboard.cut(cut_id)
            if cut is None:
                raise VlogError(f"없는 컷입니다: {cut_id}")
            if not cut.effect:
                raise VlogError(f"컷 {cut_id}에 effect가 없습니다.")
            cuts = [cut]
            if again:
                self._bump_image_attempt(run_id, cut_id)
        images = self._render_images(run_id, cuts, self._photos_by_id(run_id))
        return [(c, images[c.cut_id]) for c in cuts if c.cut_id in images]

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

    def _photos_by_id(self, run_id: str) -> dict[str, PhotoRecord]:
        return {p.photo_id: p for p in self.service.photos(run_id)}

    def _bump_image_attempt(self, run_id: str, cut_id: str) -> None:
        with session_scope(self.factory) as session:
            run = Repo(session).get_run(run_id)
            config = dict(run.config or {})
            attempts = dict(config.get("image_attempts", {}))
            attempts[cut_id] = attempts.get(cut_id, 0) + 1
            config["image_attempts"] = attempts
            run.config = config

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
        storyboard = approved.storyboard
        cuts = storyboard.ordered_cuts()
        result_generated: list[str] = []
        photos = self._photos_by_id(run_id)

        # 음악 파일이 없으면 요금을 쓰기 전에 멈춘다.
        problem = music_problem(storyboard, self.music)
        if problem is not None:
            raise VlogError(problem[1])

        # 길이와 무관하고 TTS보다 싸므로 먼저 만든다. API 키가 없으면 TTS 요금을 쓰기 전에 멈춘다.
        images = self._render_images(run_id, cuts, photos)

        # 원본 소리만 쓰는 컷뿐이거나 음악 전용이면 TTS 어댑터를 만들지 않아 API 키가 없어도 된다.
        narrated = [c for c in cuts if _needs_narration(c, storyboard)]
        audio = (
            self._narrate(run_id, narrated, self.tts_factory(), result_generated)
            if narrated
            else {}
        )
        timings = {c.cut_id: self._timing_for(c, audio, storyboard) for c in cuts}
        self._handle_too_long(run_id, approved.storyboard, audio, timings)
        self._set_status(run_id, RUN_NARRATED)

        clips, clips_generated, fallback_cuts = self._render_clips(
            run_id, cuts, photos, timings, images
        )
        self._set_status(run_id, RUN_RENDERED)

        sheet = self._make_contact_sheet(run_id, cuts, clips)
        path, digest, reused = self._compose_and_store(approved, cuts, audio, clips, timings)
        return RenderResult(
            run_id=run_id,
            version=approved.version,
            video_path=path,
            sha256=digest,
            reused_video=reused,
            images_generated=[k for k, v in images.items() if v.generated],
            tts_generated=result_generated,
            clips_generated=clips_generated,
            fallback_cuts=fallback_cuts,
            contact_sheet=sheet,
        )

    def _timing_for(
        self, cut: Cut, audio: dict[str, AudioAsset], storyboard: Storyboard
    ) -> CutTiming:
        asset = audio.get(cut.cut_id)
        if cut.source_clip is not None:
            return plan_source_timing(
                cut.source_clip.length, asset.audio_sec if asset else None, self.settings.timing
            )
        if storyboard.music_only:  # 나레이션이 없으므로 템플릿이 정한 길이를 그대로 쓴다
            return plan_fixed_timing(
                cut.target_sec, self.settings.timing, self.settings.music.min_cut_sec
            )
        assert asset is not None  # 나레이션 모드의 사진 컷은 항상 나레이션이 있다
        return plan_timing(asset.audio_sec, self.settings.timing)

    # ── 컷 이미지 효과 ────────────────────────────────────────────────────
    def _render_images(
        self, run_id: str, cuts: list[Cut], photos: dict[str, PhotoRecord]
    ) -> dict[str, ImageAsset]:
        """effect가 있는 컷만 다시 그린다. 없는 컷은 어댑터를 만들지도 않아 키가 없어도 된다."""
        targets = [c for c in cuts if c.effect]
        if not targets:
            return {}
        if self.image_factory is None or self.effects is None:
            raise ConfigError("이미지 생성이 설정되지 않았는데 effect가 있는 컷이 있습니다.")
        provider = self.image_factory()
        with session_scope(self.factory) as session:
            attempts = (Repo(session).get_run(run_id).config or {}).get("image_attempts", {})
        images: dict[str, ImageAsset] = {}
        for number, cut in enumerate(targets, start=1):
            photo = photos.get(cut.photo_id)
            if photo is None:
                raise VlogError(f"사진을 찾을 수 없습니다: {cut.photo_id}")
            analysis = photo.analysis
            prompt = self.effects.image_prompt(
                cut.effect or "",
                is_person=bool(analysis and analysis.subject_type == "인물"),
                face_hidden=bool(analysis and analysis.face_hidden),
            )
            input_hash = image_input_hash(
                photo_sha256=photo.sha256,
                crop_box=cut.shot.crop_box,
                prompt=prompt,
                provider=provider.signature(),
                attempt=int(attempts.get(cut.cut_id, 0)),
            )
            existing = self._find_image(run_id, cut.cut_id, input_hash)
            if existing is not None:
                images[cut.cut_id] = existing
                continue
            self.progress(f"이미지 생성 {number}/{len(targets)}: {cut.cut_id} ({cut.effect})")
            work = self._work_dir(run_id)
            source = prepare_input_image(
                photo.path,
                cut.shot.crop_box,
                (self.settings.compose.width, self.settings.compose.height),
                work / "inputs" / f"{cut.cut_id}_{input_hash[:10]}_image.jpg",
            )
            path = provider.generate(
                ImageRequest(source, prompt, work / "images" / f"{cut.cut_id}_{input_hash[:10]}")
            )
            with session_scope(self.factory) as session:
                session.add(
                    CutAsset(
                        run_id=run_id,
                        cut_id=cut.cut_id,
                        kind=ASSET_IMAGE,
                        input_hash=input_hash,
                        path=self._rel(path),
                        status=ASSET_DONE,
                        meta={"effect": cut.effect, "prompt": prompt, "provider": provider.name},
                    )
                )
            images[cut.cut_id] = ImageAsset(input_hash, path, generated=True)
        return images

    def _find_image(self, run_id: str, cut_id: str, input_hash: str) -> ImageAsset | None:
        with session_scope(self.factory) as session:
            row = Repo(session).find_asset(run_id, cut_id, ASSET_IMAGE, input_hash)
            if row is None or not row.path:
                return None
            path = self.settings.resolve(row.path)
            return ImageAsset(input_hash, path) if path.exists() else None

    def _clip_prompt(self, cut: Cut) -> str:
        suffix = self.effects.video_suffix(cut.effect) if cut.effect and self.effects else ""
        return f"{cut.motion_prompt}, {suffix}" if suffix else cut.motion_prompt

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
            self.progress(f"나레이션 {index + 1}/{len(cuts)}: {cut.cut_id}")
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
        generated_max = allowed_audio_sec(self.settings.timing)
        limits: dict[str, int] = {}
        locked: list[str] = []
        for cut in too_long:
            if "narration" in cut.locked:
                locked.append(cut.cut_id)
                continue
            # 원본 영상 컷은 고른 구간이, 생성 컷은 영상 최대 길이가 한도다.
            allowed = (
                cut.source_clip.length - self.settings.timing.pad_sec
                if cut.source_clip
                else generated_max
            )
            ratio = max(allowed, 0.1) / audio[cut.cut_id].audio_sec
            limits[cut.cut_id] = max(1, int(spoken_chars(cut.narration) * ratio * 0.95))
        detail = ", ".join(f"{c.cut_id}({audio[c.cut_id].audio_sec:.1f}초)" for c in too_long)
        message = f"나레이션이 컷 길이를 넘습니다: {detail}."
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
        images: dict[str, ImageAsset],
    ) -> tuple[dict[str, ClipAsset], list[str], list[str]]:
        clips: dict[str, ClipAsset] = {}
        generated: list[str] = []
        fallbacks: list[str] = []
        # 원본 영상은 생성 없이 그대로 쓴다.
        for cut in cuts:
            if cut.source_clip is not None:
                clips[cut.cut_id] = self._source_clip_asset(cut, photos)
        to_generate = [c for c in cuts if c.source_clip is None]
        if not to_generate:
            return clips, generated, fallbacks  # 영상 어댑터를 만들지 않아 ComfyUI가 없어도 된다

        video = self.video_factory()
        self._gpu_ready = False
        try:
            for number, cut in enumerate(to_generate, start=1):
                photo = photos.get(cut.photo_id)
                if photo is None:
                    raise VlogError(f"사진을 찾을 수 없습니다: {cut.photo_id}")
                seed = self._seed_for(run_id, cut.cut_id)
                styled = images.get(cut.cut_id)
                prompt = self._clip_prompt(cut)
                visual = clip_visual_key(
                    provider=video.name,
                    photo_sha256=photo.sha256,
                    crop_box=cut.shot.crop_box,
                    prompt=prompt,
                    seed=seed,
                    camera_move=cut.shot.camera_move,
                    size=video.input_size,
                    styled_image=styled.input_hash if styled else None,
                )
                timing = timings[cut.cut_id]
                input_hash = clip_input_hash(visual, timing.frames)
                existing = self._find_clip(run_id, cut.cut_id, input_hash, visual, timing.frames)
                if existing is not None:
                    clips[cut.cut_id] = existing
                    continue
                self.progress(f"영상 생성 {number}/{len(to_generate)}: {cut.cut_id} ({video.name})")
                asset = self._generate_clip(
                    run_id, cut, photo, timing, video, seed, visual, input_hash, styled, prompt
                )
                clips[cut.cut_id] = asset
                generated.append(cut.cut_id)
                if asset.fallback:
                    fallbacks.append(cut.cut_id)
        finally:
            video.release()
        return clips, generated, fallbacks

    def _source_clip_asset(self, cut: Cut, photos: dict[str, PhotoRecord]) -> ClipAsset:
        photo = photos.get(cut.photo_id)
        if photo is None or photo.video_path is None or not photo.video_path.exists():
            raise VlogError(f"원본 영상을 찾을 수 없습니다: {cut.photo_id}")
        assert cut.source_clip is not None
        return ClipAsset(
            source_clip_hash(
                video_sha256=photo.sha256,
                in_sec=cut.source_clip.in_sec,
                out_sec=cut.source_clip.out_sec,
                crop_box=cut.shot.crop_box,
            ),
            photo.video_path,
        )

    def _prepare_gpu(self, video: VideoProvider) -> None:
        """생성 전에 VRAM을 비우고 확인한다.

        ComfyUI는 다음 컷에 쓰려고 모델을 VRAM에 남겨 두므로, 컷마다 검사하면 자기가 쓰는
        메모리 때문에 실패한다. 그래서 처음과 실패 직후에만 검사한다.
        """
        if self._gpu_ready or not video.requires_gpu:
            return
        video.release()
        try:
            wait_for_free_vram(self.settings.video.min_free_vram_mb)
        except VideoError as exc:
            # 재시도나 Ken Burns로 해결되지 않고, 모든 컷이 대체되므로 멈춘다.
            raise ConfigError(str(exc)) from exc
        self._gpu_ready = True

    def _find_clip(
        self, run_id: str, cut_id: str, input_hash: str, visual: str, frames: int
    ) -> ClipAsset | None:
        with session_scope(self.factory) as session:
            # Ken Burns 대체본은 재사용하지 않는다. 원인을 고친 뒤 다시 실행하면 진짜 영상을 만든다.
            rows = [
                r
                for r in Repo(session).done_assets(run_id, cut_id, ASSET_CLIP)
                if not (r.meta or {}).get("fallback")
            ]
            # 나레이션이 짧아졌다면 같은 입력의 더 긴 클립을 그대로 쓴다.
            row = next((r for r in rows if r.input_hash == input_hash), None) or next(
                (
                    r
                    for r in rows
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
            return ClipAsset(row.input_hash, path)

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
        styled: ImageAsset | None,
        prompt: str,
    ) -> ClipAsset:
        work = self._work_dir(run_id)
        out_path = work / "clips" / f"{cut.cut_id}_{input_hash[:10]}.mp4"
        # 효과 이미지는 이미 잘려 있으므로 전체를 쓴다.
        source, crop = (styled.path, FULL_FRAME) if styled else (photo.path, cut.shot.crop_box)
        image = prepare_input_image(
            source,
            crop,
            video.input_size,
            work / "inputs" / f"{cut.cut_id}_{input_hash[:10]}.jpg",
        )
        last_error = ""
        attempts = self.settings.video.max_retries + 1
        for attempt in range(attempts):
            used_seed = (seed + attempt) % (2**32)
            try:
                self._prepare_gpu(video)
                video.generate(
                    ClipRequest(
                        image_path=image,
                        prompt=prompt,
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
            except ConfigError:
                raise  # 키·권한 문제는 재시도나 Ken Burns로 해결되지 않으므로 멈춘다
            except VlogError as exc:
                last_error = str(exc)
                log.warning(
                    "컷 %s 생성 실패 (%d/%d): %s", cut.cut_id, attempt + 1, attempts, last_error
                )
                self._gpu_ready = False  # 메모리 부족일 수 있으므로 비우고 다시 시도한다
        self._record_failure(run_id, cut, input_hash, seed, last_error)
        return self._fallback_clip(
            run_id, cut, source, crop, prompt, timing, seed, visual, input_hash, last_error
        )

    def _fallback_clip(
        self,
        run_id: str,
        cut: Cut,
        source: Path,
        crop: list[float],
        prompt: str,
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
            source,
            crop,
            fallback.input_size,
            work / "inputs" / f"{cut.cut_id}_{input_hash[:10]}_fallback.jpg",
        )
        out_path = work / "clips" / f"{cut.cut_id}_{input_hash[:10]}_fallback.mp4"
        fallback.generate(
            ClipRequest(
                image_path=image,
                prompt=prompt,
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
        # 원본 영상 컷은 파일 전체가 아니라 고른 구간의 가운데를 보여준다.
        items: list[Any] = [
            (c.cut_id, clips[c.cut_id].path, (c.source_clip.in_sec + c.source_clip.out_sec) / 2)
            if c.source_clip
            else (c.cut_id, clips[c.cut_id].path)
            for c in cuts
        ]
        try:
            return self.contact_sheet(items, out)
        except VlogError as exc:  # 대표 프레임 시트는 보조 기능이므로 합성을 막지 않는다
            log.warning("대표 프레임 시트를 만들지 못했습니다: %s", exc)
            return None

    # ── [7~8] 합성과 저장 ─────────────────────────────────────────────────
    def _music_input(self, storyboard: Storyboard) -> MusicInput | None:
        """음악 전용이면 음악을 크게, 나레이션 영상이면 나레이션 구간에서 줄어드는 배경 음악으로 쓴다."""
        if self.music is None or not storyboard.music_id:
            return None
        path = self.music.path(storyboard.music_id)
        if path is None:
            return None  # 음악 전용은 _run 시작에서 이미 막았다
        cfg = self.settings.music
        music_only = storyboard.music_only
        return MusicInput(
            path=path,
            volume=cfg.music_only_volume if music_only else self.settings.compose.bgm_volume,
            duck=not music_only,
            fade_out_sec=cfg.fade_out_sec,
        )

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
        parts = []
        for c in cuts:
            asset = audio.get(c.cut_id)
            part = {
                "audio": asset.input_hash if asset else None,
                "clip": clips[c.cut_id].input_hash,
                # 대체본과 진짜 영상은 입력 해시가 같으므로 실제 파일까지 비교해야 한다.
                "clip_file": clips[c.cut_id].path.name,
                "narration": c.narration,
                "text": c.shot.on_screen_text,
                "transition": c.shot.transition_in,
                "duration": timings[c.cut_id].duration_sec,
                "speed": timings[c.cut_id].speed,
            }
            # 영상 컷에만 넣어 사진만 쓴 기존 실행의 해시가 그대로 유지된다.
            if c.source_clip is not None:
                part["audio_mode"] = c.source_clip.audio
                part["source_mix_volume"] = cfg.source_mix_volume
            parts.append(part)
        settings_part = cfg.model_dump(exclude={"source_mix_volume"})
        music = self._music_input(approved.storyboard)
        if music is not None:  # 음악이 없으면 기존 실행의 해시가 그대로 유지된다
            stat = music.path.stat()
            settings_part["music"] = {
                "id": approved.storyboard.music_id,
                "file": music.path.name,
                "size": stat.st_size,
                "mtime": stat.st_mtime_ns,
                "volume": music.volume,
                "duck": music.duck,
                "fade_out_sec": music.fade_out_sec,
            }
        digest = compose_hash(parts, settings_part)

        with session_scope(self.factory) as session:
            existing = Repo(session).find_video(run_id, digest)
            if existing is not None and self.settings.resolve(existing.path).exists():
                run = Repo(session).get_run(run_id)
                run.status = RUN_COMPLETED
                run.error = None
                return self.settings.resolve(existing.path), existing.sha256, True

        photos = self._photos_by_id(run_id)
        compose_cuts = []
        for c in cuts:
            asset = audio.get(c.cut_id)
            clip = c.source_clip
            photo = photos.get(c.photo_id)
            compose_cuts.append(
                ComposeCut(
                    cut_id=c.cut_id,
                    clip_path=clips[c.cut_id].path,
                    audio_path=asset.path if asset else None,
                    duration_sec=timings[c.cut_id].duration_sec,
                    speed=timings[c.cut_id].speed,
                    events=_events_for(asset, timings[c.cut_id], cfg.max_chars_per_line)
                    if asset
                    else [],
                    on_screen_text=c.shot.on_screen_text,
                    transition_in=c.shot.transition_in,
                    source_start=clip.in_sec if clip else None,
                    crop_box=c.shot.crop_box if clip else None,
                    audio_mode=clip.audio if clip else "narration",
                    has_source_audio=bool(clip and photo and photo.has_audio),
                )
            )
        work_dir = self._work_dir(run_id) / "compose"
        temp = work_dir / f"final_{digest[:12]}.part.mp4"
        self.progress(f"합성 중: {len(compose_cuts)}컷")
        if music is not None:
            self.composer.compose(compose_cuts, work_dir, temp, music=music)
        else:
            self.composer.compose(compose_cuts, work_dir, temp)
        self.progress("검증 후 저장 중")
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


def _needs_narration(cut: Cut, storyboard: Storyboard) -> bool:
    if storyboard.music_only:
        return False
    return cut.source_clip is None or cut.source_clip.audio != "source"


def _events_for(asset: AudioAsset, timing: CutTiming, max_chars: int) -> list[SubtitleEvent]:
    return chunk_words(asset.words, max_chars, timing.duration_sec)
