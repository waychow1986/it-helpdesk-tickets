package com.ithelpdesk.support;

import com.ithelpdesk.ticket.Ticket;
import com.ithelpdesk.ticket.TicketDetail;
import com.ithelpdesk.ticket.TicketStatus;
import com.ithelpdesk.ticket.Urgency;
import java.time.Instant;
import java.util.List;

/** Web 切片测试用的合成工单。 */
public final class TicketSamples {

    private TicketSamples() {
    }

    public static Ticket ticket(long id, long submitterId) {
        return new Ticket(id, "合成标题-打印机", "合成描述-VPN", 1, "网络", true, Urgency.MEDIUM, submitterId, "员工甲",
            Instant.parse("2026-11-16T01:00:00Z"), TicketStatus.OPEN, null, null, null, null,
            Instant.parse("2026-11-16T01:00:00Z"));
    }

    public static TicketDetail detail(long id, long submitterId) {
        return new TicketDetail(ticket(id, submitterId), List.of(), List.of());
    }
}
