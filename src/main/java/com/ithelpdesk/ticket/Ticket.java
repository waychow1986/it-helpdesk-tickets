package com.ithelpdesk.ticket;

import java.time.Instant;

/** 工单视图（含类别、提交人、处理人显示名称）。 */
public record Ticket(long id, String title, String description, long categoryId, String categoryName,
                     boolean categoryActive, Urgency urgency, long submitterId, String submitterName,
                     Instant submittedAt, TicketStatus status, Long assigneeId, String assigneeName,
                     Instant firstResolvedAt, Long firstResolvedAssigneeId, Instant updatedAt) {

    public String categoryLabel() {
        return categoryActive ? categoryName : categoryName + "（已移除）";
    }

    public String assigneeLabel() {
        return assigneeName == null ? "未分配" : assigneeName;
    }
}
