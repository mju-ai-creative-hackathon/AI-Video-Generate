package com.example.backend.diary.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "diary_entries", indexes = {
        @Index(name = "ix_diary_owner_status_started", columnList = "user_id,status,started_at")
})
public class DiaryEntry {
    @Id
    @GeneratedValue
    private UUID id;

    @Version
    private long version;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Snapshot reference: deleting or editing a course does not erase the diary. */
    @Column(name = "course_id", nullable = false, updatable = false)
    private Long courseId;

    @Column(name = "course_title", nullable = false, updatable = false, length = 100)
    private String courseTitle;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 5000)
    private String memo = "";

    @Column(name = "footprint_svg", nullable = false, length = 32000)
    private String footprintSvg = "";

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "record_date", nullable = false, updatable = false)
    private LocalDate recordDate;

    @Column(nullable = false)
    private long steps;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiaryStatus status;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "diary_route_points", joinColumns = @JoinColumn(name = "diary_id"))
    @OrderColumn(name = "point_order")
    private List<DiaryPoint> route = new ArrayList<>();

    protected DiaryEntry() {
    }

    public DiaryEntry(UUID userId, Long courseId, String courseTitle, String title, Instant startedAt) {
        this.userId = userId;
        this.courseId = courseId;
        this.courseTitle = courseTitle;
        this.title = title.strip();
        this.startedAt = startedAt;
        this.recordDate = startedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        this.status = DiaryStatus.RECORDING;
    }

    public void complete(Instant endedAt, long steps, List<DiaryPoint> points) {
        if (status != DiaryStatus.RECORDING) {
            throw new IllegalStateException("Diary is already completed");
        }
        this.endedAt = endedAt;
        this.steps = steps;
        this.route.clear();
        this.route.addAll(points);
        this.status = DiaryStatus.COMPLETED;
    }

    public void update(String title, String memo) {
        this.title = title.strip();
        this.memo = memo;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Long getCourseId() { return courseId; }
    public String getCourseTitle() { return courseTitle; }
    public String getTitle() { return title; }
    public String getMemo() { return memo; }
    public String getFootprintSvg() { return footprintSvg; }
    public void setFootprintSvg(String footprintSvg) { this.footprintSvg = footprintSvg; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public LocalDate getRecordDate() { return recordDate; }
    public long getSteps() { return steps; }
    public DiaryStatus getStatus() { return status; }
    public List<DiaryPoint> getRoute() { return Collections.unmodifiableList(route); }
}
