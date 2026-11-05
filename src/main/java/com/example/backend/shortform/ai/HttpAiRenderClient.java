package com.example.backend.shortform.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;

/**
 * AI 영상 서버 HTTP 어댑터. 소스는 multipart로 실제 파일을 보내고, 상태는 주기적으로 조회한다.
 * 검토 단계의 스토리보드 조회·수정·승인은 AI 서버의 `/runs/{id}/...` 경로로 전달한다.
 */
@Component
public class HttpAiRenderClient implements AiRenderClient {
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern PHOTO_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern MIME_TYPE = Pattern.compile("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+");
    private static final Pattern RANGE = Pattern.compile("bytes=\\d*-\\d*(,\\s*\\d*-\\d*){0,4}");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes [0-9*/-]{1,80}");
    private static final Set<String> STATUSES = Set.of("QUEUED", "PROCESSING", "AWAITING_REVIEW", "SUCCEEDED", "FAILED");
    private static final Set<String> PHASES = Set.of("DRAFTING", "AWAITING_REVIEW", "RENDERING", "DONE");
    /** 사용자에게 그대로 보여줄 수 있는 AI 서버의 검토 응답 코드. */
    private static final Set<Integer> PASS_THROUGH = Set.of(400, 404, 409, 422);
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_STORYBOARD_BYTES = 4 * 1024 * 1024;
    private static final long HEALTH_CACHE_MS = 30_000;

    private final boolean enabled;
    private final URI baseUri;
    private final String apiKey;
    private final Duration requestTimeout;
    private final Duration submitTimeout;
    private final boolean allowInsecureResultUrls;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private volatile long healthCheckedAt;
    private volatile boolean healthy;

    @Autowired
    public HttpAiRenderClient(
            @Value("${app.shortform.ai-enabled:false}") boolean enabled,
            @Value("${app.shortform.ai-base-url:}") String baseUrl,
            @Value("${app.shortform.ai-api-key:}") String apiKey,
            @Value("${app.shortform.request-timeout-seconds:60}") int requestTimeoutSeconds,
            @Value("${app.shortform.submit-timeout-seconds:300}") int submitTimeoutSeconds,
            @Value("${app.shortform.allow-insecure-result-urls:false}") boolean allowInsecureResultUrls,
            ObjectMapper mapper
    ) {
        this.enabled = enabled;
        this.baseUri = parseBaseUri(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 90) {
            throw new IllegalArgumentException("AI request timeout must be between 1 and 90 seconds.");
        }
        if (submitTimeoutSeconds < requestTimeoutSeconds || submitTimeoutSeconds > 900) {
            throw new IllegalArgumentException("AI submit timeout must be between the request timeout and 900 seconds.");
        }
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.submitTimeout = Duration.ofSeconds(submitTimeoutSeconds);
        this.allowInsecureResultUrls = allowInsecureResultUrls;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public HttpAiRenderClient(boolean enabled, String baseUrl, String apiKey, int requestTimeoutSeconds,
                              boolean allowInsecureResultUrls, ObjectMapper mapper) {
        this(enabled, baseUrl, apiKey, requestTimeoutSeconds, Math.max(requestTimeoutSeconds, 300),
                allowInsecureResultUrls, mapper);
    }

    @Override
    public boolean isConfigured() {
        return enabled && baseUri != null && !apiKey.contains("\r") && !apiKey.contains("\n");
    }

    @Override
    public boolean isAvailable() {
        if (!isConfigured()) return false;
        long now = System.currentTimeMillis();
        if (now - healthCheckedAt < HEALTH_CACHE_MS) return healthy;
        boolean result;
        try {
            HttpRequest request = requestBuilder("/health", Duration.ofSeconds(3)).GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            result = response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = false;
        } catch (IOException | RuntimeException e) {
            result = false;
        }
        healthy = result;
        healthCheckedAt = now;
        return result;
    }

    @Override
    public String submit(RenderSubmission submission) {
        requireConfigured();
        String boundary = "Shortform-" + UUID.randomUUID();
        HttpRequest.BodyPublisher body;
        try {
            List<ManifestSource> sourceMetadata = submission.sources().stream()
                    .map(source -> new ManifestSource(source.assetId(), source.missionId(), source.position(),
                            source.contentType(), safeFilename(source)))
                    .toList();
            var manifest = new Manifest(submission.jobId(), submission.projectId(), submission.title(),
                    submission.style(), submission.styleLabel(), submission.mood(), submission.moodLabel(),
                    submission.musicId(), sourceMetadata, submission.shootingHints());
            var parts = new ArrayList<HttpRequest.BodyPublisher>();
            parts.add(text("--" + boundary + "\r\nContent-Disposition: form-data; name=\"manifest\"\r\n"
                    + "Content-Type: application/json\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(manifest)));
            parts.add(text("\r\n"));
            for (Source source : submission.sources()) {
                if (source.contentType() == null || !MIME_TYPE.matcher(source.contentType()).matches()) {
                    throw new AiRenderException("AI_INVALID_REQUEST", false, "촬영 소스 유형이 유효하지 않습니다.");
                }
                parts.add(text("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                        + safeFilename(source) + "\"\r\nContent-Type: " + source.contentType() + "\r\n\r\n"));
                parts.add(HttpRequest.BodyPublishers.ofInputStream(() -> openSource(source)));
                parts.add(text("\r\n"));
            }
            parts.add(text("--" + boundary + "--\r\n"));
            body = HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
        } catch (AiRenderException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiRenderException("AI_INVALID_REQUEST", false, "AI 제작 요청을 구성할 수 없습니다.");
        }

        // 최대 20개 원본을 올리므로 일반 요청보다 긴 제한 시간을 쓴다.
        HttpRequest request = requestBuilder("/renders", submitTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", submission.jobId().toString())
                .POST(body).build();
        JsonNode response = exchange(request, submitTimeout);
        String jobId = requiredString(response, "jobId");
        if (!JOB_ID.matcher(jobId).matches()) throw invalidResponse();
        return jobId;
    }

    @Override
    public RenderResult poll(String externalJobId) {
        requireConfigured();
        requireJobId(externalJobId);
        JsonNode response = exchange(requestBuilder("/renders/" + externalJobId, requestTimeout).GET().build(),
                requestTimeout);
        String status = requiredString(response, "status");
        if (!STATUSES.contains(status)) throw invalidResponse();
        String phase = optionalString(response, "phase");
        if (phase != null && !PHASES.contains(phase)) throw invalidResponse();
        JsonNode busyNode = response.get("busy");
        boolean busy = busyNode != null && busyNode.isBoolean() && busyNode.asBoolean();
        var progress = new Progress(limit(optionalString(response, "stage"), 40),
                limit(optionalString(response, "message"), 500),
                optionalInt(response, "current"), optionalInt(response, "total"));
        Integer storyboardVersion = optionalInt(response, "storyboardVersion");
        String error = limit(optionalString(response, "error"), 300);
        if (!status.equals("SUCCEEDED")) {
            return new RenderResult(status, null, null, phase, busy, progress, storyboardVersion, error);
        }

        // 새 계약은 URL 없이 완료를 알리고, 백엔드가 downloadVideo로 완성본을 받아 보관한다.
        String videoUrl = optionalString(response, "videoUrl");
        String thumbnailUrl = optionalString(response, "thumbnailUrl");
        if ((videoUrl != null && !validResultUrl(videoUrl))
                || (thumbnailUrl != null && !validResultUrl(thumbnailUrl))) {
            throw invalidResponse();
        }
        if (videoUrl == null && thumbnailUrl != null) throw invalidResponse();
        return new RenderResult(status, videoUrl, thumbnailUrl, phase, busy, progress, storyboardVersion, error);
    }

    @Override
    public Proxied getStoryboard(String externalJobId, Integer version) {
        requireConfigured();
        requireJobId(externalJobId);
        String query = version == null ? "" : "?version=" + Math.max(1, version);
        return proxy(requestBuilder("/runs/" + externalJobId + "/storyboard" + query, requestTimeout).GET().build());
    }

    @Override
    public Proxied saveStoryboard(String externalJobId, byte[] json) {
        requireConfigured();
        requireJobId(externalJobId);
        return proxy(jsonRequest("/runs/" + externalJobId + "/storyboard", json).PUT(
                HttpRequest.BodyPublishers.ofByteArray(json)).build());
    }

    @Override
    public Proxied regenStoryboard(String externalJobId, byte[] json) {
        requireConfigured();
        requireJobId(externalJobId);
        return proxy(jsonRequest("/runs/" + externalJobId + "/storyboard/regen", json).POST(
                HttpRequest.BodyPublishers.ofByteArray(json)).build());
    }

    @Override
    public Proxied approve(String externalJobId, int version, boolean acceptWarnings) {
        requireConfigured();
        requireJobId(externalJobId);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("version", version);
        payload.put("accept_warnings", acceptWarnings);
        byte[] json = mapper.writeValueAsBytes(payload);
        return proxy(jsonRequest("/runs/" + externalJobId + "/approve", json).POST(
                HttpRequest.BodyPublishers.ofByteArray(json)).build());
    }

    @Override
    public void startRender(String externalJobId) {
        requireConfigured();
        requireJobId(externalJobId);
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        Proxied response = proxy(jsonRequest("/runs/" + externalJobId + "/render", json).POST(
                HttpRequest.BodyPublishers.ofByteArray(json)).build());
        if (response.ok()) return;
        String code = errorCode(response.body());
        if (response.status() == 409 && "job_in_progress".equals(code)) return; // 재시도. 이미 생성 중이다
        if (response.status() == 409 && "approval_required".equals(code)) {
            throw new AiRenderException("AI_APPROVAL_REQUIRED", false, "승인된 스토리보드가 없어 영상을 만들 수 없습니다.");
        }
        throw new AiRenderException("AI_REJECTED", false, "AI 서비스가 영상 생성 요청을 거절했습니다.");
    }

    @Override
    public MediaStream openMedia(String externalJobId, String photoId, boolean poster, String range) {
        requireConfigured();
        requireJobId(externalJobId);
        if (photoId == null || !PHOTO_ID.matcher(photoId).matches()) {
            throw new AiRenderException("AI_NOT_FOUND", false, "자료를 찾을 수 없습니다.");
        }
        HttpRequest.Builder builder = requestBuilder(
                "/runs/" + externalJobId + "/media/" + photoId + (poster ? "/poster" : ""), requestTimeout)
                .setHeader("Accept", "*/*");
        if (range != null && RANGE.matcher(range.strip()).matches()) builder.header("Range", range.strip());
        HttpResponse<InputStream> response = sendStreaming(builder.GET().build());
        int status = response.statusCode();
        if (status == 200 || status == 206 || status == 416) {
            String contentType = response.headers().firstValue("Content-Type")
                    .map(value -> value.split(";")[0].strip())
                    .filter(value -> MIME_TYPE.matcher(value).matches())
                    .orElse("application/octet-stream");
            Long length = response.headers().firstValueAsLong("Content-Length").stream().boxed().findFirst()
                    .orElse(null);
            String contentRange = response.headers().firstValue("Content-Range")
                    .filter(value -> CONTENT_RANGE.matcher(value).matches()).orElse(null);
            return new MediaStream(status, contentType, length, contentRange, response.body());
        }
        closeQuietly(response.body());
        if (status == 404) throw new AiRenderException("AI_NOT_FOUND", false, "자료를 찾을 수 없습니다.");
        throw failureFor(status);
    }

    @Override
    public InputStream downloadVideo(String externalJobId) {
        requireConfigured();
        requireJobId(externalJobId);
        HttpResponse<InputStream> response = sendStreaming(
                requestBuilder("/renders/" + externalJobId + "/video", requestTimeout)
                        .setHeader("Accept", "video/mp4").GET().build());
        if (response.statusCode() == 200) return response.body();
        closeQuietly(response.body());
        // 완료 직후 파일이 아직 없을 수 있어 404도 재시도한다.
        if (response.statusCode() == 404) throw unavailable();
        throw failureFor(response.statusCode());
    }

    private HttpRequest.Builder jsonRequest(String path, byte[] json) {
        if (json == null || json.length > MAX_STORYBOARD_BYTES) {
            throw new AiRenderException("AI_INVALID_REQUEST", false, "요청 본문이 너무 큽니다.");
        }
        return requestBuilder(path, requestTimeout).header("Content-Type", "application/json");
    }

    private Proxied proxy(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
                responseInfo -> new LimitedBodySubscriber(MAX_STORYBOARD_BYTES, responseInfo.statusCode()));
        try {
            HttpResponse<byte[]> response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if ((status >= 200 && status < 300) || PASS_THROUGH.contains(status)) {
                return new Proxied(status, response.body());
            }
            throw failureFor(status);
        } catch (AiRenderException e) {
            throw e;
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw unavailable();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AiRenderException failure) throw failure;
            throw unavailable();
        } catch (RuntimeException e) {
            throw invalidResponse();
        }
    }

    private HttpResponse<InputStream> sendStreaming(HttpRequest request) {
        try {
            // 요청 제한 시간은 응답 헤더까지만 적용된다. 본문은 호출한 쪽이 읽는다.
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException e) {
            throw unavailable();
        }
    }

    private JsonNode exchange(HttpRequest request, Duration timeout) {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
                responseInfo -> new LimitedBodySubscriber(MAX_RESPONSE_BYTES, responseInfo.statusCode()));
        try {
            // Explicitly cover the complete response body, not only receipt of response headers.
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            AiRenderException statusFailure = statusFailure(status);
            if (statusFailure != null) throw statusFailure;
            byte[] bytes = response.body();
            if (bytes.length == 0) throw invalidResponse();
            JsonNode json = mapper.readTree(bytes);
            if (json == null || !json.isObject()) throw invalidResponse();
            return json;
        } catch (AiRenderException e) {
            throw e;
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw unavailable();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AiRenderException failure) throw failure;
            throw unavailable();
        } catch (RuntimeException e) {
            throw invalidResponse();
        }
    }

    @PreDestroy
    public void close() { client.shutdownNow(); }

    private HttpRequest.Builder requestBuilder(String path, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUri.toString() + path))
                .timeout(timeout).header("Accept", "application/json");
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        return builder;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new AiRenderException("AI_NOT_CONFIGURED", false, "AI 영상 제작 서비스가 아직 연결되지 않았습니다.");
        }
    }

    private static void requireJobId(String externalJobId) {
        if (externalJobId == null || !JOB_ID.matcher(externalJobId).matches()) throw invalidResponse();
    }

    private String errorCode(byte[] body) {
        try {
            JsonNode json = mapper.readTree(body);
            JsonNode code = json == null ? null : json.path("error").get("code");
            return code != null && code.isString() ? code.asString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean validResultUrl(String value) {
        if (value.length() > 2000) return false;
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                    && ("https".equalsIgnoreCase(uri.getScheme())
                    || (allowInsecureResultUrls && "http".equalsIgnoreCase(uri.getScheme())));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static URI parseBaseUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String normalized = value.strip().replaceAll("/+$", "");
            URI uri = URI.create(normalized);
            return uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String requiredString(JsonNode json, String field) {
        String value = optionalString(json, field);
        if (value == null || value.isBlank()) throw invalidResponse();
        return value;
    }

    private static String optionalString(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw invalidResponse();
        return value.asString();
    }

    private static Integer optionalInt(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalidResponse();
        return value.asInt();
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").strip();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private static String safeFilename(Source source) {
        String name = source.resource().getFilename();
        return name == null || name.isBlank() ? source.assetId().toString()
                : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static InputStream openSource(Source source) {
        try { return source.resource().getInputStream(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void closeQuietly(InputStream stream) {
        try { if (stream != null) stream.close(); } catch (IOException ignored) { }
    }

    private static HttpRequest.BodyPublisher text(String value) {
        return HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8);
    }

    private static AiRenderException invalidResponse() {
        return new AiRenderException("AI_INVALID_RESPONSE", false, "AI 서비스 응답 형식이 유효하지 않습니다.");
    }

    private static AiRenderException unavailable() {
        return new AiRenderException("AI_UNAVAILABLE", true, "AI 서비스에 일시적으로 연결할 수 없습니다.");
    }

    private static AiRenderException failureFor(int status) {
        AiRenderException failure = statusFailure(status);
        return failure == null ? invalidResponse() : failure;
    }

    private static AiRenderException statusFailure(int status) {
        if (status == 408 || status == 429 || status >= 500) return unavailable();
        if (status >= 400) return new AiRenderException("AI_REJECTED", false, "AI 서비스가 제작 요청을 거절했습니다.");
        return status < 200 || status >= 300 ? invalidResponse() : null;
    }

    private record Manifest(UUID jobId, UUID projectId,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String title,
                            String style,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String styleLabel,
                            String mood,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String moodLabel,
                            String musicId,
                            List<ManifestSource> sources,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ShootingHint> shootingHints) {}
    private record ManifestSource(UUID assetId, UUID missionId, int position, String contentType, String filename) {}

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final int httpStatus;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(int limit, int httpStatus) { this.limit = limit; this.httpStatus = httpStatus; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                int remaining = chunk.remaining();
                if (remaining > limit - buffer.size()) {
                    subscription.cancel();
                    AiRenderException failure = statusFailure(httpStatus);
                    body.completeExceptionally(failure == null ? invalidResponse() : failure);
                    return;
                }
                byte[] bytes = new byte[remaining];
                chunk.get(bytes);
                buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }
        @Override public void onComplete() { body.complete(buffer.toByteArray()); }
    }
}
