package com.example.backend.community;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CommunityCommentRepository extends JpaRepository<CommunityComment, UUID> {
    @Query("select c from CommunityComment c where c.post.id = :postId "
            + "and exists (select u.id from User u where u.id = c.authorId)")
    Page<CommunityComment> findVisibleByPostId(@Param("postId") UUID postId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommunityComment c where c.id = :id and c.post.id = :postId")
    Optional<CommunityComment> findLockedByIdAndPostId(@Param("id") UUID id, @Param("postId") UUID postId);

    void deleteByAuthorId(UUID authorId);

    @Query("select count(c) from CommunityComment c where c.post.id = :postId "
            + "and exists (select u.id from User u where u.id = c.authorId)")
    long countVisibleByPostId(@Param("postId") UUID postId);
}
