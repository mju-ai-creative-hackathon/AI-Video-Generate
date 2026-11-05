package com.example.backend.shortform.ai;

import java.util.List;
import java.util.UUID;

/**
 * Resolves shooting hints for a shortform project linked to a course.
 * Implement as a scanned bean (e.g. {@code @Component}) in the course module to replace the no-op default.
 */
public interface ShootingHintProvider {
    List<ShootingHint> hintsFor(UUID userId, Long courseId, List<Long> missionIds);
}
