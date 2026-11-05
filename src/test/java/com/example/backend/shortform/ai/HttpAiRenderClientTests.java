package com.example.backend.shortform.ai;

import com.example.backend.shortform.ai.AiRenderClient.RenderSubmission;
import com.example.backend.shortform.ai.AiRenderClient.Source;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpAiRenderClientTests {
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private final List<HttpAiRenderClient> clients = new ArrayList<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"jobId\":\"ai-job-1\"}");
    private final AtomicInteger responseStatus = new AtomicInteger(202);
    private final AtomicInteger bodyDelayMs = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();

    @BeforeEach void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/", exchange -> {
            requests.incrementAndGet();
            requestMethod.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (responseStatus.get() == 302) exchange.getResponseHeaders().add("Location", "/must-not-follow");
            exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
            try {
                if (bodyDelayMs.get() > 0) Thread.sleep(bodyDelayMs.get());
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/";
    }

    @AfterEach void stopServer() {
        clients.forEach(HttpAiRenderClient::close);
        server.stop(0);
        executor.shutdownNow();
    }

    private HttpAiRenderClient client(boolean enabled, String url, String key, int timeout, boolean insecure) {
        var client = new HttpAiRenderClient(enabled, url, key, timeout, insecure, JsonMapper.builder().build());
        clients.add(client);
        return client;
    }

    private HttpAiRenderClient configured() { return client(true, baseUrl, "provider-token", 5, false); }

    private RenderSubmission submission() {
        var source = new ByteArrayResource("ACTUAL_PHOTO_BYTES".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "asset-1.jpg"; }
        };
        var other = new ByteArrayResource("ACTUAL_VIDEO_BYTES".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "asset-2.mp4"; }
        };
        return new RenderSubmission(UUID.randomUUID(), UUID.randomUUID(), "CINEMATIC", "URBAN", "track-1",
                List.of(new Source(UUID.randomUUID(), null, 0, "image/jpeg", source),
                        new Source(UUID.randomUUID(), UUID.randomUUID(), 1, "video/mp4", other)));
    }

    @Test void sendsRealMultipartSourcesInTheSelectedOrderAndAnIdempotencyKey() {
        var submission = submission();
        assertEquals("ai-job-1", configured().submit(submission));
        assertEquals("POST", requestMethod.get());
        assertEquals("/api/renders", requestPath.get());
        assertEquals("Bearer provider-token", authorization.get());
        assertEquals(submission.jobId().toString(), idempotencyKey.get());
        String body = requestBody.get();
        assertTrue(body.contains("name=\"manifest\""));
        assertTrue(body.contains("\"style\":\"CINEMATIC\""));
        assertTrue(body.contains("\"mood\":\"URBAN\""));
        assertTrue(body.contains("\"musicId\":\"track-1\""));
        assertTrue(body.contains(submission.projectId().toString()));
        assertTrue(body.contains("filename=\"asset-1.jpg\""));
        assertTrue(body.contains("filename=\"asset-2.mp4\""));
        assertTrue(body.contains("ACTUAL_PHOTO_BYTES"));
        assertTrue(body.contains("ACTUAL_VIDEO_BYTES"));
        assertTrue(body.indexOf("ACTUAL_PHOTO_BYTES") < body.indexOf("ACTUAL_VIDEO_BYTES"));
    }

    @Test void pollsForTheCompletedVideoWithoutExposingProviderAuthentication() {
        responseStatus.set(200);
        responseBody.set("{\"status\":\"SUCCEEDED\",\"videoUrl\":\"https://cdn.example/video.mp4\",\"thumbnailUrl\":\"https://cdn.example/thumb.jpg\"}");
        var result = configured().poll("ai-job-1");
        assertEquals("GET", requestMethod.get());
        assertEquals("/api/renders/ai-job-1", requestPath.get());
        assertEquals("SUCCEEDED", result.status());
        assertEquals("https://cdn.example/video.mp4", result.videoUrl());
        assertEquals("https://cdn.example/thumb.jpg", result.thumbnailUrl());
    }

    @Test void unconfiguredOrUnsafeConfigurationDoesNotMakeNetworkRequests() {
        for (var adapter : List.of(client(false, baseUrl, "", 5, false),
                client(true, "", "", 5, false), client(true, "file:///tmp/model", "", 5, false),
                client(true, "https://user:password@example.com", "", 5, false),
                client(true, baseUrl, "unsafe\r\nheader", 5, false))) {
            assertFalse(adapter.isConfigured());
            var error = assertThrows(AiRenderException.class, () -> adapter.submit(submission()));
            assertEquals("AI_NOT_CONFIGURED", error.getCode());
            assertFalse(error.isRetryable());
        }
        assertEquals(0, requests.get());
    }

    @Test void distinguishesTransientFailuresFromProviderRejectionsWithoutLeakingBodies() {
        responseBody.set("upstream-secret-diagnostic");
        var adapter = configured();
        for (int code : new int[]{408, 429, 500, 503}) {
            responseStatus.set(code);
            var error = assertThrows(AiRenderException.class, () -> adapter.submit(submission()));
            assertEquals("AI_UNAVAILABLE", error.getCode());
            assertTrue(error.isRetryable());
            assertFalse(error.getMessage().contains("upstream-secret"));
        }
        responseStatus.set(403);
        var error = assertThrows(AiRenderException.class, () -> adapter.submit(submission()));
        assertEquals("AI_REJECTED", error.getCode());
        assertFalse(error.isRetryable());
        responseStatus.set(429);
        responseBody.set("x".repeat(512 * 1024 + 1));
        var oversizedTransient = assertThrows(AiRenderException.class, () -> adapter.submit(submission()));
        assertEquals("AI_UNAVAILABLE", oversizedTransient.getCode());
        assertTrue(oversizedTransient.isRetryable());
    }

    @Test void rejectsRedirectsAndMalformedOrUnboundedResponses() {
        var adapter = configured();
        responseStatus.set(302);
        var redirect = assertThrows(AiRenderException.class, () -> adapter.submit(submission()));
        assertEquals("AI_INVALID_RESPONSE", redirect.getCode());
        assertEquals(1, requests.get());
        responseStatus.set(200);
        for (String body : new String[]{"not-json", "[]", "{}", "{\"jobId\":123}",
                "{\"jobId\":\"../other-job\"}", "x".repeat(512 * 1024 + 1)}) {
            responseBody.set(body);
            assertEquals("AI_INVALID_RESPONSE", assertThrows(AiRenderException.class,
                    () -> adapter.submit(submission())).getCode());
        }
    }

    @Test void rejectsUnknownStatesAndUnsafeOrMissingOutputUrls() {
        var adapter = configured();
        responseStatus.set(200);
        for (String body : new String[]{"{\"status\":\"UNKNOWN\"}", "{\"status\":123}",
                "{\"status\":\"PROCESSING\",\"phase\":\"UNKNOWN\"}",
                "{\"status\":\"PROCESSING\",\"storyboardVersion\":\"2\"}",
                "{\"status\":\"SUCCEEDED\",\"thumbnailUrl\":\"https://cdn.example/thumb.jpg\"}",
                "{\"status\":\"SUCCEEDED\",\"videoUrl\":\"javascript:alert(1)\"}",
                "{\"status\":\"SUCCEEDED\",\"videoUrl\":\"http://localhost/video.mp4\"}",
                "{\"status\":\"SUCCEEDED\",\"videoUrl\":\"https://user:secret@example.com/video.mp4\"}",
                "{\"status\":\"SUCCEEDED\",\"videoUrl\":\"https://cdn.example/" + "x".repeat(2000) + "\"}",
                "{\"status\":\"SUCCEEDED\",\"videoUrl\":\"https://cdn.example/video.mp4\",\"thumbnailUrl\":\"file:///tmp/image\"}"}) {
            responseBody.set(body);
            assertEquals("AI_INVALID_RESPONSE", assertThrows(AiRenderException.class,
                    () -> adapter.poll("ai-job-1")).getCode());
        }
        int previous = requests.get();
        assertThrows(AiRenderException.class, () -> adapter.poll("../other-job"));
        assertEquals(previous, requests.get());
    }

    @Test void acceptsInProgressAndFailedStatesAndMakesInsecureResultsExplicitlyOptIn() {
        var adapter = configured();
        responseStatus.set(200);
        for (String state : List.of("QUEUED", "PROCESSING", "FAILED")) {
            responseBody.set("{\"status\":\"" + state + "\"}");
            var result = adapter.poll("ai-job-1");
            assertEquals(state, result.status());
            assertNull(result.videoUrl());
        }
        responseBody.set("{\"status\":\"SUCCEEDED\",\"videoUrl\":\"http://localhost/video.mp4\"}");
        assertEquals("http://localhost/video.mp4", client(true, baseUrl, "", 5, true).poll("ai-job-1").videoUrl());
    }

    @Test void readsReviewPhaseProgressAndCompletionWithoutPublicUrl() {
        var adapter = configured();
        responseStatus.set(200);
        responseBody.set("{\"status\":\"AWAITING_REVIEW\",\"phase\":\"AWAITING_REVIEW\",\"busy\":true,"
                + "\"stage\":\"script\",\"message\":\"대본 재생성 중\",\"current\":2,\"total\":5,"
                + "\"storyboardVersion\":3,\"error\":null}");
        var review = adapter.poll("ai-job-1");
        assertEquals("AWAITING_REVIEW", review.status());
        assertEquals("AWAITING_REVIEW", review.phase());
        assertTrue(review.busy());
        assertEquals("script", review.progress().stage());
        assertEquals(2, review.progress().current());
        assertEquals(3, review.storyboardVersion());
        responseBody.set("{\"status\":\"SUCCEEDED\",\"phase\":\"DONE\",\"storyboardVersion\":4}");
        var done = adapter.poll("ai-job-1");
        assertEquals("SUCCEEDED", done.status());
        assertNull(done.videoUrl()); // 백엔드가 /renders/{id}/video에서 내려받는다
    }

    @Test void proxiesReviewResponsesAndTreatsRepeatedRenderStartAsSuccess() {
        var adapter = configured();
        responseStatus.set(409);
        responseBody.set("{\"error\":{\"code\":\"stale_base_version\",\"message\":\"m\",\"changes\":[]}}");
        var conflict = adapter.saveStoryboard("ai-job-1", "{\"base_version\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals(409, conflict.status());
        assertEquals("PUT", requestMethod.get());
        assertEquals("/api/runs/ai-job-1/storyboard", requestPath.get());
        assertEquals("Bearer provider-token", authorization.get());

        responseStatus.set(200);
        responseBody.set("{\"version\":2}");
        assertTrue(adapter.approve("ai-job-1", 2, true).ok());
        assertEquals("/api/runs/ai-job-1/approve", requestPath.get());
        assertTrue(requestBody.get().contains("\"accept_warnings\":true"));

        responseStatus.set(409);
        responseBody.set("{\"error\":{\"code\":\"job_in_progress\",\"message\":\"busy\"}}");
        assertDoesNotThrow(() -> adapter.startRender("ai-job-1"));
        responseBody.set("{\"error\":{\"code\":\"approval_required\",\"message\":\"no\"}}");
        assertEquals("AI_APPROVAL_REQUIRED",
                assertThrows(AiRenderException.class, () -> adapter.startRender("ai-job-1")).getCode());

        responseStatus.set(401);
        assertEquals("AI_REJECTED", assertThrows(AiRenderException.class,
                () -> adapter.getStoryboard("ai-job-1", null)).getCode());
        responseStatus.set(503);
        assertEquals("AI_UNAVAILABLE", assertThrows(AiRenderException.class,
                () -> adapter.getStoryboard("ai-job-1", null)).getCode());
    }

    @Test void timesOutEvenWhenTheServerHasAlreadySentResponseHeaders() {
        responseStatus.set(200);
        bodyDelayMs.set(3000);
        var adapter = client(true, baseUrl, "", 1, false);
        assertTimeout(Duration.ofSeconds(2), () -> {
            var error = assertThrows(AiRenderException.class, () -> adapter.submit(submission()));
            assertEquals("AI_UNAVAILABLE", error.getCode());
            assertTrue(error.isRetryable());
        });
    }

    @Test void constrainsTheRequestTimeoutToTheWorkerLease() {
        assertThrows(IllegalArgumentException.class, () -> client(true, baseUrl, "", 0, false));
        assertThrows(IllegalArgumentException.class, () -> client(true, baseUrl, "", 91, false));
    }
}
