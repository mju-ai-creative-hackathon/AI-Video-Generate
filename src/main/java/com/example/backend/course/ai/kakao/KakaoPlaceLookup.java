package com.example.backend.course.ai.kakao;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 카카오 로컬 키워드 검색(v2/local/search/keyword.json)으로 서울 내 장소를 확인한다. */
@Component
public class KakaoPlaceLookup implements PlaceLookup {
    private final KakaoApi api;
    private final Map<String, Optional<KakaoPlace>> cache = KakaoApi.newCache();

    @Autowired
    public KakaoPlaceLookup(@Value("${app.kakao.rest-api-key:${KAKAO_REST_API_KEY:}}") String apiKey,
                            @Value("${app.kakao.base-url:" + KakaoApi.DEFAULT_BASE_URL + "}") String baseUrl,
                            ObjectMapper mapper) {
        this(apiKey, baseUrl, KakaoApi.defaultBuilder(), mapper);
    }

    KakaoPlaceLookup(String apiKey, String baseUrl, RestClient.Builder builder, ObjectMapper mapper) {
        this.api = new KakaoApi(apiKey, baseUrl, builder, mapper);
    }

    @Override
    public Optional<KakaoPlace> findBest(String query, Double nearLat, Double nearLng) {
        if (query == null || query.isBlank()) return Optional.empty();
        if (!api.configured("place lookup")) return Optional.empty();
        String q = query.strip();
        boolean near = nearLat != null && nearLng != null && Double.isFinite(nearLat) && Double.isFinite(nearLng);
        String key = q + "|" + (near ? String.format(Locale.ROOT, "%.3f,%.3f", nearLat, nearLng) : "-");
        Optional<KakaoPlace> cached = cache.get(key);
        if (cached != null) return cached;

        Optional<JsonNode> response = api.get(b -> {
            b.path("/v2/local/search/keyword.json")
                    .queryParam("query", q).queryParam("size", 5).queryParam("sort", "accuracy");
            if (near) b.queryParam("x", nearLng).queryParam("y", nearLat);
            return b.build();
        });
        if (response.isEmpty()) return Optional.empty(); // 오류 응답은 캐시하지 않는다
        Optional<KakaoPlace> best = pick(q, parseSeoul(response.get()));
        KakaoApi.putBounded(cache, key, best);
        return best;
    }

    private static List<KakaoPlace> parseSeoul(JsonNode json) {
        List<KakaoPlace> places = new ArrayList<>();
        JsonNode docs = json.get("documents");
        if (docs == null || !docs.isArray()) return places;
        for (JsonNode d : docs) {
            String address = KakaoApi.text(d, "address_name");
            String road = KakaoApi.text(d, "road_address_name");
            boolean seoul = address.isEmpty() ? road.startsWith("서울") : address.startsWith("서울");
            if (!seoul) continue;
            Double lng = parseCoord(KakaoApi.text(d, "x"));
            Double lat = parseCoord(KakaoApi.text(d, "y"));
            String name = KakaoApi.text(d, "place_name");
            if (lng == null || lat == null || name.isEmpty()) continue;
            places.add(new KakaoPlace(KakaoApi.text(d, "id"), name, address, road, lat, lng,
                    KakaoApi.text(d, "category_name"), KakaoApi.text(d, "place_url")));
        }
        return places;
    }

    /** 이름 완전 일치 → 부분 포함 → 첫 번째 서울 결과 순. */
    private static Optional<KakaoPlace> pick(String query, List<KakaoPlace> places) {
        if (places.isEmpty()) return Optional.empty();
        String q = normalize(query);
        for (KakaoPlace p : places) if (normalize(p.name()).equals(q)) return Optional.of(p);
        for (KakaoPlace p : places) {
            String n = normalize(p.name());
            if (n.contains(q) || q.contains(n)) return Optional.of(p);
        }
        return Optional.of(places.getFirst());
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static Double parseCoord(String s) {
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
