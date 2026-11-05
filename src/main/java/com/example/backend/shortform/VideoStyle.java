package com.example.backend.shortform;

public enum VideoStyle {
    CINEMATIC("시네마틱"), DYNAMIC("다이내믹"), VLOG("브이로그"), MINIMAL("미니멀");
    private final String label;
    VideoStyle(String label) { this.label = label; }
    public String label() { return label; }
}
