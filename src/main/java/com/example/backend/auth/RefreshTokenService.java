package com.example.backend.auth;

import com.example.backend.auth.domain.RefreshSession;
import com.example.backend.auth.domain.RefreshSessionRepository;
import com.example.backend.auth.domain.User;
import com.example.backend.auth.exception.AuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshSessionRepository refreshSessionRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long expirationSeconds;

    public RefreshTokenService(
            RefreshSessionRepository refreshSessionRepository,
            @Value("${app.refresh-token.expiration-seconds}") long expirationSeconds
    ) {
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("app.refresh-token.expiration-seconds는 양수여야 합니다.");
        }
        this.refreshSessionRepository = refreshSessionRepository;
        this.expirationSeconds = expirationSeconds;
    }

    @Transactional
    public IssuedRefreshToken issue(User user) {
        String rawToken = generateToken();
        Instant expiresAt = Instant.now().plusSeconds(expirationSeconds);
        refreshSessionRepository.save(new RefreshSession(
                user,
                hash(rawToken),
                UUID.randomUUID(),
                expiresAt
        ));
        return new IssuedRefreshToken(rawToken, expirationSeconds);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public RotatedRefreshToken rotate(String rawToken) {
        Instant now = Instant.now();
        RefreshSession current = refreshSessionRepository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(RefreshTokenService::invalidRefreshToken);

        if (current.isRevoked()) {
            refreshSessionRepository.revokeActiveFamily(current.getTokenFamilyId(), now);
            throw new AuthException(
                    HttpStatus.UNAUTHORIZED,
                    "이미 사용된 Refresh Token입니다. 다시 로그인해 주세요."
            );
        }
        if (current.isExpired(now)) {
            current.revoke(now);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Refresh Token이 만료되었습니다.");
        }

        String replacement = generateToken();
        String replacementHash = hash(replacement);
        current.rotateTo(replacementHash, now);
        refreshSessionRepository.save(new RefreshSession(
                current.getUser(),
                replacementHash,
                current.getTokenFamilyId(),
                current.getExpiresAt()
        ));

        long remainingSeconds = Math.max(0, Duration.between(now, current.getExpiresAt()).toSeconds());
        return new RotatedRefreshToken(current.getUser(), replacement, remainingSeconds);
    }

    @Transactional
    public void revokeFamily(String rawToken) {
        Instant now = Instant.now();
        refreshSessionRepository.findByTokenHashForUpdate(hash(rawToken))
                .ifPresent(session -> refreshSessionRepository.revokeActiveFamily(
                        session.getTokenFamilyId(),
                        now
                ));
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return BASE64_URL_ENCODER.encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private static AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다.");
    }

    public record IssuedRefreshToken(String token, long expiresIn) {
    }

    public record RotatedRefreshToken(User user, String token, long expiresIn) {
    }
}
