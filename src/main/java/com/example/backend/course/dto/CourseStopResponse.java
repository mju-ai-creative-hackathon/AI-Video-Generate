package com.example.backend.course.dto;

import com.example.backend.course.domain.CourseStop;

import java.time.format.DateTimeFormatter;

/** startTime: "HH:mm" 또는 null. */
public record CourseStopResponse(
        int seq,
        String name,
        String description,
        String address,
        double lat,
        double lng,
        String imageUrl,
        String startTime,
        int stayMinutes
) {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public static CourseStopResponse of(CourseStop stop) {
        return new CourseStopResponse(
                stop.getSeq(),
                stop.getName(),
                stop.getDescription(),
                stop.getAddress(),
                stop.getLat(),
                stop.getLng(),
                stop.getImageUrl(),
                stop.getStartTime() == null ? null : stop.getStartTime().format(HH_MM),
                stop.getStayMinutes()
        );
    }
}
