package com.example.backend.diary.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, UUID> {
    Optional<DiaryEntry> findByIdAndUserId(UUID id, UUID userId);

    Page<DiaryEntry> findByUserIdAndStatus(UUID userId, DiaryStatus status, Pageable pageable);

    Optional<DiaryEntry> findFirstByUserIdAndStatusOrderByStartedAtDesc(UUID userId, DiaryStatus status);

    boolean existsByUserIdAndStatus(UUID userId, DiaryStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DiaryEntry d where d.id = :id and d.userId = :userId")
    Optional<DiaryEntry> findLockedByIdAndUserId(UUID id, UUID userId);

    void deleteByUserId(UUID userId);
}
