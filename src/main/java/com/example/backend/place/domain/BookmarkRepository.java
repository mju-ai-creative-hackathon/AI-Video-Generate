package com.example.backend.place.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    boolean existsByUserIdAndPlaceId(UUID userId, Long placeId);

    @Query("select b.place.id from Bookmark b where b.user.id = :userId and b.place.id in :placeIds")
    List<Long> findBookmarkedPlaceIds(@Param("userId") UUID userId, @Param("placeIds") Collection<Long> placeIds);

    @Query(value = "select b from Bookmark b join fetch b.place where b.user.id = :userId",
            countQuery = "select count(b) from Bookmark b where b.user.id = :userId")
    Page<Bookmark> findWithPlaceByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Modifying
    @Query("delete from Bookmark b where b.user.id = :userId and b.place.id = :placeId")
    int deleteByUserIdAndPlaceId(@Param("userId") UUID userId, @Param("placeId") Long placeId);
}
