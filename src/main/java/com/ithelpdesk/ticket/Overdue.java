package com.ithelpdesk.ticket;

import java.time.Instant;

/**
 * 超时未解决判定（TD-021，FR-004、FR-006、INV-002）：状态为待处理或处理中，且当前时刻严格晚于期望解决时间。
 * 当前时刻由控制器在每个请求开始时从注入的 Clock 取一次；不在 SQL 中使用 now()。
 */
public final class Overdue {

    private Overdue() {
    }

    public static boolean isOverdue(TicketStatus status, Instant expectedAt, Instant now) {
        if (status == null || expectedAt == null || now == null) {
            return false;
        }
        return (status == TicketStatus.OPEN || status == TicketStatus.IN_PROGRESS) && now.isAfter(expectedAt);
    }
}
