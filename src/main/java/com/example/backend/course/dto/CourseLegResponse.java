package com.example.backend.course.dto;

import com.example.backend.course.domain.TravelMode;

/** fromSeq=0 이면 출발지가 사용자 현재 위치. distanceMeters 는 우회 계수 반영 추정치. */
public record CourseLegResponse(
        int fromSeq,
        int toSeq,
        String fromName,
        String toName,
        int distanceMeters,
        int estMinutes,
        TravelMode mode,
        String naverMapUrl,
        String googleMapsUrl
) {
}
