package com.example.backend.course;

import com.example.backend.auth.AuthService;
import com.example.backend.common.ApiException;
import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.course.domain.CourseSource;
import com.example.backend.course.domain.CourseStop;
import com.example.backend.course.domain.SavedCourse;
import com.example.backend.course.domain.SavedCourseRepository;
import com.example.backend.course.dto.CourseDetail;
import com.example.backend.course.dto.CourseLegResponse;
import com.example.backend.course.dto.CourseMissionResponse;
import com.example.backend.course.dto.CourseSaveResponse;
import com.example.backend.course.dto.CourseStopResponse;
import com.example.backend.course.dto.CourseOriginResponse;
import com.example.backend.course.dto.CourseSummary;
import com.example.backend.course.geo.DirectionLinks;
import com.example.backend.course.geo.GeoPoint;
import com.example.backend.course.geo.GeoUtils;
import com.example.backend.course.geo.LegEstimate;
import com.example.backend.course.geo.LegPlanner;
import com.example.backend.place.dto.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class CourseService {
    static final int MAX_PAGE_SIZE = 50;
    static final String CURRENT_LOCATION_NAME = "현재 위치";
    private static final Sort COURSE_SORT = Sort.by(Sort.Order.asc("displayOrder"), Sort.Order.asc("id"));
    private static final Sort SAVED_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final CourseRepository courseRepository;
    private final SavedCourseRepository savedCourseRepository;
    private final AuthService authService;
    private final DirectionLinks directionLinks;

    public CourseService(CourseRepository courseRepository, SavedCourseRepository savedCourseRepository,
                         AuthService authService, DirectionLinks directionLinks) {
        this.courseRepository = courseRepository;
        this.savedCourseRepository = savedCourseRepository;
        this.authService = authService;
        this.directionLinks = directionLinks;
    }

    @Transactional(readOnly = true)
    public List<String> getRegions() {
        return courseRepository.findDistinctRegions(CourseSource.PRESET);
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseSummary> getCourses(String region, Double lat, Double lng, int page, int size,
                                                  String authorizationHeader) {
        GeoPoint user = userLocation(lat, lng);
        UUID viewerId = optionalUserId(authorizationHeader);
        Pageable pageable = pageRequest(page, size, COURSE_SORT);

        Page<Course> courses = region == null || region.isBlank()
                ? courseRepository.findBySource(CourseSource.PRESET, pageable)
                : courseRepository.findBySourceAndRegion(CourseSource.PRESET, region.trim(), pageable);

        Set<Long> savedIds = savedIds(viewerId, courses.getContent());
        List<CourseSummary> content = courses.getContent().stream()
                .map(course -> summary(course, user, savedIds.contains(course.getId())))
                .toList();
        return PageResponse.of(courses, content);
    }

    @Transactional(readOnly = true)
    public CourseDetail getCourse(Long courseId, Double lat, Double lng, String authorizationHeader) {
        GeoPoint user = userLocation(lat, lng);
        UUID viewerId = optionalUserId(authorizationHeader);
        return toDetail(visibleCourse(courseId, viewerId), user, viewerId);
    }

    public CourseSaveResponse save(Long courseId, String authorizationHeader) {
        UUID userId = authService.getCurrentUser(authorizationHeader).id();
        Course course = visibleCourse(courseId, userId);
        if (!savedCourseRepository.existsByUserIdAndCourseId(userId, courseId)) {
            try {
                savedCourseRepository.saveAndFlush(new SavedCourse(userId, course));
            } catch (DataIntegrityViolationException e) {
                // 동시 요청으로 이미 저장된 경우: 멱등하게 성공 처리
            }
        }
        return new CourseSaveResponse(courseId, true);
    }

    @Transactional
    public CourseSaveResponse unsave(Long courseId, String authorizationHeader) {
        UUID userId = authService.getCurrentUser(authorizationHeader).id();
        visibleCourse(courseId, userId);
        savedCourseRepository.deleteByUserIdAndCourseId(userId, courseId);
        return new CourseSaveResponse(courseId, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseSummary> getSaved(Double lat, Double lng, int page, int size,
                                                String authorizationHeader) {
        GeoPoint user = userLocation(lat, lng);
        UUID userId = authService.getCurrentUser(authorizationHeader).id();
        Page<SavedCourse> saved = savedCourseRepository.findWithCourseByUserId(userId,
                pageRequest(page, size, SAVED_SORT));
        List<CourseSummary> content = saved.getContent().stream()
                .map(SavedCourse::getCourse)
                .map(course -> summary(course, user, true))
                .toList();
        return PageResponse.of(saved, content);
    }

    /**
     * 코스 상세 응답을 만든다. stops/missions 를 읽으므로 트랜잭션 안(또는 컬렉션이 초기화된 엔티티)에서 호출한다.
     *
     * @param user     사용자 위치(없으면 null → startLeg, distanceMeters 가 null)
     * @param viewerId 조회자(없으면 null → saved=false)
     */
    @Transactional(readOnly = true)
    public CourseDetail toDetail(Course course, GeoPoint user, UUID viewerId) {
        List<CourseStop> stops = course.getStops();
        List<CourseLegResponse> legs = new ArrayList<>();
        for (int i = 1; i < stops.size(); i++) {
            CourseStop from = stops.get(i - 1);
            CourseStop to = stops.get(i);
            legs.add(leg(from.getSeq(), from.getName(), point(from), to.getSeq(), to.getName(), point(to)));
        }
        CourseLegResponse startLeg = null;
        CourseOriginResponse origin = course.hasOrigin()
                ? new CourseOriginResponse(course.getOriginName(), course.getOriginAddress(),
                        course.getOriginLat(), course.getOriginLng())
                : null;
        if (origin != null && !stops.isEmpty()) {
            CourseStop first = stops.getFirst();
            startLeg = leg(0, origin.name(), new GeoPoint(origin.lat(), origin.lng()),
                    first.getSeq(), first.getName(), point(first));
        } else if (user != null && !stops.isEmpty()) {
            CourseStop first = stops.getFirst();
            startLeg = leg(0, CURRENT_LOCATION_NAME, user, first.getSeq(), first.getName(), point(first));
        }
        return new CourseDetail(
                course.getId(),
                course.getTitle(),
                course.getSummary(),
                course.getRegion(),
                course.getSource(),
                course.getTotalMinutes(),
                course.getEstimatedCost(),
                stops.size(),
                thumbnailUrl(stops),
                distanceToFirstStop(stops, user),
                course.getPrompt(),
                origin,
                stops.stream().map(CourseStopResponse::of).toList(),
                legs,
                startLeg,
                course.getMissions().stream().map(CourseMissionResponse::of).toList(),
                isSaved(viewerId, course)
        );
    }

    /** 코스 요약 응답. viewerId 가 있으면 저장 여부를 조회한다. */
    @Transactional(readOnly = true)
    public CourseSummary toSummary(Course course, GeoPoint user, UUID viewerId) {
        return summary(course, user, isSaved(viewerId, course));
    }

    private CourseSummary summary(Course course, GeoPoint user, boolean saved) {
        List<CourseStop> stops = course.getStops();
        return new CourseSummary(
                course.getId(),
                course.getTitle(),
                course.getSummary(),
                course.getRegion(),
                course.getSource(),
                course.getTotalMinutes(),
                course.getEstimatedCost(),
                stops.size(),
                stops.stream().map(CourseStop::getName).toList(),
                thumbnailUrl(stops),
                distanceToFirstStop(stops, user),
                saved
        );
    }

    private CourseLegResponse leg(int fromSeq, String fromName, GeoPoint from,
                                  int toSeq, String toName, GeoPoint to) {
        LegEstimate estimate = LegPlanner.estimate(from, to);
        return new CourseLegResponse(
                fromSeq,
                toSeq,
                fromName,
                toName,
                estimate.distanceMeters(),
                estimate.estMinutes(),
                estimate.mode(),
                directionLinks.naverUrl(from, fromName, to, toName, estimate.mode()),
                directionLinks.googleUrl(from, to, estimate.mode())
        );
    }

    private boolean isSaved(UUID viewerId, Course course) {
        return viewerId != null && course.getId() != null
                && savedCourseRepository.existsByUserIdAndCourseId(viewerId, course.getId());
    }

    private Course visibleCourse(Long courseId, UUID viewerId) {
        return courseRepository.findById(courseId)
                .filter(course -> course.isVisibleTo(viewerId))
                .orElseThrow(() -> ApiException.notFound("코스를 찾을 수 없습니다."));
    }

    private Set<Long> savedIds(UUID userId, List<Course> courses) {
        if (userId == null || courses.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = courses.stream().map(Course::getId).toList();
        return new HashSet<>(savedCourseRepository.findSavedCourseIds(userId, ids));
    }

    private UUID optionalUserId(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        return authService.getCurrentUser(authorizationHeader).id();
    }

    private static String thumbnailUrl(List<CourseStop> stops) {
        return stops.isEmpty() ? null : stops.getFirst().getImageUrl();
    }

    private static Integer distanceToFirstStop(List<CourseStop> stops, GeoPoint user) {
        if (user == null || stops.isEmpty()) {
            return null;
        }
        return (int) Math.round(GeoUtils.distanceMeters(user, point(stops.getFirst())));
    }

    private static GeoPoint point(CourseStop stop) {
        return new GeoPoint(stop.getLat(), stop.getLng());
    }

    static GeoPoint userLocation(Double lat, Double lng) {
        if (lat == null && lng == null) {
            return null;
        }
        if (lat == null || lng == null) {
            throw ApiException.badRequest("lat과 lng는 함께 전달해야 합니다.");
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180 || lat.isNaN() || lng.isNaN()) {
            throw ApiException.badRequest("위치 값이 올바르지 않습니다.");
        }
        return new GeoPoint(lat, lng);
    }

    private static Pageable pageRequest(int page, int size, Sort sort) {
        if (page < 0) {
            throw ApiException.badRequest("page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw ApiException.badRequest("size는 1 이상이어야 합니다.");
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), sort);
    }
}
