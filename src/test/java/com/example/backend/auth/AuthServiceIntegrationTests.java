package com.example.backend.auth;

import com.example.backend.PostgresIntegrationTest;

import com.example.backend.auth.domain.User;
import com.example.backend.auth.domain.RefreshSessionRepository;
import com.example.backend.auth.domain.UserRepository;
import com.example.backend.auth.dto.AuthResponse;
import com.example.backend.auth.dto.LoginRequest;
import com.example.backend.auth.dto.RefreshTokenRequest;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.auth.dto.UserResponse;
import com.example.backend.auth.exception.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AuthServiceIntegrationTests extends PostgresIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshSessionRepository refreshSessionRepository;

    @BeforeEach
    void cleanUp() {
        refreshSessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void signupHashesPasswordAndReturnsUsableToken() {
        AuthResponse response = authService.signup(
                new SignupRequest("  USER@Example.com ", "홍길동", "password123")
        );

        User saved = userRepository.findByEmail("user@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(new BCryptPasswordEncoder().matches("password123", saved.getPasswordHash())).isTrue();
        assertThat(response.user().email()).isEqualTo("user@example.com");

        UserResponse currentUser = authService.getCurrentUser("Bearer " + response.accessToken());
        assertThat(currentUser.id()).isEqualTo(saved.getId());
    }

    @Test
    void loginReturnsTokenForCorrectCredentials() {
        authService.signup(new SignupRequest("user@example.com", "사용자", "password123"));

        AuthResponse response = authService.login(new LoginRequest("USER@example.com", "password123"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(response.refreshToken()).hasSize(43);
        assertThat(response.refreshExpiresIn()).isEqualTo(604800);
    }

    @Test
    void duplicateEmailIsRejected() {
        authService.signup(new SignupRequest("user@example.com", "사용자", "password123"));

        assertThatThrownBy(() -> authService.signup(
                new SignupRequest("USER@example.com", "다른 사용자", "password456")
        )).isInstanceOfSatisfying(AuthException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void wrongPasswordAndInvalidTokenAreRejected() {
        authService.signup(new SignupRequest("user@example.com", "사용자", "password123"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrong-password")))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> authService.getCurrentUser("Bearer invalid.token.value"))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void googleLoginCreatesUserAndReusesSameAccount() {
        AuthResponse first = authService.loginWithGoogle(
                "google-subject-1",
                "google@example.com",
                "구글 사용자",
                "https://example.com/profile.png"
        );
        AuthResponse second = authService.loginWithGoogle(
                "google-subject-1",
                "google@example.com",
                "변경된 이름",
                "https://example.com/new-profile.png"
        );

        assertThat(second.user().id()).isEqualTo(first.user().id());
        assertThat(second.user().name()).isEqualTo("변경된 이름");
        assertThat(second.user().profileImageUrl()).isEqualTo("https://example.com/new-profile.png");
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void googleLoginLinksExistingPasswordAccountByVerifiedEmail() {
        AuthResponse signup = authService.signup(
                new SignupRequest("user@example.com", "기존 사용자", "password123")
        );

        AuthResponse google = authService.loginWithGoogle(
                "google-subject-2",
                "USER@example.com",
                "구글 이름",
                null
        );

        assertThat(google.user().id()).isEqualTo(signup.user().id());
        assertThat(authService.login(new LoginRequest("user@example.com", "password123")).user().id())
                .isEqualTo(signup.user().id());
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void refreshRotatesBothTokensAndRejectsReplay() {
        AuthResponse login = authService.signup(
                new SignupRequest("refresh@example.com", "리프레시 사용자", "password123")
        );

        AuthResponse refreshed = authService.refresh(new RefreshTokenRequest(login.refreshToken()));

        assertThat(refreshed.accessToken()).isNotBlank().isNotEqualTo(login.accessToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(refreshed.refreshExpiresIn()).isPositive().isLessThanOrEqualTo(604800);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(login.refreshToken())))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(refreshed.refreshToken())))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void logoutRevokesRefreshSession() {
        AuthResponse login = authService.signup(
                new SignupRequest("logout@example.com", "로그아웃 사용자", "password123")
        );

        authService.logout(new RefreshTokenRequest(login.refreshToken()));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(login.refreshToken())))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void databaseStoresOnlyRefreshTokenHash() {
        AuthResponse login = authService.signup(
                new SignupRequest("hash@example.com", "해시 사용자", "password123")
        );

        var storedSession = refreshSessionRepository.findAll().getFirst();
        assertThat(storedSession.getTokenHash())
                .hasSize(64)
                .isNotEqualTo(login.refreshToken());
    }
}
