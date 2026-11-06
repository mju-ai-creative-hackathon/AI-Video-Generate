package com.example.backend.shortform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 만료 시간이 있는 미디어 URL. iOS의 AVPlayer·이미지 로더는 인증 헤더를 붙이기 어려우므로
 * 결과 영상과 검토용 미디어는 서명된 URL로 제공한다. 키는 JWT 비밀값에서 용도별로 만든다.
 */
@Component
public class ShortformUrlSigner {
    private final byte[] key;
    private final long ttlSeconds;

    public ShortformUrlSigner(@Value("${app.jwt.secret}") String jwtSecret,
                              @Value("${app.shortform.media-url-ttl-seconds:3600}") long ttlSeconds) {
        if (ttlSeconds < 60 || ttlSeconds > 7 * 24 * 3600) {
            throw new IllegalArgumentException("media-url-ttl-seconds must be between 60 seconds and 7 days.");
        }
        this.key = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8), "shortform-media-url-v1");
        this.ttlSeconds = ttlSeconds;
    }

    /** 요청 기준 절대 URL. 요청 밖(워커·테스트)에서는 상대 경로를 돌려준다. */
    public String sign(String path) {
        long expires = Instant.now().getEpochSecond() + ttlSeconds;
        String query = "expires=" + expires + "&sig=" + signature(path, expires);
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).query(query).build().toUriString();
        } catch (IllegalStateException noRequest) {
            return path + "?" + query;
        }
    }

    public boolean verify(String path, Long expires, String sig) {
        if (expires == null || sig == null || expires < Instant.now().getEpochSecond()) return false;
        byte[] expected = signature(path, expires).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.US_ASCII));
    }

    private String signature(String path, long expires) {
        return HexFormat.of().formatHex(hmac(key, path + "\n" + expires));
    }

    private static byte[] hmac(byte[] secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
