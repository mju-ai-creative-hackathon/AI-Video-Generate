package com.example.backend.shortform;

import com.example.backend.shortform.ai.AiRenderClient;
import com.example.backend.shortform.ai.AiRenderException;
import com.example.backend.shortform.ai.ShootingHint;
import com.example.backend.shortform.ai.ShootingHintProvider;
import com.example.backend.shortform.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Short transactions around a durable dispatch queue. No network calls while holding a row lock.
 *
 * <p>단계: DRAFTING(초안 생성) → AWAITING_REVIEW(사람 검토, 기한 없음) → RENDERING(승인 후 생성) → DONE.
 * 검토 중에도 AI 서버를 주기적으로 조회해 재생성 진행과 스토리보드 버전을 반영한다.
 */
@Service
public class RenderStateService {
    private static final Logger log = LoggerFactory.getLogger(RenderStateService.class);
    private final RenderJobRepository jobs;
    private final ShortformAssetRepository assets;
    private final MediaStorage storage;
    private final long retryDelayMs;
    private final long pollDelayMs;
    private final long draftTimeoutSeconds;
    private final long renderTimeoutSeconds;
    private final int maxFailures;
    private final ShortformProjectRepository projects;
    private final ShootingHintProvider hints;
    public RenderStateService(RenderJobRepository jobs, ShortformAssetRepository assets, MediaStorage storage,
            long retryDelayMs, long pollDelayMs, long timeoutSeconds, int maxFailures) {
        this(jobs, assets, storage, retryDelayMs, pollDelayMs, timeoutSeconds, 7200, maxFailures, null, null);
    }
    @Autowired
    public RenderStateService(RenderJobRepository jobs, ShortformAssetRepository assets, MediaStorage storage,
            @Value("${app.shortform.retry-delay-ms:10000}") long retryDelayMs,
            @Value("${app.shortform.result-poll-delay-ms:5000}") long pollDelayMs,
            @Value("${app.shortform.draft-timeout-seconds:1800}") long draftTimeoutSeconds,
            @Value("${app.shortform.render-timeout-seconds:7200}") long renderTimeoutSeconds,
            @Value("${app.shortform.max-failures:3}") int maxFailures,
            ShortformProjectRepository projects, ShootingHintProvider hints) {
        if (retryDelayMs < 0 || pollDelayMs < 0 || draftTimeoutSeconds < 120 || renderTimeoutSeconds < 120
                || maxFailures < 1 || maxFailures > 100)
            throw new IllegalArgumentException("Invalid shortform retry/poll/timeout configuration");
        this.jobs = jobs; this.assets = assets; this.storage = storage;
        this.retryDelayMs = retryDelayMs; this.pollDelayMs = pollDelayMs;
        this.draftTimeoutSeconds = draftTimeoutSeconds; this.renderTimeoutSeconds = renderTimeoutSeconds;
        this.maxFailures = maxFailures;
        this.projects = projects; this.hints = hints;
    }

    public record Claim(UUID jobId, UUID leaseToken, String externalJobId, AiRenderClient.RenderSubmission submission) {}

    @Transactional(readOnly = true)
    public List<UUID> due() {
        return jobs.findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(ShortformService.ACTIVE, Instant.now())
                .stream().map(j -> j.id).toList();
    }

    @Transactional
    public Claim claim(UUID id) {
        var job = jobs.findLockedById(id).orElse(null);
        Instant now = Instant.now();
        if (job == null || job.status.terminal() || job.nextAttemptAt.isAfter(now)) return null;
        if (job.phase != RenderPhase.AWAITING_REVIEW) {
            Instant deadline = job.deadlineAt != null ? job.deadlineAt : job.createdAt.plusSeconds(draftTimeoutSeconds);
            if (!deadline.isAfter(now)) {
                job.fail("RENDER_TIMEOUT", "영상 제작 제한 시간을 초과했습니다.");
                return null;
            }
        }
        var sources = new ArrayList<AiRenderClient.Source>();
        if (job.externalJobId == null) { // 원본은 제출할 때만 필요하다
            var sourceMap = assets.findAllById(job.assetIds).stream().collect(Collectors.toMap(a -> a.id, Function.identity()));
            if (sourceMap.size() != job.assetIds.size()) {
                job.fail("SOURCE_MISSING", "제작에 필요한 원본을 찾을 수 없습니다."); return null;
            }
            try {
                for (int index = 0; index < job.assetIds.size(); index++) {
                    var asset = sourceMap.get(job.assetIds.get(index));
                    if (!asset.projectId.equals(job.projectId)) {
                        job.fail("SOURCE_MISSING", "프로젝트의 원본 구성이 올바르지 않습니다."); return null;
                    }
                    sources.add(new AiRenderClient.Source(asset.id, asset.missionId, index, asset.contentType, storage.load(asset.storageKey)));
                }
            } catch (RuntimeException error) {
                job.fail("SOURCE_MISSING", "제작에 필요한 원본을 읽을 수 없습니다."); return null;
            }
        }
        job.leaseToken = UUID.randomUUID();
        job.nextAttemptAt = now.plusSeconds(120);
        job.updatedAt = now;
        if (job.externalJobId == null) job.status = RenderStatus.SUBMITTING;
        return new Claim(job.id, job.leaseToken, job.externalJobId, new AiRenderClient.RenderSubmission(
                job.id, job.projectId, null, job.style.name(), job.style.label(), job.mood.name(), job.mood.label(),
                job.musicId, job.templateId, List.copyOf(sources), List.of()));
    }

    /**
     * Adds the project title and optional course shooting hints. Deliberately outside the claim transaction
     * so a failing provider can never roll back the lease; lookup failures render without hints.
     */
    public AiRenderClient.RenderSubmission withShootingHints(AiRenderClient.RenderSubmission submission) {
        if (projects == null) return submission;
        ShortformProject project;
        try {
            project = projects.findById(submission.projectId()).orElse(null);
        } catch (RuntimeException error) {
            log.warn("Shortform project lookup failed ({}); rendering without title or hints.",
                    error.getClass().getSimpleName());
            return submission;
        }
        if (project == null) return submission;
        List<ShootingHint> resolved = List.of();
        if (hints != null && project.courseId != null) {
            try {
                var result = hints.hintsFor(project.ownerId, project.courseId, project.missionIds());
                resolved = result == null ? List.of() : result.stream().filter(Objects::nonNull).limit(20).toList();
            } catch (RuntimeException error) {
                log.warn("Shooting hints unavailable for shortform project ({}); rendering without hints.",
                        error.getClass().getSimpleName());
            }
        }
        return submission.withTitleAndHints(project.title, resolved);
    }

    @Transactional
    public void submitted(Claim claim, String externalId) {
        var job = leased(claim);
        if (job == null) return;
        job.externalJobId = externalId; job.status = RenderStatus.PROCESSING;
        if (job.phase == null) job.phase = RenderPhase.DRAFTING;
        job.failures = 0; job.updatedAt = Instant.now(); job.leaseToken = null;
        job.nextAttemptAt = job.updatedAt.plusMillis(pollDelayMs);
    }

    @Transactional
    public void result(Claim claim, AiRenderClient.RenderResult result) {
        var job = leased(claim);
        if (job == null) return;
        switch (result.status()) {
            case "SUCCEEDED" -> { // 구 계약: AI 서버가 공개 URL을 준다
                if (result.videoUrl() == null) {
                    job.fail("AI_INVALID_RESPONSE", "영상 제작 서버의 응답 형식이 올바르지 않습니다."); return;
                }
                job.status = RenderStatus.SUCCEEDED; job.phase = RenderPhase.DONE;
                job.videoUrl = result.videoUrl(); job.thumbnailUrl = result.thumbnailUrl();
                job.setProgress(null, null, null, null);
            }
            case "FAILED" -> { job.fail("AI_RENDER_FAILED", "영상 제작에 실패했습니다. 새 요청으로 다시 시도해주세요."); return; }
            case "AWAITING_REVIEW" -> {
                job.status = RenderStatus.PROCESSING;
                job.phase = RenderPhase.AWAITING_REVIEW;
                job.reviewBusy = result.busy();
                job.reviewError = result.error();
                applyProgress(job, result);
            }
            case "QUEUED", "PROCESSING" -> {
                job.status = RenderStatus.PROCESSING;
                if ("RENDERING".equals(result.phase())) { job.phase = RenderPhase.RENDERING; job.reviewError = null; }
                else if ("DRAFTING".equals(result.phase())) job.phase = RenderPhase.DRAFTING;
                job.reviewBusy = false;
                applyProgress(job, result);
            }
            default -> { job.fail("AI_INVALID_RESPONSE", "영상 제작 서버의 응답 형식이 올바르지 않습니다."); return; }
        }
        if (result.storyboardVersion() != null) job.storyboardVersion = result.storyboardVersion();
        job.failures = 0; job.updatedAt = Instant.now(); job.leaseToken = null;
        job.nextAttemptAt = job.updatedAt.plusMillis(pollDelayMs);
    }

    /** 완성본을 저장소에 받아 둔 뒤 성공 처리한다. 임대를 잃었으면 false(호출한 쪽이 파일을 지운다). */
    @Transactional
    public boolean completed(Claim claim, String resultStorageKey, AiRenderClient.RenderResult result) {
        return completed(claim, resultStorageKey, null, result);
    }

    @Transactional
    public boolean completed(Claim claim, String resultStorageKey, String thumbnailKey,
                             AiRenderClient.RenderResult result) {
        var job = leased(claim);
        if (job == null) return false;
        job.status = RenderStatus.SUCCEEDED; job.phase = RenderPhase.DONE;
        job.resultStorageKey = resultStorageKey;
        job.resultThumbnailKey = thumbnailKey;
        job.reviewBusy = false; job.reviewError = null;
        job.setProgress(null, null, null, null);
        if (result.storyboardVersion() != null) job.storyboardVersion = result.storyboardVersion();
        job.failures = 0; job.updatedAt = Instant.now(); job.leaseToken = null;
        return true;
    }

    /**
     * 승인 후 영상 생성을 시작했음을 기록한다. 검토 단계일 때만 바꾸며, 진행 중인 워커의 임대를 무효로 해
     * 승인 직전에 받은 검토 상태로 되돌아가지 않게 한다.
     */
    @Transactional
    public boolean markRendering(UUID jobId) {
        var job = jobs.findLockedById(jobId).orElse(null);
        if (job == null || job.status.terminal() || job.phase != RenderPhase.AWAITING_REVIEW) return false;
        Instant now = Instant.now();
        job.phase = RenderPhase.RENDERING;
        job.reviewBusy = false; job.reviewError = null;
        job.setProgress(null, "영상 생성 대기 중", null, null);
        job.deadlineAt = now.plusSeconds(renderTimeoutSeconds);
        job.failures = 0; job.leaseToken = null;
        job.updatedAt = now; job.nextAttemptAt = now;
        return true;
    }

    /** 검토 중 재생성을 요청했음을 바로 보여주고, 다음 주기에 상태를 다시 조회하게 한다. */
    @Transactional
    public void markReviewBusy(UUID jobId) {
        var job = jobs.findLockedById(jobId).orElse(null);
        if (job == null || job.status.terminal() || job.phase != RenderPhase.AWAITING_REVIEW) return;
        Instant now = Instant.now();
        job.reviewBusy = true; job.reviewError = null;
        job.setProgress(null, "다시 생성하는 중", null, null);
        job.leaseToken = null; job.updatedAt = now; job.nextAttemptAt = now;
    }

    @Transactional
    public void failed(Claim claim, AiRenderException error) {
        var job = leased(claim);
        if (job == null) return;
        Instant now = Instant.now();
        if (job.phase == RenderPhase.AWAITING_REVIEW && error.isRetryable()) {
            // 검토는 기한이 없다. AI 서버가 잠시 꺼져 있어도 사용자의 검토 작업을 실패시키지 않는다.
            job.updatedAt = now; job.leaseToken = null;
            job.nextAttemptAt = now.plusMillis(Math.max(retryDelayMs, pollDelayMs));
            return;
        }
        job.failures++;
        if (!error.isRetryable() || job.failures >= maxFailures) {
            job.fail(error.getCode(), "영상 제작 서버에서 요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");
        } else {
            job.status = job.externalJobId == null ? RenderStatus.QUEUED : RenderStatus.PROCESSING;
            job.updatedAt = now; job.leaseToken = null;
            job.nextAttemptAt = job.updatedAt.plusMillis(retryDelayMs * job.failures);
        }
    }

    private static void applyProgress(RenderJob job, AiRenderClient.RenderResult result) {
        var progress = result.progress();
        if (progress == null) job.setProgress(null, null, null, null);
        else job.setProgress(progress.stage(), progress.message(), progress.current(), progress.total());
    }

    private RenderJob leased(Claim claim) {
        return jobs.findLockedById(claim.jobId())
                .filter(j -> !j.status.terminal() && Objects.equals(j.leaseToken, claim.leaseToken())).orElse(null);
    }
}
