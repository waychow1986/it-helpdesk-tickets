package com.ithelpdesk.support;

import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HexFormat;

/** 运行前缀与随机历史周（数据隔离，design「快速验证指南」）。 */
public final class RunPrefix {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RunPrefix() {
    }

    /** "it-<runId>-"，runId 为 8 位十六进制随机数（不含 LIKE 通配符）。 */
    public static String newPrefix() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return "it-" + HexFormat.of().formatHex(bytes) + "-";
    }

    /** 随机测试密码（只存在于内存）。 */
    public static String randomPassword() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "Pw-" + HexFormat.of().formatHex(bytes);
    }

    /** 1970～2000 年间随机一周的周三 12:00（公司时区）。 */
    public static Instant randomHistoricalWednesdayNoon(ZoneId zone) {
        LocalDate start = LocalDate.of(1971, 1, 4);
        int weeks = 52 * 29;
        LocalDate monday = start.plusWeeks(RANDOM.nextInt(weeks))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.plusDays(2).atTime(LocalTime.NOON).atZone(zone).toInstant();
    }
}
