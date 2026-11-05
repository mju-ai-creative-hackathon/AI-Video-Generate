package com.example.backend.course.recommend;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.auth.AuthService;
import com.example.backend.auth.domain.RefreshSessionRepository;
import com.example.backend.auth.domain.UserRepository;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.course.ai.gemini.AiCourseDraft;
import com.example.backend.course.ai.gemini.AiMissionDraft;
import com.example.backend.course.ai.gemini.AiStopDraft;
import com.example.backend.course.ai.gemini.CourseGenerationException;
import com.example.backend.course.ai.gemini.CourseGenerator;
import com.example.backend.course.ai.kakao.ImageLookup;
import com.example.backend.course.ai.kakao.KakaoPlace;
import com.example.backend.course.ai.kakao.PlaceLookup;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.course.domain.SavedCourseRepository;
import com.example.backend.shortform.ai.ShootingHint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.seed.courses.enabled=false", "app.course.ai-daily-limit=1"})
class CourseRecommendationIntegrationTests extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService authService;
    @Autowired private CourseRepository courseRepository;
    @Autowired private SavedCourseRepository savedCourseRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshSessionRepository refreshSessionRepository;
    @Autowired private CourseShootingHintProvider hintProvider;

    @MockitoBean private CourseGenerator generator;
    @MockitoBean private PlaceLookup placeLookup;
    @MockitoBean private ImageLookup imageLookup;

    private String token;
    private UUID userId;

    @BeforeEach
    void setUp() {
        cleanUp();
        token = "Bearer " + authService.signup(new SignupRequest("rec@example.com", "추천", "password123")).accessToken();
        userId = authService.getCurrentUser(token).id();
        given(placeLookup.findBest(eq("경복궁"), any(), any())).willReturn(Optional.of(
                new KakaoPlace("1", "경복궁", "서울 종로구 세종로 1-1", "서울 종로구 사직로 161", 37.5796, 126.9770, "고궁", "")));
        given(placeLookup.findBest(eq("광화문"), any(), any())).willReturn(Optional.of(
                new KakaoPlace("2", "광화문", "서울 종로구 세종로 1-57", "", 37.5759, 126.9768, "명소", "")));
        given(placeLookup.findBest(eq("없는곳"), any(), any())).willReturn(Optional.empty());
        given(imageLookup.findImageUrl(anyString())).willReturn(Optional.of("https://img.example.com/a.jpg"));
    }

    @AfterEach
    void cleanUp() {
        savedCourseRepository.deleteAll();
        courseRepository.deleteAll();
        refreshSessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    private static AiCourseDraft draft(String title, String... stops) {
        List<AiStopDraft> s = new java.util.ArrayList<>();
        for (int i = 0; i < stops.length; i++) {
            s.add(new AiStopDraft(i + 1, stops[i], stops[i], stops[i] + " 설명", 40, "14:0" + i));
        }
        return new AiCourseDraft(title, "요약", "종로", 120, 10000, s,
                List.of(new AiMissionDraft(1, "VIDEO", "워킹샷", "정문에서 걸어 들어가기", 5),
                        new AiMissionDraft(2, "PHOTO", "대칭샷", "정면 중앙", null)));
    }

    @Test
    void createsCoursesAndDropsUnresolvedStops() throws Exception {
        given(generator.generate(anyString(), any(), any())).willReturn(List.of(
                draft("참 좋은 날 고궁 나들이", "경복궁", "없는곳", "광화문"),
                draft("버려질 코스", "없는곳", "경복궁")));

        mockMvc.perform(post("/api/courses/recommendations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"고궁 3시간\",\"lat\":37.57,\"lng\":126.98}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses.length()").value(1))
                .andExpect(jsonPath("$.courses[0].title").value("참 좋은 날 고궁 나들이"))
                .andExpect(jsonPath("$.courses[0].source").value("AI"))
                .andExpect(jsonPath("$.courses[0].stops.length()").value(2))
                .andExpect(jsonPath("$.courses[0].stops[1].name").value("광화문"))
                .andExpect(jsonPath("$.courses[0].stops[1].seq").value(2))
                .andExpect(jsonPath("$.courses[0].legs[0].naverMapUrl").exists())
                .andExpect(jsonPath("$.courses[0].startLeg").exists())
                .andExpect(jsonPath("$.courses[0].missions.length()").value(1));

        Long courseId = courseRepository.findAll().getFirst().getId();
        List<ShootingHint> hints = hintProvider.hintsFor(userId, courseId, List.of());
        assertThat(hints).hasSize(1);
        assertThat(hints.getFirst().placeName()).isEqualTo("경복궁");
        assertThat(hintProvider.hintsFor(UUID.randomUUID(), courseId, List.of())).isEmpty();
    }

    @Test
    void requiresAuthAndPrompt() throws Exception {
        mockMvc.perform(post("/api/courses/recommendations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"x\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/courses/recommendations").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\" \"}")).andExpect(status().isBadRequest());
    }

    @Test
    void mapsGeneratorFailureAndRateLimit() throws Exception {
        given(generator.generate(anyString(), any(), any())).willThrow(new CourseGenerationException("down"));
        mockMvc.perform(post("/api/courses/recommendations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"고궁\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));

        org.mockito.Mockito.reset(generator);
        given(generator.generate(anyString(), any(), any())).willReturn(List.of(
                draft("a", "경복궁", "광화문"), draft("b", "경복궁", "광화문"), draft("c", "경복궁", "광화문")));
        mockMvc.perform(post("/api/courses/recommendations").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"고궁\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/courses/recommendations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"고궁\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void refinesCourseIntoNewCourseVisibleOnlyToOwner() throws Exception {
        given(generator.generate(anyString(), any(), any())).willReturn(List.of(draft("원본 코스", "경복궁", "광화문")));
        mockMvc.perform(post("/api/courses/recommendations").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"고궁\"}")).andExpect(status().isOk());
        Long baseId = courseRepository.findAll().getFirst().getId();

        given(generator.refine(anyString(), eq("광화문 빼고 경복궁만"), any(), any()))
                .willReturn(draft("고친 코스", "광화문", "경복궁"));
        mockMvc.perform(post("/api/courses/" + baseId + "/refinements").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"광화문 빼고 경복궁만\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("고친 코스"))
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.stops[0].name").value("광화문"))
                .andExpect(jsonPath("$.stops[0].day").value(1));
        assertThat(courseRepository.count()).isEqualTo(2);
        org.mockito.Mockito.verify(generator).refine(org.mockito.ArgumentMatchers.contains("원본 코스"),
                eq("광화문 빼고 경복궁만"), any(), any());

        String other = "Bearer " + authService.signup(new SignupRequest("rec2@example.com", "남", "password123")).accessToken();
        mockMvc.perform(post("/api/courses/" + baseId + "/refinements").header("Authorization", other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"수정\"}"))
                .andExpect(status().isNotFound());
    }
}
