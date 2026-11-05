package com.example.backend.shortform;

import com.example.backend.auth.AuthService;
import com.example.backend.common.ApiException;
import com.example.backend.shortform.ai.AiRenderClient;
import com.example.backend.shortform.ai.AiRenderException;
import com.example.backend.shortform.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.example.backend.shortform.ShortformDtos.*;

@Service
@Transactional(readOnly = true)
public class ShortformService {
    private static final Logger log = LoggerFactory.getLogger(ShortformService.class);
    static final Set<RenderStatus> ACTIVE = Set.of(RenderStatus.QUEUED, RenderStatus.SUBMITTING, RenderStatus.PROCESSING);
    /** AI 서버가 돌려주는 검토용 미디어 경로. 백엔드 서명 URL로 바꿔서 내보낸다. */
    private static final Pattern AI_MEDIA_PATH = Pattern.compile(
            "^/api/v1/runs/[^/?]+/media/([A-Za-z0-9_-]{1,64})(/poster)?(\\?.*)?$");
    private static final Set<String> MEDIA_FIELDS = Set.of("image_url", "video_url");
    private static final Set<String> DETAIL_FIELDS = Set.of(
            "issues", "warnings", "changes", "base_version", "latest_version");
    private final ShortformProjectRepository projects;
    private final ShortformAssetRepository assets;
    private final RenderJobRepository jobs;
    private final AuthService auth;
    private final MediaStorage storage;
    private final AiRenderClient ai;
    private final RenderStateService state;
    private final ShortformUrlSigner signer;
    private final ObjectMapper mapper;
    private final int dailyRenderLimit;

    public ShortformService(ShortformProjectRepository projects, ShortformAssetRepository assets,
                            RenderJobRepository jobs, AuthService auth, MediaStorage storage, AiRenderClient ai) {
        this(projects, assets, jobs, auth, storage, ai, null, null, JsonMapper.builder().build(), 0);
    }

    @Autowired
    public ShortformService(ShortformProjectRepository projects, ShortformAssetRepository assets,
                            RenderJobRepository jobs, AuthService auth, MediaStorage storage, AiRenderClient ai,
                            RenderStateService state, ShortformUrlSigner signer, ObjectMapper mapper,
                            @Value("${app.shortform.daily-render-limit:5}") int dailyRenderLimit) {
        if (dailyRenderLimit < 0) throw new IllegalArgumentException("daily-render-limit must be 0 or more");
        this.projects = projects; this.assets = assets; this.jobs = jobs;
        this.auth = auth; this.storage = storage; this.ai = ai;
        this.state = state; this.signer = signer; this.mapper = mapper;
        this.dailyRenderLimit = dailyRenderLimit;
    }

    public Options options() {
        return new Options(Arrays.stream(VideoStyle.values()).map(v -> new Option(v.name(), v.label())).toList(),
                Arrays.stream(VideoMood.values()).map(v -> new Option(v.name(), v.label())).toList(),
                5, 20, 10L * 1024 * 1024, 100L * 1024 * 1024, ai.isAvailable());
    }

    @Transactional
    public ProjectView create(String authorization, CreateRequest request) {
        var missionIds = request.missionIds() == null ? List.<Long>of() : List.copyOf(new LinkedHashSet<>(request.missionIds()));
        if (missionIds.size() > 20) throw ApiException.badRequest("촬영 미션은 최대 20개까지 연결할 수 있습니다.");
        if (request.courseId() == null && !missionIds.isEmpty()) throw ApiException.badRequest("촬영 미션을 연결하려면 코스를 함께 지정해주세요.");
        var project = projects.save(new ShortformProject(owner(authorization), request.title(), request.courseId(), missionIds));
        return view(project);
    }

    public ProjectPage list(String authorization, int page, int size) {
        UUID ownerId = owner(authorization);
        if (page < 0 || page > 10000 || size < 1 || size > 50) throw ApiException.badRequest("페이지 범위를 확인해주세요.");
        var result = projects.findByOwnerId(ownerId,
                PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id"))));
        return new ProjectPage(result.getContent().stream().map(this::view).toList(), page, size,
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    public ProjectView get(String authorization, UUID id) { return view(owned(authorization, id, false)); }

    @Transactional
    public List<AssetView> upload(String authorization, UUID id, UUID missionId, List<MultipartFile> files) {
        var project = owned(authorization, id, true);
        requireEditable(project.id);
        var existing = assets.findByProjectIdOrderByPositionAsc(id);
        if (files == null || files.isEmpty() || files.size() + existing.size() > 20)
            throw ApiException.badRequest("프로젝트에는 촬영 소스를 1~20개 등록할 수 있습니다.");
        var writtenKeys = new ArrayList<String>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) writtenKeys.forEach(ShortformService.this::deleteQuietly);
            }
        });
        int position = existing.stream().mapToInt(a -> a.position).max().orElse(-1) + 1;
        var result = new ArrayList<AssetView>();
        for (var file : files) {
            var stored = storage.store(file);
            writtenKeys.add(stored.storageKey());
            result.add(AssetView.from(assets.save(new ShortformAsset(id, missionId, position++, stored))));
        }
        return List.copyOf(result);
    }

    @Transactional
    public void deleteAsset(String authorization, UUID id, UUID assetId) {
        owned(authorization, id, true);
        requireEditable(id);
        var asset = assets.findByIdAndProjectId(assetId, id).orElseThrow(() -> ApiException.notFound("촬영 소스를 찾을 수 없습니다."));
        assets.delete(asset);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { deleteQuietly(asset.storageKey); }
        });
    }

    public record Download(Resource resource, String contentType, String filename) {}
    public Download download(String authorization, UUID id, UUID assetId) {
        owned(authorization, id, false);
        var asset = assets.findByIdAndProjectId(assetId, id).orElseThrow(() -> ApiException.notFound("촬영 소스를 찾을 수 없습니다."));
        return new Download(storage.load(asset.storageKey), asset.contentType, asset.originalFilename);
    }

    @Transactional
    public RenderView render(String authorization, UUID id, UUID key, RenderRequest request) {
        var project = owned(authorization, id, true);
        var previous = jobs.findByProjectIdAndIdempotencyKey(id, key);
        if (previous.isPresent()) {
            if (!previous.get().sameRequest(request)) throw conflict("같은 요청 키로 다른 제작 옵션을 보낼 수 없습니다.");
            return renderView(previous.get());
        }
        if (!ai.isConfigured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "영상 제작 서버가 아직 연결되지 않았습니다.");
        if (jobs.existsByProjectIdAndStatusIn(id, ACTIVE)) throw conflict("이미 영상 제작이 진행 중입니다.");
        if (request.assetIds() == null || request.assetIds().size() < 5 || request.assetIds().size() > 20
                || new HashSet<>(request.assetIds()).size() != request.assetIds().size())
            throw ApiException.badRequest("중복 없이 촬영 소스를 5~20개 선택해주세요.");
        var allowed = new HashSet<>(assets.findByProjectIdOrderByPositionAsc(id).stream().map(a -> a.id).toList());
        if (!allowed.containsAll(request.assetIds())) throw ApiException.badRequest("이 프로젝트에 등록된 촬영 소스만 사용할 수 있습니다.");
        // 가입한 누구나 GPU와 유료 TTS를 쓰게 되므로 사용자별로 하루 제작 횟수를 제한한다.
        if (dailyRenderLimit > 0 && jobs.countCreatedByOwnerSince(project.ownerId,
                Instant.now().minus(Duration.ofDays(1))) >= dailyRenderLimit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RENDER_LIMIT_EXCEEDED",
                    "영상 제작은 하루 " + dailyRenderLimit + "번까지 요청할 수 있습니다.");
        }
        return renderView(jobs.save(new RenderJob(id, key, request)));
    }

    public RenderView getRender(String authorization, UUID id, UUID renderId) {
        owned(authorization, id, false);
        return renderView(ownedJob(id, renderId));
    }

    // ── 검토: 스토리보드 조회·수정·재생성·승인 ─────────────────────────────────
    // AI 서버 호출 중 DB 연결과 잠금을 잡지 않도록 트랜잭션 밖에서 실행한다.

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public JsonNode storyboard(String authorization, UUID id, UUID renderId, Integer version) {
        owned(authorization, id, false);
        var job = ownedJob(id, renderId);
        if (job.externalJobId == null || job.phase == RenderPhase.DRAFTING || job.phase == null)
            throw conflict("STORYBOARD_NOT_READY", "아직 스토리보드를 만드는 중입니다.");
        JsonNode body = unwrap(call(() -> ai.getStoryboard(job.externalJobId, version)));
        rewriteMediaUrls(body, id, renderId);
        return body;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public JsonNode saveStoryboard(String authorization, UUID id, UUID renderId, JsonNode edit) {
        owned(authorization, id, false);
        var job = reviewable(id, renderId);
        return unwrap(call(() -> ai.saveStoryboard(job.externalJobId, json(edit))));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RenderView regenStoryboard(String authorization, UUID id, UUID renderId, JsonNode request) {
        owned(authorization, id, false);
        var job = reviewable(id, renderId);
        JsonNode payload = request == null || request.isNull() ? mapper.createObjectNode() : request;
        unwrap(call(() -> ai.regenStoryboard(job.externalJobId, json(payload))));
        if (state != null) state.markReviewBusy(job.id);
        return renderView(ownedJob(id, renderId));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RenderView approve(String authorization, UUID id, UUID renderId, ApproveRequest request) {
        owned(authorization, id, false);
        var job = reviewable(id, renderId);
        boolean accept = Boolean.TRUE.equals(request.acceptWarnings());
        // 승인은 AI 서버에서 같은 버전에 대해 멱등이고, 생성 시작은 이미 진행 중이면 그대로 둔다.
        // 그래서 중간에 실패해도 같은 요청을 다시 보내면 된다.
        unwrap(call(() -> ai.approve(job.externalJobId, request.version(), accept)));
        call(() -> { ai.startRender(job.externalJobId); return null; });
        if (state != null) state.markRendering(job.id);
        return renderView(ownedJob(id, renderId));
    }

    // ── 서명 URL로 여는 미디어 ────────────────────────────────────────────────

    public record MediaRequest(UUID projectId, UUID renderId, String photoId, boolean poster) {
        String path() {
            return "/api/shortforms/" + projectId + "/renders/" + renderId + "/media/" + photoId
                    + (poster ? "/poster" : "");
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiRenderClient.MediaStream openMedia(MediaRequest request, Long expires, String sig, String range) {
        requireSignature(request.path(), expires, sig);
        var job = ownedJob(request.projectId(), request.renderId());
        if (job.externalJobId == null) throw ApiException.notFound("자료를 찾을 수 없습니다.");
        return call(() -> ai.openMedia(job.externalJobId, request.photoId(), request.poster(), range));
    }

    public Resource resultVideo(UUID id, UUID renderId, Long expires, String sig) {
        requireSignature(videoPath(id, renderId), expires, sig);
        var job = ownedJob(id, renderId);
        if (job.resultStorageKey == null) throw ApiException.notFound("완성된 영상이 없습니다.");
        return storage.load(job.resultStorageKey);
    }

    // ── 내부 ─────────────────────────────────────────────────────────────────

    private RenderView renderView(RenderJob job) {
        String videoUrl = job.videoUrl;
        if (job.resultStorageKey != null && signer != null) videoUrl = signer.sign(videoPath(job.projectId, job.id));
        return RenderView.from(job, videoUrl);
    }

    private static String videoPath(UUID projectId, UUID renderId) {
        return "/api/shortforms/" + projectId + "/renders/" + renderId + "/video";
    }

    private void requireSignature(String path, Long expires, String sig) {
        if (signer == null || !signer.verify(path, expires, sig))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "URL_EXPIRED", "URL이 만료됐거나 올바르지 않습니다.");
    }

    private RenderJob ownedJob(UUID projectId, UUID renderId) {
        return jobs.findById(renderId).filter(j -> j.projectId.equals(projectId))
                .orElseThrow(() -> ApiException.notFound("제작 요청을 찾을 수 없습니다."));
    }

    private RenderJob reviewable(UUID projectId, UUID renderId) {
        var job = ownedJob(projectId, renderId);
        if (job.status.terminal() || job.phase != RenderPhase.AWAITING_REVIEW || job.externalJobId == null) {
            throw conflict("NOT_IN_REVIEW", job.phase == RenderPhase.RENDERING
                    ? "영상을 만드는 중에는 스토리보드를 바꿀 수 없습니다."
                    : "검토 단계의 제작만 수정·승인할 수 있습니다.");
        }
        return job;
    }

    private byte[] json(JsonNode node) {
        return mapper.writeValueAsBytes(node);
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (AiRenderException error) {
            throw switch (error.getCode()) {
                case "AI_NOT_CONFIGURED", "AI_UNAVAILABLE" -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        error.getCode(), "영상 제작 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.");
                case "AI_NOT_FOUND" -> ApiException.notFound("자료를 찾을 수 없습니다.");
                case "AI_APPROVAL_REQUIRED" -> conflict("APPROVAL_REQUIRED", error.getMessage());
                default -> new ApiException(HttpStatus.BAD_GATEWAY, "AI_BAD_GATEWAY",
                        "영상 제작 서버가 요청을 처리하지 못했습니다.");
            };
        }
    }

    /** AI 서버의 검토 응답. 오류는 코드·메시지와 사용자가 고칠 수 있는 세부 정보만 전달한다. */
    private JsonNode unwrap(AiRenderClient.Proxied response) {
        JsonNode body;
        try {
            body = response.body() == null || response.body().length == 0 ? null : mapper.readTree(response.body());
        } catch (RuntimeException error) {
            body = null;
        }
        if (response.ok()) {
            if (body == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_BAD_GATEWAY", "영상 제작 서버의 응답이 올바르지 않습니다.");
            return body;
        }
        if (response.status() == 404) throw ApiException.notFound("스토리보드를 찾을 수 없습니다.");
        JsonNode error = body == null ? null : body.get("error");
        String code = error != null && error.path("code").isString() ? error.path("code").asString() : "REVIEW_REJECTED";
        String message = error != null && error.path("message").isString()
                ? limit(error.path("message").asString(), 500) : "스토리보드를 처리하지 못했습니다.";
        var details = new LinkedHashMap<String, Object>();
        if (error != null) {
            for (String field : DETAIL_FIELDS) {
                JsonNode value = error.get(field);
                if (value != null && !value.isNull()) details.put(field, value);
            }
        } else if (body != null && body.get("detail") != null) {
            details.put("detail", body.get("detail"));
        }
        HttpStatus status = switch (response.status()) {
            case 409 -> HttpStatus.CONFLICT;
            case 422 -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_REQUEST;
        };
        throw new ShortformReviewException(status, code.toUpperCase(Locale.ROOT), message, details);
    }

    /** 검토 화면의 사진·영상 URL을 이 서버의 서명 URL로 바꾼다. 알 수 없는 URL은 내보내지 않는다. */
    private void rewriteMediaUrls(JsonNode node, UUID projectId, UUID renderId) {
        if (node instanceof ObjectNode object) {
            var updates = new LinkedHashMap<String, String>();
            for (var entry : object.properties()) {
                if (MEDIA_FIELDS.contains(entry.getKey())) {
                    JsonNode value = entry.getValue();
                    String rewritten = null;
                    if (value != null && value.isString()) {
                        Matcher matcher = AI_MEDIA_PATH.matcher(value.asString());
                        if (matcher.matches() && signer != null) {
                            rewritten = signer.sign(new MediaRequest(projectId, renderId, matcher.group(1),
                                    matcher.group(2) != null).path());
                        }
                    }
                    updates.put(entry.getKey(), rewritten);
                } else {
                    rewriteMediaUrls(entry.getValue(), projectId, renderId);
                }
            }
            updates.forEach((field, value) -> {
                if (value == null) object.putNull(field);
                else object.put(field, value);
            });
        } else if (node != null && node.isArray()) {
            for (JsonNode child : node) rewriteMediaUrls(child, projectId, renderId);
        }
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private UUID owner(String authorization) { return auth.getCurrentUser(authorization).id(); }
    private ShortformProject owned(String authorization, UUID id, boolean lock) {
        UUID ownerId = owner(authorization);
        return (lock ? projects.findLockedById(id) : projects.findById(id))
                .filter(p -> p.ownerId.equals(ownerId)).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
    }
    private ProjectView view(ShortformProject project) {
        return new ProjectView(project.id, project.title,
                assets.findByProjectIdOrderByPositionAsc(project.id).stream().map(AssetView::from).toList(),
                jobs.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.id).map(this::renderView).orElse(null), project.createdAt,
                project.courseId, project.missionIds());
    }
    private void requireEditable(UUID id) {
        if (jobs.existsByProjectId(id)) throw conflict("제작을 요청한 프로젝트의 원본은 변경할 수 없습니다. 새 프로젝트를 만들어주세요.");
    }
    private void deleteQuietly(String key) {
        try { storage.delete(key); } catch (RuntimeException error) { log.warn("Failed to remove shortform source file; cleanup required."); }
    }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
