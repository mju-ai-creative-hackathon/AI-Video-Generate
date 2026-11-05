package com.example.backend.shortform;

public enum RenderStatus {
    QUEUED, SUBMITTING, PROCESSING, SUCCEEDED, FAILED;
    public boolean terminal() { return this == SUCCEEDED || this == FAILED; }
}
