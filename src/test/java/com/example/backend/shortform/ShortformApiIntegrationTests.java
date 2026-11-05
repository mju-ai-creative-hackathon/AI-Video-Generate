package com.example.backend.shortform;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.SignupRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private static final HttpServer AI = createServer();

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired ObjectMapper mapper;
    @Autowired ShortformWorker worker;

    private String owner;
    private String other;

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
                } else {
                    String state = RESULT_STATUS.get();
                    body = "{\"status\":\"" + state + "\",\"videoUrl\":"
                            + ("SUCCEEDED".equals(state) ? "\"https://media.example.test/shortform.mp4\"" : "null")
                            + ",\"thumbnailUrl\":"
                            + ("SUCCEEDED".equals(state) ? "\"https://media.example.test/thumbnail.jpg\"" : "null") + "}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseCode, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
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
}
