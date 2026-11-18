package com.ithelpdesk.ticket;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 期望解决时间（TD-020，FR-001、FR-002、FR-020、INV-001）：不落库，由不可变的提交时间与紧急程度派生。
 * 任何 SQL 只能通过 {@link #sql(String)} 获得表达式，时长取自 {@link Urgency#resolutionHours()}。
 */
public final class ExpectedResolution {

    /** 列表 / 搜索 / 筛选 / 详情查询中选出的列别名。 */
    public static final String COLUMN_ALIAS = "expected_resolve_at";

    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9_]{0,30}");

    private ExpectedResolution() {
    }

    /**
     * 生成 {@code (<a>.submitted_at + CASE <a>.urgency WHEN 'HIGH' THEN INTERVAL '4 hours' ... END)}；
     * timestamptz 加小时数与会话时区无关，即自然时间、不顺延。
     */
    public static String sql(String alias) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("invalid table alias");
        }
        StringBuilder sql = new StringBuilder("(").append(alias).append(".submitted_at + CASE ").append(alias)
            .append(".urgency");
        for (Urgency urgency : new Urgency[] {Urgency.HIGH, Urgency.MEDIUM, Urgency.LOW}) {
            sql.append(" WHEN '").append(urgency.name()).append("' THEN INTERVAL '")
                .append(urgency.resolutionHours()).append(" hours'");
        }
        return sql.append(" END)").toString();
    }

    /** Java 侧同口径计算（用于内存构造的视图与测试核对）。 */
    public static Instant of(Instant submittedAt, Urgency urgency) {
        return submittedAt == null || urgency == null ? null : submittedAt.plus(urgency.resolutionDuration());
    }
}
