package com.example.backend.diary;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.diary.domain.DiaryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DiaryApiIntegrationTests extends PostgresIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthService auth;
    @Autowired private CourseRepository courses;
    @Autowired private DiaryEntryRepository diaries;
    @Autowired private JdbcTemplate jdbc;

    private String token;
    private String otherToken;
    private Long courseId;

    @BeforeEach
    void setup() {
        token = signup();
        otherToken = signup();
        courseId = courses.save(Course.preset("반포 한강 산책", "한강과 저녁", "서초", 120, 30000, 0)).getId();
    }

    @Test
    void startsCollectsFinalRouteAndListsPrivateDiary() throws Exception {
        JsonNode recording = start("한강에서 보낸 하루");
        String id = recording.path("id").asString();
        mvc.perform(get("/api/diaries/active").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/diaries").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));

        Instant first = Instant.parse(recording.path("startedAt").asString()).plusMillis(1);
        Instant ended = Instant.now();
        complete(id, token, ended, 4321, List.of(point(37.51, 126.99, first), point(37.52, 127.00, ended)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps").value(4321))
                .andExpect(jsonPath("$.points.length()").value(2));

        mvc.perform(get("/api/diaries/{id}", id).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId))
                .andExpect(jsonPath("$.courseTitle").value("반포 한강 산책"))
                .andExpect(jsonPath("$.title").value("한강에서 보낸 하루"))
                .andExpect(jsonPath("$.recordDate").value(Instant.parse(recording.path("startedAt").asString())
                        .atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString()))
                .andExpect(jsonPath("$.points[0].latitude").value(37.51))
                .andExpect(jsonPath("$.points[1].longitude").value(127.00));
        mvc.perform(get("/api/diaries").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].steps").value(4321));
        mvc.perform(get("/api/diaries/active").header("Authorization", token)).andExpect(status().isNoContent());
    }

    @Test
    void keepsRoutesPrivateAndCannotCompleteTwice() throws Exception {
        String id = start(null).path("id").asString();
        mvc.perform(get("/api/diaries/{id}", id).header("Authorization", otherToken)).andExpect(status().isNotFound());
        complete(id, otherToken, Instant.now(), 0, List.of()).andExpect(status().isNotFound());
        mvc.perform(patch("/api/diaries/{id}", id).header("Authorization", otherToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"침입\",\"memo\":\"침입\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/diaries/{id}", id).header("Authorization", otherToken)).andExpect(status().isNotFound());
        complete(id, token, Instant.now(), 100, List.of()).andExpect(status().isOk());
        complete(id, token, Instant.now(), 999, List.of()).andExpect(status().isConflict());
        mvc.perform(get("/api/diaries/{id}", id).header("Authorization", token))
                .andExpect(jsonPath("$.steps").value(100));
        mvc.perform(get("/api/diaries").header("Authorization", otherToken))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void preventsSecondActiveSessionAndSupportsMemoAndDeletion() throws Exception {
        String id = start(null).path("id").asString();
        mvc.perform(post("/api/diaries").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("courseId", courseId))))
                .andExpect(status().isConflict());
        complete(id, token, Instant.now(), 0, List.of()).andExpect(status().isOk());
        mvc.perform(patch("/api/diaries/{id}", id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"새 제목\",\"memo\":\"노을이 예뻤다.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("새 제목"))
                .andExpect(jsonPath("$.memo").value("노을이 예뻤다."));
        mvc.perform(get("/api/diaries/{id}", id).header("Authorization", token))
                .andExpect(jsonPath("$.memo").value("노을이 예뻤다."));
        mvc.perform(delete("/api/diaries/{id}", id).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/diaries/{id}", id).header("Authorization", token)).andExpect(status().isNotFound());
        start(null);
    }

    @Test
    void rejectsInvalidRouteAndKeepsRecordingUnchanged() throws Exception {
        JsonNode entry = start(null);
        String id = entry.path("id").asString();
        Instant first = Instant.parse(entry.path("startedAt").asString()).plusMillis(1);
        Instant ended = Instant.now();
        complete(id, token, ended, -1, List.of()).andExpect(status().isBadRequest());
        complete(id, token, ended, 0, List.of(point(91, 127, first))).andExpect(status().isBadRequest());
        complete(id, token, ended, 0, List.of(point(37, 127, ended), point(37, 127, first)))
                .andExpect(status().isBadRequest());
        complete(id, token, ended, 0, List.of(point(37, 127, ended.plusSeconds(60))))
                .andExpect(status().isBadRequest());
        complete(id, token, first.minusSeconds(60), 0, List.of()).andExpect(status().isBadRequest());
        mvc.perform(get("/api/diaries/{id}", id).header("Authorization", token))
                .andExpect(jsonPath("$.status").value("RECORDING"));
    }

    @Test
    void requiresAuthAndCannotStartOtherUsersAiCourse() throws Exception {
        Long privateCourse = courses.save(Course.ai(auth.getCurrentUser(otherToken).id(), "요구", "비공개 코스", "설명", "종로", 60, 0)).getId();
        mvc.perform(post("/api/diaries").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("courseId", privateCourse))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/diaries")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/diaries/active")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/diaries").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("courseId", courseId))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountDeletionRemovesItsLocationHistory() throws Exception {
        JsonNode recording = start(null);
        String id = recording.path("id").asString();
        UUID owner = auth.getCurrentUser(token).id();
        complete(id, token, Instant.now(), 15, List.of(point(37.5, 127,
                Instant.parse(recording.path("startedAt").asString()).plusMillis(1))))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/auth/me").header("Authorization", token)).andExpect(status().isNoContent());
        assertThat(diaries.findByIdAndUserId(UUID.fromString(id), owner)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from diary_route_points where diary_id = ?",
                Long.class, UUID.fromString(id))).isZero();
    }

    @Test
    void concurrentStartsCreateOnlyOneActiveRecord() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        String body = mapper.writeValueAsString(Map.of("courseId", courseId));
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> request = () -> {
                ready.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                return mvc.perform(post("/api/diaries").header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        } finally {
            release.countDown();
        }
    }

    private String signup() {
        return "Bearer " + auth.signup(new SignupRequest(UUID.randomUUID() + "@diary.test", "기록 사용자", "password123")).accessToken();
    }

    private JsonNode start(String title) throws Exception {
        Map<String, Object> body = title == null ? Map.of("courseId", courseId) : Map.of("courseId", courseId, "title", title);
        String json = mvc.perform(post("/api/diaries").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("RECORDING"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    private ResultActions complete(String id, String authorization, Instant endedAt, long steps, List<Map<String, Object>> points) throws Exception {
        return mvc.perform(post("/api/diaries/{id}/complete", id).header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("endedAt", endedAt.toString(), "steps", steps, "points", points))));
    }

    private Map<String, Object> point(double latitude, double longitude, Instant at) {
        return Map.of("latitude", latitude, "longitude", longitude, "recordedAt", at.toString());
    }
}
