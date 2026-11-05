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
