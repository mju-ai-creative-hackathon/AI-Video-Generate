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

/** Short transactions around a durable dispatch queue. No network calls while holding a row lock. */
@Service
public class RenderStateService {
    private static final Logger log = LoggerFactory.getLogger(RenderStateService.class);
    private final RenderJobRepository jobs;
    private final ShortformAssetRepository assets;
    private final MediaStorage storage;
    private final long retryDelayMs;
    private final long pollDelayMs;
    private final long timeoutSeconds;
    private final int maxFailures;
    private final ShortformProjectRepository projects;
    private final ShootingHintProvider hints;
    public RenderStateService(RenderJobRepository jobs, ShortformAssetRepository assets, MediaStorage storage,
            long retryDelayMs, long pollDelayMs, long timeoutSeconds, int maxFailures) {
        this(jobs, assets, storage, retryDelayMs, pollDelayMs, timeoutSeconds, maxFailures, null, null);
    }
    @Autowired
    public RenderStateService(RenderJobRepository jobs, ShortformAssetRepository assets, MediaStorage storage,
            @Value("${app.shortform.retry-delay-ms:10000}") long retryDelayMs,
            @Value("${app.shortform.result-poll-delay-ms:5000}") long pollDelayMs,
            @Value("${app.shortform.render-timeout-seconds:1800}") long timeoutSeconds,
            @Value("${app.shortform.max-failures:3}") int maxFailures,
            ShortformProjectRepository projects, ShootingHintProvider hints) {
        if (retryDelayMs < 0 || pollDelayMs < 0 || timeoutSeconds < 120 || maxFailures < 1 || maxFailures > 20)
            throw new IllegalArgumentException("Invalid shortform retry/poll/timeout configuration");
        this.jobs = jobs; this.assets = assets; this.storage = storage;
        this.retryDelayMs = retryDelayMs; this.pollDelayMs = pollDelayMs;
        this.timeoutSeconds = timeoutSeconds; this.maxFailures = maxFailures;
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
        if (!job.createdAt.plusSeconds(timeoutSeconds).isAfter(now)) {
            job.fail("RENDER_TIMEOUT", "영상 제작 제한 시간을 초과했습니다.");
            return null;
        }
        var sourceMap = assets.findAllById(job.assetIds).stream().collect(Collectors.toMap(a -> a.id, Function.identity()));
        if (sourceMap.size() != job.assetIds.size()) {
            job.fail("SOURCE_MISSING", "제작에 필요한 원본을 찾을 수 없습니다."); return null;
        }
        var sources = new ArrayList<AiRenderClient.Source>();
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
        job.leaseToken = UUID.randomUUID();
        job.nextAttemptAt = now.plusSeconds(120);
        job.updatedAt = now;
        if (job.externalJobId == null) job.status = RenderStatus.SUBMITTING;
        return new Claim(job.id, job.leaseToken, job.externalJobId, new AiRenderClient.RenderSubmission(
                job.id, job.projectId, job.style.name(), job.mood.name(), job.musicId, List.copyOf(sources)));
    }

    /**
     * Adds optional course shooting hints. Deliberately outside the claim transaction so a failing
     * provider can never roll back the lease; lookup failures render without hints.
     */
    public AiRenderClient.RenderSubmission withShootingHints(AiRenderClient.RenderSubmission submission) {
        if (projects == null || hints == null) return submission;
        List<ShootingHint> resolved;
        try {
            var project = projects.findById(submission.projectId()).orElse(null);
            if (project == null || project.courseId == null) return submission;
            var result = hints.hintsFor(project.ownerId, project.courseId, project.missionIds());
            resolved = result == null ? List.of() : result.stream().filter(Objects::nonNull).limit(20).toList();
        } catch (RuntimeException error) {
            log.warn("Shooting hints unavailable for shortform project ({}); rendering without hints.",
                    error.getClass().getSimpleName());
            return submission;
        }
        if (resolved.isEmpty()) return submission;
        return new AiRenderClient.RenderSubmission(submission.jobId(), submission.projectId(), submission.style(),
                submission.mood(), submission.musicId(), submission.sources(), resolved);
    }

    @Transactional
    public void submitted(Claim claim, String externalId) {
        var job = leased(claim);
        if (job == null) return;
        job.externalJobId = externalId; job.status = RenderStatus.PROCESSING;
        job.failures = 0; job.updatedAt = Instant.now(); job.leaseToken = null;
        job.nextAttemptAt = job.updatedAt.plusMillis(pollDelayMs);
    }

    @Transactional
    public void result(Claim claim, AiRenderClient.RenderResult result) {
        var job = leased(claim);
        if (job == null) return;
        switch (result.status()) {
            case "SUCCEEDED" -> {
                job.status = RenderStatus.SUCCEEDED;
                job.videoUrl = result.videoUrl(); job.thumbnailUrl = result.thumbnailUrl();
            }
            case "FAILED" -> { job.fail("AI_RENDER_FAILED", "영상 제작에 실패했습니다. 새 요청으로 다시 시도해주세요."); return; }
            case "QUEUED", "PROCESSING" -> job.status = RenderStatus.PROCESSING;
            default -> { job.fail("AI_INVALID_RESPONSE", "영상 제작 서버의 응답 형식이 올바르지 않습니다."); return; }
        }
        job.failures = 0; job.updatedAt = Instant.now(); job.leaseToken = null;
        job.nextAttemptAt = job.updatedAt.plusMillis(pollDelayMs);
    }

    @Transactional
    public void failed(Claim claim, AiRenderException error) {
        var job = leased(claim);
        if (job == null) return;
        job.failures++;
        if (!error.isRetryable() || job.failures >= maxFailures) {
            job.fail(error.getCode(), "영상 제작 서버에서 요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");
        } else {
            job.status = job.externalJobId == null ? RenderStatus.QUEUED : RenderStatus.PROCESSING;
            job.updatedAt = Instant.now(); job.leaseToken = null;
            job.nextAttemptAt = job.updatedAt.plusMillis(retryDelayMs * job.failures);
        }
    }

    private RenderJob leased(Claim claim) {
        return jobs.findLockedById(claim.jobId())
                .filter(j -> !j.status.terminal() && Objects.equals(j.leaseToken, claim.leaseToken())).orElse(null);
    }
}
