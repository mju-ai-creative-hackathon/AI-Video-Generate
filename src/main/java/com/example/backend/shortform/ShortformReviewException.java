package com.example.backend.shortform;

import com.example.backend.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** AI 서버의 검토 오류(버전 충돌, 검증 오류, 경고 미확인)를 세부 정보와 함께 전달한다. */
public class ShortformReviewException extends ApiException {
    private final Map<String, Object> details;

    public ShortformReviewException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(status, code, message);
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public Map<String, Object> getDetails() { return details; }
}
