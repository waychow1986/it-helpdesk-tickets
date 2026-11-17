package com.ithelpdesk.ticket;

import com.ithelpdesk.web.BadRequestException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 搜索与筛选条件（FR-022、FR-023、TD-007）。日期为公司时区日期，含首尾两日：
 * submitted_at ∈ [from 00:00, (to+1) 00:00)。参数无法解析时抛出 BadRequestException。
 */
public final class TicketSearchCriteria {

    public static final String UNASSIGNED = "unassigned";

    private final String keyword;
    private final TicketStatus status;
    private final Long categoryId;
    private final boolean unassigned;
    private final Long assigneeId;
    private final LocalDate fromDate;
    private final LocalDate toDate;
    private final Instant fromInstant;
    private final Instant toInstantExclusive;
    private final int page;
    private final String dateRangeError;

    private TicketSearchCriteria(String keyword, TicketStatus status, Long categoryId, boolean unassigned,
                                 Long assigneeId, LocalDate fromDate, LocalDate toDate, ZoneId zone, int page) {
        this.keyword = keyword;
        this.status = status;
        this.categoryId = categoryId;
        this.unassigned = unassigned;
        this.assigneeId = assigneeId;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.fromInstant = fromDate == null ? null : fromDate.atStartOfDay(zone).toInstant();
        this.toInstantExclusive = toDate == null ? null : toDate.plusDays(1).atStartOfDay(zone).toInstant();
        this.page = page;
        this.dateRangeError = fromDate != null && toDate != null && fromDate.isAfter(toDate)
            ? "开始日期不能晚于结束日期" : null;
    }

    /** 无任何条件。 */
    public static TicketSearchCriteria empty(ZoneId zone) {
        return new TicketSearchCriteria(null, null, null, false, null, null, null, zone, 1);
    }

    public static TicketSearchCriteria parse(String q, String status, String categoryId, String assignee,
                                             String from, String to, String page, ZoneId zone) {
        String keyword = blankToNull(q);
        TicketStatus parsedStatus = null;
        if (blankToNull(status) != null) {
            parsedStatus = TicketStatus.parse(status);
            if (parsedStatus == null) {
                throw new BadRequestException("status");
            }
        }
        Long parsedCategory = parsePositiveLong(categoryId, "categoryId");
        boolean isUnassigned = false;
        Long parsedAssignee = null;
        String a = blankToNull(assignee);
        if (UNASSIGNED.equals(a)) {
            isUnassigned = true;
        } else {
            parsedAssignee = parsePositiveLong(a, "assignee");
        }
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        int pageNumber = 1;
        if (blankToNull(page) != null) {
            try {
                pageNumber = Integer.parseInt(page.trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("page");
            }
            if (pageNumber < 1) {
                throw new BadRequestException("page");
            }
        }
        return new TicketSearchCriteria(keyword, parsedStatus, parsedCategory, isUnassigned, parsedAssignee,
            fromDate, toDate, zone, pageNumber);
    }

    /** 转义 LIKE 通配符：\ → \\，% → \%，_ → \_（配合 ESCAPE '\'）。 */
    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public String likePattern() {
        return keyword == null ? null : "%" + escapeLike(keyword) + "%";
    }

    /** 保留当前条件的查询参数（不含页码），用于分页链接。 */
    public Map<String, String> queryParams() {
        Map<String, String> params = new LinkedHashMap<>();
        if (keyword != null) {
            params.put("q", keyword);
        }
        if (status != null) {
            params.put("status", status.name());
        }
        if (categoryId != null) {
            params.put("categoryId", String.valueOf(categoryId));
        }
        if (unassigned) {
            params.put("assignee", UNASSIGNED);
        } else if (assigneeId != null) {
            params.put("assignee", String.valueOf(assigneeId));
        }
        if (fromDate != null) {
            params.put("from", fromDate.toString());
        }
        if (toDate != null) {
            params.put("to", toDate.toString());
        }
        return params;
    }

    public boolean hasConditions() {
        return !queryParams().isEmpty();
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static Long parsePositiveLong(String value, String name) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(v);
            if (parsed <= 0) {
                throw new BadRequestException(name);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BadRequestException(name);
        }
    }

    private static LocalDate parseDate(String value, String name) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(name);
        }
    }

    public String getKeyword() {
        return keyword;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public boolean isUnassigned() {
        return unassigned;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    /** 处理人筛选的表单值（"unassigned" 或 id）。 */
    public String getAssigneeValue() {
        if (unassigned) {
            return UNASSIGNED;
        }
        return assigneeId == null ? "" : String.valueOf(assigneeId);
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public Instant getFromInstant() {
        return fromInstant;
    }

    public Instant getToInstantExclusive() {
        return toInstantExclusive;
    }

    public int getPage() {
        return page;
    }

    public String getDateRangeError() {
        return dateRangeError;
    }
}
