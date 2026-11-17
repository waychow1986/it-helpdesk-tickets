package com.ithelpdesk.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.category.CategoryService;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.support.RunPrefix;
import com.ithelpdesk.ticket.TicketCommandService;
import com.ithelpdesk.ticket.TicketForm;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.user.UserAdminService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 周报表四项口径（V1 FR-026～FR-028、PQ-002 A、TD-008、TD-010、IQA-013、IQA-014）与 V2 第五项
 * “本周超时解决的工单数量”（TD-028、FR-017、FR-018、FR-022、AC-011、AC-012、BQ-002 人工答案 A）。
 * 测试时钟设到 1970～2000 年间随机一周，使本周统计只包含本测试数据。
 */
class WeeklyReportIT extends IntegrationTestBase {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final Duration DAY = Duration.ofDays(1);

    @Autowired
    private WeeklyReportService reports;

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private CategoryService categories;

    @Autowired
    private UserAdminService userAdmin;

    private long submit(CurrentUser who, long cat, Instant at) {
        return submit(who, cat, at, "LOW");
    }

    private long submit(CurrentUser who, long cat, Instant at, String urgency) {
        clock.set(at);
        return commands.submit(who, new TicketForm("报表合成", "d", String.valueOf(cat), urgency));
    }

    private void status(CurrentUser staff, long id, String status, Instant at) {
        clock.set(at);
        commands.changeStatus(staff, id, status);
    }

    private void claim(CurrentUser staff, long id, Instant at) {
        clock.set(at);
        commands.claim(staff, id);
    }

    @Test
    void fourMetricsFollowApprovedDefinitions() {
        Instant now = RunPrefix.randomHistoricalWednesdayNoon(SH);
        WeekRange week = WeekRange.containing(now, SH);
        Instant mon = week.start();
        Instant lastMon = mon.minus(Duration.ofDays(7));

        CurrentUser admin = data.user("admin", Role.ADMIN);
        CurrentUser emp = data.user("emp", Role.EMPLOYEE);
        CurrentUser s1 = data.user("s1", Role.IT_STAFF);
        CurrentUser s2 = data.user("s2", Role.IT_STAFF);
        CurrentUser s3 = data.user("s3", Role.IT_STAFF);
        long catX = data.category("X");
        long catY = data.category("Y");
        long catZ = data.category("Z");

        // a：上周提交、本周首次解决（S1）→ 计入 S1 与平均，不计入类别数量
        long a = submit(emp, catX, lastMon.plus(Duration.ofHours(10)));
        claim(s1, a, lastMon.plus(Duration.ofHours(11)));
        status(s1, a, "RESOLVED", mon.plus(Duration.ofHours(10)));
        // b：本周提交、本周解决（S1）
        long b = submit(emp, catX, mon.plus(Duration.ofHours(9)));
        claim(s1, b, mon.plus(Duration.ofHours(9)).plusSeconds(60));
        status(s1, b, "RESOLVED", mon.plus(DAY).plus(Duration.ofHours(9)));
        // c：本周提交、无处理人时首次解决 → "未分配"
        long c = submit(emp, catY, mon.plus(Duration.ofHours(8)));
        status(s1, c, "RESOLVED", mon.plus(DAY).plus(Duration.ofHours(8)));
        // d：上周提交、上周首次解决，本周改回后再次解决 → 不计入处理量与平均
        long d = submit(emp, catX, lastMon.plus(Duration.ofHours(1)));
        claim(s1, d, lastMon.plus(Duration.ofHours(2)));
        status(s1, d, "RESOLVED", lastMon.plus(Duration.ofHours(3)));
        status(s1, d, "IN_PROGRESS", mon.plus(Duration.ofHours(1)));
        status(s1, d, "RESOLVED", mon.plus(Duration.ofHours(2)));
        // e：本周提交、分派给 S2 后解决；S2 之后被取消 IT 同事身份仍显示
        long e = submit(emp, catY, mon.plus(Duration.ofHours(12)));
        clock.set(mon.plus(Duration.ofHours(13)));
        commands.assign(s1, e, String.valueOf(s2.getId()));
        status(s2, e, "RESOLVED", mon.plus(Duration.ofDays(2)).plus(Duration.ofHours(10)));
        // f：本周提交、未解决
        long f = submit(emp, catX, mon.plus(Duration.ofHours(14)));

        clock.set(now);
        userAdmin.changeRole(admin, s2.getId(), "EMPLOYEE");
        categories.remove(admin, catY);

        WeeklyReport report = reports.currentWeek(admin);
        assertThat(report.week()).isEqualTo(week);
        Map<Long, WeeklyReport.CategoryCount> cats = report.categories().stream()
            .collect(Collectors.toMap(WeeklyReport.CategoryCount::categoryId, Function.identity()));
        assertThat(cats.get(catX).count()).isEqualTo(2);
        assertThat(cats.get(catY).count()).isEqualTo(2);
        assertThat(cats.get(catY).label()).endsWith("（已移除）");
        assertThat(cats.get(catZ).count()).isZero();

        Map<Long, Long> staff = report.staff().stream()
            .collect(Collectors.toMap(WeeklyReport.StaffCount::userId, WeeklyReport.StaffCount::count));
        assertThat(staff).containsEntry(s1.getId(), 2L).containsEntry(s2.getId(), 1L).containsEntry(s3.getId(), 0L);
        assertThat(report.unassignedResolved()).isEqualTo(1);

        double expectedAvg = (7 * 86400 + 86400 + 86400 + 46 * 3600) / 4.0;
        assertThat(report.averageSeconds()).isEqualTo(expectedAvg);
        assertThat(report.averageDisplay()).isEqualTo("65 小时 30 分钟");
        assertThat(report.averageSecondsAttr()).isEqualTo("235800");

        // 独立 SQL 核对平均值
        Double independent = jdbc.queryForObject("SELECT AVG(EXTRACT(EPOCH FROM first_resolved_at - submitted_at))"
                + "::float8 FROM ticket WHERE first_resolved_at >= :s AND first_resolved_at < :e",
            new MapSqlParameterSource().addValue("s", Timestamp.from(week.start()))
                .addValue("e", Timestamp.from(week.endExclusive())), Double.class);
        assertThat(report.averageSeconds()).isEqualTo(independent);
        assertThat(d).isPositive();
        assertThat(f).isPositive();
    }

    @Test
    void emptyWeekShowsZeroAndNoData() {
        CurrentUser admin = data.user("admin2", Role.ADMIN);
        long cat = data.category("空周");
        clock.set(RunPrefix.randomHistoricalWednesdayNoon(SH));
        WeeklyReport report = reports.currentWeek(admin);
        assertThat(report.categories()).filteredOn(c -> c.categoryId() == cat)
            .singleElement().extracting(WeeklyReport.CategoryCount::count).isEqualTo(0L);
        assertThat(report.unassignedResolved()).isZero();
        assertThat(report.averageSeconds()).isNull();
        assertThat(report.averageDisplay()).isEqualTo("无数据");
        assertThat(report.overdueResolved()).isZero();
    }

    @Test
    void fifthMetricCountsFirstResolvedThisWeekStrictlyAfterExpected() {
        Instant now = RunPrefix.randomHistoricalWednesdayNoon(SH);
        WeekRange week = WeekRange.containing(now, SH);
        Instant mon = week.start();
        Instant lastMon = mon.minus(Duration.ofDays(7));
        CurrentUser admin = data.user("admin5", Role.ADMIN);
        CurrentUser emp = data.user("emp5", Role.EMPLOYEE);
        CurrentUser st = data.user("st5", Role.IT_STAFF);
        long cat = data.category("超时解决");

        // 恰好等于期望解决时间：不计入
        long exact = submit(emp, cat, mon.plus(Duration.ofHours(1)), "HIGH");
        status(st, exact, "RESOLVED", mon.plus(Duration.ofHours(5)));
        // 本周首次解决且晚于期望：计入
        long late = submit(emp, cat, mon.plus(Duration.ofHours(1)), "HIGH");
        status(st, late, "RESOLVED", mon.plus(Duration.ofHours(5)).plusSeconds(1));
        // 早于期望：不计入
        long early = submit(emp, cat, mon.plus(Duration.ofHours(2)), "MEDIUM");
        status(st, early, "RESOLVED", mon.plus(Duration.ofHours(20)));
        // 上周首次解决（已超时），本周改回后再次解决：不计入
        long again = submit(emp, cat, lastMon.plus(Duration.ofHours(1)), "LOW");
        status(st, again, "RESOLVED", lastMon.plus(Duration.ofHours(80)));
        status(st, again, "IN_PROGRESS", mon.plus(Duration.ofHours(3)));
        status(st, again, "RESOLVED", mon.plus(Duration.ofHours(4)));
        // 只关闭、从未解决：不计入
        long closed = submit(emp, cat, mon.plus(Duration.ofHours(1)), "HIGH");
        status(st, closed, "CLOSED", mon.plus(Duration.ofHours(10)));
        // 首次解决（已超时）后改回待处理：仍计入
        long reopened = submit(emp, cat, mon.plus(Duration.ofHours(1)), "HIGH");
        status(st, reopened, "RESOLVED", mon.plus(Duration.ofHours(6)));
        status(st, reopened, "OPEN", mon.plus(Duration.ofHours(7)));
        // 上周提交、本周首次解决且晚于期望：计入
        long carried = submit(emp, cat, lastMon.plus(Duration.ofHours(1)), "LOW");
        status(st, carried, "RESOLVED", mon.plus(Duration.ofHours(10)));

        clock.set(now);
        WeeklyReport report = reports.currentWeek(admin);
        assertThat(report.overdueResolved()).isEqualTo(3);
        // 独立 SQL（时长常量在测试中独立写出）
        long independent = data.count("SELECT COUNT(*) FROM ticket WHERE first_resolved_at >= :s "
            + "AND first_resolved_at < :e AND first_resolved_at > submitted_at + CASE urgency WHEN 'HIGH' THEN "
            + "INTERVAL '4 hours' WHEN 'MEDIUM' THEN INTERVAL '1 day' ELSE INTERVAL '3 days' END",
            new MapSqlParameterSource().addValue("s", Timestamp.from(week.start()))
                .addValue("e", Timestamp.from(week.endExclusive())));
        assertThat(report.overdueResolved()).isEqualTo(independent);
        assertThat(List.of(exact, late, early, again, closed, reopened, carried)).doesNotHaveDuplicates();
    }

    @Test
    void backlogCountsOpenOrInProgressOlderThan72HoursStrictly() {
        Instant now = RunPrefix.randomHistoricalWednesdayNoon(SH);
        CurrentUser admin = data.user("admin3", Role.ADMIN);
        CurrentUser emp = data.user("emp3", Role.EMPLOYEE);
        CurrentUser st = data.user("st3", Role.IT_STAFF);
        long cat = data.category("积压");
        clock.set(now);
        long baseline = reports.currentWeek(admin).backlog();

        Instant threshold = now.minus(Duration.ofHours(72));
        submit(emp, cat, threshold);                                           // 恰好 72 小时：不计入
        long h = submit(emp, cat, threshold.minusSeconds(1));                  // 超过 72 小时待处理：计入
        long i = submit(emp, cat, now.minus(Duration.ofDays(21)));             // 本周之前提交、处理中：计入
        status(st, i, "IN_PROGRESS", now.minus(Duration.ofDays(20)));
        long j = submit(emp, cat, now.minus(Duration.ofHours(100)));           // 已关闭：不计入
        status(st, j, "CLOSED", now.minus(Duration.ofHours(90)));
        long k = submit(emp, cat, now.minus(Duration.ofHours(100)));           // 已解决：不计入
        status(st, k, "RESOLVED", now.minus(Duration.ofHours(90)));
        submit(emp, cat, now.minus(Duration.ofHours(10)));                     // 未满 72 小时：不计入

        clock.set(now);
        long backlog = reports.currentWeek(admin).backlog();
        assertThat(backlog).isEqualTo(baseline + 2);
        long independent = data.count("SELECT COUNT(*) FROM ticket WHERE status IN ('OPEN','IN_PROGRESS') "
            + "AND submitted_at < :t", new MapSqlParameterSource("t", Timestamp.from(threshold)));
        assertThat(backlog).isEqualTo(independent);

        status(st, h, "RESOLVED", now);
        assertThat(reports.currentWeek(admin).backlog()).isEqualTo(backlog - 1);
    }
}
