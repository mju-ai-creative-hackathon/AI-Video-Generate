package com.example.backend.shortform.storage;

public record StoredMedia(
        String storageKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        MediaKind kind
) {}
