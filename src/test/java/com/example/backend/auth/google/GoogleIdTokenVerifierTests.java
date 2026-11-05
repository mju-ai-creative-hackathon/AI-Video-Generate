package com.example.backend.auth.google;

import com.example.backend.auth.exception.AuthException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleIdTokenVerifierTests {
    private static final String SERVER_CLIENT_ID = "server-client-id";

    @Test
    void acceptsValidGoogleIdToken() {
        GoogleIdTokenVerifier verifier = verifier(validJwt());

        GoogleUserInfo user = verifier.verify("google-id-token");

        assertThat(user.subject()).isEqualTo("google-subject");
        assertThat(user.email()).isEqualTo("user@example.com");
        assertThat(user.name()).isEqualTo("사용자");
        assertThat(user.profileImageUrl()).isEqualTo("https://example.com/profile.png");
    }

    @Test
    void rejectsTokenIssuedForAnotherClient() {
        Jwt jwt = jwtBuilder()
                .audience(List.of("another-client-id"))
                .build();

        assertUnauthorized(verifier(jwt));
    }

    @Test
    void rejectsExpiredToken() {
        Jwt jwt = jwtBuilder()
                .expiresAt(Instant.now().minusSeconds(1))
                .build();

        assertUnauthorized(verifier(jwt));
    }

    @Test
    void rejectsUnverifiedEmail() {
        Jwt jwt = jwtBuilder()
                .claim("email_verified", false)
                .build();

        assertUnauthorized(verifier(jwt));
    }

    private static void assertUnauthorized(GoogleIdTokenVerifier verifier) {
        assertThatThrownBy(() -> verifier.verify("google-id-token"))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static GoogleIdTokenVerifier verifier(Jwt jwt) {
        JwtDecoder decoder = token -> jwt;
        return new GoogleIdTokenVerifier(decoder, SERVER_CLIENT_ID);
    }

    private static Jwt validJwt() {
        return jwtBuilder().build();
    }

    private static Jwt.Builder jwtBuilder() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("google-id-token")
                .header("alg", "RS256")
                .subject("google-subject")
                .issuer("https://accounts.google.com")
                .audience(List.of(SERVER_CLIENT_ID))
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(300))
                .claim("email", "user@example.com")
                .claim("email_verified", true)
                .claim("name", "사용자")
                .claim("picture", "https://example.com/profile.png");
    }
}
