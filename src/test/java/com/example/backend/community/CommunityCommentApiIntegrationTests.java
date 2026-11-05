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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.seed.courses.enabled=false")
class CommunityCommentApiIntegrationTests extends PostgresIntegrationTest {
    private static final String BASE = "/api/community/posts/{postId}/comments";
    @Autowired private MockMvc mvc;
    @Autowired private AuthService auth;
    @Autowired private CommunityPostRepository posts;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private String postOwner;
    private String commenter;
    private String other;
    private UUID postId;

    @BeforeEach
    void setup() {
        postOwner = signup("게시물 작성자");
        commenter = signup("댓글 작성자");
        other = signup("다른 사용자");
        postId = createPost(postOwner);
    }

    @Test
    void createsPublicCommentWithAuthorAndUpdatesOnlyItsContent() throws Exception {
        UUID authorId = auth.getCurrentUser(commenter).id();
        JsonNode created = create(commenter, "  여행 코스가 좋아요!  ");
        String id = created.path("id").asString();
        assertThat(created.path("postId").asString()).isEqualTo(postId.toString());
        assertThat(created.path("content").asString()).isEqualTo("여행 코스가 좋아요!");
        assertThat(created.path("author").path("id").asString()).isEqualTo(authorId.toString());
        assertThat(created.path("author").path("name").asString()).isEqualTo("댓글 작성자");
        Instant createdAt = Instant.parse(created.path("createdAt").asString());
        assertThat(Instant.parse(created.path("updatedAt").asString())).isEqualTo(createdAt);

        mvc.perform(get(BASE, postId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].content").value("여행 코스가 좋아요!"));
        JsonNode updated = json(mvc.perform(patch(BASE + "/{id}", postId, id)
                        .header("Authorization", commenter).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("content", "  다음에 따라가 볼게요.  "))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("다음에 따라가 볼게요."))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(Instant.parse(updated.path("createdAt").asString())).isEqualTo(createdAt);
        assertThat(Instant.parse(updated.path("updatedAt").asString())).isAfterOrEqualTo(createdAt);
        assertThat(updated.path("author").path("id").asString()).isEqualTo(authorId.toString());
    }

    @Test
    void requiresAuthenticationForMutationsAndAllowsOnlyCommentAuthor() throws Exception {
        String id = create(commenter, "내 댓글").path("id").asString();
        mvc.perform(post(BASE, postId).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"안녕\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(BASE + "/{id}", postId, id).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"변경\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE + "/{id}", postId, id)).andExpect(status().isUnauthorized());
        for (String token : new String[]{other, postOwner}) {
            mvc.perform(patch(BASE + "/{id}", postId, id).header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"침입\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(delete(BASE + "/{id}", postId, id).header("Authorization", token))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get(BASE, postId)).andExpect(jsonPath("$.content[0].content").value("내 댓글"));
        mvc.perform(delete(BASE + "/{id}", postId, id).header("Authorization", commenter))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE, postId)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(delete(BASE + "/{id}", postId, id).header("Authorization", commenter))
                .andExpect(status().isNotFound());
    }

    @Test
    void doesNotAllowUsingACommentUnderAnotherPost() throws Exception {
        String id = create(commenter, "첫 게시물 댓글").path("id").asString();
        UUID anotherPost = createPost(other);
        mvc.perform(patch(BASE + "/{id}", anotherPost, id).header("Authorization", commenter)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"다른 게시물\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/{id}", anotherPost, id).header("Authorization", commenter))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE, anotherPost)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(BASE, postId)).andExpect(jsonPath("$.content[0].id").value(id));
        UUID missing = UUID.randomUUID();
        mvc.perform(get(BASE, missing)).andExpect(status().isNotFound());
        write(missing, commenter, "없는 게시물").andExpect(status().isNotFound());
    }

    @Test
    void validatesContentAndPaginationWithoutChangingSavedComment() throws Exception {
        String id = create(commenter, "원래 댓글").path("id").asString();
        for (String value : new String[]{"", " \n\t ", "가".repeat(1001)}) {
            write(postId, commenter, value).andExpect(status().isBadRequest());
            mvc.perform(patch(BASE + "/{id}", postId, id).header("Authorization", commenter)
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("content", value))))
                    .andExpect(status().isBadRequest());
        }
        for (String body : new String[]{"{}", "{\"content\":null}"}) {
            mvc.perform(post(BASE, postId).header("Authorization", commenter).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE, postId).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE, postId).param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE, postId).param("size", "51")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE, postId)).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].content").value("원래 댓글"));
        create(commenter, "가".repeat(1000));
    }

    @Test
    void pagesCommentsOldestFirstAndKeepsPostsSeparate() throws Exception {
        String first = create(commenter, "첫 번째").path("id").asString();
        String second = create(other, "두 번째").path("id").asString();
        String third = create(commenter, "세 번째").path("id").asString();
        UUID anotherPost = createPost(postOwner);
        write(anotherPost, commenter, "다른 게시물 댓글").andExpect(status().isCreated());
        mvc.perform(get(BASE, postId).param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get(BASE, postId).param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(third))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void removesCommentHistoryWhenCommentAuthorWithdraws() throws Exception {
        String id = create(commenter, "탈퇴 전 댓글").path("id").asString();
        create(other, "유지할 댓글");
        mvc.perform(delete("/api/auth/me").header("Authorization", commenter)).andExpect(status().isNoContent());
        mvc.perform(get(BASE, postId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].content").value("유지할 댓글"));
        assertThat(jdbc.queryForObject("select count(*) from community_comments where id = ?", Long.class, UUID.fromString(id)))
                .isZero();
        write(postId, commenter, "탈퇴한 토큰").andExpect(status().isUnauthorized());
    }

    @Test
    void deletesCommentsWithPostAndHidesWithdrawnPostAuthorsThreads() throws Exception {
        String id = create(commenter, "게시물과 삭제").path("id").asString();
        mvc.perform(delete("/api/community/posts/{id}", postId).header("Authorization", postOwner))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from community_comments where id = ?", Long.class, UUID.fromString(id)))
                .isZero();
        mvc.perform(get(BASE, postId)).andExpect(status().isNotFound());
        UUID withdrawnPost = createPost(postOwner);
        write(withdrawnPost, commenter, "작성자가 탈퇴할 게시물").andExpect(status().isCreated());
        mvc.perform(delete("/api/auth/me").header("Authorization", postOwner)).andExpect(status().isNoContent());
        mvc.perform(get(BASE, withdrawnPost)).andExpect(status().isNotFound());
        write(withdrawnPost, commenter, "추가 댓글").andExpect(status().isNotFound());
    }

    @Test
    void commentAuthorWithdrawalAndPostDeletionCanCompleteConcurrently() throws Exception {
        String id = create(commenter, "동시에 정리될 댓글").path("id").asString();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var withdrawal = executor.submit(() -> {
                ready.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Withdrawal barrier timed out");
                return mvc.perform(delete("/api/auth/me").header("Authorization", commenter))
                        .andReturn().getResponse().getStatus();
            });
            var deletion = executor.submit(() -> {
                ready.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Deletion barrier timed out");
                return mvc.perform(delete("/api/community/posts/{id}", postId).header("Authorization", postOwner))
                        .andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(withdrawal.get(20, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isEqualTo(204);
        } finally {
            release.countDown();
        }
        assertThat(jdbc.queryForObject("select count(*) from community_comments where id = ?", Long.class, UUID.fromString(id)))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from community_comments where post_id = ?", Long.class, postId))
                .isZero();
        mvc.perform(get(BASE, postId)).andExpect(status().isNotFound());
        write(createPost(other), commenter, "탈퇴한 사용자의 재작성").andExpect(status().isUnauthorized());
    }

    private UUID createPost(String token) {
        return posts.saveAndFlush(new CommunityPost(auth.getCurrentUser(token).id(), "서울 여행", "게시물 설명", Set.of(), null, null)).id;
    }
    private String signup(String name) {
        return "Bearer " + auth.signup(new SignupRequest(UUID.randomUUID() + "@comments.test", name, "password123")).accessToken();
    }
    private JsonNode create(String token, String content) throws Exception {
        return json(write(postId, token, content).andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/community/posts/" + postId + "/comments/")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private ResultActions write(UUID targetPost, String token, String content) throws Exception {
        return mvc.perform(post(BASE, targetPost).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("content", content))));
    }
    private JsonNode json(String content) { return mapper.readTree(content); }
}
