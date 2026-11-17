package com.ithelpdesk.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 设置初始管理员失败限流（TD-019）：内存中按来源地址计数，15 分钟滑动窗口内失败 5 次后该地址在窗口剩余时间内被拒绝；
 * 最多保存 10000 个地址，超出时淘汰最早的条目；进程重启清零。日志不记录来源地址。
 */
@Component
public class SetupAttemptLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(15);
    public static final int MAX_FAILURES = 5;
    public static final int MAX_ADDRESSES = 10_000;

    private static final Logger LOG = LoggerFactory.getLogger(SetupAttemptLimiter.class);

    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new LinkedHashMap<>();

    public SetupAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 该地址是否已触发限流；触发时记录 WARN（不含地址）。 */
    public synchronized boolean isBlocked(String address) {
        Instant now = clock.instant();
        Deque<Instant> times = prune(address, now);
        boolean blocked = times != null && times.size() >= MAX_FAILURES;
        if (blocked) {
            LOG.warn("设置初始管理员尝试次数超限");
        }
        return blocked;
    }

    /** 记录一次失败。 */
    public synchronized void recordFailure(String address) {
        Instant now = clock.instant();
        Deque<Instant> times = prune(address, now);
        if (times == null) {
            times = new ArrayDeque<>();
            failures.put(address, times);
            evictOverflow();
        }
        times.addLast(now);
        while (times.size() > MAX_FAILURES) {
            times.removeFirst();
        }
    }

    synchronized int trackedAddresses() {
        return failures.size();
    }

    private Deque<Instant> prune(String address, Instant now) {
        Deque<Instant> times = failures.get(address);
        if (times == null) {
            return null;
        }
        Instant cutoff = now.minus(WINDOW);
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
            times.removeFirst();
        }
        if (times.isEmpty()) {
            failures.remove(address);
            return null;
        }
        return times;
    }

    private void evictOverflow() {
        Iterator<String> it = failures.keySet().iterator();
        while (failures.size() > MAX_ADDRESSES && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
