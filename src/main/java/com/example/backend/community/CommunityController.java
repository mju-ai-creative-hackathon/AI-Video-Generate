package com.example.backend.community;

import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.example.backend.community.CommunityDtos.*;

@RestController
@RequestMapping("/api/community/posts")
public class CommunityController {
    private final CommunityService service;
    public CommunityController(CommunityService service) { this.service = service; }
    @GetMapping("/explore") public Feed explore(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) UUID seed,
            @RequestParam(required = false) Instant snapshot, @RequestParam(required = false) String cursor) {
        return service.explore(authorization, keyword, tag, size, seed, snapshot, cursor);
    }
    @GetMapping("/tags") public List<TagView> tags(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "15") int limit) { return service.tags(authorization, limit); }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PostView> create(@RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestPart("post") CreateRequest request, @RequestPart("video") MultipartFile video,
            @RequestPart("cover") MultipartFile cover) {
        var post = service.create(authorization, request, video, cover);
        return ResponseEntity.created(URI.create("/api/community/posts/" + post.id())).body(post);
    }
    @GetMapping("/{id}") public PostView get(@PathVariable UUID id) { return service.get(id); }
    @PatchMapping("/{id}") public PostView update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return service.update(authorization, id, request);
    }
    @PostMapping("/{id}/import")
    public ResponseEntity<ImportResult> importCourse(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        var result = service.importCourse(authorization, id);
        return ResponseEntity.created(URI.create("/api/diaries/" + result.diary().id())).body(result);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id) {
        service.delete(authorization, id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/{id}/media/{mediaId}") public ResponseEntity<Resource> media(@PathVariable UUID id, @PathVariable UUID mediaId) {
        var media = service.download(id, mediaId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(media.contentType())).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(media.filename(), StandardCharsets.UTF_8).build().toString())
                .body(media.resource());
    }
}
