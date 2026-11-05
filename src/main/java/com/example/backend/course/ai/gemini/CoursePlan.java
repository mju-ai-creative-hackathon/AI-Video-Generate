package com.example.backend.course.ai.gemini;

import java.util.List;

/** 코스 생성 전 계획: 사용자가 말한 출발지(없으면 null, 카카오 검색용 이름)와 서로 대비되는 콘셉트들. */
public record CoursePlan(String originQuery, List<String> concepts) {
    public static final CoursePlan EMPTY = new CoursePlan(null, List.of());
}
