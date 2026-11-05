package com.example.backend.course.recommend;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.course.ai.gemini.AiCourseDraft;
import com.example.backend.course.ai.gemini.AiMissionDraft;
import com.example.backend.course.ai.gemini.AiStopDraft;
import com.example.backend.course.ai.gemini.CourseGenerationException;
import com.example.backend.course.ai.gemini.CourseGenerator;
import com.example.backend.course.ai.kakao.ImageLookup;
import com.example.backend.course.ai.kakao.KakaoPlace;
import com.example.backend.course.ai.kakao.PlaceLookup;
import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.course.domain.MissionKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.seed.courses.enabled=false", "app.course.ai-daily-limit=20"})
class RemainingCourseRefinementIntegrationTests extends PostgresIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private AuthService auth;
    @Autowired private CourseRepository courses;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private CourseGenerator generator;
    @MockitoBean private PlaceLookup places;
    @MockitoBean private ImageLookup images;

    private String token;
    private Long baseId;

    @BeforeEach
    void setup() {
        token = signup();
        Course base = Course.preset("서울의 원래 하루", "원본 요약", "종로", 180, 30000, 0);
        base.setOrigin("서울역", "서울 용산구", 37.5559, 126.9723);
        base.addStop(1, 1, "경복궁", "이미 방문한 궁궐", "서울 종로구 사직로", 37.5796, 126.9770,
                "https://example.com/original.jpg", "100", LocalTime.of(13, 0), 40);
        base.addStop(2, 1, "광화문", "원래 남은 장소", "서울 종로구 세종대로", 37.5759, 126.9768,
                null, "101", LocalTime.of(14, 0), 30);
        base.addStop(3, 1, "청계천", "원래 마지막 장소", "서울 종로구", 37.5700, 126.9800,
                null, "102", LocalTime.of(15, 0), 30);
        base.addMission(1, MissionKind.VIDEO, "완료한 워킹샷", "정문에서 천천히 걷기", 5);
        base.addMission(2, MissionKind.PHOTO, "기존 광화문 미션", "정면 사진", null);
        baseId = courses.saveAndFlush(base).getId();
        given(images.findImageUrl(anyString())).willReturn(Optional.of("https://example.com/new.jpg"));
        given(places.findBest(eq("남산서울타워"), any(), any())).willReturn(Optional.of(
                new KakaoPlace("201", "남산서울타워", "서울 용산구", "서울 용산구 남산공원길 105",
                        37.5512, 126.9882, "관광명소", "")));
    }

    @Test
    void preservesVisitedStopAndMissionAndReplacesOnlyRemainingSchedule() throws Exception {
        given(generator.refineRemaining(anyString(), anyString(), anyInt(), any(), any())).willReturn(oneStopDraft());
        long before = courses.count();
        JsonNode changed = mapper.readTree(refine(baseId, token, Map.of("prompt", "이후에는 야경을 보고 싶어", "completedThroughStopSeq", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.stops.length()").value(2))
                .andExpect(jsonPath("$.stops[0].seq").value(1))
                .andExpect(jsonPath("$.stops[0].day").value(1))
                .andExpect(jsonPath("$.stops[0].name").value("경복궁"))
                .andExpect(jsonPath("$.stops[0].description").value("이미 방문한 궁궐"))
                .andExpect(jsonPath("$.stops[0].address").value("서울 종로구 사직로"))
                .andExpect(jsonPath("$.stops[0].lat").value(37.5796))
                .andExpect(jsonPath("$.stops[0].lng").value(126.9770))
                .andExpect(jsonPath("$.stops[0].imageUrl").value("https://example.com/original.jpg"))
                .andExpect(jsonPath("$.stops[0].startTime").value("13:00"))
                .andExpect(jsonPath("$.stops[0].stayMinutes").value(40))
                .andExpect(jsonPath("$.stops[1].seq").value(2))
                .andExpect(jsonPath("$.stops[1].name").value("남산서울타워"))
                .andExpect(jsonPath("$.missions.length()").value(2))
                .andExpect(jsonPath("$.missions[0].stopSeq").value(1))
                .andExpect(jsonPath("$.missions[0].kind").value("VIDEO"))
                .andExpect(jsonPath("$.missions[0].title").value("완료한 워킹샷"))
                .andExpect(jsonPath("$.missions[0].guide").value("정문에서 천천히 걷기"))
                .andExpect(jsonPath("$.missions[0].durationSec").value(5))
                .andExpect(jsonPath("$.missions[1].stopSeq").value(2))
                .andExpect(jsonPath("$.missions[1].title").value("새 야경 미션"))
                .andExpect(jsonPath("$.origin.name").value("서울역"))
                .andReturn().getResponse().getContentAsString());
        assertThat(changed.path("id").asLong()).isNotEqualTo(baseId);
        assertThat(courses.count()).isEqualTo(before + 1);
        mvc.perform(get("/api/courses/{id}", baseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("서울의 원래 하루"))
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.stops[1].name").value("광화문"))
                .andExpect(jsonPath("$.missions[1].title").value("기존 광화문 미션"));
        mvc.perform(get("/api/courses/{id}", changed.path("id").asLong()).header("Authorization", signup()))
                .andExpect(status().isNotFound());
        verify(generator).refineRemaining(anyString(), anyString(), eq(1), eq(37.5796), eq(126.9770));
    }

    @Test
    void filtersVisitedPlaceEvenWhenGeneratorRepeatsItInRemainingDraft() throws Exception {
        given(places.findBest(eq("경복궁 재추천"), any(), any())).willReturn(Optional.of(
                new KakaoPlace("100", "경복궁", "서울 종로구", "서울 종로구 사직로", 37.5796, 126.9770, "고궁", "")));
        given(generator.refineRemaining(anyString(), anyString(), anyInt(), any(), any())).willReturn(new AiCourseDraft(
                "남은 일정", "요약", "서울", 70, 10000,
                List.of(new AiStopDraft(1, "경복궁 재추천", "경복궁 재추천", "변조한 과거", 10, "10:00"),
                        new AiStopDraft(8, "남산서울타워", "남산서울타워", "야경", 30, "18:00")),
                List.of(new AiMissionDraft(1, "PHOTO", "과거를 덮는 미션", "제외", null),
                        new AiMissionDraft(8, "PHOTO", "새 야경 미션", "전경 촬영", null))));
        refine(baseId, token, Map.of("prompt", "야경으로 바꿔", "completedThroughStopSeq", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(2))
                .andExpect(jsonPath("$.stops[0].description").value("이미 방문한 궁궐"))
                .andExpect(jsonPath("$.stops[1].name").value("남산서울타워"))
                .andExpect(jsonPath("$.missions.length()").value(2))
                .andExpect(jsonPath("$.missions[1].stopSeq").value(2))
                .andExpect(jsonPath("$.missions[1].title").value("새 야경 미션"));
    }

    @Test
    void usesExplicitCurrentLocationForRemainingRoute() throws Exception {
        given(generator.refineRemaining(anyString(), anyString(), anyInt(), any(), any())).willReturn(oneStopDraft());
        refine(baseId, token, Map.of("prompt", "가까운 야경", "completedThroughStopSeq", 1, "lat", 37.56, "lng", 126.99))
                .andExpect(status().isOk());
        verify(generator).refineRemaining(anyString(), anyString(), eq(1), eq(37.56), eq(126.99));
        verify(places).findBest(eq("남산서울타워"), eq(37.56), eq(126.99));
    }

    @Test
    void preservesAllStopsAndMissionsThroughSelectedProgress() throws Exception {
        given(generator.refineRemaining(anyString(), anyString(), anyInt(), any(), any())).willReturn(oneStopDraft());
        refine(baseId, token, Map.of("prompt", "마지막은 야경", "completedThroughStopSeq", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.stops[0].name").value("경복궁"))
                .andExpect(jsonPath("$.stops[1].name").value("광화문"))
                .andExpect(jsonPath("$.stops[1].startTime").value("14:00"))
                .andExpect(jsonPath("$.stops[2].name").value("남산서울타워"))
                .andExpect(jsonPath("$.stops[2].seq").value(3))
                .andExpect(jsonPath("$.missions.length()").value(3))
                .andExpect(jsonPath("$.missions[1].title").value("기존 광화문 미션"))
                .andExpect(jsonPath("$.missions[2].stopSeq").value(3));
        verify(generator).refineRemaining(anyString(), anyString(), eq(2), eq(37.5759), eq(126.9768));
    }

    @Test
    void supportsNoCompletedStopsWithoutInventingVisitedHistory() throws Exception {
        given(generator.refineRemaining(anyString(), anyString(), anyInt(), any(), any())).willReturn(oneStopDraft());
        refine(baseId, token, Map.of("prompt", "남산만 방문", "completedThroughStopSeq", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(1))
                .andExpect(jsonPath("$.stops[0].seq").value(1))
                .andExpect(jsonPath("$.stops[0].name").value("남산서울타워"));
    }

    @Test
    void capsCombinedScheduleAtEightAndRemapsOnlyRetainedTailMissions() throws Exception {
        List<AiStopDraft> tail = new java.util.ArrayList<>();
        List<AiMissionDraft> missions = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String name = "새 장소 " + i;
            tail.add(new AiStopDraft(i, name, name, "새로운 방문", 20, "18:00"));
            missions.add(new AiMissionDraft(i, "PHOTO", "새 미션 " + i, "사진 촬영", null));
            given(places.findBest(eq(name), any(), any())).willReturn(Optional.of(new KakaoPlace(
                    "tail-" + i, name, "서울 중구", "서울 중구", 37.55 + i * 0.002,
                    126.99 + i * 0.002, "관광명소", "")));
        }
        given(generator.refineRemaining(anyString(), anyString(), anyInt(), any(), any())).willReturn(
                new AiCourseDraft("남은 일정 8곳 제안", "요약", "서울", 240, 20000, tail, missions));
        JsonNode result = mapper.readTree(refine(baseId, token,
                        Map.of("prompt", "남은 일정 다시 구성", "completedThroughStopSeq", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(8))
                .andExpect(jsonPath("$.stops[0].name").value("경복궁"))
                .andExpect(jsonPath("$.stops[1].name").value("광화문"))
                .andExpect(jsonPath("$.stops[2].name").value("새 장소 1"))
                .andExpect(jsonPath("$.stops[7].name").value("새 장소 6"))
                .andExpect(jsonPath("$.stops[7].seq").value(8))
                .andExpect(jsonPath("$.missions.length()").value(8))
                .andExpect(jsonPath("$.missions[2].stopSeq").value(3))
                .andExpect(jsonPath("$.missions[7].stopSeq").value(8))
                .andExpect(jsonPath("$.missions[7].title").value("새 미션 6"))
                .andReturn().getResponse().getContentAsString());
        assertThat(result.path("stops").toString()).doesNotContain("새 장소 7", "새 장소 8");
        assertThat(result.path("missions").toString()).doesNotContain("새 미션 7", "새 미션 8");
    }

    @Test
    void rejectsMissingNegativeBeyondLastAndAlreadyFinishedProgress() throws Exception {
        refine(baseId, token, Map.of("prompt", "변경" )).andExpect(status().isBadRequest());
        Map<String, Object> nullProgress = new java.util.HashMap<>();
        nullProgress.put("prompt", "변경");
        nullProgress.put("completedThroughStopSeq", null);
        refine(baseId, token, nullProgress).andExpect(status().isBadRequest());
        for (int completed : List.of(-1, 3, 99)) {
            refine(baseId, token, Map.of("prompt", "변경", "completedThroughStopSeq", completed))
                    .andExpect(status().isBadRequest());
        }
        refine(baseId, token, Map.of("prompt", " ", "completedThroughStopSeq", 1)).andExpect(status().isBadRequest());
        refine(baseId, token, Map.of("prompt", "가".repeat(501), "completedThroughStopSeq", 1))
                .andExpect(status().isBadRequest());
        refine(baseId, token, Map.of("prompt", "변경", "completedThroughStopSeq", 1, "lat", 37.5))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(generator, places);
    }

    @Test
    void rejectsUnauthenticatedAndOtherUsersPrivateCourse() throws Exception {
        mvc.perform(post("/api/courses/{id}/remaining-refinements", baseId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"변경\",\"completedThroughStopSeq\":1}"))
                .andExpect(status().isUnauthorized());
        Course privateCourse = Course.ai(auth.getCurrentUser(signup()).id(), "원본", "비공개 코스", "요약", "서울", 90, 10000);
        privateCourse.addStop(1, "경복궁", "설명", "서울", 37.5796, 126.9770, null, "100", LocalTime.NOON, 30);
        privateCourse.addStop(2, "광화문", "설명", "서울", 37.5759, 126.9768, null, "101", LocalTime.of(13, 0), 30);
        Long privateId = courses.saveAndFlush(privateCourse).getId();
        refine(privateId, token, Map.of("prompt", "변경", "completedThroughStopSeq", 1)).andExpect(status().isNotFound());
        verifyNoInteractions(generator, places);
    }

    @Test
    void mapsAiFailureWithoutCreatingPartialCourse() throws Exception {
        long before = courses.count();
        given(generator.refineRemaining(anyString(), anyString(), anyInt(), any(), any())).willThrow(new CourseGenerationException("test unavailable"));
        refine(baseId, token, Map.of("prompt", "변경", "completedThroughStopSeq", 1))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
        assertThat(courses.count()).isEqualTo(before);
    }

    @Test
    void returnsNoResultWhenOnlyAlreadyVisitedPlacesRemain() throws Exception {
        long before = courses.count();
        given(places.findBest(eq("경복궁"), any(), any())).willReturn(Optional.of(
                new KakaoPlace("100", "경복궁", "서울 종로구", "서울 종로구 사직로", 37.5796, 126.9770, "고궁", "")));
        given(generator.refineRemaining(anyString(), anyString(), anyInt(), any(), any())).willReturn(new AiCourseDraft(
                "다시 경복궁", "요약", "서울", 30, null,
                List.of(new AiStopDraft(1, "경복궁", "경복궁", "중복", 30, "18:00")), List.of()));
        refine(baseId, token, Map.of("prompt", "변경", "completedThroughStopSeq", 1))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_NO_RESULT"));
        assertThat(courses.count()).isEqualTo(before);
    }

    private AiCourseDraft oneStopDraft() {
        return new AiCourseDraft("야경으로 마무리하는 하루", "변경된 남은 일정", "용산", 60, 20000,
                List.of(new AiStopDraft(8, "남산서울타워", "남산서울타워", "야경 보기", 30, "18:00")),
                List.of(new AiMissionDraft(8, "VIDEO", "새 야경 미션", "전경을 천천히 촬영", 6)));
    }

    private ResultActions refine(Long courseId, String authorization, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/courses/{id}/remaining-refinements", courseId).header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private String signup() {
        return "Bearer " + auth.signup(new SignupRequest(UUID.randomUUID() + "@remaining.test", "일정 수정", "password123")).accessToken();
    }
}

