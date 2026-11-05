package com.example.backend.course;

import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.course.domain.CourseSource;
import com.example.backend.course.domain.MissionKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * classpath:courses/presets.json 의 프리셋 코스를 PRESET 코스가 하나도 없을 때만 넣는다.
 * 파일이 없거나 읽을 수 없으면 로그만 남기고 건너뛴다.
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
        if (!enabled || courseRepository.existsBySource(CourseSource.PRESET)) {
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
        List<Course> courses = presets.stream().map(PresetCourseSeeder::toCourse).toList();
        courseRepository.saveAll(courses);
        log.info("프리셋 코스 {}개를 넣었습니다.", courses.size());
    }

    private static Course toCourse(PresetCourse preset) {
        Course course = Course.preset(preset.title(), preset.summary(), preset.region(),
                preset.totalMinutes(), preset.estimatedCost(), preset.displayOrder());
        if (preset.stops() != null) {
            for (PresetStop stop : preset.stops()) {
                course.addStop(stop.seq(), stop.name(), stop.description(), blankToNull(stop.address()),
                        stop.lat(), stop.lng(), blankToNull(stop.imageUrl()), blankToNull(stop.externalPlaceId()),
                        parseTime(stop.startTime()), stop.stayMinutes());
            }
        }
        if (preset.missions() != null) {
            for (PresetMission mission : preset.missions()) {
                course.addMission(mission.stopSeq(), MissionKind.valueOf(mission.kind().trim().toUpperCase(Locale.ROOT)),
                        mission.title(), mission.guide(), mission.durationSec());
            }
        }
        return course;
    }

    private static LocalTime parseTime(String value) {
        return value == null || value.isBlank() ? null : LocalTime.parse(value.trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PresetCourse(String title, String summary, String region, int totalMinutes, Integer estimatedCost,
                        int displayOrder, List<PresetStop> stops, List<PresetMission> missions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PresetStop(int seq, String name, String description, String address, double lat, double lng,
                      String imageUrl, String externalPlaceId, String startTime, int stayMinutes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PresetMission(int stopSeq, String kind, String title, String guide, Integer durationSec) {
    }
}
