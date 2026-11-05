package com.example.backend.shortform;

import com.example.backend.shortform.ai.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ShortformWorker {
    private static final Logger log = LoggerFactory.getLogger(ShortformWorker.class);
    private final RenderStateService state;
    private final AiRenderClient ai;
    private final boolean enabled;
    public ShortformWorker(RenderStateService state, AiRenderClient ai,
                           @Value("${app.shortform.worker-enabled:true}") boolean enabled) {
        this.state = state; this.ai = ai; this.enabled = enabled;
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
                    if (claim.externalJobId() == null) state.submitted(claim, ai.submit(claim.submission()));
                    else state.result(claim, ai.poll(claim.externalJobId()));
                } catch (AiRenderException error) { state.failed(claim, error); }
                // Unknown failures leave the lease intact. A restart or later cycle safely reclaims it.
            } catch (RuntimeException error) {
                log.warn("Shortform dispatch could not finish for job {} ({}); durable lease will be retried.",
                        jobId, error.getClass().getSimpleName());
            }
        }
    }
}
