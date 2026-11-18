package com.ithelpdesk.ticket;

import java.time.Instant;

/**
 * 工单视图（含类别、提交人、处理人显示名称）。
 * expectedResolveAt 为期望解决时间，由查询以 {@link ExpectedResolution#sql(String)} 派生（TD-020）。
 */
public record Ticket(long id, String title, String description, long categoryId, String categoryName,
                     boolean categoryActive, Urgency urgency, long submitterId, String submitterName,
                     Instant submittedAt, TicketStatus status, Long assigneeId, String assigneeName,
                     Instant firstResolvedAt, Long firstResolvedAssigneeId, Instant updatedAt,
                     Instant expectedResolveAt) {

    /** 内存构造（如测试）：期望解决时间按同一口径由提交时间与紧急程度计算。 */
    public Ticket(long id, String title, String description, long categoryId, String categoryName,
                  boolean categoryActive, Urgency urgency, long submitterId, String submitterName,
                  Instant submittedAt, TicketStatus status, Long assigneeId, String assigneeName,
                  Instant firstResolvedAt, Long firstResolvedAssigneeId, Instant updatedAt) {
        this(id, title, description, categoryId, categoryName, categoryActive, urgency, submitterId, submitterName,
            submittedAt, status, assigneeId, assigneeName, firstResolvedAt, firstResolvedAssigneeId, updatedAt,
            ExpectedResolution.of(submittedAt, urgency));
    }

    public String categoryLabel() {
        return categoryActive ? categoryName : categoryName + "（已移除）";
    }

    public String assigneeLabel() {
        return assigneeName == null ? "未分配" : assigneeName;
    }

    /** 期望解决时间的 ISO-8601 UTC 表示（页面属性 data-expected-at，TD-033）。 */
    public String expectedResolveAtIso() {
        return expectedResolveAt == null ? "" : expectedResolveAt.toString();
    }
}
