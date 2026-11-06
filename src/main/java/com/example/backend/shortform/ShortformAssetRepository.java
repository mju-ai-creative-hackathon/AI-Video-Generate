package com.example.backend.shortform;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ShortformAssetRepository extends JpaRepository<ShortformAsset, UUID> {
    List<ShortformAsset> findByProjectIdOrderByPositionAsc(UUID projectId);
    Optional<ShortformAsset> findByIdAndProjectId(UUID id, UUID projectId);
}
