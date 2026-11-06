package com.example.backend.shortform;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.shortform.ai.ShootingHint;
import com.example.backend.shortform.ai.ShootingHintProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.shortform.worker-enabled=false",
        "app.shortform.ai-enabled=true",
        "app.shortform.retry-delay-ms=0",
        "app.shortform.result-poll-delay-ms=0"
})
@AutoConfigureMockMvc
class ShortformApiIntegrationTests extends PostgresIntegrationTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6P9sAAAAASUVORK5CYII=");
    private static final AtomicInteger SUBMIT_CODE = new AtomicInteger(200);
    private static final AtomicReference<String> RESULT_STATUS = new AtomicReference<>("PROCESSING");
    private static final List<String> SUBMISSIONS = new CopyOnWriteArrayList<>();
    private static final List<String> IDEMPOTENCY_KEYS = new CopyOnWriteArrayList<>();
    private static final Path STORAGE = Path.of("build", "shortform-api-" + UUID.randomUUID()).toAbsolutePath();
    private static final List<String> RUN_CALLS = new CopyOnWriteArrayList<>();
    /** MediaStorage가 MP4로 인식하는 최소 헤더(ftyp isom) + 본문. */
    private static final byte[] RESULT_MP4 = mp4();
    private static final HttpServer AI = createServer();
    private static final long HINTED_COURSE = 777L;
    private static final List<List<Object>> HINT_CALLS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class HintConfig {
        @Bean @Primary
        ShootingHintProvider testShootingHintProvider() {
            return (userId, courseId, missionIds) -> {
                HINT_CALLS.add(List.of(userId, courseId, missionIds));
                if (courseId != HINTED_COURSE) return List.of();
                return missionIds.stream().map(id -> new ShootingHint(id, "서울숲", id % 2 == 0 ? "VIDEO" : "PHOTO",
                        "미션 " + id, "가이드 " + id, id % 2 == 0 ? 5 : null)).toList();
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired ObjectMapper mapper;
    @Autowired ShortformWorker worker;

    private String owner;
    private String other;

    private static byte[] mp4() {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[]{0, 0, 0, 24});
        out.writeBytes("ftypisom".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{0, 0, 2, 0});
        out.writeBytes("isomiso2".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("RESULT-VIDEO-BYTES".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String type, byte[] bytes)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static HttpServer createServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/renders", exchange -> {
                String body;
                int responseCode = 200;
                if ("POST".equals(exchange.getRequestMethod())) {
                    SUBMISSIONS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                    String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    IDEMPOTENCY_KEYS.add(key == null ? "" : key);
                    responseCode = SUBMIT_CODE.get();
                    body = responseCode == 200 ? "{\"jobId\":\"ai-" + key + "\"}" : "{\"message\":\"temporary failure\"}";
                } else if (exchange.getRequestURI().getPath().endsWith("/video")) {
                    exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                    exchange.sendResponseHeaders(200, RESULT_MP4.length);
                    exchange.getResponseBody().write(RESULT_MP4);
                    exchange.close();
                    return;
                } else {
                    String state = RESULT_STATUS.get();
                    if ("SUCCEEDED_STORED".equals(state)) {
                        // 새 계약: URL 없이 완료를 알리고 백엔드가 /video에서 받아 간다.
                        body = "{\"status\":\"SUCCEEDED\",\"phase\":\"DONE\",\"storyboardVersion\":2}";
                    } else if ("AWAITING_REVIEW".equals(state)) {
                        body = "{\"status\":\"AWAITING_REVIEW\",\"phase\":\"AWAITING_REVIEW\",\"busy\":false,"
                                + "\"storyboardVersion\":1}";
                    } else {
                        body = "{\"status\":\"" + state + "\",\"videoUrl\":"
                                + ("SUCCEEDED".equals(state) ? "\"https://media.example.test/shortform.mp4\"" : "null")
                                + ",\"thumbnailUrl\":"
                                + ("SUCCEEDED".equals(state) ? "\"https://media.example.test/thumbnail.jpg\"" : "null") + "}";
                    }
                }
                respond(exchange, responseCode, "application/json", body.getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/runs", exchange -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                exchange.getRequestBody().readAllBytes();
                RUN_CALLS.add(method + " " + path.substring(path.indexOf("/", "/runs/".length())));
                if (path.endsWith("/poster")) {
                    respond(exchange, 200, "image/jpeg", "POSTER".getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("/storyboard") && "GET".equals(method)) {
                    respond(exchange, 200, "application/json", ("{\"version\":1,\"cuts\":[{\"cut_id\":\"c1\","
                            + "\"media\":{\"photo_id\":\"p01\",\"image_url\":\"/api/v1/runs/x/media/p01/poster?expires=1&sig=a\","
                            + "\"video_url\":\"http://lan-only.example/secret.mp4\"}}],\"photos\":[]}")
                            .getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("/storyboard")) {
                    respond(exchange, 409, "application/json", ("{\"error\":{\"code\":\"stale_base_version\","
                            + "\"message\":\"최신 버전이 아닙니다.\",\"base_version\":1,\"latest_version\":2,"
                            + "\"changes\":[{\"cut_id\":\"c1\"}]}}").getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("/approve")) {
                    respond(exchange, 200, "application/json",
                            "{\"version\":1,\"warnings\":[]}".getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("/render")) {
                    respond(exchange, 202, "application/json",
                            "{\"job_id\":\"j1\",\"run_id\":\"r1\"}".getBytes(StandardCharsets.UTF_8));
                } else {
                    respond(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                }
            });
            server.createContext("/catalog", exchange ->
                    respond(exchange, 200, "application/json", ("{\"templates\":[{\"id\":\"reel_mood_trip_temp_v1\","
                            + "\"name\":\"감성 여행 몽타주\",\"hook_type\":\"장소 제시형\",\"audio_mode\":\"music\","
                            + "\"music\":null}],\"music\":[{\"id\":\"calm_piano_01\",\"name\":\"잔잔한 피아노\","
                            + "\"moods\":[\"잔잔함\"]}]}").getBytes(StandardCharsets.UTF_8)));
            server.createContext("/health", exchange ->
                    respond(exchange, 200, "application/json", "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8)));
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.shortform.ai-base-url", () -> "http://127.0.0.1:" + AI.getAddress().getPort());
        registry.add("app.shortform.storage-root", STORAGE::toString);
    }

    @BeforeEach
    void createUsers() {
        SUBMIT_CODE.set(200);
        RESULT_STATUS.set("PROCESSING");
        SUBMISSIONS.clear();
        IDEMPOTENCY_KEYS.clear();
        HINT_CALLS.clear();
        RUN_CALLS.clear();
        owner = token("owner");
        other = token("other");
    }

    private String token(String prefix) {
        return "Bearer " + auth.signup(new SignupRequest(prefix + UUID.randomUUID() + "@example.test",
                "테스트 사용자", "password123")).accessToken();
    }

    @AfterEach
    void finishRemainingTestJobs() {
        SUBMIT_CODE.set(200);
        RESULT_STATUS.set("SUCCEEDED");
        // Keep unrelated test jobs from being picked up by the next test's worker call.
        worker.runOnce();
        worker.runOnce();
        worker.runOnce();
    }

    @AfterAll
    static void stopServer() { AI.stop(0); }

    private JsonNode createProject() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/shortforms").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"서울의 하루\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode createProject(String body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/shortforms").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode manifest(String multipartBody) {
        int metadata = multipartBody.indexOf("name=\"manifest\"");
        assertThat(metadata).isGreaterThanOrEqualTo(0);
        int start = multipartBody.indexOf("\r\n\r\n", metadata) + 4;
        int end = multipartBody.indexOf("\r\n--", start);
        return mapper.readTree(new String(multipartBody.substring(start, end).getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.UTF_8));
    }

    private List<String> upload(String projectId, int count) throws Exception {
        var request = multipart("/api/shortforms/" + projectId + "/assets");
        for (int index = 0; index < count; index++)
            request.file(new MockMultipartFile("files", "photo-" + index + ".png", "image/png", PNG));
        JsonNode result = mapper.readTree(mvc.perform(request.header("Authorization", owner))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        var ids = new ArrayList<String>();
        for (JsonNode item : result) ids.add(item.get("id").asText());
        return ids;
    }

    private String renderBody(List<String> ids, String style) {
        return mapper.writeValueAsString(java.util.Map.of("assetIds", ids, "style", style, "mood", "URBAN"));
    }

    private JsonNode submit(String projectId, List<String> ids, UUID key) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/shortforms/" + projectId + "/renders")
                        .header("Authorization", owner).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(renderBody(ids, "CINEMATIC")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode job(String projectId, String renderId) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/shortforms/" + projectId + "/renders/" + renderId)
                        .header("Authorization", owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void optionsArePublicAndProjectResourcesArePrivate() throws Exception {
        JsonNode options = mapper.readTree(mvc.perform(get("/api/shortforms/options"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(options.get("renderingAvailable").asBoolean()).isTrue();
        mvc.perform(post("/api/shortforms").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"test\"}"))
                .andExpect(status().isUnauthorized());
        String projectId = createProject().get("id").asText();
        var ids = upload(projectId, 1);
        String contentUrl = "/api/shortforms/" + projectId + "/assets/" + ids.getFirst() + "/content";
        mvc.perform(get("/api/shortforms/" + projectId).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get(contentUrl)).andExpect(status().isUnauthorized());
        mvc.perform(get(contentUrl).header("Authorization", other)).andExpect(status().isNotFound());
        byte[] downloaded = mvc.perform(get(contentUrl).header("Authorization", owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(PNG);
    }

    @Test
    void rejectsUnsafeFilesForeignSourcesAndTooFewSources() throws Exception {
        String first = createProject().get("id").asText();
        String second = createProject().get("id").asText();
        mvc.perform(multipart("/api/shortforms/" + first + "/assets").file(new MockMultipartFile(
                "files", "attack.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", owner)).andExpect(status().isBadRequest());
        var firstIds = upload(first, 5);
        var secondIds = upload(second, 1);
        mvc.perform(post("/api/shortforms/" + first + "/renders").header("Authorization", owner)
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(renderBody(firstIds.subList(0, 4), "CINEMATIC"))).andExpect(status().isBadRequest());
        var mixed = new ArrayList<>(firstIds);
        mixed.set(0, secondIds.getFirst());
        mvc.perform(post("/api/shortforms/" + first + "/renders").header("Authorization", owner)
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(renderBody(mixed, "CINEMATIC"))).andExpect(status().isBadRequest());
    }

    @Test
    void submitsOrderedSourcesAndOptionsThenReturnsCompletedVideo() throws Exception {
        String projectId = createProject().get("id").asText();
        var ids = upload(projectId, 5).reversed();
        UUID key = UUID.randomUUID();
        JsonNode initial = submit(projectId, ids, key);
        String renderId = initial.get("id").asText();
        assertThat(initial.get("status").asText()).isEqualTo("QUEUED");
        assertThat(submit(projectId, ids, key).get("id").asText()).isEqualTo(renderId);
        worker.runOnce();
        assertThat(SUBMISSIONS).hasSize(1);
        assertThat(IDEMPOTENCY_KEYS).containsExactly(renderId);
        String multipartBody = SUBMISSIONS.getFirst();
        int metadata = multipartBody.indexOf("name=\"manifest\"");
        assertThat(metadata).isGreaterThanOrEqualTo(0);
        int start = multipartBody.indexOf("\r\n\r\n", metadata) + 4;
        int end = multipartBody.indexOf("\r\n--", start);
        JsonNode manifest = mapper.readTree(multipartBody.substring(start, end));
        assertThat(manifest.get("style").asText()).isEqualTo("CINEMATIC");
        assertThat(manifest.get("mood").asText()).isEqualTo("URBAN");
        assertThat(manifest.has("shootingHints")).isFalse();
        assertThat(HINT_CALLS).isEmpty();
        for (int position = 0; position < ids.size(); position++) {
            assertThat(manifest.get("sources").get(position).get("assetId").asText()).isEqualTo(ids.get(position));
            assertThat(manifest.get("sources").get(position).get("position").asInt()).isEqualTo(position);
        }
        assertThat(job(projectId, renderId).get("status").asText()).isEqualTo("PROCESSING");
        RESULT_STATUS.set("SUCCEEDED");
        worker.runOnce();
        JsonNode done = job(projectId, renderId);
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("videoUrl").asText()).isEqualTo("https://media.example.test/shortform.mp4");
        assertThat(done.get("thumbnailUrl").asText()).isEqualTo("https://media.example.test/thumbnail.jpg");
    }

    @Test
    void preventsConflictingRequestsAndMutationAfterSubmission() throws Exception {
        String projectId = createProject().get("id").asText();
        var ids = upload(projectId, 5);
        UUID key = UUID.randomUUID();
        submit(projectId, ids, key);
        mvc.perform(post("/api/shortforms/" + projectId + "/renders").header("Authorization", owner)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(renderBody(ids, "MINIMAL"))).andExpect(status().isConflict());
        mvc.perform(post("/api/shortforms/" + projectId + "/renders").header("Authorization", owner)
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(renderBody(ids, "CINEMATIC"))).andExpect(status().isConflict());
        mvc.perform(delete("/api/shortforms/" + projectId + "/assets/" + ids.getFirst())
                .header("Authorization", owner)).andExpect(status().isConflict());
    }

    @Test
    void retriesTemporarySubmissionWithSameIdempotencyKey() throws Exception {
        String projectId = createProject().get("id").asText();
        var ids = upload(projectId, 5);
        String renderId = submit(projectId, ids, UUID.randomUUID()).get("id").asText();
        SUBMIT_CODE.set(503);
        worker.runOnce();
        assertThat(job(projectId, renderId).get("status").asText()).isNotEqualTo("FAILED");
        SUBMIT_CODE.set(200);
        worker.runOnce();
        assertThat(IDEMPOTENCY_KEYS).containsExactly(renderId, renderId);
        assertThat(job(projectId, renderId).get("status").asText()).isEqualTo("PROCESSING");
    }

    @Test
    void exposesSafeFailureWithoutUpstreamBody() throws Exception {
        String projectId = createProject().get("id").asText();
        var ids = upload(projectId, 5);
        String renderId = submit(projectId, ids, UUID.randomUUID()).get("id").asText();
        worker.runOnce();
        RESULT_STATUS.set("FAILED");
        worker.runOnce();
        JsonNode result = job(projectId, renderId);
        assertThat(result.get("status").asText()).isEqualTo("FAILED");
        assertThat(result.get("errorCode").asText()).isNotBlank();
        assertThat(result.get("errorMessage").asText()).doesNotContain("temporary failure");
        assertThat(result.get("videoUrl").isNull()).isTrue();
    }

    @Test
    void createsProjectWithOptionalCourseLink() throws Exception {
        JsonNode plain = createProject();
        assertThat(plain.get("courseId").isNull()).isTrue();
        assertThat(plain.get("missionIds").size()).isZero();

        JsonNode linked = createProject("{\"title\":\"코스 브이로그\",\"courseId\":777,\"missionIds\":[3,1,3]}");
        assertThat(linked.get("courseId").asLong()).isEqualTo(777L);
        assertThat(linked.get("missionIds").toString()).isEqualTo("[3,1]");
        JsonNode fetched = mapper.readTree(mvc.perform(get("/api/shortforms/" + linked.get("id").asText())
                        .header("Authorization", owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(fetched.get("courseId").asLong()).isEqualTo(777L);
        assertThat(fetched.get("missionIds").toString()).isEqualTo("[3,1]");

        mvc.perform(post("/api/shortforms").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"missionIds\":[1]}")).andExpect(status().isBadRequest());
        var tooMany = java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList();
        mvc.perform(post("/api/shortforms").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("title", "t", "courseId", 1, "missionIds", tooMany))))
                .andExpect(status().isBadRequest());
    }

    private byte[] streamed(String url) throws Exception {
        var started = mvc.perform(get(url)).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void reviewsStoryboardBeforeRenderingAndStoresTheFinishedVideo() throws Exception {
        String projectId = createProject().get("id").asText();
        String renderId = submit(projectId, upload(projectId, 5), UUID.randomUUID()).get("id").asText();
        String base = "/api/shortforms/" + projectId + "/renders/" + renderId;
        String approveBody = "{\"version\":1,\"acceptWarnings\":false}";

        // 초안 생성 중에는 승인할 수 없고, AI 서버에 생성 요청도 가지 않는다.
        worker.runOnce();
        mvc.perform(post(base + "/approve").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(approveBody)).andExpect(status().isConflict());
        assertThat(RUN_CALLS).isEmpty();

        RESULT_STATUS.set("AWAITING_REVIEW");
        worker.runOnce();
        JsonNode review = job(projectId, renderId);
        assertThat(review.get("status").asText()).isEqualTo("PROCESSING"); // 기존 앱 호환
        assertThat(review.get("phase").asText()).isEqualTo("AWAITING_REVIEW");
        assertThat(review.get("storyboardVersion").asInt()).isEqualTo(1);

        // 스토리보드: 다른 사용자는 볼 수 없고, 미디어 URL은 이 서버의 서명 URL로 바뀐다.
        mvc.perform(get(base + "/storyboard").header("Authorization", other)).andExpect(status().isNotFound());
        JsonNode storyboard = mapper.readTree(mvc.perform(get(base + "/storyboard").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode media = storyboard.get("cuts").get(0).get("media");
        String poster = media.get("image_url").asText();
        assertThat(poster).contains(base + "/media/p01/poster?expires=");
        assertThat(media.get("video_url").isNull()).isTrue(); // 알 수 없는 AI 서버 URL은 내보내지 않는다
        assertThat(streamed(poster)).isEqualTo("POSTER".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get(poster.replaceAll("sig=[0-9a-f]+", "sig=00"))).andExpect(status().isUnauthorized());

        // AI 서버의 검토 오류는 코드와 세부 정보만 전달한다.
        JsonNode stale = mapper.readTree(mvc.perform(put(base + "/storyboard").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"base_version\":1,\"cuts\":[]}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(stale.get("code").asText()).isEqualTo("STALE_BASE_VERSION");
        assertThat(stale.get("details").get("changes").size()).isEqualTo(1);

        JsonNode approved = mapper.readTree(mvc.perform(post(base + "/approve").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(approved.get("phase").asText()).isEqualTo("RENDERING");
        assertThat(RUN_CALLS).containsSubsequence("POST /approve", "POST /render");

        RESULT_STATUS.set("SUCCEEDED_STORED");
        worker.runOnce();
        JsonNode done = job(projectId, renderId);
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("phase").asText()).isEqualTo("DONE");
        String videoUrl = done.get("videoUrl").asText();
        assertThat(videoUrl).contains(base + "/video?expires=");
        byte[] video = mvc.perform(get(videoUrl)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(video).isEqualTo(RESULT_MP4);
        mvc.perform(get(base + "/video")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsTemplatesAndForwardsTheChosenTemplateAndMusic() throws Exception {
        JsonNode options = mapper.readTree(mvc.perform(get("/api/shortforms/options"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(options.get("templates").get(0).get("code").asText()).isEqualTo("reel_mood_trip_temp_v1");
        assertThat(options.get("templates").get(0).get("audioMode").asText()).isEqualTo("MUSIC");
        assertThat(options.get("musics").get(0).get("code").asText()).isEqualTo("calm_piano_01");

        String projectId = createProject().get("id").asText();
        var ids = upload(projectId, 5);
        String unknown = mapper.writeValueAsString(java.util.Map.of("assetIds", ids, "style", "VLOG",
                "mood", "CALM", "templateId", "no_such_template"));
        mvc.perform(post("/api/shortforms/" + projectId + "/renders").header("Authorization", owner)
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(unknown)).andExpect(status().isBadRequest());

        String chosen = mapper.writeValueAsString(java.util.Map.of("assetIds", ids, "style", "VLOG",
                "mood", "CALM", "templateId", "reel_mood_trip_temp_v1", "musicId", "calm_piano_01"));
        JsonNode render = mapper.readTree(mvc.perform(post("/api/shortforms/" + projectId + "/renders")
                        .header("Authorization", owner).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(chosen))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(render.get("templateId").asText()).isEqualTo("reel_mood_trip_temp_v1");
        worker.runOnce();
        JsonNode manifest = manifest(SUBMISSIONS.getFirst());
        assertThat(manifest.get("formatId").asText()).isEqualTo("reel_mood_trip_temp_v1");
        assertThat(manifest.get("musicId").asText()).isEqualTo("calm_piano_01");
    }

    @Test
    void forwardsShootingHintsOnlyWhenProviderReturnsSome() throws Exception {
        JsonNode hinted = createProject("{\"title\":\"코스 브이로그\",\"courseId\":777,\"missionIds\":[1,2]}");
        String hintedId = hinted.get("id").asText();
        submit(hintedId, upload(hintedId, 5), UUID.randomUUID());
        worker.runOnce();
        assertThat(SUBMISSIONS).hasSize(1);
        JsonNode hints = manifest(SUBMISSIONS.getFirst()).get("shootingHints");
        assertThat(hints.size()).isEqualTo(2);
        assertThat(hints.get(0).get("missionId").asLong()).isEqualTo(1L);
        assertThat(hints.get(0).get("kind").asText()).isEqualTo("PHOTO");
        assertThat(hints.get(0).get("placeName").asText()).isEqualTo("서울숲");
        assertThat(hints.get(1).get("kind").asText()).isEqualTo("VIDEO");
        assertThat(hints.get(1).get("durationSec").asInt()).isEqualTo(5);
        assertThat(hints.get(1).get("title").asText()).isEqualTo("미션 2");
        assertThat(hints.get(1).get("guide").asText()).isEqualTo("가이드 2");
        assertThat(HINT_CALLS).hasSize(1);
        assertThat(HINT_CALLS.getFirst().get(1)).isEqualTo(777L);
        assertThat(HINT_CALLS.getFirst().get(2)).isEqualTo(List.of(1L, 2L));

        RESULT_STATUS.set("SUCCEEDED");
        worker.runOnce();
        SUBMISSIONS.clear();
        JsonNode empty = createProject("{\"title\":\"힌트 없는 코스\",\"courseId\":778}");
        String emptyId = empty.get("id").asText();
        submit(emptyId, upload(emptyId, 5), UUID.randomUUID());
        worker.runOnce();
        assertThat(SUBMISSIONS).hasSize(1);
        assertThat(manifest(SUBMISSIONS.getFirst()).has("shootingHints")).isFalse();
    }
}
