package com.example.backend.community;

import com.example.backend.auth.AuthService;
import com.example.backend.auth.domain.User;
import com.example.backend.auth.domain.UserRepository;
import com.example.backend.auth.exception.AuthException;
import com.example.backend.common.ApiException;
import com.example.backend.place.dto.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.example.backend.community.CommunityCommentDtos.*;

@Service
@Transactional(readOnly = true)
public class CommunityCommentService {
    private final CommunityCommentRepository comments;
    private final CommunityPostRepository posts;
    private final UserRepository users;
    private final AuthService auth;

    public CommunityCommentService(CommunityCommentRepository comments, CommunityPostRepository posts,
                                   UserRepository users, AuthService auth) {
        this.comments = comments; this.posts = posts; this.users = users; this.auth = auth;
    }

    public PageResponse<CommentView> list(UUID postId, int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw ApiException.badRequest("page는 0 이상, size는 1~50이어야 합니다.");
        visiblePost(postId, false);
        var result = comments.findVisibleByPostId(postId, PageRequest.of(page, size,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
        var authors = users.findAllById(result.getContent().stream().map(CommunityComment::getAuthorId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        var content = result.getContent().stream().filter(c -> authors.containsKey(c.getAuthorId()))
                .map(c -> view(c, authors.get(c.getAuthorId()))).toList();
        return PageResponse.of(result, content);
    }

    @Transactional
    public CommentView create(String authorization, UUID postId, WriteRequest request) {
        User author = lockedUser(authorization);
        String content = cleanContent(request.content());
        var post = visiblePost(postId, true);
        return view(comments.saveAndFlush(new CommunityComment(post, author.getId(), content)), author);
    }

    @Transactional
    public CommentView update(String authorization, UUID postId, UUID commentId, WriteRequest request) {
        User author = lockedUser(authorization);
        String content = cleanContent(request.content());
        visiblePost(postId, true);
        var comment = ownComment(postId, commentId, author.getId());
        comment.updateContent(content);
        comments.flush();
        return view(comment, author);
    }

    @Transactional
    public void delete(String authorization, UUID postId, UUID commentId) {
        User author = lockedUser(authorization);
        visiblePost(postId, true);
        comments.delete(ownComment(postId, commentId, author.getId()));
    }

    private CommunityComment ownComment(UUID postId, UUID commentId, UUID authorId) {
        return comments.findLockedByIdAndPostId(commentId, postId).filter(c -> c.getAuthorId().equals(authorId))
                .orElseThrow(() -> ApiException.notFound("댓글을 찾을 수 없습니다."));
    }

    private User lockedUser(String authorization) {
        UUID userId = auth.getCurrentUser(authorization).id();
        return users.lockById(userId)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
    }

    private CommunityPost visiblePost(UUID postId, boolean lock) {
        return (lock ? posts.findLockedById(postId) : posts.findById(postId))
                .filter(p -> users.existsById(p.authorId))
                .orElseThrow(() -> ApiException.notFound("게시물을 찾을 수 없습니다."));
    }

    private static CommentView view(CommunityComment comment, User author) {
        return new CommentView(comment.getId(), comment.getPost().id, comment.getContent(),
                new CommunityDtos.Author(author.getId(), author.getName(), author.getProfileImageUrl()),
                comment.getCreatedAt(), comment.getUpdatedAt());
    }

    private static String cleanContent(String content) {
        if (content == null || content.isBlank() || content.length() > 1000 || content.indexOf('\0') >= 0) {
            throw ApiException.badRequest("댓글은 공백이 아닌 1~1,000자로 입력해 주세요.");
        }
        return content.strip();
    }
}
