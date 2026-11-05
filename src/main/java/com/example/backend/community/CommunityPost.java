package com.example.backend.community;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "community_posts", indexes = {
        @Index(name = "ix_community_post_author", columnList = "author_id"),
        @Index(name = "ix_community_post_created", columnList = "created_at")
})
public class CommunityPost {
    @Id @GeneratedValue
    UUID id;

    // The feed joins active users; account withdrawal must not be blocked by a post FK.
    @Column(name = "author_id", nullable = false, updatable = false)
    UUID authorId;

    @Column(nullable = false, length = 100)
    String title;

    @Column(length = 3000)
    String caption;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @ElementCollection
    @CollectionTable(name = "community_post_tags", joinColumns = @JoinColumn(name = "post_id"),
            indexes = @Index(name = "ix_community_tag", columnList = "tag"))
    @Column(name = "tag", nullable = false, length = 30)
    Set<String> tags = new LinkedHashSet<>();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    List<CommunityMedia> media = new ArrayList<>();

    protected CommunityPost() {}

    CommunityPost(UUID authorId, String title, String caption, Set<String> tags) {
        this.authorId = authorId;
        this.title = title;
        this.caption = caption;
        this.tags = new LinkedHashSet<>(tags);
        this.createdAt = Instant.now();
    }
}
