package com.example.backend.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(String code, String message, Map<String, String> errors, Instant timestamp) {
    public ApiError(String code, String message) {
        this(code, message, Map.of(), Instant.now());
    }
}
