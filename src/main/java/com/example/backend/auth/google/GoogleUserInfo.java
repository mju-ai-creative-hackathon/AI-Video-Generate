package com.example.backend.auth.google;

public record GoogleUserInfo(String subject, String email, String name, String profileImageUrl) {
}
