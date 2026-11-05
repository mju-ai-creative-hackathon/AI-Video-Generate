package com.example.backend.course.geo;

public final class GeoUtils {
    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    // 서울시 대략적 경계 상자
    private static final double SEOUL_MIN_LAT = 37.41;
    private static final double SEOUL_MAX_LAT = 37.72;
    private static final double SEOUL_MIN_LNG = 126.73;
    private static final double SEOUL_MAX_LNG = 127.27;

    private GeoUtils() {
    }

    /** 두 지점의 직선(대원) 거리, 미터. */
    public static double distanceMeters(GeoPoint from, GeoPoint to) {
        double lat1 = Math.toRadians(from.lat());
        double lat2 = Math.toRadians(to.lat());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(to.lng() - from.lng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    public static boolean isInSeoul(GeoPoint point) {
        return point.lat() >= SEOUL_MIN_LAT && point.lat() <= SEOUL_MAX_LAT
                && point.lng() >= SEOUL_MIN_LNG && point.lng() <= SEOUL_MAX_LNG;
    }
}
