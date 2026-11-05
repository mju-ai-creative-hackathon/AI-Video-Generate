package com.example.backend.shortform;

import com.example.backend.shortform.storage.MediaKind;
import com.example.backend.shortform.storage.StoredMedia;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shortform_assets", indexes = @Index(name = "ix_shortform_asset_project", columnList = "project_id"))
public class ShortformAsset {
    @Id @GeneratedValue UUID id;
    @Column(name = "project_id", nullable = false, updatable = false) UUID projectId;
    @Column(name = "mission_id") UUID missionId;
    @Column(nullable = false, unique = true, length = 100) String storageKey;
    @Column(nullable = false, length = 255) String originalFilename;
    @Column(nullable = false, length = 100) String contentType;
    @Column(nullable = false) long sizeBytes;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) MediaKind kind;
    @Column(nullable = false) int position;
    @Column(nullable = false, updatable = false) Instant createdAt;
    protected ShortformAsset() {}
    ShortformAsset(UUID projectId, UUID missionId, int position, StoredMedia stored) {
        this.projectId = projectId; this.missionId = missionId; this.position = position;
        this.storageKey = stored.storageKey(); this.originalFilename = stored.originalFilename();
        this.contentType = stored.contentType(); this.sizeBytes = stored.sizeBytes(); this.kind = stored.kind();
        this.createdAt = Instant.now();
    }
}
