package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 员工可见范围、补充说明与沟通记录（FR-010～FR-013、FR-019、INV-003、INV-005、TD-014、IQA-003、IQA-004）。 */
class TicketAccessIT extends IntegrationTestBase {

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private TicketQueryService queries;

    @Test
    void employeeSeesOnlyOwnTicketsOfAnyStatus() {
        CurrentUser e1 = data.user("e1", Role.EMPLOYEE);
        CurrentUser e2 = data.user("e2", Role.EMPLOYEE);
        CurrentUser staff = data.user("t1", Role.IT_STAFF);
        long category = data.category("隔离");
        long a = commands.submit(e1, new TicketForm("隔离共同词 甲", "d", String.valueOf(category), "LOW"));
        long b = commands.submit(e1, new TicketForm("隔离共同词 乙", "d", String.valueOf(category), "LOW"));
        long c = commands.submit(e2, new TicketForm("隔离共同词 丙", "d", String.valueOf(category), "LOW"));
        commands.changeStatus(staff, b, "CLOSED");

        TicketPage mine = queries.search(e1, TicketSearchCriteria.empty(ZoneId.of("Asia/Shanghai")));
        assertThat(mine.items()).extracting(Ticket::id).contains(a, b).doesNotContain(c);
        assertThat(mine.items()).allMatch(t -> t.submitterId() == e1.getId());
        TicketPage keyword = queries.search(e1, TicketSearchCriteria.parse("隔离共同词", null, null, null, null, null, null, ZoneId.of("Asia/Shanghai")));
        assertThat(keyword.items()).extracting(Ticket::id).contains(a, b).doesNotContain(c);

        assertThatThrownBy(() -> queries.detail(e1, c)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.detail(e1, Long.MAX_VALUE)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> commands.addComment(e1, c, "越权补充")).isInstanceOf(AccessDeniedException.class);
        assertThat(queries.detail(staff, c).ticket().id()).isEqualTo(c);
    }

    @Test
    void commentsAppendInOrderWithoutChangingStatusOrAssignee() {
        CurrentUser e1 = data.user("e3", Role.EMPLOYEE);
        CurrentUser staff = data.user("t3", Role.IT_STAFF);
        long category = data.category("沟通");
        clock.set(Instant.parse("2026-11-16T01:00:00Z"));
        long id = commands.submit(e1, new TicketForm("沟通测试", "d", String.valueOf(category), "MEDIUM"));
        commands.claim(staff, id);
        commands.changeStatus(staff, id, "CLOSED");
        Ticket before = queries.detail(staff, id).ticket();

        clock.advance(Duration.ofMinutes(1));
        commands.addComment(e1, id, "  员工补充说明  ");
        clock.advance(Duration.ofMinutes(1));
        commands.addComment(staff, id, "IT 回复");
        assertThatThrownBy(() -> commands.addComment(e1, id, "   ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> commands.addComment(staff, id, "")).isInstanceOf(ValidationException.class);

        TicketDetail detail = queries.detail(e1, id);
        assertThat(detail.comments()).extracting(TicketComment::body).containsExactly("员工补充说明", "IT 回复");
        assertThat(detail.comments()).extracting(TicketComment::authorRole)
            .containsExactly(Role.EMPLOYEE, Role.IT_STAFF);
        assertThat(detail.comments().get(0).createdAt()).isBefore(detail.comments().get(1).createdAt());
        assertThat(detail.ticket().status()).isEqualTo(before.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(detail.ticket().assigneeId()).isEqualTo(before.assigneeId()).isEqualTo(staff.getId());
        assertThat(queries.detail(staff, id).comments()).isEqualTo(detail.comments());
    }

    @Test
    void adminCannotReadTickets() {
        CurrentUser admin = data.user("a1", Role.ADMIN);
        assertThatThrownBy(() -> queries.search(admin, TicketSearchCriteria.empty(ZoneId.of("Asia/Shanghai"))))
            .isInstanceOf(AccessDeniedException.class);
    }
}
