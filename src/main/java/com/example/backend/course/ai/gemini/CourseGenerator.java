package com.example.backend.course.ai.gemini;

import java.util.List;

public interface CourseGenerator {
    /**
     * 자유 문장 요청으로 최대 3개의 서울 코스 초안을 만든다.
     *
     * @param userLat 사용자 위도 (없으면 null)
     * @param userLng 사용자 경도 (없으면 null)
     * @throws CourseGenerationException 설정 누락, 업스트림 실패, 타임아웃, 유효하지 않은 출력
     */
    List<AiCourseDraft> generate(String prompt, Double userLat, Double userLng);
}
