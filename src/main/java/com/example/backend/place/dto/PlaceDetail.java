package com.example.backend.place.dto;

import com.example.backend.place.domain.Place;

public record PlaceDetail(
        Long id,
        String title,
        String district,
        String category,
        String categoryLabel,
        String imageUrl,
        boolean curated,
        boolean bookmarked,
        String description,
        String address,
        Double latitude,
        Double longitude
) {
    public static PlaceDetail of(Place place, boolean bookmarked) {
        return new PlaceDetail(
                place.getId(),
                place.getTitle(),
                place.getDistrict(),
                place.getCategory().name(),
                place.getCategory().getLabel(),
                place.getImageUrl(),
                place.isCurated(),
                bookmarked,
                place.getDescription(),
                place.getAddress(),
                place.getLatitude(),
                place.getLongitude()
        );
    }
}
