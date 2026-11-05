package com.example.backend.shortform;

import com.example.backend.shortform.ai.*;
import com.example.backend.shortform.storage.MediaStorage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RenderStateServiceTests {
    private final RenderJobRepository jobs = mock(RenderJobRepository.class);
    private final ShortformAssetRepository assets = mock(ShortformAssetRepository.class);
    private final MediaStorage storage = mock(MediaStorage.class);
    private final RenderStateService service = new RenderStateService(jobs, assets, storage, 0, 0, 1800, 3);

    private RenderJob job() {
        UUID projectId = UUID.randomUUID();
        var sources = new ArrayList<ShortformAsset>();
        for (int i = 0; i < 5; i++) {
            var asset = new ShortformAsset();
            asset.id = UUID.randomUUID(); asset.projectId = projectId;
            asset.contentType = "image/png"; asset.storageKey = "source-" + i;
            sources.add(asset);
        }
        var job = new RenderJob(projectId, UUID.randomUUID(), new ShortformDtos.RenderRequest(
                VideoStyle.CINEMATIC, VideoMood.CALM, null, sources.stream().map(a -> a.id).toList()));
        job.id = UUID.randomUUID();
        when(jobs.findLockedById(job.id)).thenReturn(Optional.of(job));
        when(assets.findAllById(any())).thenReturn(sources);
        when(storage.load(any())).thenReturn(new ByteArrayResource(new byte[]{1}));
        return job;
    }

    @Test void leasePreventsConcurrentClaimAndStaleCompletion() {
        var job = job();
        var first = service.claim(job.id);
        assertEquals(RenderStatus.SUBMITTING, job.status);
        assertNull(service.claim(job.id));
        job.nextAttemptAt = Instant.now().minusSeconds(1); // Simulate a crashed worker's expired lease.
        var replacement = service.claim(job.id);
        assertNotEquals(first.leaseToken(), replacement.leaseToken());
        service.submitted(first, "stale-worker");
        assertNull(job.externalJobId);
        service.submitted(replacement, "current-worker");
        assertEquals("current-worker", job.externalJobId);
        assertEquals(RenderStatus.PROCESSING, job.status);
    }

    @Test void transientFailuresRetryThenReachTerminalFailure() {
        var job = job();
        var error = new AiRenderException("AI_UNAVAILABLE", true, "safe message");
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claim = service.claim(job.id);
            assertNotNull(claim);
            service.failed(claim, error);
            assertEquals(attempt, job.failures);
            assertEquals(attempt == 3 ? RenderStatus.FAILED : RenderStatus.QUEUED, job.status);
        }
        assertNull(service.claim(job.id));
        assertEquals("AI_UNAVAILABLE", job.errorCode);
    }

    private AiRenderClient.RenderResult review() {
        return new AiRenderClient.RenderResult("AWAITING_REVIEW", null, null, "AWAITING_REVIEW", false,
                null, 2, null);
    }

    @Test void reviewHasNoDeadlineAndToleratesProviderOutages() {
        var job = job();
        var first = service.claim(job.id);
        service.submitted(first, "ai-1");
        service.result(service.claim(job.id), review());
        assertEquals(RenderStatus.PROCESSING, job.status);
        assertEquals(RenderPhase.AWAITING_REVIEW, job.phase);
        assertEquals(2, job.storyboardVersion);
        job.createdAt = Instant.now().minusSeconds(86_400); // 사람이 하루 동안 검토해도 실패하지 않는다
        var error = new AiRenderException("AI_UNAVAILABLE", true, "safe message");
        for (int attempt = 0; attempt < 5; attempt++) {
            var claim = service.claim(job.id);
            assertNotNull(claim);
            service.failed(claim, error);
        }
        assertEquals(0, job.failures);
        assertEquals(RenderStatus.PROCESSING, job.status);
    }

    @Test void approvalStartsRenderDeadlineAndDiscardsStaleReviewPoll() {
        var job = job();
        service.submitted(service.claim(job.id), "ai-1");
        service.result(service.claim(job.id), review());
        var stale = service.claim(job.id); // 승인 직전에 워커가 검토 상태를 조회하던 중
        assertTrue(service.markRendering(job.id));
        assertEquals(RenderPhase.RENDERING, job.phase);
        assertNotNull(job.deadlineAt);
        service.result(stale, review());
        assertEquals(RenderPhase.RENDERING, job.phase); // 늦게 도착한 검토 상태로 되돌아가지 않는다
        assertFalse(service.markRendering(job.id)); // 검토 단계가 아니면 다시 바꾸지 않는다

        var claim = service.claim(job.id);
        assertTrue(service.completed(claim, "result-key", new AiRenderClient.RenderResult("SUCCEEDED", null, null)));
        assertEquals(RenderStatus.SUCCEEDED, job.status);
        assertEquals(RenderPhase.DONE, job.phase);
        assertEquals("result-key", job.resultStorageKey);
        assertFalse(service.completed(claim, "other-key", new AiRenderClient.RenderResult("SUCCEEDED", null, null)));
    }

    @Test void totalTimeoutAndMissingSourceFailWithoutCallingProvider() {
        var job = job();
        job.createdAt = Instant.now().minusSeconds(1801);
        assertNull(service.claim(job.id));
        assertEquals("RENDER_TIMEOUT", job.errorCode);
        var missing = job();
        when(assets.findAllById(any())).thenReturn(List.of());
        assertNull(service.claim(missing.id));
        assertEquals("SOURCE_MISSING", missing.errorCode);
    }
}
