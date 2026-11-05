package com.example.backend.shortform;

import com.example.backend.shortform.storage.MediaKind;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ShortformDtos {
    private ShortformDtos() {}
    public record CreateRequest(@NotBlank @Size(max = 100) String title, @Positive Long courseId,
                                @Size(max = 20) List<@NotNull @Positive Long> missionIds) {
        public CreateRequest(String title) { this(title, null, null); }
    }
    public record RenderRequest(@NotNull VideoStyle style, @NotNull VideoMood mood,
                                @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String musicId,
                                @NotNull @Size(min = 5, max = 20) List<@NotNull UUID> assetIds) {}
    public record Option(String code, String label) {}
    public record Options(List<Option> styles, List<Option> moods, int minAssets, int maxAssets,
                          long maxImageBytes, long maxVideoBytes, boolean renderingAvailable) {}
    public record AssetView(UUID id, String originalFilename, String contentType, long sizeBytes,
                            MediaKind kind, UUID missionId, String contentUrl) {
        static AssetView from(ShortformAsset asset) {
            return new AssetView(asset.id, asset.originalFilename, asset.contentType, asset.sizeBytes,
                    asset.kind, asset.missionId, "/api/shortforms/" + asset.projectId + "/assets/" + asset.id + "/content");
        }
    }
    /**
     * 검토 흐름의 필드(phase 이후)는 기존 앱이 무시해도 되도록 뒤에 붙였다.
     * videoUrl은 백엔드가 보관한 완성본이면 조회할 때마다 새로 서명한 URL이다.
     */
    public record RenderView(UUID id, UUID projectId, RenderStatus status, VideoStyle style, VideoMood mood,
                             String musicId, String videoUrl, String thumbnailUrl, String errorCode,
                             String errorMessage, Instant createdAt, Instant updatedAt,
                             RenderPhase phase, boolean reviewBusy, Progress progress,
                             Integer storyboardVersion, String reviewError) {
        static RenderView from(RenderJob job, String videoUrl) {
            Progress progress = job.progressStage == null && job.progressMessage == null ? null
                    : new Progress(job.progressStage, job.progressMessage, job.progressCurrent, job.progressTotal);
            return new RenderView(job.id, job.projectId, job.status, job.style, job.mood, job.musicId,
                    videoUrl, job.thumbnailUrl, job.errorCode, job.errorMessage, job.createdAt, job.updatedAt,
                    job.phase, Boolean.TRUE.equals(job.reviewBusy), progress, job.storyboardVersion,
                    job.reviewError);
        }
    }
    public record Progress(String stage, String message, Integer current, Integer total) {}
    public record ApproveRequest(@NotNull @Positive Integer version, Boolean acceptWarnings) {}
    public record ProjectView(UUID id, String title, List<AssetView> assets, RenderView latestRender, Instant createdAt,
                              Long courseId, List<Long> missionIds) {}
    public record ProjectPage(List<ProjectView> items, int page, int size, long totalElements, int totalPages, boolean hasNext) {}
}
