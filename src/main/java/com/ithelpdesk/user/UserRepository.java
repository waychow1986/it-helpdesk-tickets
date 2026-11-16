package com.ithelpdesk.user;

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

/** 账号数据访问（只含 SQL，全部命名参数）。 */
@Repository
public class UserRepository {

    /** 启动引导使用的事务级 advisory lock 键（TD-011）。 */
    public static final long BOOTSTRAP_LOCK_KEY = 7243011L;

    private static final String COLUMNS =
        "id, login_name, password_hash, display_name, role, created_at, created_by";

    private static final RowMapper<AppUser> MAPPER = UserRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static AppUser map(ResultSet rs, int row) throws SQLException {
        long createdBy = rs.getLong("created_by");
        return new AppUser(rs.getLong("id"), rs.getString("login_name"), rs.getString("password_hash"),
            rs.getString("display_name"), Role.valueOf(rs.getString("role")),
            rs.getTimestamp("created_at").toInstant(), rs.wasNull() ? null : createdBy);
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE id = :id",
            new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    public Optional<AppUser> findByLoginName(String loginName) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE login_name = :login",
            new MapSqlParameterSource("login", loginName), MAPPER).stream().findFirst();
    }

    public List<AppUser> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user ORDER BY created_at DESC, id DESC", MAPPER);
    }

    /** 当前身份为 IT 同事的账号，按姓名排序。 */
    public List<AppUser> findCurrentItStaff() {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE role = 'IT_STAFF' ORDER BY display_name, id",
            MAPPER);
    }

    public long insert(String loginName, String passwordHash, String displayName, Role role, Instant now,
                       Long createdBy) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO app_user(login_name, password_hash, display_name, role, created_at, created_by) "
                + "VALUES (:login, :hash, :display, :role, :now, :createdBy)",
            new MapSqlParameterSource()
                .addValue("login", loginName)
                .addValue("hash", passwordHash)
                .addValue("display", displayName)
                .addValue("role", role.name())
                .addValue("now", Timestamp.from(now))
                .addValue("createdBy", createdBy),
            keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    /** 仅允许 EMPLOYEE ↔ IT_STAFF；返回受影响行数。 */
    public int updateStaffRole(long id, Role newRole) {
        return jdbc.update("UPDATE app_user SET role = :role WHERE id = :id AND role IN ('EMPLOYEE','IT_STAFF')",
            new MapSqlParameterSource().addValue("role", newRole.name()).addValue("id", id));
    }

    /** 读取并共享锁定账号身份（分派校验，防止并发身份变更）。 */
    public Optional<Role> findRoleForShare(long id) {
        return jdbc.query("SELECT role FROM app_user WHERE id = :id FOR SHARE",
            new MapSqlParameterSource("id", id), (rs, i) -> Role.valueOf(rs.getString("role")))
            .stream().findFirst();
    }

    // ---------- 启动引导（TD-011） ----------

    public void acquireBootstrapLock() {
        jdbc.query("SELECT pg_advisory_xact_lock(:key)", new MapSqlParameterSource("key", BOOTSTRAP_LOCK_KEY),
            rs -> null);
    }

    public boolean existsAdmin() {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE role = 'ADMIN')",
            new MapSqlParameterSource(), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** 插入无凭据的引导管理员；登录名已被占用时不插入并返回 false。 */
    public boolean insertBootstrapAdmin(String loginName, String displayName, Instant now) {
        int rows = jdbc.update("INSERT INTO app_user(login_name, password_hash, display_name, role, created_at) "
                + "VALUES (:login, NULL, :display, 'ADMIN', :now) ON CONFLICT (login_name) DO NOTHING",
            new MapSqlParameterSource()
                .addValue("login", loginName)
                .addValue("display", displayName)
                .addValue("now", Timestamp.from(now)));
        return rows > 0;
    }
}
