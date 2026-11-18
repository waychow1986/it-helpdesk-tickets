package com.ithelpdesk.report;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.config.AppProperties;
import com.ithelpdesk.web.AccessDeniedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 周报表（只读，每次打开实时计算，FR-025～FR-028）。 */
@Service
public class WeeklyReportService {

    public static final Duration BACKLOG_AGE = Duration.ofHours(72);

    private final WeeklyReportRepository repository;
    private final Clock clock;
    private final AppProperties properties;

    public WeeklyReportService(WeeklyReportRepository repository, Clock clock, AppProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public WeeklyReport currentWeek(CurrentUser actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException();
        }
        Instant now = clock.instant();
        WeekRange week = WeekRange.containing(now, properties.zoneId());
        return new WeeklyReport(week, repository.categoryCounts(week), repository.staffCounts(week),
            repository.unassignedResolved(week), repository.averageResolutionSeconds(week),
            repository.backlog(now.minus(BACKLOG_AGE)), repository.overdueResolvedCount(week));
    }
}
