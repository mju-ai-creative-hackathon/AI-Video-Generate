package com.example.backend.shortform;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.*;

public interface RenderJobRepository extends JpaRepository<RenderJob, UUID> {
    Optional<RenderJob> findByProjectIdAndIdempotencyKey(UUID projectId, UUID key);
    Optional<RenderJob> findFirstByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId);
    boolean existsByProjectId(UUID projectId);
    boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<RenderStatus> statuses);
    List<RenderJob> findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Collection<RenderStatus> statuses, Instant now);
    /** 사용자별 하루 제작 횟수 제한용. */
    @Query("select count(j) from RenderJob j where j.createdAt >= :since"
            + " and j.projectId in (select p.id from ShortformProject p where p.ownerId = :ownerId)")
    long countCreatedByOwnerSince(UUID ownerId, Instant since);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from RenderJob j where j.id = :id")
    Optional<RenderJob> findLockedById(UUID id);
}
