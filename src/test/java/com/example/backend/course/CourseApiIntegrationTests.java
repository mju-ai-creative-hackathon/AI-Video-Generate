package com.example.backend.course;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.auth.AuthService;
import com.example.backend.auth.domain.RefreshSessionRepository;
import com.example.backend.auth.domain.UserRepository;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.course.domain.CourseSource;
import com.example.backend.course.domain.MissionKind;
import com.example.backend.course.domain.SavedCourseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.seed.courses.enabled=true",
        "app.course.naver-appname=com.test.course"
})
class CourseApiIntegrationTests extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService authService;
    @Autowired private CourseRepository courseRepository;
    @Autowired private SavedCourseRepository savedCourseRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshSessionRepository refreshSessionRepository;
    @Autowired private PresetCourseSeeder presetCourseSeeder;

    private Long jongnoId;
    private Long seongsuId;
    private Long aiCourseId;
    private UUID ownerId;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        cleanUp();
        presetCourseSeeder.run(null);
        jongnoId = presetId("종로");
        seongsuId = presetId("성수");

        ownerToken = "Bearer " + authService.signup(
                new SignupRequest("course-owner@example.com", "코스 주인", "password123")).accessToken();
        otherToken = "Bearer " + authService.signup(
                new SignupRequest("course-other@example.com", "다른 사람", "password123")).accessToken();
        ownerId = authService.getCurrentUser(ownerToken).id();

        Course ai = Course.ai(ownerId, "을지로 노포 투어", "나만의 을지로", "AI 추천", "을지로", 120, 30000);
        ai.addStop(1, "을지로3가", "노포 골목", null, 37.5663, 126.9918, null, null, LocalTime.of(18, 0), 60);
        ai.addMission(1, MissionKind.PHOTO, "간판 샷", "오래된 간판", null);
        aiCourseId = courseRepository.save(ai).getId();
    }

    @AfterEach
    void cleanUp() {
        savedCourseRepository.deleteAll();
        courseRepository.deleteAll();
        refreshSessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Long presetId(String region) {
        return courseRepository.findBySourceAndRegion(CourseSource.PRESET, region, PageRequest.of(0, 1))
                .getContent().getFirst().getId();
    }

    @Test
    void seederLoadsFixtureOnlyOnce() {
        presetCourseSeeder.run(null);
        assertThat(courseRepository.findBySource(CourseSource.PRESET, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    void regionsAreInDisplayOrder() throws Exception {
        mockMvc.perform(get("/api/courses/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(contains("종로", "성수")));
    }

    @Test
    void listReturnsOnlyPresetsAndFiltersByRegion() throws Exception {
        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].title").value(contains("종로 궁궐과 시장 산책", "성수 숲과 카페")))
                .andExpect(jsonPath("$.content[*].source").value(everyItem(is("PRESET"))))
                .andExpect(jsonPath("$.content[*].saved").value(everyItem(is(false))))
                .andExpect(jsonPath("$.content[0].distanceMeters").value(nullValue()));

        mockMvc.perform(get("/api/courses").param("region", "성수"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].stopCount").value(2))
                .andExpect(jsonPath("$.content[0].stopNames").value(contains("서울숲", "성수 카페거리")))
                .andExpect(jsonPath("$.content[0].thumbnailUrl").value("https://picsum.photos/seed/sf/800/600"))
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(get("/api/courses").param("size", "1"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void distanceIsFilledWithLocation() throws Exception {
        mockMvc.perform(get("/api/courses").param("region", "종로")
                        .param("lat", "37.5796").param("lng", "126.9770"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].distanceMeters").value(0));

        mockMvc.perform(get("/api/courses").param("region", "성수")
                        .param("lat", "37.5796").param("lng", "126.9770"))
                .andExpect(jsonPath("$.content[0].distanceMeters").value(greaterThan(5000)));
    }

    @Test
    void latWithoutLngIs400() throws Exception {
        mockMvc.perform(get("/api/courses").param("lat", "37.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/courses/{id}", jongnoId).param("lng", "127.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailHasStopsLegsMissionsAndLinks() throws Exception {
        mockMvc.perform(get("/api/courses/{id}", jongnoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[*].seq").value(contains(1, 2, 3)))
                .andExpect(jsonPath("$.stops[0].startTime").value("14:00"))
                .andExpect(jsonPath("$.stops[1].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$.stops[2].startTime").value(nullValue()))
                .andExpect(jsonPath("$.legs.length()").value(2))
                .andExpect(jsonPath("$.legs[0].fromSeq").value(1))
                .andExpect(jsonPath("$.legs[0].toSeq").value(2))
                .andExpect(jsonPath("$.legs[0].mode").value("WALK"))
                .andExpect(jsonPath("$.legs[1].mode").value("PUBLIC"))
                .andExpect(jsonPath("$.legs[0].naverMapUrl").value(containsString("nmap://route/walk?")))
                .andExpect(jsonPath("$.legs[0].naverMapUrl").value(containsString("&appname=com.test.course")))
                .andExpect(jsonPath("$.legs[1].googleMapsUrl").value(containsString("travelmode=transit")))
                .andExpect(jsonPath("$.startLeg").value(nullValue()))
                .andExpect(jsonPath("$.missions[*].stopSeq").value(contains(1, 3)))
                .andExpect(jsonPath("$.missions[1].kind").value("VIDEO"))
                .andExpect(jsonPath("$.missions[1].durationSec").value(5))
                .andExpect(jsonPath("$.saved").value(false));

        mockMvc.perform(get("/api/courses/{id}", jongnoId).param("lat", "37.5665").param("lng", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startLeg.fromSeq").value(0))
                .andExpect(jsonPath("$.startLeg.toSeq").value(1))
                .andExpect(jsonPath("$.startLeg.fromName").value("현재 위치"))
                .andExpect(jsonPath("$.startLeg.naverMapUrl").value(containsString("sname=%ED%98%84%EC%9E%AC%20%EC%9C%84%EC%B9%98")))
                .andExpect(jsonPath("$.distanceMeters").value(greaterThan(1000)));
    }

    @Test
    void aiCourseIsVisibleOnlyToOwner() throws Exception {
        mockMvc.perform(get("/api/courses/{id}", aiCourseId).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.prompt").value("을지로 노포 투어"));

        mockMvc.perform(get("/api/courses/{id}", aiCourseId).header("Authorization", otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/courses/{id}", aiCourseId))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/courses/{id}/save", aiCourseId).header("Authorization", otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/courses").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void saveIsIdempotentAndListedNewestFirst() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put("/api/courses/{id}/save", seongsuId).header("Authorization", ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.courseId").value(seongsuId))
                    .andExpect(jsonPath("$.saved").value(true));
        }
        assertThat(savedCourseRepository.count()).isEqualTo(1);

        mockMvc.perform(put("/api/courses/{id}/save", aiCourseId).header("Authorization", ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/courses/saved").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id").value(contains(aiCourseId.intValue(), seongsuId.intValue())))
                .andExpect(jsonPath("$.content[*].saved").value(everyItem(is(true))));

        mockMvc.perform(get("/api/courses").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.content[*].saved").value(contains(false, true)));
        mockMvc.perform(get("/api/courses/{id}", seongsuId).header("Authorization", ownerToken))
                .andExpect(jsonPath("$.saved").value(true));
        mockMvc.perform(get("/api/courses/{id}", seongsuId).header("Authorization", otherToken))
                .andExpect(jsonPath("$.saved").value(false));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete("/api/courses/{id}/save", seongsuId).header("Authorization", ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.saved").value(false));
        }
        mockMvc.perform(get("/api/courses/saved").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void authIsRequiredForSaveEndpoints() throws Exception {
        mockMvc.perform(put("/api/courses/{id}/save", jongnoId)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/courses/{id}/save", jongnoId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/courses/saved")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/courses").header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownCourseIs404() throws Exception {
        mockMvc.perform(get("/api/courses/{id}", 999_999L)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/courses/{id}/save", 999_999L).header("Authorization", ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/courses/{id}/save", 999_999L).header("Authorization", ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void rateLimitCountCountsRecentAiCourses() {
        assertThat(courseRepository.countBySourceAndOwnerIdAndCreatedAtAfter(
                CourseSource.AI, ownerId, Instant.now().minusSeconds(3600))).isEqualTo(1);
        assertThat(courseRepository.countBySourceAndOwnerIdAndCreatedAtAfter(
                CourseSource.AI, ownerId, Instant.now().plusSeconds(60))).isZero();
    }
}
