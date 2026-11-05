package com.example.backend.place.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceRepository extends JpaRepository<Place, Long> {
    Page<Place> findByCategory(PlaceCategory category, Pageable pageable);

    Page<Place> findByCurated(boolean curated, Pageable pageable);

    Page<Place> findByCategoryAndCurated(PlaceCategory category, boolean curated, Pageable pageable);
}
