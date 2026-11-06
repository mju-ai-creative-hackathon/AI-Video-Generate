package com.example.backend.shortform;

public enum VideoMood {
    URBAN("도시적인"), ROMANTIC("로맨틱한"), CALM("차분한"), ENERGETIC("활기찬");
    private final String label;
    VideoMood(String label) { this.label = label; }
    public String label() { return label; }
}
