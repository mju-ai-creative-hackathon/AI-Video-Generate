package com.example.backend.auth;

import com.example.backend.auth.exception.AuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JwtTokenProvider {
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String HEADER = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
    private static final Pattern PAYLOAD_PATTERN = Pattern.compile(
            "\\{\"sub\":\"([0-9a-fA-F-]{36})\",\"iat\":(\\d+),\"exp\":(\\d+),"
                    + "\"jti\":\"[0-9a-fA-F-]{36}\"\\}"
    );

    private final byte[] secret;
    private final long expirationSeconds;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-seconds}") long expirationSeconds
    ) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.jwt.secret은 32바이트 이상이어야 합니다.");
        }
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("app.jwt.expiration-seconds는 양수여야 합니다.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expirationSeconds = expirationSeconds;
    }

    public String createToken(UUID userId) {
        Instant now = Instant.now();
        String claims = "{\"sub\":\"" + userId + "\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + now.plusSeconds(expirationSeconds).getEpochSecond()
                + ",\"jti\":\"" + UUID.randomUUID() + "\"}";
        String payload = encode(claims);
        String unsignedToken = HEADER + "." + payload;
        return unsignedToken + "." + encode(sign(unsignedToken));
    }

    public UUID parseUserId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw invalidToken();
            }

            byte[] expectedSignature = sign(parts[0] + "." + parts[1]);
            byte[] actualSignature = BASE64_URL_DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                throw invalidToken();
            }

            String payload = new String(BASE64_URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            Matcher matcher = PAYLOAD_PATTERN.matcher(payload);
            if (!matcher.matches() || Long.parseLong(matcher.group(3)) <= Instant.now().getEpochSecond()) {
                throw invalidToken();
            }
            return UUID.fromString(matcher.group(1));
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw invalidToken();
        }
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("토큰에 서명할 수 없습니다.", e);
        }
    }

    private static String encode(String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] value) {
        return BASE64_URL_ENCODER.encodeToString(value);
    }

    private static AuthException invalidToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 토큰입니다.");
    }
}
