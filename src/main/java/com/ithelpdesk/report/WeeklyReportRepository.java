package com.ithelpdesk.report;

import com.ithelpdesk.ticket.ExpectedResolution;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 周报表聚合 SQL；时刻参数全部取自应用 Clock，不使用 SQL now()（TD-010）。 */
@Repository
public class WeeklyReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public WeeklyReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static MapSqlParameterSource range(WeekRange week) {
        return new MapSqlParameterSource()
            .addValue("start", Timestamp.from(week.start()))
            .addValue("end", Timestamp.from(week.endExclusive()));
    }

    /** 全部有效类别（无工单为 0）∪ 本周有工单的已移除类别；按名称排序。 */
    public List<WeeklyReport.CategoryCount> categoryCounts(WeekRange week) {
        return jdbc.query("SELECT c.id, c.name, c.active, COUNT(t.id) AS cnt FROM category c "
                + "LEFT JOIN ticket t ON t.category_id = c.id AND t.submitted_at >= :start AND t.submitted_at < :end "
                + "GROUP BY c.id HAVING c.active OR COUNT(t.id) > 0 ORDER BY c.name, c.id",
            range(week),
            (rs, i) -> new WeeklyReport.CategoryCount(rs.getLong("id"), rs.getString("name"),
                rs.getBoolean("active"), rs.getLong("cnt")));
    }

    /** 当前全部 IT 同事（无则 0）∪ 本周有计数的其他账号；按姓名排序。 */
    public List<WeeklyReport.StaffCount> staffCounts(WeekRange week) {
        return jdbc.query("SELECT u.id, u.display_name, COUNT(t.id) AS cnt FROM app_user u "
                + "LEFT JOIN ticket t ON t.first_resolved_assignee_id = u.id AND t.first_resolved_at >= :start "
                + "AND t.first_resolved_at < :end "
                + "GROUP BY u.id HAVING u.role = 'IT_STAFF' OR COUNT(t.id) > 0 ORDER BY u.display_name, u.id",
            range(week),
            (rs, i) -> new WeeklyReport.StaffCount(rs.getLong("id"), rs.getString("display_name"),
                rs.getLong("cnt")));
    }

    /** 本周首次解决时无处理人的工单数（"未分配"行）。 */
    public long unassignedResolved(WeekRange week) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE first_resolved_assignee_id IS NULL "
                + "AND first_resolved_at >= :start AND first_resolved_at < :end", range(week), Long.class);
        return count == null ? 0 : count;
    }

    /** 本周首次解决工单的平均（首次已解决时刻 − 提交时刻）秒数；无工单为 null。 */
    public Double averageResolutionSeconds(WeekRange week) {
        BigDecimal avg = jdbc.queryForObject("SELECT AVG(EXTRACT(EPOCH FROM (first_resolved_at - submitted_at))) "
                + "FROM ticket WHERE first_resolved_at >= :start AND first_resolved_at < :end",
            range(week), BigDecimal.class);
        return avg == null ? null : avg.doubleValue();
    }

    /** 积压：状态为待处理 / 处理中且提交早于 (now − 72 小时)（严格小于），不受本周限制（FR-028）。 */
    public long backlog(Instant threshold) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE status IN ('OPEN','IN_PROGRESS') "
                + "AND submitted_at < :threshold",
            new MapSqlParameterSource("threshold", Timestamp.from(threshold)), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 本周超时解决：首次已解决时刻在本周内且严格晚于期望解决时间的工单数（FR-017、BQ-002 人工答案 A、TD-028）。
     * 再次解决、只关闭从未解决、恰好等于期望解决时间均不计入；首次解决后状态再变不影响计入。
     */
    public long overdueResolvedCount(WeekRange week) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ticket t WHERE t.first_resolved_at >= :start "
                + "AND t.first_resolved_at < :end AND t.first_resolved_at > " + ExpectedResolution.sql("t"),
            range(week), Long.class);
        return count == null ? 0 : count;
    }
}
