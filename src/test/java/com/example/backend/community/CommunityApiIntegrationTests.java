package com.example.backend.community;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.SignupRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CommunityApiIntegrationTests extends PostgresIntegrationTest {
    private static final String BASE = "/api/community/posts";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6P9sAAAAASUVORK5CYII=");
    private static final byte[] MP4 = ByteBuffer.allocate(24).putInt(24)
            .put("ftypisom".getBytes(StandardCharsets.US_ASCII))
            .putInt(0).put("isommp42".getBytes(StandardCharsets.US_ASCII)).array();
    private static final Path STORAGE = Path.of("build", "community-test-" + UUID.randomUUID()).toAbsolutePath();
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired CommunityPostRepository posts;
    @Autowired ObjectMapper mapper;
    private String owner;
    private String other;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.shortform.storage-root", STORAGE::toString);
    }
    @BeforeEach void setup() {
        posts.deleteAll();
        owner = token("owner"); other = token("other");
    }
    private String token(String prefix) {
        return "Bearer " + auth.signup(new SignupRequest(prefix + UUID.randomUUID() + "@example.test", "서울 여행자", "password123")).accessToken();
    }
    private JsonNode json(String body) { return mapper.readTree(body); }
    private MockMultipartHttpServletRequestBuilder upload(String title, String caption, List<String> tags) {
        return multipart(BASE).file(new MockMultipartFile("post", "", "application/json",
                mapper.writeValueAsBytes(Map.of("title", title, "caption", caption, "tags", tags))));
    }
    private MockMultipartFile video() { return new MockMultipartFile("video", "clip.mp4", "video/mp4", MP4); }
    private MockMultipartFile cover() { return new MockMultipartFile("cover", "cover.png", "image/png", PNG); }
    private JsonNode create(String bearer, String title, String caption, String... tags) throws Exception {
        return json(mvc.perform(upload(title, caption, List.of(tags)).file(video()).file(cover())
                        .header("Authorization", bearer))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private JsonNode explore(String keyword, String tag) throws Exception {
        var request = get(BASE + "/explore");
        if (keyword != null) request.param("keyword", keyword);
        if (tag != null) request.param("tag", tag);
        return json(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test void postMediaIsPublicAndOnlyAuthorCanDelete() throws Exception {
        var post = create(owner, "한강 산책", "함께 걸은 하루", "#Couple", "서울");
        String id = post.get("id").asText();
        assertThat(post.get("tags").toString()).contains("couple", "서울");
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isOk());
        var image = mvc.perform(get(post.get("thumbnailUrl").asText())).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(image.getContentAsByteArray()).isEqualTo(PNG);
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE + "/" + id).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/" + id).header("Authorization", owner)).andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get(post.get("thumbnailUrl").asText())).andExpect(status().isNotFound());
    }

    @Test void searchesTitleCaptionAndTagsWithLiteralWildcards() throws Exception {
        create(owner, "Seoul SKY", "한강의 노을", "#Couple", "서울");
        create(other, "골목 산책", "좋은 하루", "카페");
        assertThat(explore("sky", null).get("items").size()).isEqualTo(1);
        assertThat(explore("노을", null).get("items").size()).isEqualTo(1);
        assertThat(explore("couple", null).get("items").size()).isEqualTo(1);
        assertThat(explore(null, "#COUPLE").get("items").size()).isEqualTo(1);
        assertThat(explore("골목", "couple").get("items").size()).isZero();
        assertThat(explore("%", null).get("items").size()).isZero();
        assertThat(explore("_", null).get("items").size()).isZero();
        var tags = json(mvc.perform(get(BASE + "/tags")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(tags.toString()).contains("couple", "postCount");
    }

    @Test void randomFeedHasStablePaginationAndExcludesViewerPosts() throws Exception {
        var own = create(owner, "내 게시물", "본인 제외");
        for (int i = 0; i < 5; i++) create(other, "여행 " + i, "다른 사람의 순간");
        var first = json(mvc.perform(get(BASE + "/explore").param("size", "2").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String seed = first.get("seed").asText(), snapshot = first.get("snapshot").asText();
        var replay = json(mvc.perform(get(BASE + "/explore").param("size", "2").param("seed", seed)
                        .param("snapshot", snapshot).header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(replay.get("items")).isEqualTo(first.get("items"));
        Set<String> ids = new HashSet<>();
        var page = first;
        while (true) {
            for (var item : page.get("items")) assertThat(ids.add(item.get("id").asText())).isTrue();
            if (!page.get("hasNext").asBoolean()) break;
            page = json(mvc.perform(get(BASE + "/explore").param("size", "2").param("seed", seed)
                            .param("snapshot", snapshot).param("cursor", page.get("nextCursor").asText())
                            .header("Authorization", owner))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        assertThat(ids).hasSize(5).doesNotContain(own.get("id").asText());
    }

    @Test void withdrawnAuthorsDisappearFromFeedDetailMediaAndTags() throws Exception {
        var post = create(owner, "탈퇴 전 게시물", "비공개 전환", "탈퇴태그");
        auth.deleteAccount(owner);
        assertThat(explore(null, null).get("items").size()).isZero();
        mvc.perform(get(BASE + "/" + post.get("id").asText())).andExpect(status().isNotFound());
        mvc.perform(get(post.get("thumbnailUrl").asText())).andExpect(status().isNotFound());
        var tags = json(mvc.perform(get(BASE + "/tags")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(tags.size()).isZero();
    }

    @Test void rejectsUnauthenticatedAndUnsafeUploadsAndInvalidParameters() throws Exception {
        mvc.perform(upload("영상", "", List.of()).file(video()).file(cover())).andExpect(status().isUnauthorized());
        mvc.perform(upload("위험 파일", "", List.of()).file(new MockMultipartFile("video", "evil.svg", "image/svg+xml",
                        "<svg/>".getBytes(StandardCharsets.UTF_8))).file(cover()).header("Authorization", owner))
                .andExpect(status().isBadRequest());
        mvc.perform(upload("잘못된 태그", "", List.of("tag with spaces")).file(video()).file(cover())
                        .header("Authorization", owner))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/explore").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/explore").param("size", "51")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/explore").param("cursor", "invalid")).andExpect(status().isBadRequest());
        assertThat(posts.count()).isZero();
    }

    @Test void acceptsOnlyOneVideoWithPhotoCover() throws Exception {
        mvc.perform(upload("영상", "", List.of()).file(video()).header("Authorization", owner))
                .andExpect(status().isBadRequest());
        mvc.perform(upload("사진", "", List.of())
                        .file(new MockMultipartFile("video", "photo.png", "image/png", PNG)).file(cover())
                        .header("Authorization", owner))
                .andExpect(status().isBadRequest());
        var result = json(mvc.perform(upload("영상", "", List.of()).file(video()).file(cover())
                        .header("Authorization", owner))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(result.get("media").size()).isEqualTo(1);
        assertThat(result.get("media").get(0).get("kind").asText()).isEqualTo("VIDEO");
        assertThat(mvc.perform(get(result.get("thumbnailUrl").asText())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(PNG);
    }

    @Test void rejectsInvalidAuthenticationAndDatabaseUnsafeInput() throws Exception {
        for (String path : List.of("/explore", "/tags")) {
            mvc.perform(get(BASE + path).header("Authorization", "Bearer invalid.token.value"))
                    .andExpect(status().isUnauthorized());
        }
        String nul = Character.toString(0);
        mvc.perform(get(BASE + "/explore").param("keyword", "서울" + nul))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/explore").param("snapshot", "1969-12-31T23:59:59Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(upload("서울" + nul, "설명", List.of()).file(video()).file(cover()).header("Authorization", owner))
                .andExpect(status().isBadRequest());
        mvc.perform(upload("서울", "설명" + nul, List.of()).file(video()).file(cover()).header("Authorization", owner))
                .andExpect(status().isBadRequest());
        assertThat(posts.count()).isZero();
    }

    @Test void deletingSeenPostAndAddingNewPostDoesNotShiftRemainingCursorPages() throws Exception {
        for (int index = 0; index < 5; index++) create(other, "기존 게시물 " + index, "탐색 세션");
        var first = json(mvc.perform(get(BASE + "/explore").param("size", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String seed = first.get("seed").asText(), snapshot = first.get("snapshot").asText();
        var baseline = json(mvc.perform(get(BASE + "/explore").param("seed", seed)
                        .param("snapshot", snapshot).param("size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> expectedRemaining = new ArrayList<>();
        for (int index = 2; index < baseline.get("items").size(); index++)
            expectedRemaining.add(baseline.get("items").get(index).get("id").asText());

        mvc.perform(delete(BASE + "/" + first.get("items").get(0).get("id").asText())
                .header("Authorization", other)).andExpect(status().isNoContent());
        var newPost = create(other, "새 게시물", "기존 snapshot 이후 생성");
        List<String> actualRemaining = new ArrayList<>();
        JsonNode page = first;
        int pages = 0;
        while (page.get("hasNext").asBoolean()) {
            assertThat(++pages).isLessThan(10);
            page = json(mvc.perform(get(BASE + "/explore").param("seed", seed).param("snapshot", snapshot)
                            .param("cursor", page.get("nextCursor").asText()).param("size", "2"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            for (var item : page.get("items")) actualRemaining.add(item.get("id").asText());
        }
        assertThat(actualRemaining).containsExactlyElementsOf(expectedRemaining)
                .doesNotContain(newPost.get("id").asText());
        var refreshed = explore(null, null);
        List<String> refreshedIds = new ArrayList<>();
        for (var item : refreshed.get("items")) refreshedIds.add(item.get("id").asText());
        assertThat(refreshedIds).contains(newPost.get("id").asText());
    }
}
