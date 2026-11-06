package com.example.backend.shortform.ai;

/** Safe provider failure. Never contains upstream response bodies or credentials. */
public class AiRenderException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public AiRenderException(String code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
}
