package com.example.backend.shortform;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 공통 오류 형식(code, message, errors, timestamp)에 검토 세부 정보(details)를 더한다. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShortformExceptionHandler {
    @ExceptionHandler(ShortformReviewException.class)
    public ResponseEntity<Map<String, Object>> handleReview(ShortformReviewException exception) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", exception.getCode());
        body.put("message", exception.getMessage());
        body.put("errors", Map.of());
        body.put("timestamp", Instant.now());
        body.put("details", exception.getDetails());
        return ResponseEntity.status(exception.getStatus()).body(body);
    }
}
