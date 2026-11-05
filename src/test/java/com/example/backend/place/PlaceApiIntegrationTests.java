package com.example.backend.place;

import com.example.backend.auth.AuthService;
import com.example.backend.auth.domain.UserRepository;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.place.domain.BookmarkRepository;
import com.example.backend.place.domain.Place;
import com.example.backend.place.domain.PlaceCategory;
import com.example.backend.place.domain.PlaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlaceApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private BookmarkRepository bookmarkRepository;

    @Autowired
    private UserRepository userRepository;

    private Place walk;
    private Place cafe;
    private Place walk2;
    private String token;

    @BeforeEach
    void setUp() {
        cleanUp();
        walk = placeRepository.save(place("북촌 골목 산책", PlaceCategory.WALK, "종로구", true, 1));
        cafe = placeRepository.save(place("익선동 한옥 카페", PlaceCategory.CAFE, "종로구", true, 2));
        walk2 = placeRepository.save(place("청계천 야간 산책", PlaceCategory.WALK, "중구", false, 3));
        token = "Bearer " + authService.signup(
                new SignupRequest("place@example.com", "장소 사용자", "password123")).accessToken();
    }

    @AfterEach
    void cleanUp() {
        bookmarkRepository.deleteAll();
        placeRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void categoriesAreReturnedInChipOrder() throws Exception {
        mockMvc.perform(get("/api/places/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(contains("ALL", "RESTAURANT", "CAFE", "CULTURE", "WALK", "EVENT")))
                .andExpect(jsonPath("$[0].label").value("전체"))
                .andExpect(jsonPath("$[4].label").value("산책"));
    }

    @Test
    void listFiltersByCategoryAndCurated() throws Exception {
        mockMvc.perform(get("/api/places").param("category", "WALK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].title").value(contains("북촌 골목 산책", "청계천 야간 산책")))
                .andExpect(jsonPath("$.content[*].categoryLabel").value(everyItem(is("산책"))))
                .andExpect(jsonPath("$.content[*].bookmarked").value(everyItem(is(false))));

        mockMvc.perform(get("/api/places").param("category", "walk").param("curated", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].district").value("종로구"));

        mockMvc.perform(get("/api/places").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void invalidCategoryReturns400() throws Exception {
        mockMvc.perform(get("/api/places").param("category", "BAR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void bookmarkedFlagIsFilledWithToken() throws Exception {
        mockMvc.perform(put("/api/places/{id}/bookmark", cafe.getId()).header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/places").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].bookmarked").value(contains(false, true, false)));

        mockMvc.perform(get("/api/places"))
                .andExpect(jsonPath("$.content[*].bookmarked").value(everyItem(is(false))));

        mockMvc.perform(get("/api/places").header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bookmarkPutAndDeleteAreIdempotent() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put("/api/places/{id}/bookmark", walk.getId()).header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.placeId").value(walk.getId()))
                    .andExpect(jsonPath("$.bookmarked").value(true));
        }
        assertThat(bookmarkRepository.count()).isEqualTo(1);

        mockMvc.perform(put("/api/places/{id}/bookmark", walk2.getId()).header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/places/bookmarks").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id").value(contains(walk2.getId().intValue(), walk.getId().intValue())))
                .andExpect(jsonPath("$.content[*].bookmarked").value(everyItem(is(true))));

        mockMvc.perform(get("/api/places/{id}", walk.getId()).header("Authorization", token))
                .andExpect(jsonPath("$.bookmarked").value(true));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete("/api/places/{id}/bookmark", walk.getId()).header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookmarked").value(false));
        }
        assertThat(bookmarkRepository.count()).isEqualTo(1);
    }

    @Test
    void bookmarkRequiresToken() throws Exception {
        mockMvc.perform(put("/api/places/{id}/bookmark", walk.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(delete("/api/places/{id}/bookmark", walk.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/places/bookmarks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingPlaceReturns404() throws Exception {
        mockMvc.perform(get("/api/places/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("장소를 찾을 수 없습니다."));
        mockMvc.perform(put("/api/places/{id}/bookmark", 999_999L).header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailIncludesDescriptionAndLocation() throws Exception {
        mockMvc.perform(get("/api/places/{id}", walk.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("북촌 골목 산책"))
                .andExpect(jsonPath("$.category").value("WALK"))
                .andExpect(jsonPath("$.description").value("설명"))
                .andExpect(jsonPath("$.latitude").value(37.58))
                .andExpect(jsonPath("$.bookmarked").value(false));
    }

    private static Place place(String title, PlaceCategory category, String district, boolean curated, int order) {
        return new Place(title, "설명", category, district, "서울 " + district, "https://picsum.photos/seed/" + order + "/800/600",
                37.58, 126.98, curated, order);
    }
}
