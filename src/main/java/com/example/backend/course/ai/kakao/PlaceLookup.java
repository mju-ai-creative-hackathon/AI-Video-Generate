package com.example.backend.course.ai.kakao;

import java.util.Optional;

/** 장소명을 서울 내 실제 장소(좌표·주소)로 확인한다. 실패하면 빈 값을 돌려주며 예외를 던지지 않는다. */
public interface PlaceLookup {
    Optional<KakaoPlace> findBest(String query, Double nearLat, Double nearLng);
}
