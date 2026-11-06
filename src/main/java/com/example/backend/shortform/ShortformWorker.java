package com.example.backend.shortform;

import com.example.backend.common.ApiException;
import com.example.backend.shortform.ai.*;
import com.example.backend.shortform.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class ShortformWorker {
    private static final Logger log = LoggerFactory.getLogger(ShortformWorker.class);
    /** 완성본 상한. 1080x1920 숏폼 1분이 수십 MB이므로 넉넉히 둔다. */
    private static final long MAX_RESULT_BYTES = 1024L * 1024 * 1024;
    private final RenderStateService state;
    private final AiRenderClient ai;
    private final MediaStorage storage;
    private final boolean enabled;
    public ShortformWorker(RenderStateService state, AiRenderClient ai, MediaStorage storage,
                           @Value("${app.shortform.worker-enabled:true}") boolean enabled) {
        this.state = state; this.ai = ai; this.storage = storage; this.enabled = enabled;
    }
    @Scheduled(fixedDelayString = "${app.shortform.poll-delay-ms:3000}")
    public void scheduled() { if (enabled) runOnce(); }

    /** Also callable by deterministic tests without enabling the scheduler. */
    public void runOnce() {
        if (!ai.isConfigured()) return;
        for (var jobId : state.due()) {
            try {
                var claim = state.claim(jobId);
                if (claim == null) continue;
                try {
                    if (claim.externalJobId() == null) state.submitted(claim, ai.submit(state.withShootingHints(claim.submission())));
                    else {
                        var result = ai.poll(claim.externalJobId());
                        if ("SUCCEEDED".equals(result.status()) && result.videoUrl() == null) store(claim, result);
                        else state.result(claim, result);
                    }
                } catch (AiRenderException error) { state.failed(claim, error); }
                // Unknown failures leave the lease intact. A restart or later cycle safely reclaims it.
            } catch (RuntimeException error) {
                log.warn("Shortform dispatch could not finish for job {} ({}); durable lease will be retried.",
                        jobId, error.getClass().getSimpleName());
            }
        }
    }

    /** 완성본을 받아 비공개 저장소에 보관한 뒤 성공 처리한다. 트랜잭션 밖에서 내려받는다. */
    private void store(RenderStateService.Claim claim, AiRenderClient.RenderResult result) {
        String key;
        try (InputStream video = ai.downloadVideo(claim.externalJobId())) {
            key = storage.storeGenerated(video, MAX_RESULT_BYTES).storageKey();
        } catch (ApiException error) {
            // 저장 중 오류(연결 끊김 포함)는 다시 받는다. 형식·용량 문제는 다시 받아도 같다.
            if (error.getStatus().is5xxServerError()) {
                throw new AiRenderException("AI_UNAVAILABLE", true, "완성본을 내려받지 못했습니다.");
            }
            throw new AiRenderException("AI_INVALID_RESPONSE", false, "AI 서비스가 올바른 영상 파일을 주지 않았습니다.");
        } catch (IOException error) {
            throw new AiRenderException("AI_UNAVAILABLE", true, "완성본을 내려받지 못했습니다.");
        }
        if (!state.completed(claim, key, result)) {
            try { storage.delete(key); } catch (RuntimeException ignored) { }
        }
    }
}
