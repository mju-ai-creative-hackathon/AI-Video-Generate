package com.example.backend.course.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalTime;

@Entity
@Table(name = "course_stops")
public class CourseStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(length = 255)
    private String address;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "external_place_id", length = 50)
    private String externalPlaceId;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "stay_minutes", nullable = false)
    private int stayMinutes;

    // 기존 행 호환을 위해 nullable. null 이면 1일차.
    @Column(name = "day_no")
    private Integer day;

    public int getDay() {
        return day == null ? 1 : day;
    }

    void setDay(int day) {
        this.day = Math.max(1, day);
    }

    protected CourseStop() {
    }

    CourseStop(Course course, int seq, String name, String description, String address, double lat, double lng,
               String imageUrl, String externalPlaceId, LocalTime startTime, int stayMinutes) {
        this.course = course;
        this.seq = seq;
        this.name = name;
        this.description = description == null ? "" : description;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.imageUrl = imageUrl;
        this.externalPlaceId = externalPlaceId;
        this.startTime = startTime;
        this.stayMinutes = stayMinutes;
    }

    public Long getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public int getSeq() {
        return seq;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getAddress() {
        return address;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getExternalPlaceId() {
        return externalPlaceId;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public int getStayMinutes() {
        return stayMinutes;
    }
}
