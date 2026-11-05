package com.example.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @NotBlank(message = "Google ID 토큰은 필수입니다.")
        String idToken
) {
}
