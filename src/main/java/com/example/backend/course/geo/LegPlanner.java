package com.example.backend.course.geo;

import com.example.backend.course.domain.TravelMode;

/**
 * 외부 길찾기 API 없이 구간 이동을 대략 추정한다.
 * 직선거리 ×1.3(우회) 이 1.5km 이하면 도보(4.5km/h), 넘으면 대중교통(대기 10분 + 20km/h).
 */
public final class LegPlanner {
    static final double DETOUR_FACTOR = 1.3;
    static final int WALK_LIMIT_METERS = 1500;
    static final double WALK_METERS_PER_MINUTE = 4500d / 60d;
    static final double PUBLIC_METERS_PER_MINUTE = 20000d / 60d;
    static final int PUBLIC_OVERHEAD_MINUTES = 10;

    private LegPlanner() {
    }

    public static LegEstimate estimate(GeoPoint from, GeoPoint to) {
        double detour = GeoUtils.distanceMeters(from, to) * DETOUR_FACTOR;
        int distance = (int) Math.round(detour);
        if (detour <= WALK_LIMIT_METERS) {
            return new LegEstimate(distance, minutes(detour / WALK_METERS_PER_MINUTE), TravelMode.WALK);
        }
        return new LegEstimate(distance,
                minutes(PUBLIC_OVERHEAD_MINUTES + detour / PUBLIC_METERS_PER_MINUTE), TravelMode.PUBLIC);
    }

    private static int minutes(double value) {
        return Math.max(1, (int) Math.ceil(value));
    }
}
