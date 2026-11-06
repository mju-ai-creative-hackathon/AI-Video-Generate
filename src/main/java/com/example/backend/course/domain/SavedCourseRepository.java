package com.example.backend.course.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SavedCourseRepository extends JpaRepository<SavedCourse, Long> {

    boolean existsByUserIdAndCourseId(UUID userId, Long courseId);

    @Query("select s.course.id from SavedCourse s where s.userId = :userId and s.course.id in :courseIds")
    List<Long> findSavedCourseIds(@Param("userId") UUID userId, @Param("courseIds") Collection<Long> courseIds);

    @Query(value = "select s from SavedCourse s join fetch s.course where s.userId = :userId",
            countQuery = "select count(s) from SavedCourse s where s.userId = :userId")
    Page<SavedCourse> findWithCourseByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Modifying
    @Query("delete from SavedCourse s where s.userId = :userId and s.course.id = :courseId")
    int deleteByUserIdAndCourseId(@Param("userId") UUID userId, @Param("courseId") Long courseId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SavedCourse s where s.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SavedCourse s where s.course.id = :courseId")
    int deleteByCourseId(@Param("courseId") Long courseId);
}
