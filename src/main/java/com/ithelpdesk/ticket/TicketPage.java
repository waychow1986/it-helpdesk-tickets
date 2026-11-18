package com.ithelpdesk.ticket;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一页工单列表（每页 50 条，V1 TD-012）；overdueIds 为本请求 now 下超时未解决的工单（V2 FR-005、TD-021）。
 */
public record TicketPage(List<Ticket> items, int page, boolean hasNext, Set<Long> overdueIds) {

    public TicketPage(List<Ticket> items, int page, boolean hasNext) {
        this(items, page, hasNext, Set.of());
    }

    /** 以本请求的同一当前时刻判定每行的超时标识。 */
    public TicketPage withOverdue(Instant now) {
        Set<Long> ids = items.stream()
            .filter(t -> Overdue.isOverdue(t.status(), t.expectedResolveAt(), now))
            .map(Ticket::id)
            .collect(Collectors.toUnmodifiableSet());
        return new TicketPage(items, page, hasNext, ids);
    }

    public boolean overdue(Ticket ticket) {
        return overdueIds.contains(ticket.id());
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
