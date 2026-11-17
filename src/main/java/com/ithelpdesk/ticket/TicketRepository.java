package com.ithelpdesk.ticket;

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

/**
 * 工单数据访问。提交后标题、描述、类别、紧急程度、提交人、提交时间没有任何更新语句（FR-009、INV-004）。
 * 动态筛选 SQL 只在 {@link #search} 中拼装，所有值使用命名参数绑定。
 */
@Repository
public class TicketRepository {

    /** 工单行锁读取结果（写操作使用）。 */
    public record TicketState(long id, long submitterId, TicketStatus status, Long assigneeId,
                              Instant firstResolvedAt) {
    }

    /** 筛选下拉中的处理人选项。 */
    public record UserOption(long id, String displayName) {
    }

    private static final String SELECT = "SELECT t.id, t.title, t.description, t.category_id, c.name AS category_name, "
        + "c.active AS category_active, t.urgency, t.submitter_id, s.display_name AS submitter_name, t.submitted_at, "
        + "t.status, t.assignee_id, a.display_name AS assignee_name, t.first_resolved_at, "
        + "t.first_resolved_assignee_id, t.updated_at, " + ExpectedResolution.sql("t") + " AS "
        + ExpectedResolution.COLUMN_ALIAS + " "
        + "FROM ticket t JOIN category c ON c.id = t.category_id JOIN app_user s ON s.id = t.submitter_id "
        + "LEFT JOIN app_user a ON a.id = t.assignee_id ";

    private static final RowMapper<Ticket> MAPPER = TicketRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public TicketRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static Ticket map(ResultSet rs, int row) throws SQLException {
        return new Ticket(rs.getLong("id"), rs.getString("title"), rs.getString("description"),
            rs.getLong("category_id"), rs.getString("category_name"), rs.getBoolean("category_active"),
            Urgency.valueOf(rs.getString("urgency")), rs.getLong("submitter_id"), rs.getString("submitter_name"),
            instant(rs, "submitted_at"), TicketStatus.valueOf(rs.getString("status")),
            nullableLong(rs, "assignee_id"), rs.getString("assignee_name"), instant(rs, "first_resolved_at"),
            nullableLong(rs, "first_resolved_assignee_id"), instant(rs, "updated_at"),
            instant(rs, ExpectedResolution.COLUMN_ALIAS));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public long insert(String title, String description, long categoryId, Urgency urgency, long submitterId,
                       Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO ticket(title, description, category_id, urgency, submitter_id, submitted_at, status, "
                + "assignee_id, updated_at) VALUES (:title, :description, :categoryId, :urgency, :submitterId, :now, "
                + "'OPEN', NULL, :now)",
            new MapSqlParameterSource()
                .addValue("title", title)
                .addValue("description", description)
                .addValue("categoryId", categoryId)
                .addValue("urgency", urgency.name())
                .addValue("submitterId", submitterId)
                .addValue("now", Timestamp.from(now)),
            keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    public Optional<Ticket> findById(long id) {
        return jdbc.query(SELECT + "WHERE t.id = :id", new MapSqlParameterSource("id", id), MAPPER)
            .stream().findFirst();
    }

    /** 读取并以行锁锁定工单（认领 / 分派 / 改状态串行化）。 */
    public Optional<TicketState> lockForUpdate(long id) {
        return jdbc.query("SELECT id, submitter_id, status, assignee_id, first_resolved_at FROM ticket WHERE id = :id "
                + "FOR UPDATE",
            new MapSqlParameterSource("id", id),
            (rs, i) -> new TicketState(rs.getLong("id"), rs.getLong("submitter_id"),
                TicketStatus.valueOf(rs.getString("status")), nullableLong(rs, "assignee_id"),
                instant(rs, "first_resolved_at")))
            .stream().findFirst();
    }

    /** 读取工单提交人（补充说明 / 回复授权使用）；不存在时为空。 */
    public Optional<Long> findSubmitterId(long id) {
        return jdbc.query("SELECT submitter_id FROM ticket WHERE id = :id", new MapSqlParameterSource("id", id),
            (rs, i) -> rs.getLong("submitter_id")).stream().findFirst();
    }

    public int updateAssignee(long id, long assigneeId, Instant now) {
        return jdbc.update("UPDATE ticket SET assignee_id = :assignee, updated_at = :now WHERE id = :id",
            new MapSqlParameterSource()
                .addValue("assignee", assigneeId)
                .addValue("now", Timestamp.from(now))
                .addValue("id", id));
    }

    /** 修改状态；首次已解决的冗余列只在为 NULL 时写入（TD-008、INV-005）。 */
    public int updateStatus(long id, TicketStatus status, Instant now) {
        return jdbc.update("UPDATE ticket SET status = :status, updated_at = :now, "
                + "first_resolved_at = CASE WHEN :status = 'RESOLVED' AND first_resolved_at IS NULL THEN :now "
                + "ELSE first_resolved_at END, "
                + "first_resolved_assignee_id = CASE WHEN :status = 'RESOLVED' AND first_resolved_at IS NULL "
                + "THEN assignee_id ELSE first_resolved_assignee_id END "
                + "WHERE id = :id",
            new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("now", Timestamp.from(now))
                .addValue("id", id));
    }

    /**
     * 搜索与筛选：可见范围（submitterScope 非空时只含该提交人的工单）+ 各条件 AND；
     * 按提交时间、id 倒序；limit 由调用方多取 1 条以判断是否有下一页。
     */
    public List<Ticket> search(TicketSearchCriteria criteria, Long submitterScope, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT).append("WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (submitterScope != null) {
            sql.append(" AND t.submitter_id = :scope");
            params.addValue("scope", submitterScope);
        }
        if (criteria.likePattern() != null) {
            sql.append(" AND (t.title ILIKE :q ESCAPE '\\' OR t.description ILIKE :q ESCAPE '\\')");
            params.addValue("q", criteria.likePattern());
        }
        if (criteria.getStatus() != null) {
            sql.append(" AND t.status = :status");
            params.addValue("status", criteria.getStatus().name());
        }
        if (criteria.getCategoryId() != null) {
            sql.append(" AND t.category_id = :categoryId");
            params.addValue("categoryId", criteria.getCategoryId());
        }
        if (criteria.isUnassigned()) {
            sql.append(" AND t.assignee_id IS NULL");
        } else if (criteria.getAssigneeId() != null) {
            sql.append(" AND t.assignee_id = :assigneeId");
            params.addValue("assigneeId", criteria.getAssigneeId());
        }
        if (criteria.getFromInstant() != null) {
            sql.append(" AND t.submitted_at >= :from");
            params.addValue("from", Timestamp.from(criteria.getFromInstant()));
        }
        if (criteria.getToInstantExclusive() != null) {
            sql.append(" AND t.submitted_at < :to");
            params.addValue("to", Timestamp.from(criteria.getToInstantExclusive()));
        }
        sql.append(" ORDER BY t.submitted_at DESC, t.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, MAPPER);
    }

    /** 处理人筛选选项：当前 IT 同事 + 曾作为处理人出现过的账号（去重，按姓名排序）。 */
    public List<UserOption> findAssigneeOptions() {
        return jdbc.query("SELECT u.id, u.display_name FROM app_user u WHERE u.role = 'IT_STAFF' "
                + "OR EXISTS (SELECT 1 FROM ticket t WHERE t.assignee_id = u.id) ORDER BY u.display_name, u.id",
            (rs, i) -> new UserOption(rs.getLong("id"), rs.getString("display_name")));
    }
}
