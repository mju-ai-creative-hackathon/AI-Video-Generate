package com.example.backend.course.recommend;

import com.example.backend.common.ApiException;
import com.example.backend.course.ai.gemini.AiCourseDraft;
import com.example.backend.course.ai.kakao.KakaoPlace;
import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseStop;
import com.example.backend.course.geo.GeoPoint;
import com.example.backend.course.geo.GeoUtils;
import com.example.backend.course.geo.LegPlanner;
import org.springframework.http.HttpStatus;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Immutable-in-practice snapshots and deterministic preservation of the completed prefix. */
final class RemainingCourseBuilder {
    private RemainingCourseBuilder() {}

    static Course snapshot(Course original, UUID owner, int completedThroughStopSeq) {
        Course prefix = Course.ai(owner, original.getPrompt(), original.getTitle(), original.getSummary(),
                original.getRegion(), original.getTotalMinutes(), original.getEstimatedCost());
        copyOrigin(original, prefix);
        original.getStops().stream().filter(s -> s.getSeq() <= completedThroughStopSeq)
                .forEach(s -> copyStop(prefix, s.getSeq(), s, s.getDay(), s.getStartTime()));
        original.getMissions().stream().filter(m -> m.getStopSeq() <= completedThroughStopSeq)
                .forEach(m -> prefix.addMission(m.getStopSeq(), m.getKind(), m.getTitle(), m.getGuide(), m.getDurationSec()));
        return prefix;
    }

    static void validateDraft(AiCourseDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.stops().isEmpty()
                || draft.stops().size() > 8) {
            throw invalidDraft();
        }
        var sequences = new HashSet<Integer>();
        for (var stop : draft.stops()) {
            if (stop == null || stop.seq() <= 0 || !sequences.add(stop.seq()) || stop.name() == null
                    || stop.name().isBlank() || stop.stayMinutes() < 1 || stop.stayMinutes() > 1440
                    || stop.day() < 1 || stop.day() > 30) {
                throw invalidDraft();
            }
        }
    }

    static boolean alreadyCompleted(Course prefix, KakaoPlace place) {
        return prefix.getStops().stream().anyMatch(stop ->
                (stop.getExternalPlaceId() != null && stop.getExternalPlaceId().equals(place.id()))
                || normalize(stop.getName()).equals(normalize(place.name()))
                || GeoUtils.distanceMeters(point(stop), new GeoPoint(place.lat(), place.lng())) <= 15);
    }

    static Course combine(Course prefix, Course tail, UUID owner, String history) {
        record Scheduled(CourseStop source, int day, LocalTime time) {}
        List<Scheduled> scheduled = new ArrayList<>();
        prefix.getStops().forEach(s -> scheduled.add(new Scheduled(s, s.getDay(), s.getStartTime())));
        CourseStop previous = prefix.getStops().isEmpty() ? null : prefix.getStops().getLast();
        Long previousEnd = previous == null || previous.getStartTime() == null ? null
                : absolute(previous.getDay(), previous.getStartTime()) + previous.getStayMinutes();
        int previousDay = previous == null ? 1 : previous.getDay();
        for (CourseStop stop : tail.getStops()) {
            int day = Math.max(previousDay, stop.getDay());
            int travel = previous == null ? 0 : LegPlanner.estimate(point(previous), point(stop)).estMinutes();
            Long requested = stop.getStartTime() == null ? null : absolute(day, stop.getStartTime());
            Long earliest = previousEnd == null ? null : previousEnd + travel;
            Long start = requested == null ? earliest : earliest == null ? requested : Math.max(requested, earliest);
            LocalTime time = stop.getStartTime();
            if (start != null) {
                day = Math.toIntExact(start / 1440 + 1);
                time = LocalTime.ofSecondOfDay(start % 1440 * 60);
                previousEnd = start + stop.getStayMinutes();
            } else {
                previousEnd = null;
            }
            scheduled.add(new Scheduled(stop, day, time));
            previous = stop;
            previousDay = day;
        }
        long duration = 0;
        for (int i = 0; i < scheduled.size(); i++) {
            duration += scheduled.get(i).source().getStayMinutes();
            if (i > 0) duration += LegPlanner.estimate(point(scheduled.get(i - 1).source()),
                    point(scheduled.get(i).source())).estMinutes();
        }
        Scheduled first = scheduled.getFirst();
        Scheduled last = scheduled.getLast();
        if (first.time() != null && last.time() != null) {
            duration = Math.max(duration, absolute(last.day(), last.time()) + last.source().getStayMinutes()
                    - absolute(first.day(), first.time()));
        }
        Course combined = Course.ai(owner, history, tail.getTitle(), tail.getSummary(),
                tail.getRegion() == null ? prefix.getRegion() : tail.getRegion(),
                (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration)),
                prefix.getStops().isEmpty() ? tail.getEstimatedCost() : null);
        copyOrigin(prefix, combined);
        int seq = 1;
        for (Scheduled item : scheduled) copyStop(combined, seq++, item.source(), item.day(), item.time());
        prefix.getMissions().forEach(m -> combined.addMission(m.getStopSeq(), m.getKind(), m.getTitle(),
                m.getGuide(), m.getDurationSec()));
        int offset = prefix.getStops().size();
        tail.getMissions().forEach(m -> combined.addMission(offset + m.getStopSeq(), m.getKind(), m.getTitle(),
                m.getGuide(), m.getDurationSec()));
        return combined;
    }

    private static void copyStop(Course target, int seq, CourseStop source, int day, LocalTime startTime) {
        target.addStop(seq, day, source.getName(), source.getDescription(), source.getAddress(), source.getLat(),
                source.getLng(), source.getImageUrl(), source.getExternalPlaceId(), startTime, source.getStayMinutes());
    }

    private static void copyOrigin(Course source, Course target) {
        if (source.hasOrigin()) target.setOrigin(source.getOriginName(), source.getOriginAddress(),
                source.getOriginLat(), source.getOriginLng());
    }

    private static long absolute(int day, LocalTime time) { return ((long) day - 1) * 1440 + time.toSecondOfDay() / 60; }
    private static GeoPoint point(CourseStop stop) { return new GeoPoint(stop.getLat(), stop.getLng()); }
    private static String normalize(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
    private static ApiException invalidDraft() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_NO_RESULT", "수정된 일정 형식이 올바르지 않습니다. 다시 시도해 주세요.");
    }
}
