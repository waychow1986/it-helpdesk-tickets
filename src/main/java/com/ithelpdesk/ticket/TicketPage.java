package com.ithelpdesk.ticket;

import java.util.List;

/** 一页工单列表（每页 50 条，TD-012）。 */
public record TicketPage(List<Ticket> items, int page, boolean hasNext) {

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
