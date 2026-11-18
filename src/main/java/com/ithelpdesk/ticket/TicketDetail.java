package com.ithelpdesk.ticket;

import java.time.Instant;
import java.util.List;

/**
 * 工单详情：基本信息 + 沟通记录 + 处理记录（V1 FR-011、FR-019、FR-020）；
 * overdue 为本请求 now 下的超时未解决标识（V2 FR-005、TD-021）。
 */
public record TicketDetail(Ticket ticket, List<TicketComment> comments, List<TicketEvent> events, boolean overdue) {

    public TicketDetail(Ticket ticket, List<TicketComment> comments, List<TicketEvent> events) {
        this(ticket, comments, events, false);
    }

    /** 以本请求的当前时刻判定超时标识。 */
    public TicketDetail withOverdue(Instant now) {
        return new TicketDetail(ticket, comments, events,
            Overdue.isOverdue(ticket.status(), ticket.expectedResolveAt(), now));
    }
}
