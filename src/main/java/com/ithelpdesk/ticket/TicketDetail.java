package com.ithelpdesk.ticket;

import java.util.List;

/** 工单详情：基本信息 + 沟通记录 + 处理记录（FR-011、FR-019、FR-020）。 */
public record TicketDetail(Ticket ticket, List<TicketComment> comments, List<TicketEvent> events) {
}
