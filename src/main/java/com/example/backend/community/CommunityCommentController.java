package com.example.backend.community;

import com.example.backend.place.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
import static com.example.backend.community.CommunityCommentDtos.*;

@RestController
@RequestMapping("/api/community/posts/{postId}/comments")
public class CommunityCommentController {
    private final CommunityCommentService service;
    public CommunityCommentController(CommunityCommentService service) { this.service = service; }

    @GetMapping
    public PageResponse<CommentView> list(@PathVariable UUID postId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return service.list(postId, page, size);
    }

    @PostMapping
    public ResponseEntity<CommentView> create(@PathVariable UUID postId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody WriteRequest request) {
        var comment = service.create(authorization, postId, request);
        return ResponseEntity.created(URI.create("/api/community/posts/" + postId + "/comments/" + comment.id()))
                .body(comment);
    }

    @PatchMapping("/{commentId}")
    public CommentView update(@PathVariable UUID postId, @PathVariable UUID commentId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody WriteRequest request) {
        return service.update(authorization, postId, commentId, request);
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID postId, @PathVariable UUID commentId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        service.delete(authorization, postId, commentId);
        return ResponseEntity.noContent().build();
    }
}
