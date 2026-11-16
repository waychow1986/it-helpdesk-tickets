package com.ithelpdesk.ticket;

import java.time.Instant;

/** 处理记录：认领、分派、状态修改（只追加，FR-020）。 */
public record TicketEvent(long id, long ticketId, long actorId, String actorName, EventType eventType,
                          String fromAssigneeName, String toAssigneeName, String fromStatus, String toStatus,
                          Instant createdAt) {

    /** 变更前值。 */
    public String fromValue() {
        if (eventType == EventType.STATUS) {
            return TicketStatus.labelOf(fromStatus);
        }
        return fromAssigneeName == null ? "未分配" : fromAssigneeName;
    }

    /** 变更后值。 */
    public String toValue() {
        if (eventType == EventType.STATUS) {
            return TicketStatus.labelOf(toStatus);
        }
        return toAssigneeName == null ? "未分配" : toAssigneeName;
    }

    /** 变更字段名称。 */
    public String fieldLabel() {
        return eventType == EventType.STATUS ? "状态" : "处理人";
    }
}
