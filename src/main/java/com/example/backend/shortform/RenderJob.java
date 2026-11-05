package com.example.backend.shortform;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "shortform_render_jobs",
        uniqueConstraints = @UniqueConstraint(name = "uk_shortform_job_request", columnNames = {"project_id", "idempotency_key"}),
        indexes = @Index(name = "ix_shortform_job_dispatch", columnList = "status,next_attempt_at"))
public class RenderJob {
    @Id @GeneratedValue UUID id;
    @Column(name = "project_id", nullable = false, updatable = false) UUID projectId;
    @Column(name = "idempotency_key", nullable = false, updatable = false) UUID idempotencyKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) RenderStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) VideoStyle style;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) VideoMood mood;
    @Column(length = 100) String musicId;
    @ElementCollection
    @CollectionTable(name = "shortform_render_sources", joinColumns = @JoinColumn(name = "render_job_id"))
    @OrderColumn(name = "source_order")
    @Column(name = "asset_id", nullable = false)
    List<UUID> assetIds = new ArrayList<>();
    @Column(length = 128) String externalJobId;
    @Column(length = 2000) String videoUrl;
    @Column(length = 2000) String thumbnailUrl;
    @Column(length = 80) String errorCode;
    @Column(length = 300) String errorMessage;
    @Column(nullable = false) int failures;
    // 검토 흐름. 기존 행은 모두 null이며 이전처럼 동작한다.
    @Enumerated(EnumType.STRING) @Column(length = 20) RenderPhase phase;
    Boolean reviewBusy;
    @Column(length = 40) String progressStage;
    @Column(length = 500) String progressMessage;
    Integer progressCurrent;
    Integer progressTotal;
    Integer storyboardVersion;
    @Column(length = 300) String reviewError;
    /** 현재 단계의 제한 시각. null이면 createdAt 기준 초안 제한 시간을 쓴다. 검토 중에는 보지 않는다. */
    Instant deadlineAt;
    /** 백엔드가 받아 보관한 완성본의 저장소 키. */
    @Column(length = 100) String resultStorageKey;
    UUID leaseToken;
    @Column(name = "next_attempt_at", nullable = false) Instant nextAttemptAt;
    @Column(nullable = false, updatable = false) Instant createdAt;
    @Column(nullable = false) Instant updatedAt;
    @Version long version;
    protected RenderJob() {}
    RenderJob(UUID projectId, UUID key, ShortformDtos.RenderRequest request) {
        this.projectId = projectId; this.idempotencyKey = key;
        this.style = request.style(); this.mood = request.mood(); this.musicId = request.musicId();
        this.assetIds = new ArrayList<>(request.assetIds()); this.status = RenderStatus.QUEUED;
        this.phase = RenderPhase.DRAFTING;
        this.createdAt = Instant.now(); this.updatedAt = createdAt; this.nextAttemptAt = createdAt;
    }
    void setProgress(String stage, String message, Integer current, Integer total) {
        progressStage = stage; progressMessage = message; progressCurrent = current; progressTotal = total;
    }
    boolean sameRequest(ShortformDtos.RenderRequest request) {
        return style == request.style() && mood == request.mood() && Objects.equals(musicId, request.musicId())
                && assetIds.equals(request.assetIds());
    }
    void fail(String code, String message) {
        status = RenderStatus.FAILED; errorCode = code; errorMessage = message;
        updatedAt = Instant.now(); leaseToken = null;
    }
}
