package com.example.backend.course.ai.gemini;

/** 사용자에게 그대로 보여줘도 안전한 메시지만 담는다. 업스트림 응답 본문이나 키는 넣지 않는다. */
public class CourseGenerationException extends RuntimeException {
    public CourseGenerationException(String message) {
        super(message);
    }

    public CourseGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
