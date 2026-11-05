package com.example.backend.shortform.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
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

/** Sends real source bytes using the draft multipart/polling AI contract. */
@Component
public class HttpAiRenderClient implements AiRenderClient {
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern MIME_TYPE = Pattern.compile("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+");
    private static final Set<String> STATUSES = Set.of("QUEUED", "PROCESSING", "SUCCEEDED", "FAILED");
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private final boolean enabled;
    private final URI baseUri;
    private final String apiKey;
    private final Duration requestTimeout;
    private final boolean allowInsecureResultUrls;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpAiRenderClient(
            @Value("${app.shortform.ai-enabled:false}") boolean enabled,
            @Value("${app.shortform.ai-base-url:}") String baseUrl,
            @Value("${app.shortform.ai-api-key:}") String apiKey,
            @Value("${app.shortform.request-timeout-seconds:60}") int requestTimeoutSeconds,
            @Value("${app.shortform.allow-insecure-result-urls:false}") boolean allowInsecureResultUrls,
            ObjectMapper mapper
    ) {
        this.enabled = enabled;
        this.baseUri = parseBaseUri(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 90) {
            throw new IllegalArgumentException("AI request timeout must be between 1 and 90 seconds.");
        }
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.allowInsecureResultUrls = allowInsecureResultUrls;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isConfigured() {
        return enabled && baseUri != null && !apiKey.contains("\r") && !apiKey.contains("\n");
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
            var manifest = new Manifest(submission.jobId(), submission.projectId(), submission.style(),
                    submission.mood(), submission.musicId(), sourceMetadata, submission.shootingHints());
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

        HttpRequest request = requestBuilder("/renders")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", submission.jobId().toString())
                .POST(body).build();
        JsonNode response = exchange(request);
        String jobId = requiredString(response, "jobId");
        if (!JOB_ID.matcher(jobId).matches()) throw invalidResponse();
        return jobId;
    }

    @Override
    public RenderResult poll(String externalJobId) {
        requireConfigured();
        if (externalJobId == null || !JOB_ID.matcher(externalJobId).matches()) throw invalidResponse();
        JsonNode response = exchange(requestBuilder("/renders/" + externalJobId).GET().build());
        String status = requiredString(response, "status");
        if (!STATUSES.contains(status)) throw invalidResponse();
        if (!status.equals("SUCCEEDED")) return new RenderResult(status, null, null);

        String videoUrl = requiredString(response, "videoUrl");
        String thumbnailUrl = optionalString(response, "thumbnailUrl");
        if (!validResultUrl(videoUrl) || (thumbnailUrl != null && !validResultUrl(thumbnailUrl))) {
            throw invalidResponse();
        }
        return new RenderResult(status, videoUrl, thumbnailUrl);
    }

    private JsonNode exchange(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
                responseInfo -> new LimitedBodySubscriber(MAX_RESPONSE_BYTES, responseInfo.statusCode()));
        try {
            // Explicitly cover the complete response body, not only receipt of response headers.
            HttpResponse<byte[]> response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
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

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUri.toString() + path))
                .timeout(requestTimeout).header("Accept", "application/json");
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        return builder;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new AiRenderException("AI_NOT_CONFIGURED", false, "AI 영상 제작 서비스가 아직 연결되지 않았습니다.");
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

    private static String safeFilename(Source source) {
        String name = source.resource().getFilename();
        return name == null || name.isBlank() ? source.assetId().toString()
                : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static InputStream openSource(Source source) {
        try { return source.resource().getInputStream(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
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

    private static AiRenderException statusFailure(int status) {
        if (status == 408 || status == 429 || status >= 500) return unavailable();
        if (status >= 400) return new AiRenderException("AI_REJECTED", false, "AI 서비스가 제작 요청을 거절했습니다.");
        return status < 200 || status >= 300 ? invalidResponse() : null;
    }

    private record Manifest(UUID jobId, UUID projectId, String style, String mood, String musicId,
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
