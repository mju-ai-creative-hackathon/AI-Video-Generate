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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from RenderJob j where j.id = :id")
    Optional<RenderJob> findLockedById(UUID id);
}
