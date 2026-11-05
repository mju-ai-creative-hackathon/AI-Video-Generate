package com.example.backend.course.dto;

import com.example.backend.course.domain.CourseSource;

import java.util.List;

/**
 * legs: 연속한 장소 사이 구간. startLeg: 출발지(origin)가 있으면 출발지 → 1번 장소, 없으면 사용자 위치 → 1번 장소
 * (둘 다 없으면 null, fromSeq=0). origin: 프롬프트에서 뽑은 출발지(없으면 null).
 * prompt: AI 코스의 생성 요청문(PRESET 은 null).
 */
public record CourseDetail(
        Long id,
        String title,
        String summary,
        String region,
        CourseSource source,
        int totalMinutes,
        Integer estimatedCost,
        int stopCount,
        String thumbnailUrl,
        Integer distanceMeters,
        String prompt,
        CourseOriginResponse origin,
        List<CourseStopResponse> stops,
        List<CourseLegResponse> legs,
        CourseLegResponse startLeg,
        List<CourseMissionResponse> missions,
        boolean saved
) {
}
