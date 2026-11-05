package com.example.backend.course.dto;

/** 코스 출발지(사용자가 출발점으로 말한 곳). 노는 장소가 아니라 동선의 시작점이다. */
public record CourseOriginResponse(String name, String address, double lat, double lng) {
}
