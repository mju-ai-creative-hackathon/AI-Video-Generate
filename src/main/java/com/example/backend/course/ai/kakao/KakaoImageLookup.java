package com.example.backend.course.ai.kakao;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/** 다음 이미지 검색(v2/search/image)으로 대표 이미지 URL을 찾는다. https 만 돌려준다. */
@Component
public class KakaoImageLookup implements ImageLookup {
    static final int MIN_WIDTH = 300;
    private final KakaoApi api;
    private final Map<String, Optional<String>> cache = KakaoApi.newCache();

    @Autowired
    public KakaoImageLookup(@Value("${app.kakao.rest-api-key:${KAKAO_REST_API_KEY:}}") String apiKey,
                            @Value("${app.kakao.base-url:" + KakaoApi.DEFAULT_BASE_URL + "}") String baseUrl,
                            ObjectMapper mapper) {
        this(apiKey, baseUrl, KakaoApi.defaultBuilder(), mapper);
    }

    KakaoImageLookup(String apiKey, String baseUrl, RestClient.Builder builder, ObjectMapper mapper) {
        this.api = new KakaoApi(apiKey, baseUrl, builder, mapper);
    }

    @Override
    public Optional<String> findImageUrl(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        if (!api.configured("image lookup")) return Optional.empty();
        String q = query.strip();
        Optional<String> cached = cache.get(q);
        if (cached != null) return cached;

        Optional<JsonNode> response = api.get(b -> b.path("/v2/search/image")
                .queryParam("query", q).queryParam("size", 5).queryParam("sort", "accuracy").build());
        if (response.isEmpty()) return Optional.empty(); // 오류 응답은 캐시하지 않는다
        Optional<String> url = pick(response.get());
        KakaoApi.putBounded(cache, q, url);
        return url;
    }

    /** 폭 300 미만은 건너뛰고 첫 https image_url. 없으면 https thumbnail_url 로 대체. */
    private static Optional<String> pick(JsonNode json) {
        JsonNode docs = json.get("documents");
        if (docs == null || !docs.isArray()) return Optional.empty();
        String fallback = null;
        for (JsonNode d : docs) {
            JsonNode w = d.get("width");
            if (w != null && w.isNumber() && w.asInt() < MIN_WIDTH) continue;
            String image = KakaoApi.text(d, "image_url");
            if (isHttps(image)) return Optional.of(image);
            String thumb = KakaoApi.text(d, "thumbnail_url");
            if (fallback == null && isHttps(thumb)) fallback = thumb;
        }
        return Optional.ofNullable(fallback);
    }

    private static boolean isHttps(String value) {
        if (value.isEmpty() || value.length() > 2000) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
