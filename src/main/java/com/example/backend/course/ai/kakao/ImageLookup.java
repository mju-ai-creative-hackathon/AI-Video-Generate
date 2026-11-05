package com.example.backend.course.ai.kakao;

import java.util.Optional;

/** 검색어에 맞는 대표 이미지(https URL)를 찾는다. 실패하면 빈 값을 돌려주며 예외를 던지지 않는다. */
public interface ImageLookup {
    Optional<String> findImageUrl(String query);
}
