package com.example.backend.course.geo;

import com.example.backend.course.domain.TravelMode;

/** distanceMeters 는 우회 계수를 반영한 예상 이동 거리. */
public record LegEstimate(int distanceMeters, int estMinutes, TravelMode mode) {
}
