package com.ithelpdesk.report;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ithelpdesk.support.WebSliceTestBase;
import com.ithelpdesk.support.WebSliceTestConfig;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;

/** 周报表模型与页面渲染：V1 四项格式不变，V2 第五项（TD-028、FR-017、FR-018、AC-011、AC-012）。 */
@WebMvcTest
@Import(WebSliceTestConfig.class)
class WeeklyReportTest extends WebSliceTestBase {

    private static WeeklyReport report(Double avg) {
        return report(avg, 0);
    }

    private static WeeklyReport report(Double avg, long overdueResolved) {
        WeekRange week = WeekRange.containing(Instant.parse("2026-11-19T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        return new WeeklyReport(week, List.of(new WeeklyReport.CategoryCount(1, "网络", true, 2)),
            List.of(new WeeklyReport.StaffCount(7, "IT 甲", 1)), 0, avg, 3, overdueResolved);
    }

    private String render(WeeklyReport value) throws Exception {
        when(weeklyReportService.currentWeek(any())).thenReturn(value);
        return mvc.perform(get("/admin/reports/weekly").with(as(admin))).andReturn().getResponse()
            .getContentAsString();
    }

    @Test
    void fifthItemRendersZeroWhenNothingCounted() throws Exception {
        assertThat(report(null).overdueResolved()).isZero();
        String html = render(report(null));
        assertThat(html).contains("本周超时解决的工单数量")
            .contains("data-testid=\"report-overdue-resolved\">0<");
    }

    @Test
    void fifthItemFollowsV1ItemsAndV1FormatsUnchanged() throws Exception {
        String html = render(report(3600.0, 4));
        assertThat(html).contains("data-testid=\"report-overdue-resolved\">4<")
            .contains("data-testid=\"report-avg\" data-avg-seconds=\"3600\">1 小时 0 分钟<")
            .contains("data-testid=\"report-backlog\">3<")
            .contains("data-testid=\"report-week\">2026-11-16（周一）至 2026-11-22（周日）<");
        assertThat(html.indexOf("report-backlog")).isLessThan(html.indexOf("report-overdue-resolved"));
        assertThat(html.indexOf("report-unassigned")).isLessThan(html.indexOf("report-overdue-resolved"));
    }

    @Test
    void noDataShowsPlaceholder() {
        assertThat(report(null).averageDisplay()).isEqualTo("无数据");
        assertThat(report(null).averageSecondsAttr()).isEmpty();
    }

    @Test
    void roundsToMinutesAndAllowsHoursOver24() {
        // 50 小时 29 分 30 秒 → 四舍五入到 50 小时 30 分钟
        assertThat(report(50 * 3600 + 29 * 60 + 30.0).averageDisplay()).isEqualTo("50 小时 30 分钟");
        assertThat(report(50 * 3600 + 29 * 60 + 29.4).averageDisplay()).isEqualTo("50 小时 29 分钟");
        assertThat(report(59.0).averageDisplay()).isEqualTo("0 小时 1 分钟");
        assertThat(report(0.0).averageDisplay()).isEqualTo("0 小时 0 分钟");
    }

    @Test
    void secondsAttributeRoundedToWholeSecond() {
        assertThat(report(3600.5).averageSecondsAttr()).isEqualTo("3601");
        assertThat(report(3600.4).averageSecondsAttr()).isEqualTo("3600");
    }

    @Test
    void removedCategoryLabelled() {
        assertThat(new WeeklyReport.CategoryCount(1, "网络", false, 2).label()).isEqualTo("网络（已移除）");
        assertThat(new WeeklyReport.CategoryCount(1, "网络", true, 2).label()).isEqualTo("网络");
    }
}
