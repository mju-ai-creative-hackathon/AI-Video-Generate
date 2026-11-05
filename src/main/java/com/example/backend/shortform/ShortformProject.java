package com.example.backend.shortform;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shortform_projects", indexes = @Index(name = "ix_shortform_project_owner", columnList = "owner_id,created_at"))
public class ShortformProject {
    @Id @GeneratedValue UUID id;
    @Column(name = "owner_id", nullable = false, updatable = false) UUID ownerId;
    @Column(nullable = false, length = 100) String title;
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    protected ShortformProject() {}
    ShortformProject(UUID ownerId, String title) {
        this.ownerId = ownerId; this.title = title.strip(); this.createdAt = Instant.now();
    }
}
