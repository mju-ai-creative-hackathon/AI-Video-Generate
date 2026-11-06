package com.example.backend.course.recommend;

import com.example.backend.course.ai.kakao.KakaoPlace;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceExclusionTests {
    private static KakaoPlace p(String name, String category) {
        return new KakaoPlace("1", name, "서울", "", 37.5, 127.0, category, "");
    }

    @Test
    void excludesPublicOfficesAndOrdinaryConvenienceStores() {
        assertThat(CourseRecommendationService.isExcluded(p("연희동주민센터", "사회,공공기관 > 행정기관 > 주민센터"))).isTrue();
        assertThat(CourseRecommendationService.isExcluded(p("서대문구청", "사회,공공기관 > 행정기관 > 구청"))).isTrue();
        assertThat(CourseRecommendationService.isExcluded(p("GS25 경의선책거리점", "가정,생활 > 편의점 > GS25"))).isTrue();
        assertThat(CourseRecommendationService.isExcluded(p("CU 여의도한강공원1호점", "가정,생활 > 편의점 > CU"))).isFalse();
        assertThat(CourseRecommendationService.isExcluded(p("서대문형무소역사관", "문화,예술 > 문화시설 > 박물관"))).isFalse();
    }
}
