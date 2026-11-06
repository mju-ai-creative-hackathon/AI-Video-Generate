package com.example.backend.community;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.course.domain.Course;
import com.example.backend.course.domain.CourseRepository;
import com.example.backend.diary.domain.DiaryEntryRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    @Autowired CourseRepository courses;
    @Autowired DiaryEntryRepository diaries;
    @Autowired ObjectMapper mapper;
    private String owner;
    private String other;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.shortform.storage-root", STORAGE::toString);
    }
    @BeforeEach void setup() {
        diaries.deleteAll();
        posts.deleteAll();
        owner = token("owner"); other = token("other");
    }
    private String token(String prefix) {
        return "Bearer " + auth.signup(new SignupRequest(prefix + UUID.randomUUID() + "@example.test", "서울 여행자", "password123")).accessToken();
    }
    private JsonNode json(String body) { return mapper.readTree(body); }
    private MockMultipartHttpServletRequestBuilder upload(String title, String caption, List<String> tags) {
        return upload(title, caption, tags, null);
    }
    private MockMultipartHttpServletRequestBuilder upload(String title, String caption, List<String> tags, Long courseId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("caption", caption);
        metadata.put("tags", tags);
        if (courseId != null) metadata.put("courseId", courseId);
        return multipart(BASE).file(new MockMultipartFile("post", "", "application/json",
                mapper.writeValueAsBytes(metadata)));
    }
    private MockMultipartFile video() { return new MockMultipartFile("video", "clip.mp4", "video/mp4", MP4); }
    private MockMultipartFile cover() { return new MockMultipartFile("cover", "cover.png", "image/png", PNG); }
    private JsonNode create(String bearer, String title, String caption, String... tags) throws Exception {
        return json(mvc.perform(upload(title, caption, List.of(tags)).file(video()).file(cover())
                        .header("Authorization", bearer))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private JsonNode createWithCourse(String bearer, Long courseId, String title) throws Exception {
        return json(mvc.perform(upload(title, "코스와 연결된 여행 기록", List.of("코스"), courseId)
                        .file(video()).file(cover()).header("Authorization", bearer))
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

    @Test void importsLinkedCourseAsPrivateSnapshotAndStartsDiary() throws Exception {
        UUID ownerId = auth.getCurrentUser(owner).id();
        UUID otherId = auth.getCurrentUser(other).id();
        Course original = Course.ai(ownerId, "서울 야경", "원본 야경 코스", "한강을 따라 걷는 코스", "서울", 180, 25000);
        original.setOrigin("서울역", "서울 용산구", 37.5547, 126.9706);
        original.addStop(1, 1, "남산", "야경 감상", "서울 중구", 37.5512, 126.9882,
                "https://example.test/namsan.jpg", "place-1", null, 60);
        original.addStop(2, 2, "한강", "산책", "서울 영등포구", 37.5284, 126.9326,
                "https://example.test/river.jpg", "place-2", null, 90);
        original.addMission(1, com.example.backend.course.domain.MissionKind.PHOTO,
                "야경 촬영", "도시 불빛을 촬영하세요.", 30);
        courses.saveAndFlush(original);

        JsonNode post = createWithCourse(owner, original.getId(), "서울 야경 여행 기록");
        assertThat(post.path("course").path("id").asLong()).isEqualTo(original.getId());
        assertThat(post.path("course").path("title").asText()).isEqualTo("원본 야경 코스");
        mvc.perform(get("/api/courses/{id}", original.getId()).header("Authorization", other))
                .andExpect(status().isNotFound());

        JsonNode imported = json(mvc.perform(post(BASE + "/{id}/import", post.path("id").asText())
                        .header("Authorization", other))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/diaries/")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        long importedCourseId = imported.path("courseId").asLong();
        assertThat(importedCourseId).isNotEqualTo(original.getId());
        assertThat(imported.path("diary").path("courseId").asLong()).isEqualTo(importedCourseId);
        assertThat(imported.path("diary").path("status").asText()).isEqualTo("RECORDING");

        mvc.perform(get("/api/courses/{id}", importedCourseId).header("Authorization", other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("IMPORTED"))
                .andExpect(jsonPath("$.origin.name").value("서울역"))
                .andExpect(jsonPath("$.stops.length()").value(2))
                .andExpect(jsonPath("$.stops[1].day").value(2))
                .andExpect(jsonPath("$.missions.length()").value(1));
        assertThat(courses.findByOwnerId(otherId)).extracting(Course::getId).containsExactly(importedCourseId);

        mvc.perform(post(BASE + "/{id}/import", post.path("id").asText()).header("Authorization", other))
                .andExpect(status().isConflict());
        assertThat(courses.findByOwnerId(otherId)).extracting(Course::getId).containsExactly(importedCourseId);
    }

    @Test void authorCanUpdatePostAndLinkOrRemoveCourse() throws Exception {
        Course preset = courses.saveAndFlush(Course.preset("공개 기본 코스", null, "서대문", 120, null, 1));
        Course privateAi = courses.saveAndFlush(Course.ai(auth.getCurrentUser(other).id(), "비공개", "남의 AI 코스",
                null, "서울", 60, null));
        JsonNode post = create(owner, "원래 제목", "원래 내용", "원래");
        String id = post.path("id").asText();

        mvc.perform(patch(BASE + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":" + preset.getId() + "}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(BASE + "/{id}", id).header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":" + preset.getId() + "}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch(BASE + "/{id}", id).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":" + privateAi.getId() + "}"))
                .andExpect(status().isNotFound());

        mvc.perform(patch(BASE + "/{id}", id).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"바뀐 제목\",\"tags\":[\"#노을\",\"서대문\"],\"courseId\":"
                                + preset.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("바뀐 제목"))
                .andExpect(jsonPath("$.caption").value("원래 내용"))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.course.id").value(preset.getId()))
                .andExpect(jsonPath("$.course.title").value("공개 기본 코스"));
        mvc.perform(get(BASE + "/{id}", id))
                .andExpect(jsonPath("$.course.id").value(preset.getId()))
                .andExpect(jsonPath("$.title").value("바뀐 제목"));

        mvc.perform(patch(BASE + "/{id}", id).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"removeCourse\":true,\"courseId\":" + preset.getId() + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(BASE + "/{id}", id).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"removeCourse\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.course").doesNotExist());
    }

    @Test void importRequiresAuthenticationAndLinkedCourse() throws Exception {
        JsonNode post = create(owner, "코스 없는 기록", "가져올 코스 없음");
        mvc.perform(post(BASE + "/{id}/import", post.path("id").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/{id}/import", post.path("id").asText()).header("Authorization", other))
                .andExpect(status().isNotFound());
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
