package com.example.backend.place;

import com.example.backend.auth.AuthService;
import com.example.backend.auth.domain.User;
import com.example.backend.auth.domain.UserRepository;
import com.example.backend.auth.exception.AuthException;
import com.example.backend.common.ApiException;
import com.example.backend.place.domain.Bookmark;
import com.example.backend.place.domain.BookmarkRepository;
import com.example.backend.place.domain.Place;
import com.example.backend.place.domain.PlaceCategory;
import com.example.backend.place.domain.PlaceRepository;
import com.example.backend.place.dto.BookmarkResponse;
import com.example.backend.place.dto.CategoryResponse;
import com.example.backend.place.dto.PageResponse;
import com.example.backend.place.dto.PlaceDetail;
import com.example.backend.place.dto.PlaceSummary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class PlaceService {
    static final int MAX_PAGE_SIZE = 50;
    private static final Sort PLACE_SORT = Sort.by(Sort.Order.asc("displayOrder"), Sort.Order.asc("id"));
    private static final Sort BOOKMARK_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final PlaceRepository placeRepository;
    private final BookmarkRepository bookmarkRepository;
    private final UserRepository userRepository;
    private final AuthService authService;

    public PlaceService(PlaceRepository placeRepository, BookmarkRepository bookmarkRepository,
                        UserRepository userRepository, AuthService authService) {
        this.placeRepository = placeRepository;
        this.bookmarkRepository = bookmarkRepository;
        this.userRepository = userRepository;
        this.authService = authService;
    }

    public List<CategoryResponse> getCategories() {
        List<CategoryResponse> categories = new ArrayList<>();
        categories.add(new CategoryResponse("ALL", "전체"));
        for (PlaceCategory category : PlaceCategory.values()) {
            categories.add(new CategoryResponse(category.name(), category.getLabel()));
        }
        return categories;
    }

    @Transactional(readOnly = true)
    public PageResponse<PlaceSummary> getPlaces(String categoryCode, Boolean curated, int page, int size,
                                                String authorizationHeader) {
        UUID userId = optionalUserId(authorizationHeader);
        PlaceCategory category = parseCategory(categoryCode);
        Pageable pageable = pageRequest(page, size, PLACE_SORT);

        Page<Place> places;
        if (category == null && curated == null) {
            places = placeRepository.findAll(pageable);
        } else if (category == null) {
            places = placeRepository.findByCurated(curated, pageable);
        } else if (curated == null) {
            places = placeRepository.findByCategory(category, pageable);
        } else {
            places = placeRepository.findByCategoryAndCurated(category, curated, pageable);
        }

        Set<Long> bookmarkedIds = bookmarkedIds(userId, places.getContent());
        List<PlaceSummary> content = places.getContent().stream()
                .map(place -> PlaceSummary.of(place, bookmarkedIds.contains(place.getId())))
                .toList();
        return PageResponse.of(places, content);
    }

    @Transactional(readOnly = true)
    public PlaceDetail getPlace(Long placeId, String authorizationHeader) {
        UUID userId = optionalUserId(authorizationHeader);
        Place place = placeRepository.findById(placeId).orElseThrow(PlaceService::placeNotFound);
        boolean bookmarked = userId != null && bookmarkRepository.existsByUserIdAndPlaceId(userId, placeId);
        return PlaceDetail.of(place, bookmarked);
    }

    public BookmarkResponse addBookmark(Long placeId, String authorizationHeader) {
        UUID userId = authService.getCurrentUser(authorizationHeader).id();
        Place place = placeRepository.findById(placeId).orElseThrow(PlaceService::placeNotFound);
        if (!bookmarkRepository.existsByUserIdAndPlaceId(userId, placeId)) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
            try {
                bookmarkRepository.saveAndFlush(new Bookmark(user, place));
            } catch (DataIntegrityViolationException e) {
                // 동시 요청으로 이미 저장된 경우: 멱등하게 성공 처리
            }
        }
        return new BookmarkResponse(placeId, true);
    }

    @Transactional
    public BookmarkResponse removeBookmark(Long placeId, String authorizationHeader) {
        UUID userId = authService.getCurrentUser(authorizationHeader).id();
        if (!placeRepository.existsById(placeId)) {
            throw placeNotFound();
        }
        bookmarkRepository.deleteByUserIdAndPlaceId(userId, placeId);
        return new BookmarkResponse(placeId, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<PlaceSummary> getBookmarks(int page, int size, String authorizationHeader) {
        UUID userId = authService.getCurrentUser(authorizationHeader).id();
        Page<Bookmark> bookmarks = bookmarkRepository.findWithPlaceByUserId(userId, pageRequest(page, size, BOOKMARK_SORT));
        List<PlaceSummary> content = bookmarks.getContent().stream()
                .map(bookmark -> PlaceSummary.of(bookmark.getPlace(), true))
                .toList();
        return PageResponse.of(bookmarks, content);
    }

    private UUID optionalUserId(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        return authService.getCurrentUser(authorizationHeader).id();
    }

    private Set<Long> bookmarkedIds(UUID userId, List<Place> places) {
        if (userId == null || places.isEmpty()) {
            return Set.of();
        }
        List<Long> placeIds = places.stream().map(Place::getId).toList();
        return new HashSet<>(bookmarkRepository.findBookmarkedPlaceIds(userId, placeIds));
    }

    private static PlaceCategory parseCategory(String code) {
        if (code == null || code.isBlank() || code.equalsIgnoreCase("ALL")) {
            return null;
        }
        try {
            return PlaceCategory.valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("지원하지 않는 카테고리입니다: " + code);
        }
    }

    private static Pageable pageRequest(int page, int size, Sort sort) {
        if (page < 0) {
            throw ApiException.badRequest("page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw ApiException.badRequest("size는 1 이상이어야 합니다.");
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), sort);
    }

    private static ApiException placeNotFound() {
        return ApiException.notFound("장소를 찾을 수 없습니다.");
    }
}
