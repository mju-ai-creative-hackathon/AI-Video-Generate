package com.example.backend.course.recommend;

import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseMission;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.course.domain.CourseStop;
import com.example.backend.shortform.ai.ShootingHint;
import com.example.backend.shortform.ai.ShootingHintProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 숏폼 렌더 요청에 코스 촬영 미션을 힌트로 붙인다. 본인에게 보이는 코스의 미션만 사용한다. */
@Component
public class CourseShootingHintProvider implements ShootingHintProvider {
    private final CourseRepository courseRepository;

    public CourseShootingHintProvider(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShootingHint> hintsFor(UUID userId, Long courseId, List<Long> missionIds) {
        if (courseId == null) {
            return List.of();
        }
        Course course = courseRepository.findById(courseId).filter(c -> c.isVisibleTo(userId)).orElse(null);
        if (course == null) {
            return List.of();
        }
        Map<Integer, String> stopNames = course.getStops().stream()
                .collect(Collectors.toMap(CourseStop::getSeq, CourseStop::getName, (a, b) -> a));
        Map<Long, CourseMission> byId = course.getMissions().stream()
                .collect(Collectors.toMap(CourseMission::getId, Function.identity()));
        List<CourseMission> selected = new ArrayList<>();
        if (missionIds == null || missionIds.isEmpty()) {
            selected.addAll(course.getMissions());
        } else {
            missionIds.stream().map(byId::get).filter(m -> m != null).forEach(selected::add);
        }
        return selected.stream()
                .limit(20)
                .map(m -> new ShootingHint(m.getId(), stopNames.get(m.getStopSeq()), m.getKind().name(),
                        m.getTitle(), m.getGuide(), m.getDurationSec()))
                .toList();
    }
}
