package com.example.backend.course.recommend;

import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.MissionKind;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RemainingCourseBuilderTests {
    @Test
    void preservesCompletedOvernightVisitAndMovesRemainingVisitPastCheckout() {
        UUID owner = UUID.randomUUID();
        Course original = Course.preset("서울의 밤", "기존 설명", "중구", 160, 80000, 0);
        original.setOrigin("출발지", "출발 주소", 37.55, 126.97);
        original.addStop(1, 1, "숙소", "완료한 숙박", "숙소 주소", 37.56, 126.98,
                "https://example.test/hotel.jpg", "hotel", LocalTime.of(23, 30), 90);
        original.addStop(2, "기존 식당", "교체할 장소", "주소", 37.57, 126.99,
                null, "old", LocalTime.of(2, 0), 30);
        original.addMission(1, MissionKind.PHOTO, "숙소 앞 사진", "완료한 미션", null);
        Course prefix = RemainingCourseBuilder.snapshot(original, owner, 1);

        Course tail = Course.ai(owner, "새 요청", "새 코스", "새 설명", "종로", 1, 20000);
        tail.addStop(1, 1, "새 식당", "새 장소", "새 주소", 37.57, 126.99,
                null, "new", LocalTime.of(0, 10), 45);
        tail.addMission(1, MissionKind.VIDEO, "음식 영상", "천천히 촬영", 10);

        Course result = RemainingCourseBuilder.combine(prefix, tail, owner, "일정 수정");

        var completed = result.getStops().getFirst();
        assertThat(completed.getName()).isEqualTo("숙소");
        assertThat(completed.getDescription()).isEqualTo("완료한 숙박");
        assertThat(completed.getAddress()).isEqualTo("숙소 주소");
        assertThat(completed.getExternalPlaceId()).isEqualTo("hotel");
        assertThat(completed.getImageUrl()).isEqualTo("https://example.test/hotel.jpg");
        assertThat(completed.getStartTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(completed.getStayMinutes()).isEqualTo(90);
        assertThat(completed.getDay()).isEqualTo(1);
        assertThat(result.getStops().getLast().getDay()).isEqualTo(2);
        assertThat(result.getStops().getLast().getStartTime()).isAfter(LocalTime.of(1, 0));
        assertThat(result.getTotalMinutes()).isGreaterThan(135);
        assertThat(result.getEstimatedCost()).isNull();
        assertThat(result.getOriginName()).isEqualTo("출발지");
        assertThat(result.getMissions()).hasSize(2);
        assertThat(result.getMissions().getFirst().getTitle()).isEqualTo("숙소 앞 사진");
        assertThat(result.getMissions().getFirst().getStopSeq()).isEqualTo(1);
        assertThat(result.getMissions().getLast().getStopSeq()).isEqualTo(2);
        assertThat(original.getStops().getLast().getName()).isEqualTo("기존 식당");
        assertThat(original.getMissions()).hasSize(1);
    }

    @Test
    void rolloverCarriesAcrossMidnightWhenPreviousVisitHasUnknownStart() {
        UUID owner = UUID.randomUUID();
        Course prefix = Course.ai(owner, null, "기존 코스", "", "중구", 60, null);
        prefix.addStop(1, 2, "완료 장소", "", null, 37.56, 126.98, null, "visited", null, 30);
        Course tail = Course.ai(owner, "요청", "새 코스", "", "중구", 90, null);
        tail.addStop(1, 1, "늦은 식당", "", null, 37.57, 126.99, null, "late", LocalTime.of(23, 50), 45);
        tail.addStop(2, 1, "숙소", "", null, 37.58, 127.00, null, "hotel", LocalTime.of(0, 10), 30);

        Course result = RemainingCourseBuilder.combine(prefix, tail, owner, "요청");

        assertThat(result.getStops().get(1).getDay()).isEqualTo(2);
        assertThat(result.getStops().getLast().getDay()).isEqualTo(3);
        assertThat(result.getStops().getLast().getStartTime()).isAfter(LocalTime.of(0, 35));
        assertThat(result.getTotalMinutes()).isGreaterThan(105);
    }
}
