package com.example.backend.community;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, UUID> {}
