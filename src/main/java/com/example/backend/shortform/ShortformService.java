package com.example.backend.shortform;

import com.example.backend.auth.AuthService;
import com.example.backend.common.ApiException;
import com.example.backend.shortform.ai.AiRenderClient;
import com.example.backend.shortform.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import static com.example.backend.shortform.ShortformDtos.*;

@Service
@Transactional(readOnly = true)
public class ShortformService {
    private static final Logger log = LoggerFactory.getLogger(ShortformService.class);
    static final Set<RenderStatus> ACTIVE = Set.of(RenderStatus.QUEUED, RenderStatus.SUBMITTING, RenderStatus.PROCESSING);
    private final ShortformProjectRepository projects;
    private final ShortformAssetRepository assets;
    private final RenderJobRepository jobs;
    private final AuthService auth;
    private final MediaStorage storage;
    private final AiRenderClient ai;

    public ShortformService(ShortformProjectRepository projects, ShortformAssetRepository assets,
                            RenderJobRepository jobs, AuthService auth, MediaStorage storage, AiRenderClient ai) {
        this.projects = projects; this.assets = assets; this.jobs = jobs;
        this.auth = auth; this.storage = storage; this.ai = ai;
    }

    public Options options() {
        return new Options(Arrays.stream(VideoStyle.values()).map(v -> new Option(v.name(), v.label())).toList(),
                Arrays.stream(VideoMood.values()).map(v -> new Option(v.name(), v.label())).toList(),
                5, 20, 10L * 1024 * 1024, 100L * 1024 * 1024, ai.isConfigured());
    }

    @Transactional
    public ProjectView create(String authorization, CreateRequest request) {
        var project = projects.save(new ShortformProject(owner(authorization), request.title()));
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
        owned(authorization, id, true);
        var previous = jobs.findByProjectIdAndIdempotencyKey(id, key);
        if (previous.isPresent()) {
            if (!previous.get().sameRequest(request)) throw conflict("같은 요청 키로 다른 제작 옵션을 보낼 수 없습니다.");
            return RenderView.from(previous.get());
        }
        if (!ai.isConfigured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "영상 제작 서버가 아직 연결되지 않았습니다.");
        if (jobs.existsByProjectIdAndStatusIn(id, ACTIVE)) throw conflict("이미 영상 제작이 진행 중입니다.");
        if (request.assetIds() == null || request.assetIds().size() < 5 || request.assetIds().size() > 20
                || new HashSet<>(request.assetIds()).size() != request.assetIds().size())
            throw ApiException.badRequest("중복 없이 촬영 소스를 5~20개 선택해주세요.");
        var allowed = new HashSet<>(assets.findByProjectIdOrderByPositionAsc(id).stream().map(a -> a.id).toList());
        if (!allowed.containsAll(request.assetIds())) throw ApiException.badRequest("이 프로젝트에 등록된 촬영 소스만 사용할 수 있습니다.");
        return RenderView.from(jobs.save(new RenderJob(id, key, request)));
    }

    public RenderView getRender(String authorization, UUID id, UUID renderId) {
        owned(authorization, id, false);
        return jobs.findById(renderId).filter(j -> j.projectId.equals(id)).map(RenderView::from)
                .orElseThrow(() -> ApiException.notFound("제작 요청을 찾을 수 없습니다."));
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
                jobs.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.id).map(RenderView::from).orElse(null), project.createdAt);
    }
    private void requireEditable(UUID id) {
        if (jobs.existsByProjectId(id)) throw conflict("제작을 요청한 프로젝트의 원본은 변경할 수 없습니다. 새 프로젝트를 만들어주세요.");
    }
    private void deleteQuietly(String key) {
        try { storage.delete(key); } catch (RuntimeException error) { log.warn("Failed to remove shortform source file; cleanup required."); }
    }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message); }
}
