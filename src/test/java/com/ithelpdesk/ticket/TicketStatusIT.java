package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.BadRequestException;
import com.ithelpdesk.web.ValidationException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 状态修改、首次已解决、回复与员工越权（FR-017、FR-018、FR-020、FR-021、INV-001、INV-005、TD-008、IQA-006、IQA-007）。 */
class TicketStatusIT extends IntegrationTestBase {

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private TicketQueryService queries;

    @Test
    void anyTransitionRecordedAndFirstResolvedFrozen() {
        CurrentUser e1 = data.user("e1", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t1", Role.IT_STAFF);
        CurrentUser t2 = data.user("t2", Role.IT_STAFF);
        long cat = data.category("状态");
        clock.set(Instant.parse("2026-11-16T00:00:00Z"));
        long id = commands.submit(e1, new TicketForm("状态流转", "d", String.valueOf(cat), "LOW"));
        commands.claim(t1, id);
        String[] sequence = {"IN_PROGRESS", "RESOLVED", "IN_PROGRESS", "RESOLVED", "CLOSED", "OPEN"};
        Instant firstResolved = null;
        for (int i = 0; i < sequence.length; i++) {
            Instant at = Instant.parse("2026-11-16T01:00:00Z").plusSeconds(600L * i);
            clock.set(at);
            if (i == 2) {
                commands.assign(t1, id, String.valueOf(t2.getId()));
            }
            assertThat(commands.changeStatus(t1, id, sequence[i])).isEqualTo("状态已更新");
            if (firstResolved == null && "RESOLVED".equals(sequence[i])) {
                firstResolved = at;
            }
            assertThat(queries.detail(e1, id).ticket().status()).isEqualTo(TicketStatus.valueOf(sequence[i]));
        }
        assertThat(commands.changeStatus(t1, id, "OPEN")).isEqualTo("状态未变化");

        TicketDetail detail = queries.detail(e1, id);
        assertThat(detail.ticket().firstResolvedAt()).isEqualTo(firstResolved);
        assertThat(detail.ticket().firstResolvedAssigneeId()).isEqualTo(t1.getId());
        assertThat(detail.events().stream().filter(e -> e.eventType() == EventType.STATUS))
            .extracting(TicketEvent::fromStatus, TicketEvent::toStatus)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("OPEN", "IN_PROGRESS"),
                org.assertj.core.groups.Tuple.tuple("IN_PROGRESS", "RESOLVED"),
                org.assertj.core.groups.Tuple.tuple("RESOLVED", "IN_PROGRESS"),
                org.assertj.core.groups.Tuple.tuple("IN_PROGRESS", "RESOLVED"),
                org.assertj.core.groups.Tuple.tuple("RESOLVED", "CLOSED"),
                org.assertj.core.groups.Tuple.tuple("CLOSED", "OPEN"));
        assertThatThrownBy(() -> commands.changeStatus(t1, id, "DONE")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void firstResolvedWithoutAssigneeIsNull() {
        CurrentUser e1 = data.user("e2", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t3", Role.IT_STAFF);
        long cat = data.category("未分配解决");
        long id = commands.submit(e1, new TicketForm("未分配解决", "d", String.valueOf(cat), "LOW"));
        commands.changeStatus(t1, id, "RESOLVED");
        Ticket ticket = queries.detail(t1, id).ticket();
        assertThat(ticket.firstResolvedAt()).isNotNull();
        assertThat(ticket.firstResolvedAssigneeId()).isNull();
    }

    @Test
    void staffReplyKeepsStatusAndAssignee() {
        CurrentUser e1 = data.user("e4", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t4", Role.IT_STAFF);
        long cat = data.category("回复");
        long id = commands.submit(e1, new TicketForm("回复", "d", String.valueOf(cat), "LOW"));
        commands.addComment(t1, id, "已收到，处理中");
        assertThatThrownBy(() -> commands.addComment(t1, id, " \n ")).isInstanceOf(ValidationException.class);
        TicketDetail detail = queries.detail(e1, id);
        assertThat(detail.comments()).singleElement().satisfies(c -> {
            assertThat(c.authorRole()).isEqualTo(Role.IT_STAFF);
            assertThat(c.body()).isEqualTo("已收到，处理中");
        });
        assertThat(detail.ticket().status()).isEqualTo(TicketStatus.OPEN);
        assertThat(detail.ticket().assigneeId()).isNull();
    }

    @Test
    void employeeCannotClaimAssignOrChangeStatus() {
        CurrentUser e1 = data.user("e5", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t5", Role.IT_STAFF);
        long cat = data.category("员工越权");
        long id = commands.submit(e1, new TicketForm("员工越权", "d", String.valueOf(cat), "LOW"));
        assertThatThrownBy(() -> commands.claim(e1, id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> commands.assign(e1, id, String.valueOf(t1.getId())))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> commands.changeStatus(e1, id, "RESOLVED")).isInstanceOf(AccessDeniedException.class);
        TicketDetail detail = queries.detail(e1, id);
        assertThat(detail.ticket().status()).isEqualTo(TicketStatus.OPEN);
        assertThat(detail.ticket().assigneeId()).isNull();
        assertThat(detail.events()).isEmpty();
    }
}
