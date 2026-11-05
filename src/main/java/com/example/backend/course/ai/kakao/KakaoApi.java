package com.example.backend.course.ai.kakao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 카카오 REST API(dapi.kakao.com) 공통 호출부: KakaoAK 인증, 5초 타임아웃, 실패 시 빈 값, 작은 메모리 캐시. */
final class KakaoApi {
    static final String DEFAULT_BASE_URL = "https://dapi.kakao.com";
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_CACHE_ENTRIES = 1000;
    private static final Logger log = LoggerFactory.getLogger(KakaoApi.class);

    private final String apiKey;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final AtomicBoolean warnedMissingKey = new AtomicBoolean();

    KakaoApi(String apiKey, String baseUrl, RestClient.Builder builder, ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.mapper = mapper;
        this.client = builder.baseUrl(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl).build();
    }

    static RestClient.Builder defaultBuilder() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }

    /** 키가 없으면 한 번만 경고하고 false. */
    boolean configured(String feature) {
        boolean ok = !apiKey.isBlank() && !apiKey.contains("\r") && !apiKey.contains("\n");
        if (!ok && warnedMissingKey.compareAndSet(false, true)) {
            log.warn("Kakao REST API key is not configured (app.kakao.rest-api-key / KAKAO_REST_API_KEY); {} disabled.",
                    feature);
        }
        return ok;
    }

    /** GET 후 JSON 객체를 돌려준다. 네트워크·4xx·5xx·파싱 오류는 로그만 남기고 빈 값. */
    Optional<JsonNode> get(Function<UriBuilder, URI> uri) {
        try {
            String body = client.get().uri(uri)
                    .header("Authorization", "KakaoAK " + apiKey)
                    .header("Accept", "application/json")
                    .retrieve().body(String.class);
            if (body == null || body.isBlank()) return Optional.empty();
            JsonNode json = mapper.readTree(body);
            return json != null && json.isObject() ? Optional.of(json) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Kakao API call failed: {}", e.toString());
            return Optional.empty();
        }
    }

    static <V> ConcurrentHashMap<String, V> newCache() { return new ConcurrentHashMap<>(); }

    /** 단순 상한: 가득 차면 비우고 다시 채운다. */
    static <V> void putBounded(Map<String, V> cache, String key, V value) {
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(key, value);
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asString("").strip();
    }
}
