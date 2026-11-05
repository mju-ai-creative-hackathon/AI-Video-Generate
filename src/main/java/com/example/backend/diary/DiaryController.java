package com.example.backend.diary;

import com.example.backend.place.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/diaries")
public class DiaryController {
    private final DiaryService service;
    public DiaryController(DiaryService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<DiaryDtos.Detail> start(@RequestHeader(value="Authorization", required=false) String auth,
                                                 @Valid @RequestBody DiaryDtos.Start request) {
        var result = service.start(auth, request);
        return ResponseEntity.created(URI.create("/api/diaries/" + result.id())).body(result);
    }
    @GetMapping("/active")
    public ResponseEntity<DiaryDtos.Detail> active(@RequestHeader(value="Authorization", required=false) String auth) {
        var result = service.active(auth);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }
    @PostMapping("/{id}/complete")
    public DiaryDtos.Detail complete(@RequestHeader(value="Authorization", required=false) String auth,
                                    @PathVariable UUID id, @Valid @RequestBody DiaryDtos.Complete request) {
        return service.complete(auth, id, request);
    }
    @GetMapping
    public PageResponse<DiaryDtos.Summary> list(@RequestHeader(value="Authorization", required=false) String auth,
                                              @RequestParam(defaultValue="0") int page,
                                              @RequestParam(defaultValue="10") int size) {
        return service.list(auth, page, size);
    }
    @GetMapping("/{id}")
    public DiaryDtos.Detail detail(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return service.get(auth, id);
    }
    @PatchMapping("/{id}")
    public DiaryDtos.Detail update(@RequestHeader(value="Authorization", required=false) String auth,
                                  @PathVariable UUID id, @Valid @RequestBody DiaryDtos.Update request) {
        return service.update(auth, id, request);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        service.delete(auth, id);
        return ResponseEntity.noContent().build();
    }
}
