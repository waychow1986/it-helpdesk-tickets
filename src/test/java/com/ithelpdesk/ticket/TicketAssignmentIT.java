package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.user.UserAdminService;
import com.ithelpdesk.web.ValidationException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** 认领与分派（FR-014、FR-015、FR-016、FR-020、INV-002、INV-005、IQA-005）。 */
class TicketAssignmentIT extends IntegrationTestBase {

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private TicketQueryService queries;

    @Autowired
    private UserAdminService userAdmin;

    @Test
    void staffSeesTicketsOfAllSubmitters() {
        CurrentUser e1 = data.user("e1", Role.EMPLOYEE);
        CurrentUser e2 = data.user("e2", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t1", Role.IT_STAFF);
        long cat = data.category("全部");
        long a = commands.submit(e1, new TicketForm("全部可见甲", "d", String.valueOf(cat), "LOW"));
        long b = commands.submit(e2, new TicketForm("全部可见乙", "d", String.valueOf(cat), "LOW"));
        TicketPage page = queries.search(t1, TicketSearchCriteria.parse(null, null, String.valueOf(cat), null, null,
            null, null, ZoneId.of("Asia/Shanghai")));
        assertThat(page.items()).extracting(Ticket::id).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void claimAndAssignRecordEventsWithBeforeAndAfter() {
        CurrentUser e1 = data.user("e3", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t3", Role.IT_STAFF);
        CurrentUser t2 = data.user("t4", Role.IT_STAFF);
        long cat = data.category("认领");
        long id = commands.submit(e1, new TicketForm("认领分派", "d", String.valueOf(cat), "LOW"));

        clock.set(Instant.parse("2026-11-16T02:00:00Z"));
        assertThat(commands.claim(t1, id)).isEqualTo("已认领该工单");
        assertThat(commands.claim(t1, id)).isEqualTo("您已是该工单处理人");
        clock.set(Instant.parse("2026-11-16T02:01:00Z"));
        assertThat(commands.assign(t1, id, String.valueOf(t2.getId()))).isEqualTo("已分派该工单");
        assertThat(commands.assign(t1, id, String.valueOf(t2.getId()))).isEqualTo("处理人未变化");
        clock.set(Instant.parse("2026-11-16T02:02:00Z"));
        commands.claim(t1, id);

        TicketDetail detail = queries.detail(e1, id);
        assertThat(detail.ticket().assigneeId()).isEqualTo(t1.getId());
        List<TicketEvent> events = detail.events();
        assertThat(events).hasSize(3);
        assertThat(events).extracting(TicketEvent::eventType)
            .containsExactly(EventType.CLAIM, EventType.ASSIGN, EventType.CLAIM);
        assertThat(events.get(0).fromValue()).isEqualTo("未分配");
        assertThat(events.get(0).toValue()).isEqualTo(t1.getDisplayName());
        assertThat(events.get(0).actorName()).isEqualTo(t1.getDisplayName());
        assertThat(events.get(0).createdAt()).isEqualTo(Instant.parse("2026-11-16T02:00:00Z"));
        assertThat(events.get(1).fromValue()).isEqualTo(t1.getDisplayName());
        assertThat(events.get(1).toValue()).isEqualTo(t2.getDisplayName());
        assertThat(events.get(2).fromValue()).isEqualTo(t2.getDisplayName());
        assertThat(events.get(2).toValue()).isEqualTo(t1.getDisplayName());
    }

    @Test
    void assignTargetMustBeAnotherCurrentStaff() {
        CurrentUser admin = data.user("admin", Role.ADMIN);
        CurrentUser e1 = data.user("e5", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t5", Role.IT_STAFF);
        CurrentUser former = data.user("t6", Role.IT_STAFF);
        userAdmin.changeRole(admin, former.getId(), "EMPLOYEE");
        long cat = data.category("分派校验");
        long id = commands.submit(e1, new TicketForm("分派校验", "d", String.valueOf(cat), "LOW"));
        for (long target : new long[] {e1.getId(), admin.getId(), former.getId(), t1.getId(), Long.MAX_VALUE}) {
            assertThatThrownBy(() -> commands.assign(t1, id, String.valueOf(target)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrors())
                    .containsEntry("assigneeId", "分派对象必须是其他 IT 同事"));
        }
        TicketDetail detail = queries.detail(t1, id);
        assertThat(detail.ticket().assigneeId()).isNull();
        assertThat(detail.events()).isEmpty();
        assertThat(queries.assignableStaff(t1)).extracting(u -> u.id())
            .doesNotContain(t1.getId(), former.getId(), e1.getId(), admin.getId());
    }

    @Test
    void failedTransactionRollsBackEntirely() {
        CurrentUser e1 = data.user("e7", Role.EMPLOYEE);
        long cat = data.category("回滚");
        long id = commands.submit(e1, new TicketForm("回滚", "d", String.valueOf(cat), "LOW"));
        CurrentUser ghost = new CurrentUser(Long.MAX_VALUE, "ghost", "幽灵", Role.IT_STAFF, null);
        assertThatThrownBy(() -> commands.claim(ghost, id)).isInstanceOf(DataIntegrityViolationException.class);
        TicketDetail detail = queries.detail(e1, id);
        assertThat(detail.ticket().assigneeId()).isNull();
        assertThat(detail.events()).isEmpty();
    }
}
