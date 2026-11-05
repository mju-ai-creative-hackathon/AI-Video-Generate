package com.example.backend.course.dto;

import com.example.backend.course.domain.CourseMission;
import com.example.backend.course.domain.MissionKind;

public record CourseMissionResponse(
        Long id,
        int stopSeq,
        MissionKind kind,
        String title,
        String guide,
        Integer durationSec
) {
    public static CourseMissionResponse of(CourseMission mission) {
        return new CourseMissionResponse(
                mission.getId(),
                mission.getStopSeq(),
                mission.getKind(),
                mission.getTitle(),
                mission.getGuide(),
                mission.getDurationSec()
        );
    }
}
