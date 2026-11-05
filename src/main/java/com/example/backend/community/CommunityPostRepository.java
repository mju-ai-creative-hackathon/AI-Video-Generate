package com.example.backend.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CommunityPost p where p.id = :id")
    Optional<CommunityPost> findLockedById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CommunityPost p where exists "
            + "(select c.id from CommunityComment c where c.post = p and c.authorId = :authorId) order by p.id")
    List<CommunityPost> lockCommentedPostsByAuthor(UUID authorId);
}
