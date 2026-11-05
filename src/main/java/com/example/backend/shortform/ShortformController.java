package com.example.backend.shortform;

import com.example.backend.shortform.ai.AiRenderClient;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.backend.shortform.ShortformDtos.*;

@RestController
@RequestMapping("/api/shortforms")
public class ShortformController {
    private final ShortformService service;
    public ShortformController(ShortformService service) { this.service = service; }
    @GetMapping("/options") public Options options() { return service.options(); }
    @PostMapping public ResponseEntity<ProjectView> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateRequest request) {
        var project = service.create(authorization, request);
        return ResponseEntity.created(URI.create("/api/shortforms/" + project.id())).body(project);
    }
    @GetMapping public ProjectPage list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(authorization, page, size);
    }
    @GetMapping("/{id}") public ProjectView get(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable UUID id) { return service.get(authorization, id); }
    @PostMapping(value = "/{id}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<AssetView>> upload(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @RequestParam(required = false) UUID missionId,
            @RequestPart("files") List<MultipartFile> files) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(authorization, id, missionId, files));
    }
    @DeleteMapping("/{id}/assets/{assetId}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable UUID id, @PathVariable UUID assetId) {
        service.deleteAsset(authorization, id, assetId); return ResponseEntity.noContent().build();
    }
    @GetMapping("/{id}/assets/{assetId}/content")
    public ResponseEntity<Resource> content(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable UUID id, @PathVariable UUID assetId) {
        var download = service.download(authorization, id, assetId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(download.filename(), StandardCharsets.UTF_8).build().toString())
                .body(download.resource());
    }
    @PostMapping("/{id}/renders")
    public ResponseEntity<RenderView> render(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RenderRequest request) {
        var job = service.render(authorization, id, key, request);
        return ResponseEntity.accepted().location(URI.create("/api/shortforms/" + id + "/renders/" + job.id())).body(job);
    }
    @GetMapping("/{id}/renders/{renderId}")
    public RenderView renderStatus(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable UUID id, @PathVariable UUID renderId) {
        return service.getRender(authorization, id, renderId);
    }

    // ── 검토 ──────────────────────────────────────────────────────────────────
    @GetMapping("/{id}/renders/{renderId}/storyboard")
    public JsonNode storyboard(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable UUID id, @PathVariable UUID renderId,
                               @RequestParam(required = false) Integer version) {
        return service.storyboard(authorization, id, renderId, version);
    }
    @PutMapping("/{id}/renders/{renderId}/storyboard")
    public JsonNode saveStoryboard(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable UUID id, @PathVariable UUID renderId, @RequestBody JsonNode edit) {
        return service.saveStoryboard(authorization, id, renderId, edit);
    }
    @PostMapping("/{id}/renders/{renderId}/storyboard/regen")
    public ResponseEntity<RenderView> regenStoryboard(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @PathVariable UUID renderId, @RequestBody(required = false) JsonNode request) {
        return ResponseEntity.accepted().body(service.regenStoryboard(authorization, id, renderId, request));
    }
    @PostMapping("/{id}/renders/{renderId}/approve")
    public RenderView approve(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @PathVariable UUID id, @PathVariable UUID renderId,
                              @Valid @RequestBody ApproveRequest request) {
        return service.approve(authorization, id, renderId, request);
    }

    // ── 서명 URL (인증 헤더 없이 <img>, AVPlayer에서 연다) ──────────────────────
    @GetMapping("/{id}/renders/{renderId}/media/{photoId}")
    public ResponseEntity<StreamingResponseBody> media(@PathVariable UUID id, @PathVariable UUID renderId,
            @PathVariable String photoId, @RequestParam(required = false) Long expires,
            @RequestParam(required = false) String sig, @RequestHeader(value = "Range", required = false) String range) {
        return stream(service.openMedia(new ShortformService.MediaRequest(id, renderId, photoId, false),
                expires, sig, range));
    }
    @GetMapping("/{id}/renders/{renderId}/media/{photoId}/poster")
    public ResponseEntity<StreamingResponseBody> poster(@PathVariable UUID id, @PathVariable UUID renderId,
            @PathVariable String photoId, @RequestParam(required = false) Long expires,
            @RequestParam(required = false) String sig) {
        return stream(service.openMedia(new ShortformService.MediaRequest(id, renderId, photoId, true),
                expires, sig, null));
    }
    @GetMapping("/{id}/renders/{renderId}/video")
    public ResponseEntity<Resource> video(@PathVariable UUID id, @PathVariable UUID renderId,
                                          @RequestParam(required = false) Long expires,
                                          @RequestParam(required = false) String sig) {
        // Resource 본문이라 Spring이 Range 요청(탐색)을 처리한다.
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("shortform.mp4").build().toString())
                .body(service.resultVideo(id, renderId, expires, sig));
    }

    private static ResponseEntity<StreamingResponseBody> stream(AiRenderClient.MediaStream media) {
        var builder = ResponseEntity.status(media.status())
                .contentType(MediaType.parseMediaType(media.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (media.contentLength() != null) builder.contentLength(media.contentLength());
        if (media.contentRange() != null) builder.header(HttpHeaders.CONTENT_RANGE, media.contentRange());
        StreamingResponseBody body = output -> {
            try (InputStream input = media.body()) { input.transferTo(output); }
        };
        return builder.body(body);
    }
}
