package com.ithelpdesk.category;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 类别数据访问。 */
@Repository
public class CategoryRepository {

    private static final String COLUMNS = "id, name, active, created_at, removed_at";
    private static final RowMapper<Category> MAPPER = CategoryRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public CategoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static Category map(ResultSet rs, int row) throws SQLException {
        Timestamp removed = rs.getTimestamp("removed_at");
        return new Category(rs.getLong("id"), rs.getString("name"), rs.getBoolean("active"),
            rs.getTimestamp("created_at").toInstant(), removed == null ? null : removed.toInstant());
    }

    /** 全部类别：有效在前，按名称排序。 */
    public List<Category> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM category ORDER BY active DESC, name, id", MAPPER);
    }

    public List<Category> findActive() {
        return jdbc.query("SELECT " + COLUMNS + " FROM category WHERE active ORDER BY name, id", MAPPER);
    }

    public Optional<Category> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM category WHERE id = :id",
            new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    /** 以共享锁读取类别（提交工单时确认仍有效，防止并发移除）。 */
    public Optional<Category> findByIdForShare(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM category WHERE id = :id FOR SHARE",
            new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    public boolean existsActiveName(String name, Long excludeId) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM category WHERE active AND name = :name AND id <> :exclude)",
            new MapSqlParameterSource().addValue("name", name).addValue("exclude", excludeId == null ? -1L : excludeId),
            Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public long insert(String name, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO category(name, active, created_at) VALUES (:name, TRUE, :now)",
            new MapSqlParameterSource().addValue("name", name).addValue("now", Timestamp.from(now)),
            keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    public int rename(long id, String name) {
        return jdbc.update("UPDATE category SET name = :name WHERE id = :id",
            new MapSqlParameterSource().addValue("name", name).addValue("id", id));
    }

    public int remove(long id, Instant now) {
        return jdbc.update("UPDATE category SET active = FALSE, removed_at = :now WHERE id = :id AND active",
            new MapSqlParameterSource().addValue("id", id).addValue("now", Timestamp.from(now)));
    }
}
