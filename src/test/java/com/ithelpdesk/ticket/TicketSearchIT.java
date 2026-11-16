package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.category.CategoryService;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.user.Role;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 搜索与筛选（FR-022、FR-023、FR-024、INV-003、TD-007、TD-012、IQA-011、IQA-012）。
 * 共享库中存在其他数据，因此每次查询都限定在本次运行创建的类别内，结果与预期集合精确比较。
 */
class TicketSearchIT extends IntegrationTestBase {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private TicketQueryService queries;

    @Autowired
    private CategoryService categories;

    private CurrentUser e1;
    private CurrentUser e2;
    private CurrentUser t1;
    private CurrentUser t2;
    private long catA;
    private long catB;
    private long k1;
    private long k2;
    private long k3;
    private long k4;
    private long k5;

    private long submit(CurrentUser who, String title, String desc, long cat, String localTime) {
        clock.set(LocalDateTime.parse(localTime).atZone(SH).toInstant());
        return commands.submit(who, new TicketForm(title, desc, String.valueOf(cat), "MEDIUM"));
    }

    @BeforeAll
    void seed() {
        e1 = data.user("e1", Role.EMPLOYEE);
        e2 = data.user("e2", Role.EMPLOYEE);
        t1 = data.user("t1", Role.IT_STAFF);
        t2 = data.user("t2", Role.IT_STAFF);
        CurrentUser admin = data.user("admin", Role.ADMIN);
        catA = data.category("类别A");
        catB = data.category("类别B");
        k1 = submit(e1, "打印机卡纸", "无法打印", catA, "2026-03-01T10:00:00");
        k2 = submit(e1, "网络问题", "VPN 连接断开 Error", catA, "2026-03-05T09:00:00");
        k3 = submit(e1, "Outlook 无法启动", "启动报错 100%_done", catB, "2026-03-10T08:00:00");
        k4 = submit(e1, "账号锁定", "control", catB, "2026-03-10T23:59:00");
        k5 = submit(e2, "打印机卡纸 二", "别人的工单", catA, "2026-03-11T00:00:00");
        commands.claim(t1, k2);
        commands.changeStatus(t1, k2, "IN_PROGRESS");
        commands.assign(t1, k3, String.valueOf(t2.getId()));
        commands.changeStatus(t1, k3, "RESOLVED");
        categories.remove(admin, catB);
    }

    private Set<Long> search(CurrentUser who, String q, String status, String category, String assignee, String from,
                             String to) {
        Set<Long> ids = new LinkedHashSet<>();
        List<String> cats = category != null ? List.of(category) : List.of(String.valueOf(catA), String.valueOf(catB));
        for (String cat : cats) {
            TicketSearchCriteria c = TicketSearchCriteria.parse(q, status, cat, assignee, from, to, null, SH);
            queries.search(who, c).items().forEach(t -> ids.add(t.id()));
        }
        return ids;
    }

    @Test
    void keywordMatchesTitleOrDescriptionFragments() {
        assertThat(search(t1, "卡纸", null, null, null, null, null)).containsExactlyInAnyOrder(k1, k5);
        assertThat(search(t1, "打印", null, null, null, null, null)).containsExactlyInAnyOrder(k1, k5);
        assertThat(search(t1, "VPN 连接", null, null, null, null, null)).containsExactly(k2);
        assertThat(search(t1, "不存在的文字XYZ", null, null, null, null, null)).isEmpty();
    }

    @Test
    void keywordCaseInsensitiveAndWildcardsLiteral() {
        assertThat(search(t1, "outlook", null, null, null, null, null)).containsExactly(k3);
        assertThat(search(t1, "ERROR", null, null, null, null, null)).containsExactly(k2);
        assertThat(search(t1, "100%_", null, null, null, null, null)).containsExactly(k3);
        assertThat(search(t1, "%", null, null, null, null, null)).containsExactly(k3);
        assertThat(search(t1, "_", null, null, null, null, null)).containsExactly(k3);
    }

    @Test
    void singleFilters() {
        assertThat(search(t1, null, "OPEN", null, null, null, null)).containsExactlyInAnyOrder(k1, k4, k5);
        assertThat(search(t1, null, "IN_PROGRESS", null, null, null, null)).containsExactly(k2);
        assertThat(search(t1, null, null, String.valueOf(catB), null, null, null))
            .containsExactlyInAnyOrder(k3, k4);
        assertThat(search(t1, null, null, null, String.valueOf(t1.getId()), null, null)).containsExactly(k2);
        assertThat(search(t1, null, null, null, String.valueOf(t2.getId()), null, null)).containsExactly(k3);
        assertThat(search(t1, null, null, null, "unassigned", null, null)).containsExactlyInAnyOrder(k1, k4, k5);
        assertThat(search(t1, null, null, null, null, "2026-03-05", "2026-03-10"))
            .containsExactlyInAnyOrder(k2, k3, k4);
        assertThat(search(t1, null, null, null, null, "2026-03-11", null)).containsExactly(k5);
        assertThat(search(t1, null, null, null, null, null, "2026-03-01")).containsExactly(k1);
    }

    @Test
    void combinedConditionsAllApply() {
        assertThat(search(t1, "打印", "OPEN", String.valueOf(catA), null, null, null))
            .containsExactlyInAnyOrder(k1, k5);
        assertThat(search(t1, "打印", "OPEN", String.valueOf(catA), "unassigned", "2026-03-02", null))
            .containsExactly(k5);
        assertThat(search(t1, "打印", "RESOLVED", String.valueOf(catA), null, null, null)).isEmpty();
    }

    @Test
    void employeeSearchesOnlyOwnTickets() {
        assertThat(search(e1, "卡纸", null, null, null, null, null)).containsExactly(k1);
        assertThat(search(e2, "卡纸", null, null, null, null, null)).containsExactly(k5);
        assertThat(search(e2, null, null, String.valueOf(catB), null, null, null)).isEmpty();
    }

    @Test
    void fromAfterToReturnsNothing() {
        TicketSearchCriteria c = TicketSearchCriteria.parse(null, null, String.valueOf(catA), null, "2026-03-10",
            "2026-03-01", null, SH);
        TicketPage page = queries.search(t1, c);
        assertThat(page.items()).isEmpty();
        assertThat(c.getDateRangeError()).isEqualTo("开始日期不能晚于结束日期");
    }

    @Test
    void paginationFiftyPerPageNewestFirst() {
        long cat = data.category("分页");
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            created.add(submit(e1, "分页 " + i, "d", cat, LocalDateTime.of(2025, 1, 1, 0, 0).plusMinutes(i)
                .toString()));
        }
        TicketPage p1 = queries.search(t1, TicketSearchCriteria.parse(null, null, String.valueOf(cat), null, null,
            null, "1", SH));
        TicketPage p2 = queries.search(t1, TicketSearchCriteria.parse(null, null, String.valueOf(cat), null, null,
            null, "2", SH));
        assertThat(p1.items()).hasSize(50);
        assertThat(p1.hasNext()).isTrue();
        assertThat(p2.items()).hasSize(1);
        assertThat(p2.hasNext()).isFalse();
        assertThat(p1.items().get(0).id()).isEqualTo(created.get(50));
        assertThat(p2.items().get(0).id()).isEqualTo(created.get(0));
        List<Long> order = new ArrayList<>(p1.items().stream().map(Ticket::id).toList());
        order.add(p2.items().get(0).id());
        List<Long> expected = new ArrayList<>(created);
        java.util.Collections.reverse(expected);
        assertThat(order).isEqualTo(expected);
    }
}
