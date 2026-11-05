package com.example.backend.community;

import com.example.backend.diary.DiaryDtos;
import com.example.backend.shortform.storage.MediaKind;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CommunityDtos {
    private CommunityDtos() {}

    public record CreateRequest(
            @NotBlank @Size(max = 100) String title,
            @Size(max = 3000) String caption,
            @Size(max = 10) List<@NotBlank @Size(max = 30) String> tags,
            @Positive Long courseId
    ) {}

    public record Author(UUID id, String name, String profileImageUrl) {}

    public record MediaView(UUID id, MediaKind kind, String contentType, long sizeBytes, String url) {}

    public record LinkedCourse(Long id, String title) {}

    public record PostView(UUID id, String title, String caption, Author author, List<String> tags,
                           String thumbnailUrl, List<MediaView> media, LinkedCourse course, Instant createdAt) {}

    public record ImportResult(Long courseId, DiaryDtos.Detail diary) {}

    public record Feed(List<PostView> items, UUID seed, Instant snapshot, String nextCursor, boolean hasNext) {}

    public record TagView(String tag, long postCount) {}
}
