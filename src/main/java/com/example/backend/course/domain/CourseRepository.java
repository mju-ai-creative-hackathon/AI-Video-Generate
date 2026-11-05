package com.example.backend.course.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 목록의 stops/missions 는 Course 의 @BatchSize 로 묶어 읽는다(N+1 방지).
 * 정렬은 Pageable 로 넘긴다(CourseService: displayOrder, id).
 */
public interface CourseRepository extends JpaRepository<Course, Long> {

    Page<Course> findBySource(CourseSource source, Pageable pageable);

    Page<Course> findBySourceAndRegion(CourseSource source, String region, Pageable pageable);

    boolean existsBySource(CourseSource source);

    List<Course> findBySourceAndOwnerId(CourseSource source, UUID ownerId);

    List<Course> findByOwnerId(UUID ownerId);

    /** AI 코스 생성 횟수 제한용. */
    long countBySourceAndOwnerIdAndCreatedAtAfter(CourseSource source, UUID ownerId, Instant after);

    /** 지역 칩: 지역별 최소 displayOrder 순. */
    @Query("select c.region from Course c where c.source = :source and c.region is not null "
            + "group by c.region order by min(c.displayOrder), c.region")
    List<String> findDistinctRegions(@Param("source") CourseSource source);
}
