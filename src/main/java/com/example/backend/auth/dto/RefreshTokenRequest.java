package com.example.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequest(
        @NotBlank(message = "Refresh Token은 필수입니다.")
        @Size(max = 512, message = "Refresh Token 형식이 올바르지 않습니다.")
        String refreshToken
) {
}
