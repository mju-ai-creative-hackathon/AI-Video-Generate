package com.example.backend.course.ai.gemini;

/** AI가 제안한 코스의 방문 장소. searchQuery 는 카카오 장소 키워드 검색용 문자열. startTime 은 "HH:mm" 또는 null. */
public record AiStopDraft(int seq, String name, String searchQuery, String description, int stayMinutes,
                          String startTime) {}
