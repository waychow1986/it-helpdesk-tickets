package com.ithelpdesk.support;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.user.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 集成测试合成数据：账号、类别名均带本次运行前缀；按"数据库数据契约"顺序按前缀清理
 * （ticket_event → ticket_comment → ticket → category → app_user）。不触碰启动引导创建的初始管理员。
 */
public final class TestData {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    private final NamedParameterJdbcTemplate jdbc;
    private final String prefix;

    public TestData(NamedParameterJdbcTemplate jdbc, String prefix) {
        this.jdbc = jdbc;
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    public String name(String suffix) {
        return prefix + suffix;
    }

    /** 直接插入账号（BCrypt 哈希），返回 CurrentUser。 */
    public CurrentUser user(String suffix, Role role, String rawPassword) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO app_user(login_name, password_hash, display_name, role, created_at) "
                + "VALUES (:login, :hash, :display, :role, :now)",
            new MapSqlParameterSource()
                .addValue("login", name(suffix))
                .addValue("hash", rawPassword == null ? null : ENCODER.encode(rawPassword))
                .addValue("display", name(suffix) + "-姓名")
                .addValue("role", role.name())
                .addValue("now", Timestamp.from(Instant.now())),
            keys, new String[] {"id"});
        return new CurrentUser(keys.getKey().longValue(), name(suffix), name(suffix) + "-姓名", role, null);
    }

    public CurrentUser user(String suffix, Role role) {
        return user(suffix, role, RunPrefix.randomPassword());
    }

    public long category(String suffix) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO category(name, active, created_at) VALUES (:name, TRUE, :now)",
            new MapSqlParameterSource().addValue("name", name(suffix)).addValue("now", Timestamp.from(Instant.now())),
            keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    /** 统计本次前缀相关工单 id（用于比较集合）。 */
    public List<Long> ticketIdsOfPrefix() {
        return jdbc.queryForList("SELECT t.id FROM ticket t JOIN app_user u ON u.id = t.submitter_id "
            + "WHERE u.login_name LIKE :p ORDER BY t.id", new MapSqlParameterSource("p", prefix + "%"), Long.class);
    }

    public long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    /** 按前缀清理本次运行数据。 */
    public void cleanup() {
        cleanup(jdbc, prefix);
    }

    public static void cleanup(NamedParameterJdbcTemplate jdbc, String prefix) {
        MapSqlParameterSource p = new MapSqlParameterSource("p", prefix + "%");
        String users = "(SELECT id FROM app_user WHERE login_name LIKE :p)";
        String categories = "(SELECT id FROM category WHERE name LIKE :p)";
        String tickets = "(SELECT id FROM ticket WHERE submitter_id IN " + users + " OR category_id IN " + categories
            + " OR assignee_id IN " + users + " OR first_resolved_assignee_id IN " + users + ")";
        jdbc.update("DELETE FROM ticket_event WHERE ticket_id IN " + tickets + " OR actor_id IN " + users
            + " OR from_assignee_id IN " + users + " OR to_assignee_id IN " + users, p);
        jdbc.update("DELETE FROM ticket_comment WHERE ticket_id IN " + tickets + " OR author_id IN " + users, p);
        jdbc.update("DELETE FROM ticket WHERE id IN " + tickets, p);
        jdbc.update("DELETE FROM category WHERE name LIKE :p", p);
        jdbc.update("UPDATE app_user SET created_by = NULL WHERE created_by IN " + users, p);
        jdbc.update("DELETE FROM app_user WHERE login_name LIKE :p", p);
    }
}
