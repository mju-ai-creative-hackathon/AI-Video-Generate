package com.example.backend.shortform.ai;

import org.springframework.core.io.Resource;

import java.util.List;
import java.util.UUID;

/** Draft provider boundary. Change this adapter when the AI team's contract is finalized. */
public interface AiRenderClient {
    boolean isConfigured();

    String submit(RenderSubmission request);

    RenderResult poll(String externalJobId);

    record RenderSubmission(UUID jobId, UUID projectId, String style, String mood, String musicId,
                            List<Source> sources) {
        public RenderSubmission {
            sources = List.copyOf(sources);
        }
    }

    record Source(UUID assetId, UUID missionId, int position, String contentType, Resource resource) {}

    record RenderResult(String status, String videoUrl, String thumbnailUrl) {}
}
