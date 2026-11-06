package com.example.backend.course.ai.kakao;

/** 카카오 로컬 키워드 검색으로 확인한 실제 장소. lat=위도(y), lng=경도(x). */
public record KakaoPlace(String id, String name, String address, String roadAddress, double lat, double lng,
                         String categoryName, String placeUrl) {}
