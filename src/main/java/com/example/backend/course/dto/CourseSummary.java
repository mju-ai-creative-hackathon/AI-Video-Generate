package com.example.backend.course.dto;

import com.example.backend.course.domain.CourseSource;

import java.util.List;

/** distanceMeters: 사용자 위치 → 첫 장소 직선거리(위치 미전달 시 null). */
public record CourseSummary(
        Long id,
        String title,
        String summary,
        String region,
        CourseSource source,
        int totalMinutes,
        Integer estimatedCost,
        int stopCount,
        List<String> stopNames,
        String thumbnailUrl,
        Integer distanceMeters,
        boolean saved
) {
}
