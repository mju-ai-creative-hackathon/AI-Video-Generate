package com.example.backend.course;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.course.PresetCourseSeeder.PresetCourse;
import com.example.backend.course.PresetCourseSeeder.PresetMission;
import com.example.backend.course.PresetCourseSeeder.PresetStop;
import com.example.backend.course.PresetCourseSeeder.SyncResult;
import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseMission;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.course.domain.CourseSource;
import com.example.backend.course.domain.CourseStop;
import com.example.backend.course.domain.MissionKind;
import com.example.backend.course.domain.SavedCourseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 시더 동기화 검증. 앱 시작 시 자동 시딩은 끄고 테스트가 직접 호출한다. */
@SpringBootTest
@TestPropertySource(properties = "app.seed.courses.enabled=false")
class PresetCourseSeederIntegrationTests extends PostgresIntegrationTest {

    @Autowired private CourseRepository courseRepository;
    @Autowired private SavedCourseRepository savedCourseRepository;
    @Autowired private PresetCourseSeeder seeder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate tx;

    @BeforeEach
    @AfterEach
    void clean() {
        savedCourseRepository.deleteAll();
        courseRepository.deleteAll();
    }

    @Test
    void insertsAllPresetsOnEmptyDb() throws Exception {
        List<PresetCourse> fixture = fixture();

        SyncResult result = seeder.sync(fixture);

        assertThat(result).isEqualTo(new SyncResult(fixture.size(), 0, 0));
        assertThat(presetCount()).isEqualTo(fixture.size());
        tx.executeWithoutResult(s -> {
            Course jongno = byTitle(fixture.get(0).title());
            assertThat(jongno.getStops()).hasSize(fixture.get(0).stops().size());
            assertThat(jongno.getMissions()).hasSize(fixture.get(0).missions().size());
        });
    }

    @Test
    void rerunWithSameFixtureChangesNothing() throws Exception {
        List<PresetCourse> fixture = fixture();
        seeder.sync(fixture);
        List<Long> missionIdsBefore = missionIds(fixture.get(0).title());

        SyncResult result = seeder.sync(fixture);

        assertThat(result).isEqualTo(new SyncResult(0, 0, fixture.size()));
        assertThat(presetCount()).isEqualTo(fixture.size());
        assertThat(missionIds(fixture.get(0).title())).isEqualTo(missionIdsBefore);
    }

    @Test
    void rerunWithChangedFixtureReplacesMissionsAndKeepsCourseId() throws Exception {
        List<PresetCourse> fixture = fixture();
        seeder.sync(fixture);
        PresetCourse original = fixture.get(0);
        Long courseId = idOf(original.title());
        List<Long> stopIdsBefore = stopIds(original.title());

        PresetCourse changed = new PresetCourse(original.title(), "바뀐 요약", original.region(),
                original.totalMinutes() + 10, 99000, 7, original.stops(), List.of(
                new PresetMission(1, "VIDEO", "새 영상 미션", "새 가이드", 6),
                new PresetMission(1, "photo", "새 사진 미션", "사진 가이드", null),
                new PresetMission(2, "VIDEO", "두번째 장소 영상", "가이드", 4)));
        List<PresetCourse> next = new ArrayList<>(fixture);
        next.set(0, changed);

        SyncResult result = seeder.sync(next);

        assertThat(result).isEqualTo(new SyncResult(0, 1, fixture.size() - 1));
        assertThat(presetCount()).isEqualTo(fixture.size());
        assertThat(idOf(original.title())).isEqualTo(courseId);
        assertThat(stopIds(original.title())).isEqualTo(stopIdsBefore);
        tx.executeWithoutResult(s -> {
            Course course = courseRepository.findById(courseId).orElseThrow();
            assertThat(course.getSummary()).isEqualTo("바뀐 요약");
            assertThat(course.getTotalMinutes()).isEqualTo(original.totalMinutes() + 10);
            assertThat(course.getEstimatedCost()).isEqualTo(99000);
            assertThat(course.getDisplayOrder()).isEqualTo(7);
            assertThat(course.getMissions())
                    .extracting(CourseMission::getStopSeq, CourseMission::getKind, CourseMission::getTitle,
                            CourseMission::getDurationSec)
                    .containsExactly(
                            tuple(1, MissionKind.VIDEO, "새 영상 미션", 6),
                            tuple(1, MissionKind.PHOTO, "새 사진 미션", null),
                            tuple(2, MissionKind.VIDEO, "두번째 장소 영상", 4));
        });
    }

    @Test
    void changedStopsAreReplacedAndUnknownPresetsAreKept() throws Exception {
        List<PresetCourse> fixture = fixture();
        seeder.sync(fixture);
        PresetCourse original = fixture.get(0);
        Long courseId = idOf(original.title());
        Long otherPresetId = tx.execute(s -> courseRepository.save(
                Course.preset("JSON에 없는 코스", null, "기타", 60, null, 99)).getId());

        PresetStop first = original.stops().get(0);
        PresetStop renamed = new PresetStop(first.seq(), "바뀐 장소", first.description(), first.address(),
                first.lat(), first.lng(), first.imageUrl(), first.externalPlaceId(), first.startTime(),
                first.stayMinutes());
        List<PresetStop> stops = new ArrayList<>(original.stops());
        stops.set(0, renamed);
        List<PresetCourse> next = new ArrayList<>(fixture);
        next.set(0, new PresetCourse(original.title(), original.summary(), original.region(),
                original.totalMinutes(), original.estimatedCost(), original.displayOrder(), stops,
                original.missions()));

        seeder.sync(next);

        assertThat(idOf(original.title())).isEqualTo(courseId);
        assertThat(courseRepository.findById(otherPresetId)).isPresent();
        assertThat(presetCount()).isEqualTo(fixture.size() + 1);
        tx.executeWithoutResult(s -> assertThat(byTitle(original.title()).getStops())
                .extracting(CourseStop::getName)
                .startsWith("바뀐 장소")
                .hasSize(original.stops().size()));
    }

    private List<PresetCourse> fixture() throws Exception {
        try (InputStream in = new ClassPathResource(PresetCourseSeeder.RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<PresetCourse>>() {
            });
        }
    }

    private long presetCount() {
        return courseRepository.findBySource(CourseSource.PRESET, PageRequest.of(0, 100)).getTotalElements();
    }

    private Course byTitle(String title) {
        List<Course> matches = courseRepository.findBySource(CourseSource.PRESET, PageRequest.of(0, 100))
                .getContent().stream().filter(c -> c.getTitle().equals(title)).toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private Long idOf(String title) {
        return tx.execute(s -> byTitle(title).getId());
    }

    private List<Long> missionIds(String title) {
        return tx.execute(s -> byTitle(title).getMissions().stream().map(CourseMission::getId).toList());
    }

    private List<Long> stopIds(String title) {
        return tx.execute(s -> byTitle(title).getStops().stream().map(CourseStop::getId).toList());
    }
}
