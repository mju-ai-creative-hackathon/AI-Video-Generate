package com.example.backend.shortform;

import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URI;
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
}
