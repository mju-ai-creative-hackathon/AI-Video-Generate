package com.example.backend.place.dto;

import com.example.backend.place.domain.Place;

public record PlaceSummary(
        Long id,
        String title,
        String district,
        String category,
        String categoryLabel,
        String imageUrl,
        boolean curated,
        boolean bookmarked
) {
    public static PlaceSummary of(Place place, boolean bookmarked) {
        return new PlaceSummary(
                place.getId(),
                place.getTitle(),
                place.getDistrict(),
                place.getCategory().name(),
                place.getCategory().getLabel(),
                place.getImageUrl(),
                place.isCurated(),
                bookmarked
        );
    }
}
