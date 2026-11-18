package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 超时判定（TD-021、FR-004、FR-006、INV-002、AC-003、AC-004）。 */
class OverdueTest {

    private static final Instant EXPECTED = Instant.parse("2026-11-16T05:00:00Z");

    @Test
    void onlyUnresolvedAndStrictlyLater() {
        Instant before = EXPECTED.minusSeconds(1);
        Instant later = EXPECTED.plusNanos(1000);
        for (TicketStatus s : TicketStatus.values()) {
            boolean unresolved = s == TicketStatus.OPEN || s == TicketStatus.IN_PROGRESS;
            assertThat(Overdue.isOverdue(s, EXPECTED, before)).as("%s 早于", s).isFalse();
            assertThat(Overdue.isOverdue(s, EXPECTED, EXPECTED)).as("%s 恰好等于", s).isFalse();
            assertThat(Overdue.isOverdue(s, EXPECTED, later)).as("%s 晚于", s).isEqualTo(unresolved);
        }
    }

    @Test
    void statusChangedBackIsJudgedAtThatMoment() {
        Instant now = EXPECTED.plusSeconds(3600);
        assertThat(Overdue.isOverdue(TicketStatus.RESOLVED, EXPECTED, now)).isFalse();
        assertThat(Overdue.isOverdue(TicketStatus.OPEN, EXPECTED, now)).isTrue();
        assertThat(Overdue.isOverdue(TicketStatus.CLOSED, EXPECTED, now)).isFalse();
        assertThat(Overdue.isOverdue(TicketStatus.IN_PROGRESS, EXPECTED, now)).isTrue();
        assertThat(Overdue.isOverdue(null, EXPECTED, now)).isFalse();
        assertThat(Overdue.isOverdue(TicketStatus.OPEN, null, now)).isFalse();
    }

    @Test
    void pageAndDetailUseSameNow() {
        Instant submitted = EXPECTED.minusSeconds(4 * 3600);
        Ticket open = new Ticket(1, "a", "d", 1, "c", true, Urgency.HIGH, 2, "s", submitted, TicketStatus.OPEN, null,
            null, null, null, submitted);
        Ticket resolved = new Ticket(2, "b", "d", 1, "c", true, Urgency.HIGH, 2, "s", submitted,
            TicketStatus.RESOLVED, null, null, null, null, submitted);
        Instant now = EXPECTED.plusSeconds(60);
        TicketPage page = new TicketPage(List.of(open, resolved), 1, false).withOverdue(now);
        assertThat(page.overdue(open)).isTrue();
        assertThat(page.overdue(resolved)).isFalse();
        assertThat(new TicketDetail(open, List.of(), List.of()).withOverdue(now).overdue()).isTrue();
        assertThat(new TicketDetail(open, List.of(), List.of()).withOverdue(EXPECTED).overdue()).isFalse();
    }
}
