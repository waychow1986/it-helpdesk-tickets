package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.web.BadRequestException;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TicketSearchCriteriaTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    private static TicketSearchCriteria parse(String q, String status, String cat, String assignee, String from,
                                              String to, String page) {
        return TicketSearchCriteria.parse(q, status, cat, assignee, from, to, page, SH);
    }

    @Test
    void escapesLikeWildcards() {
        assertThat(TicketSearchCriteria.escapeLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
        assertThat(parse(" 卡纸 ", null, null, null, null, null, null).likePattern()).isEqualTo("%卡纸%");
        assertThat(parse("  ", null, null, null, null, null, null).likePattern()).isNull();
    }

    @Test
    void datesConvertToCompanyZoneHalfOpenRange() {
        TicketSearchCriteria c = parse(null, null, null, null, "2026-11-01", "2026-11-03", null);
        assertThat(c.getFromInstant()).isEqualTo(Instant.parse("2026-10-31T16:00:00Z"));
        assertThat(c.getToInstantExclusive()).isEqualTo(Instant.parse("2026-11-03T16:00:00Z"));
        assertThat(c.getDateRangeError()).isNull();
    }

    @Test
    void fromAfterToReportsError() {
        assertThat(parse(null, null, null, null, "2026-11-05", "2026-11-01", null).getDateRangeError())
            .isEqualTo("开始日期不能晚于结束日期");
    }

    @Test
    void unassignedAndAssigneeParsing() {
        TicketSearchCriteria u = parse(null, null, null, "unassigned", null, null, null);
        assertThat(u.isUnassigned()).isTrue();
        assertThat(u.getAssigneeId()).isNull();
        assertThat(u.getAssigneeValue()).isEqualTo("unassigned");
        TicketSearchCriteria a = parse(null, "IN_PROGRESS", "3", "12", null, null, "2");
        assertThat(a.getAssigneeId()).isEqualTo(12L);
        assertThat(a.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(a.getCategoryId()).isEqualTo(3L);
        assertThat(a.getPage()).isEqualTo(2);
        assertThat(a.queryParams()).containsKeys("status", "categoryId", "assignee").doesNotContainKey("page");
    }

    @Test
    void invalidValuesAreBadRequests() {
        assertThatThrownBy(() -> parse(null, "DONE", null, null, null, null, null))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> parse(null, null, "abc", null, null, null, null))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> parse(null, null, null, "x", null, null, null))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> parse(null, null, null, null, "2026-13-01", null, null))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> parse(null, null, null, null, null, null, "0"))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> parse(null, null, null, null, null, null, "two"))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void pageStartsAtOne() {
        assertThat(parse(null, null, null, null, null, null, null).getPage()).isEqualTo(1);
        assertThat(parse(null, null, null, null, null, null, "").getPage()).isEqualTo(1);
        assertThat(TicketSearchCriteria.empty(SH).hasConditions()).isFalse();
    }
}
