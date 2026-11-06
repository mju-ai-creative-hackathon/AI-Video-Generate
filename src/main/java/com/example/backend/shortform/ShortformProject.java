package com.example.backend.shortform;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "shortform_projects", indexes = @Index(name = "ix_shortform_project_owner", columnList = "owner_id,created_at"))
public class ShortformProject {
    @Id @GeneratedValue UUID id;
    @Column(name = "owner_id", nullable = false, updatable = false) UUID ownerId;
    @Column(nullable = false, length = 100) String title;
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    /** Optional course link. Mission ids are kept as a comma separated column (max 20). */
    @Column(name = "course_id", updatable = false) Long courseId;
    @Column(name = "mission_ids", length = 500, updatable = false) String missionIds;
    protected ShortformProject() {}
    ShortformProject(UUID ownerId, String title) { this(ownerId, title, null, List.of()); }
    ShortformProject(UUID ownerId, String title, Long courseId, List<Long> missionIds) {
        this.ownerId = ownerId; this.title = title.strip(); this.createdAt = Instant.now();
        this.courseId = courseId;
        this.missionIds = missionIds == null || missionIds.isEmpty() ? null
                : missionIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
    List<Long> missionIds() {
        if (missionIds == null || missionIds.isBlank()) return List.of();
        return Arrays.stream(missionIds.split(",")).map(String::strip).filter(v -> !v.isEmpty()).map(Long::valueOf).toList();
    }
}
