package com.ithelpdesk.report;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** 自然周：公司时区周一 00:00 至下周一 00:00（左闭右开，TD-009、A-002）。 */
public record WeekRange(LocalDate monday, LocalDate sunday, Instant start, Instant endExclusive) {

    public static WeekRange containing(Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate nextMonday = monday.plusDays(7);
        return new WeekRange(monday, monday.plusDays(6), monday.atStartOfDay(zone).toInstant(),
            nextMonday.atStartOfDay(zone).toInstant());
    }

    /** "YYYY-MM-DD（周一）至 YYYY-MM-DD（周日）"。 */
    public String label() {
        return monday + "（周一）至 " + sunday + "（周日）";
    }
}
