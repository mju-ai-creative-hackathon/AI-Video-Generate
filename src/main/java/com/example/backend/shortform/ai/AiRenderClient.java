package com.example.backend.shortform.ai;

import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * AI 영상 서버 경계. 초안 생성 → 사람 검토·승인 → 영상 생성 순서로 진행한다.
 * 승인 전에는 AI 서버가 TTS·영상 생성을 시작하지 않는다.
 */
public interface AiRenderClient {
    boolean isConfigured();

    /** 설정돼 있고 AI 서버가 응답하는지. 기본은 설정 여부만 본다. */
    default boolean isAvailable() { return isConfigured(); }

    /** 소스를 넘기고 초안 생성을 시작한다. AI 작업 ID를 돌려준다. */
    String submit(RenderSubmission request);

    RenderResult poll(String externalJobId);

    /** 스토리보드 조회. 2xx와 사용자에게 보여줄 4xx(404·409·422)는 응답으로, 그 외는 예외로 돌려준다. */
    default Proxied getStoryboard(String externalJobId, Integer version) { throw unsupported(); }

    default Proxied saveStoryboard(String externalJobId, byte[] json) { throw unsupported(); }

    default Proxied regenStoryboard(String externalJobId, byte[] json) { throw unsupported(); }

    default Proxied approve(String externalJobId, int version, boolean acceptWarnings) { throw unsupported(); }

    /** 승인된 최신 버전으로 영상 생성을 시작한다. 이미 생성 중이면 그대로 둔다. */
    default void startRender(String externalJobId) { throw unsupported(); }

    /** 검토 화면용 원본·대표 이미지. range는 영상 탐색용 Range 헤더(없으면 null). */
    default MediaStream openMedia(String externalJobId, String photoId, boolean poster, String range) {
        throw unsupported();
    }

    /** 완성본. 호출한 쪽이 스트림을 닫는다. */
    default InputStream downloadVideo(String externalJobId) { throw unsupported(); }

    /** 완성본의 대표 이미지(JPEG). 호출한 쪽이 스트림을 닫는다. */
    default InputStream downloadThumbnail(String externalJobId) { throw unsupported(); }

    /** 앱에 보여줄 템플릿과 음악 목록. AI 서버에 닿지 않으면 빈 목록이다. */
    default Catalog catalog() { return Catalog.EMPTY; }

    private static AiRenderException unsupported() {
        return new AiRenderException("AI_NOT_CONFIGURED", false, "AI 영상 제작 서비스가 검토 기능을 지원하지 않습니다.");
    }

    record RenderSubmission(UUID jobId, UUID projectId, String title, String style, String styleLabel,
                            String mood, String moodLabel, String musicId, String templateId,
                            List<Source> sources, List<ShootingHint> shootingHints) {
        public RenderSubmission {
            sources = List.copyOf(sources);
            shootingHints = shootingHints == null ? List.of() : List.copyOf(shootingHints);
        }

        public RenderSubmission(UUID jobId, UUID projectId, String style, String mood, String musicId,
                                List<Source> sources, List<ShootingHint> shootingHints) {
            this(jobId, projectId, null, style, null, mood, null, musicId, null, sources, shootingHints);
        }

        public RenderSubmission(UUID jobId, UUID projectId, String style, String mood, String musicId,
                                List<Source> sources) {
            this(jobId, projectId, style, mood, musicId, sources, List.of());
        }

        public RenderSubmission withTitleAndHints(String newTitle, List<ShootingHint> hints) {
            return new RenderSubmission(jobId, projectId, newTitle, style, styleLabel, mood, moodLabel, musicId,
                    templateId, sources, hints);
        }
    }

    /** audioMode: narration(나레이션) 또는 music(음악만). music은 템플릿의 기본 음악 id. */
    record TemplateInfo(String id, String name, String hookType, String audioMode, String music) {}

    record MusicInfo(String id, String name, List<String> moods) {
        public MusicInfo { moods = moods == null ? List.of() : List.copyOf(moods); }
    }

    record Catalog(List<TemplateInfo> templates, List<MusicInfo> music) {
        public static final Catalog EMPTY = new Catalog(List.of(), List.of());

        public Catalog {
            templates = templates == null ? List.of() : List.copyOf(templates);
            music = music == null ? List.of() : List.copyOf(music);
        }

        public boolean loaded() { return !templates.isEmpty(); }
        public boolean hasTemplate(String id) { return templates.stream().anyMatch(t -> t.id().equals(id)); }
        public boolean hasMusic(String id) { return music.stream().anyMatch(m -> m.id().equals(id)); }
    }

    record Source(UUID assetId, UUID missionId, int position, String contentType, Resource resource) {}

    /**
     * status: QUEUED, PROCESSING, AWAITING_REVIEW, SUCCEEDED, FAILED.
     * phase: DRAFTING, AWAITING_REVIEW, RENDERING, DONE (구 계약의 서버는 보내지 않는다).
     * SUCCEEDED인데 videoUrl이 없으면 {@link #downloadVideo}로 완성본을 받는다.
     */
    record RenderResult(String status, String videoUrl, String thumbnailUrl, String phase, boolean busy,
                        Progress progress, Integer storyboardVersion, String error) {
        public RenderResult(String status, String videoUrl, String thumbnailUrl) {
            this(status, videoUrl, thumbnailUrl, null, false, null, null, null);
        }
    }

    record Progress(String stage, String message, Integer current, Integer total) {}

    /** AI 서버 응답 본문(JSON)과 상태 코드. */
    record Proxied(int status, byte[] body) {
        public boolean ok() { return status >= 200 && status < 300; }
    }

    record MediaStream(int status, String contentType, Long contentLength, String contentRange,
                       InputStream body) {}
}
