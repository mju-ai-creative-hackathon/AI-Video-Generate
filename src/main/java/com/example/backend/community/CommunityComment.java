package com.example.backend.community;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "community_comments", indexes = {
        @Index(name = "ix_community_comment_post_created", columnList = "post_id,created_at,id"),
        @Index(name = "ix_community_comment_author", columnList = "author_id")
})
public class CommunityComment {
    @Id
    @GeneratedValue
    private UUID id;

    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, updatable = false)
    private CommunityPost post;

    // Retain no user FK so account withdrawal can remove comments without blocking.
    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityComment() {}

    public CommunityComment(CommunityPost post, UUID authorId, String content) {
        this.post = post;
        this.authorId = authorId;
        this.content = content.strip();
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    public void updateContent(String content) {
        this.content = content.strip();
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public CommunityPost getPost() { return post; }
    public UUID getAuthorId() { return authorId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
