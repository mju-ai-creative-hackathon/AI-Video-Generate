package com.example.backend.course.geo;

import com.example.backend.course.domain.TravelMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoTests {

    private static final GeoPoint GYEONGBOKGUNG = new GeoPoint(37.5796, 126.9770);
    private static final GeoPoint TONGIN_MARKET = new GeoPoint(37.5809, 126.9696);
    private static final GeoPoint GWANGJANG_MARKET = new GeoPoint(37.5700, 126.9996);

    @Test
    void haversineMatchesKnownDistances() {
        assertThat(GeoUtils.distanceMeters(new GeoPoint(37, 127), new GeoPoint(38, 127)))
                .isCloseTo(111_195, within(10d));
        assertThat(GeoUtils.distanceMeters(GYEONGBOKGUNG, GYEONGBOKGUNG)).isZero();
        // 서울시청 → 강남역 약 8.9km
        assertThat(GeoUtils.distanceMeters(new GeoPoint(37.5665, 126.9780), new GeoPoint(37.4979, 127.0276)))
                .isBetween(8_500d, 9_200d);
    }

    @Test
    void seoulBoundingBox() {
        assertThat(GeoUtils.isInSeoul(GYEONGBOKGUNG)).isTrue();
        assertThat(GeoUtils.isInSeoul(new GeoPoint(35.1796, 129.0756))).isFalse(); // 부산
        assertThat(GeoUtils.isInSeoul(new GeoPoint(37.4563, 126.7052))).isFalse(); // 인천
    }

    @Test
    void shortLegIsWalkAndLongLegIsPublic() {
        LegEstimate walk = LegPlanner.estimate(GYEONGBOKGUNG, TONGIN_MARKET);
        assertThat(walk.mode()).isEqualTo(TravelMode.WALK);
        double expectedWalk = GeoUtils.distanceMeters(GYEONGBOKGUNG, TONGIN_MARKET) * 1.3;
        assertThat(walk.distanceMeters()).isEqualTo((int) Math.round(expectedWalk));
        assertThat(walk.estMinutes()).isEqualTo((int) Math.ceil(expectedWalk / (4500d / 60)));

        LegEstimate transit = LegPlanner.estimate(TONGIN_MARKET, GWANGJANG_MARKET);
        assertThat(transit.mode()).isEqualTo(TravelMode.PUBLIC);
        double expectedPublic = GeoUtils.distanceMeters(TONGIN_MARKET, GWANGJANG_MARKET) * 1.3;
        assertThat(transit.estMinutes()).isEqualTo((int) Math.ceil(10 + expectedPublic / (20000d / 60)));
    }

    @Test
    void samePointTakesAtLeastOneMinute() {
        LegEstimate leg = LegPlanner.estimate(GYEONGBOKGUNG, GYEONGBOKGUNG);
        assertThat(leg.mode()).isEqualTo(TravelMode.WALK);
        assertThat(leg.distanceMeters()).isZero();
        assertThat(leg.estMinutes()).isEqualTo(1);
    }

    @Test
    void directionLinksAreBuiltWithEncodedNamesAndAppName() {
        DirectionLinks links = new DirectionLinks("com.test.app");
        String naver = links.naverUrl(GYEONGBOKGUNG, "현재 위치", TONGIN_MARKET, "통인시장", TravelMode.WALK);
        assertThat(naver).isEqualTo("nmap://route/walk?slat=37.5796&slng=126.977"
                + "&sname=%ED%98%84%EC%9E%AC%20%EC%9C%84%EC%B9%98"
                + "&dlat=37.5809&dlng=126.9696&dname=%ED%86%B5%EC%9D%B8%EC%8B%9C%EC%9E%A5"
                + "&appname=com.test.app");
        assertThat(links.naverUrl(GYEONGBOKGUNG, "a", TONGIN_MARKET, "b", TravelMode.PUBLIC))
                .startsWith("nmap://route/public?");

        assertThat(links.googleUrl(GYEONGBOKGUNG, GWANGJANG_MARKET, TravelMode.PUBLIC))
                .isEqualTo("https://www.google.com/maps/dir/?api=1&origin=37.5796,126.977"
                        + "&destination=37.57,126.9996&travelmode=transit");
        assertThat(links.googleUrl(GYEONGBOKGUNG, TONGIN_MARKET, TravelMode.WALK)).endsWith("&travelmode=walking");
    }
}
