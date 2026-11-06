package com.example.backend.diary;

import com.example.backend.auth.AuthService;
import com.example.backend.auth.domain.UserRepository;
import com.example.backend.common.ApiException;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.diary.domain.*;
import com.example.backend.place.dto.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DiaryService {
    private final DiaryEntryRepository repository;
    private final CourseRepository courses;
    private final UserRepository users;
    private final AuthService auth;
    public DiaryService(DiaryEntryRepository repository, CourseRepository courses, UserRepository users, AuthService auth) {
        this.repository = repository; this.courses = courses; this.users = users; this.auth = auth;
    }
    @Transactional
    public DiaryDtos.Detail start(String authorization, DiaryDtos.Start request) {
        UUID userId = userId(authorization);
        users.lockById(userId).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));
        if (repository.existsByUserIdAndStatus(userId, DiaryStatus.RECORDING)) {
            throw conflict("이미 진행 중인 일정이 있습니다. 종료하거나 삭제한 뒤 시작해 주세요.");
        }
        var course = courses.findById(request.courseId()).filter(c -> c.isVisibleTo(userId))
                .orElseThrow(() -> ApiException.notFound("코스를 찾을 수 없습니다."));
        String title = request.title() == null ? course.getTitle() : request.title().trim();
        if (title.isBlank()) throw ApiException.badRequest("제목을 입력해 주세요.");
        return detail(repository.saveAndFlush(new DiaryEntry(userId, course.getId(), course.getTitle(), title,
                Instant.now().truncatedTo(ChronoUnit.MICROS))));
    }
    public DiaryDtos.Detail active(String authorization) {
        return repository.findFirstByUserIdAndStatusOrderByStartedAtDesc(userId(authorization), DiaryStatus.RECORDING)
                .map(this::detail).orElse(null);
    }
    @Transactional
    public DiaryDtos.Detail complete(String authorization, UUID id, DiaryDtos.Complete request) {
        var entry = locked(id, userId(authorization));
        if (entry.getStatus() != DiaryStatus.RECORDING) throw conflict("이미 종료된 일정입니다.");
        Instant end = request.endedAt();
        if (end.isBefore(entry.getStartedAt()) || end.isAfter(Instant.now().plusSeconds(30))) {
            throw ApiException.badRequest("종료 시각은 시작 이후이며 현재 시각보다 미래일 수 없습니다.");
        }
        Instant previous = entry.getStartedAt();
        for (var point : request.points()) {
            if (!Double.isFinite(point.latitude()) || !Double.isFinite(point.longitude())
                    || point.recordedAt().isBefore(previous) || point.recordedAt().isAfter(end)) {
                throw ApiException.badRequest("위치 시각은 일정 시작~종료 사이의 시간순이어야 합니다.");
            }
            previous = point.recordedAt();
        }
        entry.complete(end.truncatedTo(ChronoUnit.MICROS), request.steps(), request.points().stream()
                .map(p -> new DiaryPoint(p.latitude(), p.longitude(), p.recordedAt().truncatedTo(ChronoUnit.MICROS))).toList());
        entry.setFootprintSvg(FootprintRenderer.render(entry.getRoute()));
        repository.flush();
        return detail(entry);
    }
    public PageResponse<DiaryDtos.Summary> list(String authorization, int page, int size) {
        UUID userId = userId(authorization);
        if (page < 0 || size < 1 || size > 50) throw ApiException.badRequest("page는 0 이상, size는 1~50이어야 합니다.");
        var result = repository.findByUserIdAndStatus(userId, DiaryStatus.COMPLETED,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))));
        return PageResponse.of(result, result.getContent().stream().map(e -> new DiaryDtos.Summary(e.getId(),
                e.getCourseId(), e.getCourseTitle(), e.getTitle(), e.getRecordDate(), e.getSteps(), e.getStatus(),
                e.getStartedAt(), e.getEndedAt(), e.getFootprintSvg())).toList());
    }
    public DiaryDtos.Detail get(String authorization, UUID id) {
        return detail(repository.findByIdAndUserId(id, userId(authorization))
                .orElseThrow(() -> ApiException.notFound("기록을 찾을 수 없습니다.")));
    }
    @Transactional
    public DiaryDtos.Detail update(String authorization, UUID id, DiaryDtos.Update request) {
        var entry = locked(id, userId(authorization));
        if (entry.getStatus() != DiaryStatus.COMPLETED) throw conflict("일정을 종료한 뒤 일기를 작성해 주세요.");
        entry.update(request.title().trim(), request.memo());
        return detail(entry);
    }
    @Transactional
    public void delete(String authorization, UUID id) { repository.delete(locked(id, userId(authorization))); }
    private DiaryEntry locked(UUID id, UUID userId) {
        users.lockById(userId).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));
        return repository.findLockedByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("기록을 찾을 수 없습니다."));
    }
    private UUID userId(String authorization) { return auth.getCurrentUser(authorization).id(); }
    private DiaryDtos.Detail detail(DiaryEntry e) {
        return new DiaryDtos.Detail(e.getId(), e.getCourseId(), e.getCourseTitle(), e.getTitle(), e.getMemo(),
                e.getRecordDate(), e.getSteps(), e.getStatus(), e.getStartedAt(), e.getEndedAt(), e.getRoute().stream()
                .map(p -> new DiaryDtos.Point(p.getLatitude(), p.getLongitude(), p.getRecordedAt())).toList(),
                FootprintRenderer.render(e.getRoute()));
    }
    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "DIARY_STATE_CONFLICT", message);
    }
}
