package com.example.backend.community;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** PostgreSQL discovery queries. A seed fixes random order for the lifetime of one feed. */
@Repository
public class CommunityFeedRepository {
    private static final String SORT_KEY = "md5(cast(p.id as text) || :seed) || cast(p.id as text)";
    private final NamedParameterJdbcTemplate jdbc;

    public CommunityFeedRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(UUID id, String cursor) {}

    public List<Entry> explore(UUID viewerId, String keyword, String tag, UUID seed,
                               Instant snapshot, String after, int limit) {
        var parameters = new MapSqlParameterSource()
                .addValue("seed", seed.toString())
                .addValue("snapshot", Timestamp.from(snapshot))
                .addValue("limit", limit);
        var sql = new StringBuilder("select p.id, ")
                .append(SORT_KEY)
                .append(" as sort_key from community_posts p join users u on u.id = p.author_id")
                .append(" where p.created_at <= :snapshot");
        if (viewerId != null) {
            sql.append(" and p.author_id <> :viewer");
            parameters.addValue("viewer", viewerId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (lower(p.title) like :keyword escape '!' ")
                    .append("or lower(coalesce(p.caption, '')) like :keyword escape '!' ")
                    .append("or exists (select 1 from community_post_tags kt ")
                    .append("where kt.post_id = p.id and lower(kt.tag) like :keyword escape '!'))");
            parameters.addValue("keyword", literalContains(keyword.strip().toLowerCase(Locale.ROOT)));
        }
        if (tag != null && !tag.isBlank()) {
            sql.append(" and exists (select 1 from community_post_tags ft ")
                    .append("where ft.post_id = p.id and ft.tag = :tag)");
            parameters.addValue("tag", tag);
        }
        if (after != null && !after.isBlank()) {
            sql.append(" and (").append(SORT_KEY).append(") > :after");
            parameters.addValue("after", after);
        }
        sql.append(" order by sort_key asc limit :limit");
        return jdbc.query(sql.toString(), parameters,
                (row, rowNumber) -> new Entry(row.getObject("id", UUID.class), row.getString("sort_key")));
    }

    public List<CommunityDtos.TagView> popularTags(UUID viewerId, int limit) {
        var parameters = new MapSqlParameterSource().addValue("limit", limit);
        var sql = new StringBuilder("select t.tag, count(*) as post_count ")
                .append("from community_post_tags t join community_posts p on p.id = t.post_id ")
                .append("join users u on u.id = p.author_id where 1 = 1");
        if (viewerId != null) {
            sql.append(" and p.author_id <> :viewer");
            parameters.addValue("viewer", viewerId);
        }
        sql.append(" group by t.tag order by post_count desc, t.tag asc limit :limit");
        return jdbc.query(sql.toString(), parameters,
                (row, rowNumber) -> new CommunityDtos.TagView(row.getString("tag"), row.getLong("post_count")));
    }

    private static String literalContains(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
