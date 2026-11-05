package com.example.backend.community;

import com.example.backend.auth.AuthService;
import com.example.backend.auth.domain.User;
import com.example.backend.auth.domain.UserRepository;
import com.example.backend.common.ApiException;
import com.example.backend.shortform.storage.*;
import org.slf4j.*;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.example.backend.community.CommunityDtos.*;

@Service
@Transactional(readOnly = true)
public class CommunityService {
    private static final Logger log = LoggerFactory.getLogger(CommunityService.class);
    private final CommunityPostRepository posts;
    private final CommunityFeedRepository feed;
    private final AuthService auth;
    private final UserRepository users;
    private final MediaStorage storage;
    public CommunityService(CommunityPostRepository posts, CommunityFeedRepository feed, AuthService auth,
                            UserRepository users, MediaStorage storage) {
        this.posts = posts; this.feed = feed; this.auth = auth; this.users = users; this.storage = storage;
    }

    @Transactional
    public PostView create(String authorization, CreateRequest request, List<MultipartFile> files, MultipartFile cover) {
        UUID authorId = auth.getCurrentUser(authorization).id();
        rejectNul(request.title());
        rejectNul(request.caption());
        if (files == null || files.isEmpty() || files.size() > 10) throw ApiException.badRequest("사진·영상을 1~10개 첨부해주세요.");
        var tags = new LinkedHashSet<String>();
        if (request.tags() != null) request.tags().forEach(t -> tags.add(normalizeTag(t)));
        var post = new CommunityPost(authorId, request.title().strip(), request.caption() == null ? "" : request.caption().strip(), tags);
        var written = new ArrayList<String>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) written.forEach(CommunityService.this::deleteQuietly);
            }
        });
        for (int index = 0; index < files.size(); index++) {
            var stored = storage.store(files.get(index)); written.add(stored.storageKey());
            post.media.add(new CommunityMedia(post, index, stored));
        }
        if (cover != null) {
            var stored = storage.store(cover); written.add(stored.storageKey());
            if (stored.kind() != MediaKind.PHOTO) throw ApiException.badRequest("표지에는 사진만 사용할 수 있습니다.");
            post.media.add(new CommunityMedia(post, -1, stored));
        } else if (post.media.getFirst().kind == MediaKind.VIDEO) {
            throw ApiException.badRequest("첫 첨부가 동영상이면 그리드에 표시할 표지 사진을 함께 보내주세요.");
        }
        posts.saveAndFlush(post);
        return view(post, users.findById(authorId).orElseThrow(() -> ApiException.notFound("작성자를 찾을 수 없습니다.")));
    }

    public Feed explore(String authorization, String keyword, String tag, int size, UUID seed, Instant snapshot, String cursor) {
        UUID viewer = optionalViewer(authorization);
        if (size < 1 || size > 50) throw ApiException.badRequest("size는 1~50이어야 합니다.");
        if (keyword != null && keyword.length() > 100) throw ApiException.badRequest("검색어는 100자 이하여야 합니다.");
        rejectNul(keyword);
        if (cursor != null && (seed == null || snapshot == null || !cursor.matches("[a-f0-9]{32}[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
            throw ApiException.badRequest("다음 페이지에는 반환된 seed, snapshot, cursor를 함께 전달해주세요.");
        UUID selectedSeed = seed == null ? UUID.randomUUID() : seed;
        Instant selectedSnapshot = snapshot == null ? Instant.now() : snapshot;
        if (selectedSnapshot.isBefore(Instant.EPOCH) || selectedSnapshot.isAfter(Instant.now().plusSeconds(5)))
            throw ApiException.badRequest("조회 기준 시각은 1970년 이후부터 현재 시각까지 사용할 수 있습니다.");
        var entries = feed.explore(viewer, keyword == null ? null : keyword.strip(),
                tag == null ? null : normalizeTag(tag), selectedSeed, selectedSnapshot, cursor, size + 1);
        boolean hasNext = entries.size() > size;
        var selected = entries.stream().limit(size).toList();
        var byId = posts.findAllById(selected.stream().map(CommunityFeedRepository.Entry::id).toList())
                .stream().collect(Collectors.toMap(p -> p.id, Function.identity()));
        var authors = users.findAllById(byId.values().stream().map(p -> p.authorId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        var items = selected.stream().map(e -> byId.get(e.id())).filter(Objects::nonNull)
                .filter(p -> authors.containsKey(p.authorId)).map(p -> view(p, authors.get(p.authorId))).toList();
        return new Feed(items, selectedSeed, selectedSnapshot,
                hasNext ? selected.getLast().cursor() : null, hasNext);
    }

    public List<TagView> tags(String authorization, int limit) {
        UUID viewer = optionalViewer(authorization);
        if (limit < 1 || limit > 30) throw ApiException.badRequest("태그 개수는 1~30이어야 합니다.");
        return feed.popularTags(viewer, limit);
    }

    public PostView get(UUID id) {
        var post = existing(id);
        return view(post, users.findById(post.authorId).orElseThrow(() -> ApiException.notFound("게시물을 찾을 수 없습니다.")));
    }

    public record Download(Resource resource, String contentType, String filename) {}
    public Download download(UUID id, UUID mediaId) {
        var post = existing(id);
        var media = post.media.stream().filter(m -> m.id.equals(mediaId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("게시물 미디어를 찾을 수 없습니다."));
        return new Download(storage.load(media.storageKey), media.contentType, media.originalFilename);
    }

    @Transactional
    public void delete(String authorization, UUID id) {
        UUID viewer = auth.getCurrentUser(authorization).id();
        var post = existing(id);
        if (!post.authorId.equals(viewer)) throw ApiException.notFound("게시물을 찾을 수 없습니다.");
        var keys = post.media.stream().map(m -> m.storageKey).toList();
        posts.delete(post);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { keys.forEach(CommunityService.this::deleteQuietly); }
        });
    }
    private UUID optionalViewer(String authorization) {
        return authorization == null ? null : auth.getCurrentUser(authorization).id();
    }
    private CommunityPost existing(UUID id) {
        return posts.findById(id).filter(p -> users.existsById(p.authorId))
                .orElseThrow(() -> ApiException.notFound("게시물을 찾을 수 없습니다."));
    }
    private PostView view(CommunityPost post, User author) {
        var ordered = post.media.stream().sorted(Comparator.comparingInt(m -> m.position)).toList();
        String thumbnail = ordered.isEmpty() ? null : mediaUrl(post.id, ordered.getFirst().id);
        return new PostView(post.id, post.title, post.caption,
                new Author(author.getId(), author.getName(), author.getProfileImageUrl()),
                post.tags.stream().sorted().toList(), thumbnail,
                ordered.stream().filter(m -> m.position >= 0).map(m -> new MediaView(m.id, m.kind, m.contentType,
                        m.sizeBytes, mediaUrl(post.id, m.id))).toList(), post.createdAt);
    }
    private static String mediaUrl(UUID postId, UUID mediaId) { return "/api/community/posts/" + postId + "/media/" + mediaId; }
    private static String normalizeTag(String value) {
        if (value == null) throw ApiException.badRequest("태그를 확인해주세요.");
        String tag = Normalizer.normalize(value.strip().replaceFirst("^#", ""), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (!tag.matches("[\\p{L}\\p{N}_]{1,30}")) throw ApiException.badRequest("태그는 문자·숫자·밑줄 1~30자로 입력해주세요.");
        return tag;
    }
    private void deleteQuietly(String key) {
        try { storage.delete(key); } catch (RuntimeException error) { log.warn("Community media cleanup required."); }
    }
    private static void rejectNul(String value) {
        if (value != null && value.indexOf('\0') >= 0) throw ApiException.badRequest("입력에 지원하지 않는 문자가 포함되어 있습니다.");
    }
}
