package com.example.backend.course.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "courses", indexes = {
        @Index(name = "idx_courses_source_region", columnList = "source, region"),
        @Index(name = "idx_courses_owner_created", columnList = "owner_id, created_at")
})
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 500)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourseSource source;

    /** AI 코스의 소유자(users.id). FK 없이 값만 보관한다. */
    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(length = 30)
    private String region;

    @Column(name = "total_minutes", nullable = false)
    private int totalMinutes;

    /** 예상 비용(원). */
    @Column(name = "estimated_cost")
    private Integer estimatedCost;

    @Column(length = 1000)
    private String prompt;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 목록 조회 시 여러 코스의 stops/missions 를 IN 쿼리로 묶어 읽어 N+1 을 피한다.
    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    @BatchSize(size = 50)
    private List<CourseStop> stops = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stopSeq ASC, id ASC")
    @BatchSize(size = 50)
    private List<CourseMission> missions = new ArrayList<>();

    protected Course() {
    }

    private Course(CourseSource source, UUID ownerId, String prompt, String title, String summary, String region,
                   int totalMinutes, Integer estimatedCost, int displayOrder) {
        this.source = source;
        this.ownerId = ownerId;
        this.prompt = prompt;
        this.title = title;
        this.summary = summary;
        this.region = region;
        this.totalMinutes = totalMinutes;
        this.estimatedCost = estimatedCost;
        this.displayOrder = displayOrder;
        this.createdAt = Instant.now();
    }

    public static Course preset(String title, String summary, String region, int totalMinutes,
                                Integer estimatedCost, int displayOrder) {
        return new Course(CourseSource.PRESET, null, null, title, summary, region,
                totalMinutes, estimatedCost, displayOrder);
    }

    public static Course ai(UUID ownerId, String prompt, String title, String summary, String region,
                            int totalMinutes, Integer estimatedCost) {
        return new Course(CourseSource.AI, ownerId, prompt, title, summary, region,
                totalMinutes, estimatedCost, 0);
    }

    public CourseStop addStop(int seq, String name, String description, String address, double lat, double lng,
                              String imageUrl, String externalPlaceId, LocalTime startTime, int stayMinutes) {
        CourseStop stop = new CourseStop(this, seq, name, description, address, lat, lng,
                imageUrl, externalPlaceId, startTime, stayMinutes);
        stops.add(stop);
        return stop;
    }

    public CourseMission addMission(int stopSeq, MissionKind kind, String title, String guide, Integer durationSec) {
        CourseMission mission = new CourseMission(this, stopSeq, kind, title, guide, durationSec);
        missions.add(mission);
        return mission;
    }

    /** PRESET 은 누구나, AI 코스는 소유자만 볼 수 있다. */
    public boolean isVisibleTo(UUID viewerId) {
        return source == CourseSource.PRESET || (ownerId != null && ownerId.equals(viewerId));
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public CourseSource getSource() {
        return source;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getRegion() {
        return region;
    }

    public int getTotalMinutes() {
        return totalMinutes;
    }

    public Integer getEstimatedCost() {
        return estimatedCost;
    }

    public String getPrompt() {
        return prompt;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<CourseStop> getStops() {
        return Collections.unmodifiableList(stops);
    }

    public List<CourseMission> getMissions() {
        return Collections.unmodifiableList(missions);
    }
}
