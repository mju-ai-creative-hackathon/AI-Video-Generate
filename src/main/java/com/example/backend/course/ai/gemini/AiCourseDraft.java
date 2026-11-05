package com.example.backend.course.ai.gemini;

import java.util.List;

/** AI가 생성한 서울 코스 초안. stops 는 방문 순서대로 정렬되어 있다. */
public record AiCourseDraft(String title, String summary, String region, int totalMinutes, Integer estimatedCost,
                            List<AiStopDraft> stops, List<AiMissionDraft> missions) {
    public AiCourseDraft {
        stops = stops == null ? List.of() : List.copyOf(stops);
        missions = missions == null ? List.of() : List.copyOf(missions);
    }
}
