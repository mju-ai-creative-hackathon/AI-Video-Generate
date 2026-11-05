package com.example.backend.auth.google;

import com.example.backend.auth.exception.AuthException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class GoogleIdTokenVerifier {
    private static final Set<String> GOOGLE_ISSUERS = Set.of(
            "https://accounts.google.com",
            "accounts.google.com"
    );

    private final JwtDecoder jwtDecoder;
    private final String serverClientId;

    public GoogleIdTokenVerifier(
            @Qualifier("googleJwtDecoder") JwtDecoder jwtDecoder,
            @Value("${app.google.server-client-id}") String serverClientId
    ) {
        this.jwtDecoder = jwtDecoder;
        this.serverClientId = serverClientId;
    }

    public GoogleUserInfo verify(String idToken) {
        try {
            Jwt jwt = jwtDecoder.decode(idToken);
            validateStandardClaims(jwt);

            String email = jwt.getClaimAsString("email");
            Boolean emailVerified = jwt.getClaim("email_verified");
            if (email == null || email.isBlank() || !Boolean.TRUE.equals(emailVerified)) {
                throw invalidToken();
            }

            return new GoogleUserInfo(
                    jwt.getSubject(),
                    email,
                    jwt.getClaimAsString("name"),
                    jwt.getClaimAsString("picture")
            );
        } catch (AuthException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private void validateStandardClaims(Jwt jwt) {
        Instant expiresAt = jwt.getExpiresAt();
        if (jwt.getSubject() == null
                || !GOOGLE_ISSUERS.contains(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())
                || expiresAt == null
                || !expiresAt.isAfter(Instant.now())
                || !jwt.getAudience().contains(serverClientId)) {
            throw invalidToken();
        }
    }

    private static AuthException invalidToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Google ID 토큰입니다.");
    }
}
