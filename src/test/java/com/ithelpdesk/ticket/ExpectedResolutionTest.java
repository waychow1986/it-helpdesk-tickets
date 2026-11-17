package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 期望解决时间（TD-020、FR-001、INV-001、AC-001、BQ-001 人工答案 A）：自然时间，不顺延。 */
class ExpectedResolutionTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(ZONE).toInstant();
    }

    @Test
    void resolutionHoursPerUrgency() {
        assertThat(Urgency.HIGH.resolutionHours()).isEqualTo(4);
        assertThat(Urgency.MEDIUM.resolutionHours()).isEqualTo(24);
        assertThat(Urgency.LOW.resolutionHours()).isEqualTo(72);
        for (Urgency u : Urgency.values()) {
            assertThat(u.resolutionDuration()).isEqualTo(Duration.ofHours(u.resolutionHours()));
        }
    }

    @Test
    void naturalTimeAcrossNightWeekendAndMonth() {
        // 跨夜：周三 22:30 高紧急 → 周四 02:30
        assertThat(ExpectedResolution.of(at("2026-11-18T22:30:00"), Urgency.HIGH)).isEqualTo(at("2026-11-19T02:30:00"));
        // 跨周末：周五 18:00 低紧急 → 周一 18:00（不顺延周末）
        assertThat(ExpectedResolution.of(at("2026-11-20T18:00:00"), Urgency.LOW)).isEqualTo(at("2026-11-23T18:00:00"));
        // 跨周末：周六 10:15 中紧急 → 周日 10:15
        assertThat(ExpectedResolution.of(at("2026-11-21T10:15:00"), Urgency.MEDIUM))
            .isEqualTo(at("2026-11-22T10:15:00"));
        // 跨月与跨年：12-31 23:00 中紧急 → 次年 01-01 23:00；11-30 20:00 低紧急 → 12-03 20:00
        assertThat(ExpectedResolution.of(at("2026-12-31T23:00:00"), Urgency.MEDIUM))
            .isEqualTo(at("2027-01-01T23:00:00"));
        assertThat(ExpectedResolution.of(at("2026-11-30T20:00:00"), Urgency.LOW)).isEqualTo(at("2026-12-03T20:00:00"));
        assertThat(ExpectedResolution.of(null, Urgency.LOW)).isNull();
    }

    @Test
    void sqlExpressionMatchesEnum() {
        assertThat(ExpectedResolution.sql("t")).isEqualTo("(t.submitted_at + CASE t.urgency "
            + "WHEN 'HIGH' THEN INTERVAL '4 hours' WHEN 'MEDIUM' THEN INTERVAL '24 hours' "
            + "WHEN 'LOW' THEN INTERVAL '72 hours' END)");
        String other = ExpectedResolution.sql("tk");
        assertThat(other).startsWith("(tk.submitted_at + CASE tk.urgency");
        for (Urgency u : Urgency.values()) {
            assertThat(other).contains("WHEN '" + u.name() + "' THEN INTERVAL '" + u.resolutionHours() + " hours'");
        }
    }

    @Test
    void sqlRejectsUnsafeAlias() {
        for (String bad : new String[] {null, "", "t;drop", "T", "1t"}) {
            assertThatThrownBy(() -> ExpectedResolution.sql(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ticketConvenienceConstructorUsesSameRule() {
        Instant submitted = at("2026-11-16T09:00:00");
        Ticket t = new Ticket(1, "t", "d", 1, "c", true, Urgency.HIGH, 2, "s", submitted, TicketStatus.OPEN, null,
            null, null, null, submitted);
        assertThat(t.expectedResolveAt()).isEqualTo(submitted.plus(Duration.ofHours(4)));
        assertThat(t.expectedResolveAtIso()).isEqualTo("2026-11-16T05:00:00Z");
    }
}
