package com.example.backend.course.recommend;

import com.example.backend.auth.AuthService;
import com.example.backend.common.ApiException;
import com.example.backend.course.CourseService;
import com.example.backend.course.ai.gemini.AiCourseDraft;
import com.example.backend.course.ai.gemini.AiMissionDraft;
import com.example.backend.course.ai.gemini.AiStopDraft;
import com.example.backend.course.ai.gemini.CourseGenerationException;
import com.example.backend.course.ai.gemini.CourseGenerator;
import com.example.backend.course.ai.kakao.ImageLookup;
import com.example.backend.course.ai.kakao.KakaoPlace;
import com.example.backend.course.ai.kakao.PlaceLookup;
import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.course.domain.CourseSource;
import com.example.backend.course.domain.MissionKind;
import com.example.backend.course.dto.CourseDetail;
import com.example.backend.course.geo.GeoPoint;
import com.example.backend.course.geo.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 프롬프트 → Gemini 코스 초안 → 카카오로 좌표·사진 검증 → AI 코스 저장 → 상세 응답.
 * Gemini 호출(수십 초) 동안 DB 트랜잭션을 잡지 않도록 저장만 TransactionTemplate 으로 감싼다.
 */
@Service
public class CourseRecommendationService {
    private static final Logger log = LoggerFactory.getLogger(CourseRecommendationService.class);
    private static final int MAX_COURSES = 3;
    private static final int COURSES_PER_REQUEST = 3;

    private final AuthService authService;
    private final CourseGenerator generator;
    private final PlaceLookup placeLookup;
    private final ImageLookup imageLookup;
    private final CourseRepository courseRepository;
    private final CourseService courseService;
    private final TransactionTemplate tx;
    private final int dailyRequestLimit;
    private final ObjectMapper mapper;

    public CourseRecommendationService(AuthService authService, CourseGenerator generator, PlaceLookup placeLookup,
                                       ImageLookup imageLookup, CourseRepository courseRepository,
                                       CourseService courseService, TransactionTemplate tx, ObjectMapper mapper,
                                       @Value("${app.course.ai-daily-limit:${COURSE_AI_DAILY_LIMIT:20}}") int dailyRequestLimit) {
        this.authService = authService;
        this.generator = generator;
        this.placeLookup = placeLookup;
        this.imageLookup = imageLookup;
        this.courseRepository = courseRepository;
        this.courseService = courseService;
        this.tx = tx;
        this.dailyRequestLimit = dailyRequestLimit;
        this.mapper = mapper;
    }

    public record RecommendResponse(List<CourseDetail> courses) {
    }

    record ResolvedStop(AiStopDraft draft, KakaoPlace place, String imageUrl) {
    }

    public RecommendResponse recommend(String authorization, String prompt, Double lat, Double lng) {
        UUID userId = authService.getCurrentUser(authorization).id();
        GeoPoint user = userLocation(lat, lng);
        String cleanPrompt = prompt.strip();

        checkRateLimit(userId);

        List<AiCourseDraft> drafts;
        try {
            drafts = generator.generate(cleanPrompt, user == null ? null : user.lat(), user == null ? null : user.lng());
        } catch (CourseGenerationException e) {
            log.warn("AI 코스 생성 실패: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "AI 추천을 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
        }

        List<List<ResolvedStop>> resolved = resolveAll(drafts.stream().limit(MAX_COURSES).toList(), user);

        List<CourseDetail> result = tx.execute(status -> {
            List<CourseDetail> details = new ArrayList<>();
            for (int i = 0; i < resolved.size(); i++) {
                List<ResolvedStop> stops = resolved.get(i);
                if (stops.size() < 2) {
                    continue;
                }
                Course course = courseRepository.save(build(userId, cleanPrompt, drafts.get(i), stops));
                details.add(courseService.toDetail(course, user, userId));
            }
            return details;
        });

        if (result == null || result.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_NO_RESULT",
                    "조건에 맞는 서울 코스를 찾지 못했어요. 요청을 조금 바꿔 다시 시도해 주세요.");
        }
        return new RecommendResponse(result);
    }

    /** 기존 코스(기본 코스 또는 본인 AI 코스)를 프롬프트로 고친 새 AI 코스를 만든다. 원본은 그대로 둔다. */
    public CourseDetail refine(String authorization, Long courseId, String prompt, Double lat, Double lng) {
        UUID userId = authService.getCurrentUser(authorization).id();
        GeoPoint user = userLocation(lat, lng);
        String cleanPrompt = prompt.strip();
        checkRateLimit(userId);

        record Base(String json, String originalPrompt) {
        }
        Base base = tx.execute(status -> {
            Course course = courseRepository.findById(courseId)
                    .filter(c -> c.isVisibleTo(userId))
                    .orElseThrow(() -> ApiException.notFound("코스를 찾을 수 없습니다."));
            return new Base(baseJson(course), course.getPrompt());
        });

        AiCourseDraft draft;
        try {
            draft = generator.refine(base.json(), cleanPrompt,
                    user == null ? null : user.lat(), user == null ? null : user.lng());
        } catch (CourseGenerationException e) {
            log.warn("AI 코스 수정 실패: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "코스를 고치지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        List<ResolvedStop> stops = resolveAll(List.of(draft), user).getFirst();
        if (stops.size() < 2) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_NO_RESULT",
                    "요청대로 고친 코스를 찾지 못했어요. 표현을 바꿔 다시 시도해 주세요.");
        }
        String history = base.originalPrompt() == null ? cleanPrompt : base.originalPrompt() + " / 수정: " + cleanPrompt;
        return tx.execute(status -> {
            Course course = courseRepository.save(build(userId, history, draft, stops));
            return courseService.toDetail(course, user, userId);
        });
    }

    private void checkRateLimit(UUID userId) {
        long recent = courseRepository.countBySourceAndOwnerIdAndCreatedAtAfter(
                CourseSource.AI, userId, Instant.now().minus(Duration.ofDays(1)));
        if (recent >= (long) dailyRequestLimit * COURSES_PER_REQUEST) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "오늘 AI 추천 횟수를 모두 사용했어요. 내일 다시 시도해 주세요.");
        }
    }

    private String baseJson(Course course) {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("title", course.getTitle());
        root.put("summary", course.getSummary());
        root.put("region", course.getRegion());
        root.put("totalMinutes", course.getTotalMinutes());
        root.put("estimatedCost", course.getEstimatedCost());
        root.put("stops", course.getStops().stream().map(s -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("seq", s.getSeq());
            m.put("day", s.getDay());
            m.put("name", s.getName());
            m.put("searchQuery", s.getName());
            m.put("description", s.getDescription());
            m.put("stayMinutes", s.getStayMinutes());
            m.put("startTime", s.getStartTime() == null ? null : s.getStartTime().toString());
            return m;
        }).toList());
        root.put("missions", course.getMissions().stream().map(mi -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("stopSeq", mi.getStopSeq());
            m.put("kind", mi.getKind().name());
            m.put("title", mi.getTitle());
            m.put("guide", mi.getGuide());
            m.put("durationSec", mi.getDurationSec());
            return m;
        }).toList());
        return mapper.writeValueAsString(root);
    }

    /** 코스별·장소별 카카오 조회를 가상 스레드로 병렬 처리한다. 서울 밖이거나 못 찾은 장소는 버린다. */
    private List<List<ResolvedStop>> resolveAll(List<AiCourseDraft> drafts, GeoPoint user) {
        Double nearLat = user == null ? null : user.lat();
        Double nearLng = user == null ? null : user.lng();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<List<Future<Optional<ResolvedStop>>>> futures = new ArrayList<>();
            for (AiCourseDraft draft : drafts) {
                List<Future<Optional<ResolvedStop>>> perCourse = new ArrayList<>();
                for (AiStopDraft stop : draft.stops()) {
                    perCourse.add(pool.submit(() -> resolve(stop, nearLat, nearLng)));
                }
                futures.add(perCourse);
            }
            List<List<ResolvedStop>> out = new ArrayList<>();
            for (List<Future<Optional<ResolvedStop>>> perCourse : futures) {
                List<ResolvedStop> stops = new ArrayList<>();
                Set<String> seenPlaces = new HashSet<>();
                for (Future<Optional<ResolvedStop>> f : perCourse) {
                    try {
                        f.get().filter(r -> seenPlaces.add(r.place().id())).ifPresent(stops::add);
                    } catch (Exception e) {
                        log.warn("장소 확인 실패", e);
                    }
                }
                out.add(stops);
            }
            return out;
        }
    }

    private Optional<ResolvedStop> resolve(AiStopDraft stop, Double nearLat, Double nearLng) {
        String query = stop.searchQuery() == null || stop.searchQuery().isBlank() ? stop.name() : stop.searchQuery();
        Optional<KakaoPlace> place = placeLookup.findBest(query, nearLat, nearLng);
        if (place.isEmpty() && !query.equals(stop.name())) {
            place = placeLookup.findBest(stop.name(), nearLat, nearLng);
        }
        if (place.isEmpty()) {
            // "광장시장 박가네빈대떡(본점)" 같은 긴 이름은 괄호·지점명을 떼고 한 번 더 찾는다.
            String simple = stop.name().replaceAll("\\(.*?\\)", " ").replaceAll("\\s+(본점|[가-힣A-Za-z0-9]+점)$", "").strip();
            if (!simple.isEmpty() && !simple.equals(stop.name()) && !simple.equals(query)) {
                place = placeLookup.findBest(simple, nearLat, nearLng);
            }
        }
        if (place.isEmpty()) {
            log.info("카카오에서 찾지 못해 제외한 장소: name={}, query={}", stop.name(), query);
        }
        return place
                .filter(p -> GeoUtils.isInSeoul(new GeoPoint(p.lat(), p.lng())))
                .map(p -> new ResolvedStop(stop, p, imageLookup.findImageUrl(p.name()).orElse(null)));
    }

    private static Course build(UUID userId, String prompt, AiCourseDraft draft, List<ResolvedStop> stops) {
        Course course = Course.ai(userId, truncate(prompt, 1000), truncate(draft.title(), 100),
                truncate(draft.summary(), 500), truncate(draft.region(), 30),
                Math.max(draft.totalMinutes(), 1), draft.estimatedCost());
        Map<Integer, Integer> seqMap = new HashMap<>();
        Map<Integer, String> nameBySeq = new HashMap<>();
        int seq = 1;
        for (ResolvedStop r : stops) {
            KakaoPlace p = r.place();
            String address = p.roadAddress() == null || p.roadAddress().isBlank() ? p.address() : p.roadAddress();
            course.addStop(seq, r.draft().day(), truncate(p.name(), 100), truncate(r.draft().description(), 1000), truncate(address, 255),
                    p.lat(), p.lng(), truncate(r.imageUrl(), 1000), truncate(p.id(), 50),
                    parseTime(r.draft().startTime()), Math.max(r.draft().stayMinutes(), 1));
            seqMap.put(r.draft().seq(), seq);
            nameBySeq.put(seq, p.name());
            seq++;
        }
        for (AiMissionDraft m : draft.missions()) {
            Integer mapped = seqMap.get(m.stopSeq());
            if (mapped == null) {
                continue;
            }
            MissionKind kind;
            try {
                kind = MissionKind.valueOf(m.kind().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                continue;
            }
            course.addMission(mapped, kind, truncate(m.title(), 100), truncate(m.guide(), 1000),
                    kind == MissionKind.VIDEO ? m.durationSec() : null);
        }
        return course;
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static GeoPoint userLocation(Double lat, Double lng) {
        if (lat == null && lng == null) {
            return null;
        }
        if (lat == null || lng == null || lat.isNaN() || lng.isNaN() || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw ApiException.badRequest("lat과 lng는 함께, 올바른 값으로 전달해야 합니다.");
        }
        return new GeoPoint(lat, lng);
    }
}
