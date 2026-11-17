package com.ithelpdesk.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyReportTest {

    private static WeeklyReport report(Double avg) {
        WeekRange week = WeekRange.containing(Instant.parse("2026-11-19T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        return new WeeklyReport(week, List.of(), List.of(), 0, avg, 0);
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
