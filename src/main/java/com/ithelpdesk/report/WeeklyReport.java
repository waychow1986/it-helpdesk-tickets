package com.ithelpdesk.report;

import java.util.List;

/** 周报表四项指标（FR-026～FR-028）。averageSeconds 为 null 表示无数据。 */
public record WeeklyReport(WeekRange week, List<CategoryCount> categories, List<StaffCount> staff,
                           long unassignedResolved, Double averageSeconds, long backlog) {

    /** 每个类别的工单数量（本周提交）。 */
    public record CategoryCount(long categoryId, String name, boolean active, long count) {
        public String label() {
            return active ? name : name + "（已移除）";
        }
    }

    /** 每位 IT 同事处理量（本周首次解决，归属当时处理人）。 */
    public record StaffCount(long userId, String displayName, long count) {
    }

    /** 平均处理时长显示：四舍五入到分钟，"H 小时 M 分钟"，H 可超过 24；无数据显示"无数据"。 */
    public String averageDisplay() {
        if (averageSeconds == null) {
            return "无数据";
        }
        long minutes = Math.round(averageSeconds / 60.0);
        return (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟";
    }

    /** 四舍五入到整秒（供自动化核对）；无数据为空串。 */
    public String averageSecondsAttr() {
        return averageSeconds == null ? "" : String.valueOf(Math.round(averageSeconds));
    }
}
