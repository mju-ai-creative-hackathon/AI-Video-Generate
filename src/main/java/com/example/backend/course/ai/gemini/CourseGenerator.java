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

    /** 요청에서 출발지와 대비되는 콘셉트를 뽑는다. 실패해도 예외 대신 출발지 없음·기본 콘셉트를 돌려준다. */
    CoursePlan plan(String prompt);

    /**
     * plan 결과로 코스를 만든다. originLabel 이 있으면 (startLat,startLng) 는 그 출발지 좌표이며 코스는 거기서 출발한다.
     */
    List<AiCourseDraft> generate(String prompt, Double startLat, Double startLng, CoursePlan plan, String originLabel);

    /**
     * 기존 코스(baseCourseJson)를 사용자 요청에 맞게 고친 코스 1개를 만든다.
     *
     * @throws CourseGenerationException 설정 누락, 업스트림 실패, 타임아웃, 유효하지 않은 출력
     */
    AiCourseDraft refine(String baseCourseJson, String prompt, Double userLat, Double userLng);

    /** 완료한 장소는 문맥으로만 제공하고 아직 진행하지 않은 일정만 생성한다. */
    default AiCourseDraft refineRemaining(String baseCourseJson, String prompt, int completedThroughStopSeq,
                                         Double userLat, Double userLng) {
        return refine(baseCourseJson, prompt, userLat, userLng);
    }
}
