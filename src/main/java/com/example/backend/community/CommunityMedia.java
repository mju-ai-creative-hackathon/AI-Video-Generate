package com.example.backend.community;

import com.example.backend.shortform.storage.MediaKind;
import com.example.backend.shortform.storage.StoredMedia;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "community_post_media", indexes = @Index(name = "ix_community_media_post", columnList = "post_id"))
public class CommunityMedia {
    @Id @GeneratedValue
    UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    CommunityPost post;

    @Column(nullable = false, unique = true, length = 100)
    String storageKey;

    @Column(nullable = false, length = 255)
    String originalFilename;

    @Column(nullable = false, length = 100)
    String contentType;

    @Column(nullable = false)
    long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    MediaKind kind;

    // -1 is reserved for a separate photo cover of a video-first post.
    @Column(nullable = false)
    int position;

    protected CommunityMedia() {}

    CommunityMedia(CommunityPost post, int position, StoredMedia stored) {
        this.post = post;
        this.position = position;
        this.storageKey = stored.storageKey();
        this.originalFilename = stored.originalFilename();
        this.contentType = stored.contentType();
        this.sizeBytes = stored.sizeBytes();
        this.kind = stored.kind();
    }
}
