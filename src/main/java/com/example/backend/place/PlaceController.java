package com.example.backend.place;

import com.example.backend.place.dto.BookmarkResponse;
import com.example.backend.place.dto.CategoryResponse;
import com.example.backend.place.dto.PageResponse;
import com.example.backend.place.dto.PlaceDetail;
import com.example.backend.place.dto.PlaceSummary;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/places")
public class PlaceController {
    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return placeService.getCategories();
    }

    @GetMapping
    public PageResponse<PlaceSummary> places(
            @RequestParam(defaultValue = "ALL") String category,
            @RequestParam(required = false) Boolean curated,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return placeService.getPlaces(category, curated, page, size, authorization);
    }

    @GetMapping("/bookmarks")
    public PageResponse<PlaceSummary> bookmarks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return placeService.getBookmarks(page, size, authorization);
    }

    @GetMapping("/{id}")
    public PlaceDetail place(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return placeService.getPlace(id, authorization);
    }

    @PutMapping("/{id}/bookmark")
    public BookmarkResponse addBookmark(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return placeService.addBookmark(id, authorization);
    }

    @DeleteMapping("/{id}/bookmark")
    public BookmarkResponse removeBookmark(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return placeService.removeBookmark(id, authorization);
    }
}
