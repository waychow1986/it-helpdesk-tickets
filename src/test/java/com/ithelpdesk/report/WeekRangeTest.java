package com.ithelpdesk.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class WeekRangeTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @Test
    void midWeekFallsBackToMonday() {
        // 2026-11-19 是周四；上海时间 10:00
        WeekRange w = WeekRange.containing(Instant.parse("2026-11-19T02:00:00Z"), SH);
        assertThat(w.monday()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(w.sunday()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(w.start()).isEqualTo(Instant.parse("2026-11-15T16:00:00Z"));
        assertThat(w.endExclusive()).isEqualTo(Instant.parse("2026-11-22T16:00:00Z"));
        assertThat(w.label()).isEqualTo("2026-11-16（周一）至 2026-11-22（周日）");
    }

    @Test
    void mondayMidnightBelongsToNewWeekAndSundayEndToOld() {
        WeekRange monday = WeekRange.containing(Instant.parse("2026-11-15T16:00:00Z"), SH);
        assertThat(monday.monday()).isEqualTo(LocalDate.of(2026, 11, 16));
        WeekRange sunday = WeekRange.containing(Instant.parse("2026-11-15T15:59:59Z"), SH);
        assertThat(sunday.monday()).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void utcDateDiffersFromCompanyDate() {
        // UTC 周日 17:00 = 上海周一 01:00
        WeekRange w = WeekRange.containing(Instant.parse("2026-11-22T17:00:00Z"), SH);
        assertThat(w.monday()).isEqualTo(LocalDate.of(2026, 11, 23));
    }

    @Test
    void crossYearIsoWeek() {
        // 2026-01-01 周四，所在周周一为 2025-12-29
        WeekRange w = WeekRange.containing(Instant.parse("2026-01-01T04:00:00Z"), SH);
        assertThat(w.monday()).isEqualTo(LocalDate.of(2025, 12, 29));
        assertThat(w.sunday()).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(w.label()).isEqualTo("2025-12-29（周一）至 2026-01-04（周日）");
    }
}
