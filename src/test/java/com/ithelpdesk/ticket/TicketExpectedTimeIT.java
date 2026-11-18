package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.category.CategoryService;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.web.AccessDeniedException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 期望解决时间在真实 dev 实例的各读取路径上一致且不随操作改变（TD-020、FR-001～FR-003、INV-001、AC-001、AC-002）。
 * 期望值由测试独立按 submitted_at + 4/24/72 小时计算。
 */
class TicketExpectedTimeIT extends IntegrationTestBase {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final Map<Urgency, Long> HOURS = Map.of(Urgency.HIGH, 4L, Urgency.MEDIUM, 24L, Urgency.LOW, 72L);

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private TicketQueryService queries;

    @Autowired
    private CategoryService categories;

    private Instant storedSubmittedAt(long id) {
        return jdbc.queryForObject("SELECT submitted_at FROM ticket WHERE id = :id",
            new MapSqlParameterSource("id", id), Timestamp.class).toInstant();
    }

    private Instant independentExpected(long id, Urgency urgency) {
        return storedSubmittedAt(id).plus(Duration.ofHours(HOURS.get(urgency)));
    }

    private TicketSearchCriteria criteria(String q, String status, long cat) {
        return TicketSearchCriteria.parse(q, status, String.valueOf(cat), null, null, null, null, SH);
    }

    private void assertEverywhere(CurrentUser employee, CurrentUser staff, long cat, String keyword, long id,
                                  Instant expected) {
        assertThat(queries.detail(employee, id).ticket().expectedResolveAt()).isEqualTo(expected);
        assertThat(queries.detail(staff, id).ticket().expectedResolveAt()).isEqualTo(expected);
        for (CurrentUser who : List.of(employee, staff)) {
            List<TicketSearchCriteria> views = List.of(criteria(null, null, cat), criteria(keyword, null, cat),
                TicketSearchCriteria.parse(keyword, null, null, null, null, null, null, SH));
            for (TicketSearchCriteria c : views) {
                assertThat(queries.search(who, c).items()).filteredOn(t -> t.id() == id).singleElement()
                    .extracting(Ticket::expectedResolveAt).isEqualTo(expected);
            }
        }
    }

    @Test
    void expectedTimeIsSubmittedPlusUrgencyHoursOnEveryReadPath() {
        CurrentUser e1 = data.user("e1", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t1", Role.IT_STAFF);
        long cat = data.category("期望");
        String keyword = data.prefix() + "期望词";
        // 跨夜 / 跨周末 / 跨月：周五 23:30（公司时区）提交
        Instant base = Instant.parse("2026-10-30T15:30:00Z");
        long[] ids = new long[3];
        Urgency[] urgencies = {Urgency.HIGH, Urgency.MEDIUM, Urgency.LOW};
        for (int i = 0; i < 3; i++) {
            clock.set(base.plusSeconds(60L * i));
            ids[i] = commands.submit(e1, new TicketForm(keyword + "-" + urgencies[i], "d", String.valueOf(cat),
                urgencies[i].name()));
        }
        for (int i = 0; i < 3; i++) {
            Instant expected = independentExpected(ids[i], urgencies[i]);
            assertThat(expected).isEqualTo(base.plusSeconds(60L * i).plus(Duration.ofHours(HOURS.get(urgencies[i]))));
            assertEverywhere(e1, t1, cat, keyword, ids[i], expected);
            assertThat(queries.search(t1, criteria(null, "OPEN", cat)).items()).filteredOn(t -> t.id() == ids[0])
                .singleElement().extracting(Ticket::expectedResolveAt).isEqualTo(independentExpected(ids[0],
                    Urgency.HIGH));
        }
        // LOW：2026-10-30 23:32 + 72h = 2026-11-02 23:32（跨月、跨周末，不顺延）
        assertThat(queries.detail(e1, ids[2]).ticket().expectedResolveAt())
            .isEqualTo(Instant.parse("2026-11-02T15:32:00Z"));
    }

    @Test
    void operationsDoNotChangeExpectedTimeAndEmployeeScopeIsKept() {
        CurrentUser admin = data.user("admin", Role.ADMIN);
        CurrentUser e1 = data.user("e2", Role.EMPLOYEE);
        CurrentUser other = data.user("e3", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t2", Role.IT_STAFF);
        CurrentUser t2 = data.user("t3", Role.IT_STAFF);
        long cat = data.category("操作");
        String keyword = data.prefix() + "操作词";
        clock.set(Instant.parse("2026-11-16T01:00:00Z"));
        long id = commands.submit(e1, new TicketForm(keyword, "d", String.valueOf(cat), "HIGH"));
        Instant expected = independentExpected(id, Urgency.HIGH);

        List<Runnable> operations = List.of(
            () -> commands.claim(t1, id),
            () -> commands.assign(t1, id, String.valueOf(t2.getId())),
            () -> commands.changeStatus(t2, id, "IN_PROGRESS"),
            () -> commands.addComment(t2, id, "回复"),
            () -> commands.addComment(e1, id, "补充说明"),
            () -> commands.changeStatus(t2, id, "RESOLVED"),
            () -> categories.rename(admin, cat, data.name("操作改名")),
            () -> commands.changeStatus(t2, id, "CLOSED"),
            () -> categories.remove(admin, cat));
        for (Runnable op : operations) {
            clock.advance(Duration.ofHours(7));
            op.run();
            assertEverywhere(e1, t1, cat, keyword, id, expected);
        }
        assertThat(queries.detail(e1, id).ticket().status()).isEqualTo(TicketStatus.CLOSED);
        assertThatThrownBy(() -> queries.detail(other, id)).isInstanceOf(AccessDeniedException.class);
        assertThat(queries.search(other, TicketSearchCriteria.parse(keyword, null, null, null, null, null, null, SH))
            .items()).isEmpty();
    }
}
