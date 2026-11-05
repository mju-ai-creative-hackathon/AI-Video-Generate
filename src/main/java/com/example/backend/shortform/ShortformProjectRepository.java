package com.example.backend.shortform;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface ShortformProjectRepository extends JpaRepository<ShortformProject, UUID> {
    Page<ShortformProject> findByOwnerId(UUID ownerId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ShortformProject p where p.id = :id")
    Optional<ShortformProject> findLockedById(UUID id);
}
