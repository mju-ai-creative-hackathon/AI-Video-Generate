package com.example.backend.course;

import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseMission;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.course.domain.CourseSource;
import com.example.backend.course.domain.CourseStop;
import com.example.backend.course.domain.MissionKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * classpath:courses/presets.json 의 프리셋 코스를 DB 와 동기화한다(멱등).
 * 같은 제목의 PRESET 코스가 있으면 id 를 유지한 채 정보·미션(필요하면 장소)을 JSON 기준으로 바꾸고, 없으면 새로 넣는다.
 * JSON 에 없는 PRESET 코스는 지우지 않는다. 파일이 없거나 읽을 수 없으면 로그만 남기고 건너뛴다.
 */
@Component
public class PresetCourseSeeder implements ApplicationRunner {
    static final String RESOURCE = "courses/presets.json";
    private static final Logger log = LoggerFactory.getLogger(PresetCourseSeeder.class);

    private final CourseRepository courseRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public PresetCourseSeeder(CourseRepository courseRepository, ObjectMapper objectMapper,
                              @Value("${app.seed.courses.enabled:${SEED_COURSES_ENABLED:true}}") boolean enabled) {
        this.courseRepository = courseRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.info("프리셋 코스 파일({})이 없어 시딩을 건너뜁니다.", RESOURCE);
            return;
        }
        List<PresetCourse> presets;
        try (InputStream in = resource.getInputStream()) {
            presets = objectMapper.readValue(in, new TypeReference<List<PresetCourse>>() {
            });
        } catch (IOException | RuntimeException e) {
            log.error("프리셋 코스 파일({})을 읽지 못해 시딩을 건너뜁니다.", RESOURCE, e);
            return;
        }
        sync(presets);
    }

    /** JSON 프리셋 목록을 DB 에 반영한다. 한 트랜잭션에서 처리한다. */
    @Transactional
    public SyncResult sync(List<PresetCourse> presets) {
        Map<String, Course> existing = new HashMap<>();
        for (Course course : courseRepository.findBySource(CourseSource.PRESET,
                PageRequest.of(0, Integer.MAX_VALUE, Sort.by("id"))).getContent()) {
            existing.putIfAbsent(course.getTitle(), course);
        }
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        List<Course> toInsert = new ArrayList<>();
        for (PresetCourse preset : presets) {
            Course course = existing.get(preset.title());
            if (course == null) {
                course = takeOverFormer(existing, preset);
            }
            if (course == null) {
                Course created = Course.preset(preset.title(), preset.summary(), preset.region(),
                        preset.totalMinutes(), preset.estimatedCost(), preset.displayOrder());
                addStops(created, preset);
                addMissions(created, preset);
                toInsert.add(created);
                inserted++;
            } else if (apply(course, preset)) {
                updated++;
            } else {
                unchanged++;
            }
        }
        courseRepository.saveAll(toInsert);
        log.info("프리셋 코스 동기화: 추가 {}개, 갱신 {}개, 변경 없음 {}개", inserted, updated, unchanged);
        return new SyncResult(inserted, updated, unchanged);
    }

    /** 예전 제목으로 저장된 코스가 있으면 새 제목으로 바꿔 이어받는다. */
    private static Course takeOverFormer(Map<String, Course> existing, PresetCourse preset) {
        for (String former : preset.formerTitles()) {
            Course course = existing.remove(former);
            if (course != null) {
                course.renamePreset(preset.title());
                existing.put(preset.title(), course);
                log.info("프리셋 코스 제목 변경: '{}' → '{}'", former, preset.title());
                return course;
            }
        }
        return null;
    }

    /** 기존 코스를 JSON 기준으로 맞춘다. 바뀐 게 있으면 true. */
    private static boolean apply(Course course, PresetCourse preset) {
        boolean changed = false;
        if (!Objects.equals(course.getSummary(), preset.summary())
                || !Objects.equals(course.getRegion(), preset.region())
                || course.getTotalMinutes() != preset.totalMinutes()
                || !Objects.equals(course.getEstimatedCost(), preset.estimatedCost())
                || course.getDisplayOrder() != preset.displayOrder()) {
            course.updatePresetInfo(preset.summary(), preset.region(), preset.totalMinutes(),
                    preset.estimatedCost(), preset.displayOrder());
            changed = true;
        }
        List<StopKey> wantStops = stopsOf(preset).stream().map(PresetCourseSeeder::keyOf).toList();
        List<StopKey> haveStops = course.getStops().stream().map(PresetCourseSeeder::keyOf).toList();
        if (!wantStops.equals(haveStops)) {
            course.clearStops();
            addStops(course, preset);
            changed = true;
        }
        List<MissionKey> wantMissions = missionsOf(preset).stream().map(PresetCourseSeeder::keyOf).toList();
        List<MissionKey> haveMissions = course.getMissions().stream().map(PresetCourseSeeder::keyOf).toList();
        if (!wantMissions.equals(haveMissions)) {
            course.clearMissions();
            addMissions(course, preset);
            changed = true;
        }
        return changed;
    }

    private static void addStops(Course course, PresetCourse preset) {
        for (PresetStop stop : stopsOf(preset)) {
            course.addStop(stop.seq(), stop.name(), stop.description(), blankToNull(stop.address()),
                    stop.lat(), stop.lng(), blankToNull(stop.imageUrl()), blankToNull(stop.externalPlaceId()),
                    parseTime(stop.startTime()), stop.stayMinutes());
        }
    }

    private static void addMissions(Course course, PresetCourse preset) {
        for (PresetMission mission : missionsOf(preset)) {
            course.addMission(mission.stopSeq(), kindOf(mission), mission.title(), mission.guide(),
                    mission.durationSec());
        }
    }

    /** DB 의 @OrderBy(seq) 와 비교할 수 있게 seq 순으로 정렬한다. */
    private static List<PresetStop> stopsOf(PresetCourse preset) {
        return preset.stops() == null ? List.of()
                : preset.stops().stream().sorted(Comparator.comparingInt(PresetStop::seq)).toList();
    }

    /** DB 의 @OrderBy(stopSeq, id) 와 같은 순서가 되도록 stopSeq 기준 안정 정렬한다. */
    private static List<PresetMission> missionsOf(PresetCourse preset) {
        return preset.missions() == null ? List.of()
                : preset.missions().stream().sorted(Comparator.comparingInt(PresetMission::stopSeq)).toList();
    }

    private static MissionKind kindOf(PresetMission mission) {
        return MissionKind.valueOf(mission.kind().trim().toUpperCase(Locale.ROOT));
    }

    private static StopKey keyOf(PresetStop s) {
        return new StopKey(s.seq(), s.name(), s.description(), blankToNull(s.address()), s.lat(), s.lng(),
                blankToNull(s.imageUrl()), blankToNull(s.externalPlaceId()), parseTime(s.startTime()), s.stayMinutes());
    }

    private static StopKey keyOf(CourseStop s) {
        return new StopKey(s.getSeq(), s.getName(), s.getDescription(), s.getAddress(), s.getLat(), s.getLng(),
                s.getImageUrl(), s.getExternalPlaceId(), s.getStartTime(), s.getStayMinutes());
    }

    private static MissionKey keyOf(PresetMission m) {
        return new MissionKey(m.stopSeq(), kindOf(m), m.title(), m.guide(), m.durationSec());
    }

    private static MissionKey keyOf(CourseMission m) {
        return new MissionKey(m.getStopSeq(), m.getKind(), m.getTitle(), m.getGuide(), m.getDurationSec());
    }

    private record StopKey(int seq, String name, String description, String address, double lat, double lng,
                           String imageUrl, String externalPlaceId, LocalTime startTime, int stayMinutes) {
    }

    private record MissionKey(int stopSeq, MissionKind kind, String title, String guide, Integer durationSec) {
    }

    public record SyncResult(int inserted, int updated, int unchanged) {
    }

    private static LocalTime parseTime(String value) {
        return value == null || value.isBlank() ? null : LocalTime.parse(value.trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PresetCourse(String title, String summary, String region, int totalMinutes, Integer estimatedCost,
                        int displayOrder, List<PresetStop> stops, List<PresetMission> missions,
                        List<String> formerTitles) {
        /** 예전 제목 목록. 제목을 바꿀 때 기존 코스(id·저장 기록)를 이어받는 데 쓴다. */
        PresetCourse {
            formerTitles = formerTitles == null ? List.of() : List.copyOf(formerTitles);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PresetStop(int seq, String name, String description, String address, double lat, double lng,
                      String imageUrl, String externalPlaceId, String startTime, int stayMinutes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PresetMission(int stopSeq, String kind, String title, String guide, Integer durationSec) {
    }
}
