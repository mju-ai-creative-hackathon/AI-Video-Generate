package com.example.backend.place.domain;

public enum PlaceCategory {
    RESTAURANT("맛집"),
    CAFE("카페"),
    CULTURE("전시·문화"),
    WALK("산책"),
    EVENT("이벤트");

    private final String label;

    PlaceCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
