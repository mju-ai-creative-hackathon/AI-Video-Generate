package com.example.backend.community;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class CommunityCommentDtos {
    private CommunityCommentDtos() {}

    public record WriteRequest(@NotBlank @Size(max = 1000) String content) {}

    public record CommentView(UUID id, UUID postId, String content, CommunityDtos.Author author,
                              Instant createdAt, Instant updatedAt) {}
}
