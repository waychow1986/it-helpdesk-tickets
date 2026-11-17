package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.ithelpdesk.support.LogCapture;
import com.ithelpdesk.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SetupAttemptLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-05T00:00:00Z"), ZoneId.of("UTC"));
    private final SetupAttemptLimiter limiter = new SetupAttemptLimiter(clock);

    @Test
    void sixthAttemptWithinWindowIsBlockedAndRecoversAfterWindow() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.isBlocked("10.0.0.1")).as("attempt %d", i + 1).isFalse();
            limiter.recordFailure("10.0.0.1");
            clock.advance(Duration.ofMinutes(1));
        }
        assertThat(limiter.isBlocked("10.0.0.1")).isTrue();
        // 第一次失败发生在 T0，窗口 15 分钟滑动：T0+15m 时第一次失败移出窗口
        clock.set(Instant.parse("2026-01-05T00:14:59Z"));
        assertThat(limiter.isBlocked("10.0.0.1")).isTrue();
        clock.set(Instant.parse("2026-01-05T00:15:00Z"));
        assertThat(limiter.isBlocked("10.0.0.1")).isFalse();
        clock.set(Instant.parse("2026-01-05T00:30:00Z"));
        assertThat(limiter.isBlocked("10.0.0.1")).isFalse();
        assertThat(limiter.trackedAddresses()).isZero();
    }

    @Test
    void failuresOutsideWindowDoNotAccumulate() {
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("10.0.0.2");
            clock.advance(Duration.ofMinutes(4));
        }
        assertThat(limiter.isBlocked("10.0.0.2")).isFalse();
    }

    @Test
    void addressesAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("10.0.0.3");
        }
        assertThat(limiter.isBlocked("10.0.0.3")).isTrue();
        assertThat(limiter.isBlocked("10.0.0.4")).isFalse();
    }

    @Test
    void evictsOldestEntriesBeyondCapacity() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("first");
        }
        assertThat(limiter.isBlocked("first")).isTrue();
        for (int i = 0; i < SetupAttemptLimiter.MAX_ADDRESSES; i++) {
            limiter.recordFailure("addr-" + i);
        }
        assertThat(limiter.trackedAddresses()).isEqualTo(SetupAttemptLimiter.MAX_ADDRESSES);
        assertThat(limiter.isBlocked("first")).isFalse();
    }

    @Test
    void throttleWarningDoesNotContainAddress() {
        try (LogCapture logs = new LogCapture(SetupAttemptLimiter.class)) {
            for (int i = 0; i < 5; i++) {
                limiter.recordFailure("203.0.113.77");
            }
            assertThat(logs.events()).isEmpty();
            assertThat(limiter.isBlocked("203.0.113.77")).isTrue();
            assertThat(logs.has(Level.WARN)).isTrue();
            assertThat(logs.messages()).containsExactly("设置初始管理员尝试次数超限");
            assertThat(logs.messages()).noneMatch(m -> m.contains("203.0.113.77"));
        }
    }
}
