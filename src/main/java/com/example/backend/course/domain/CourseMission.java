package com.example.backend.course.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "course_missions")
public class CourseMission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "stop_seq", nullable = false)
    private int stopSeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MissionKind kind;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 1000)
    private String guide;

    @Column(name = "duration_sec")
    private Integer durationSec;

    protected CourseMission() {
    }

    CourseMission(Course course, int stopSeq, MissionKind kind, String title, String guide, Integer durationSec) {
        this.course = course;
        this.stopSeq = stopSeq;
        this.kind = kind;
        this.title = title;
        this.guide = guide == null ? "" : guide;
        this.durationSec = durationSec;
    }

    public Long getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public int getStopSeq() {
        return stopSeq;
    }

    public MissionKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getGuide() {
        return guide;
    }

    public Integer getDurationSec() {
        return durationSec;
    }
}
