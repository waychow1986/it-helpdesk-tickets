package com.ithelpdesk.ticket;

import java.time.Duration;

/**
 * 紧急程度：固定三级低、中、高（V1 PQ-001 A）。
 * V2：期望解决时长（自然时间，不扣除夜间、周末和节假日；BQ-001 人工答案 A，FR-001、TD-020）——
 * 高 4 小时、中 24 小时、低 72 小时。这是时长的唯一定义处。
 */
public enum Urgency {
    LOW("低", 72),
    MEDIUM("中", 24),
    HIGH("高", 4);

    private final String label;
    private final int resolutionHours;

    Urgency(String label, int resolutionHours) {
        this.label = label;
        this.resolutionHours = resolutionHours;
    }

    public String getLabel() {
        return label;
    }

    /** 期望解决时长（小时）。 */
    public int resolutionHours() {
        return resolutionHours;
    }

    /** 期望解决时长。 */
    public Duration resolutionDuration() {
        return Duration.ofHours(resolutionHours);
    }

    /** 解析码值；为空或无法识别时返回 null。 */
    public static Urgency parse(String code) {
        if (code == null) {
            return null;
        }
        for (Urgency value : values()) {
            if (value.name().equals(code.trim())) {
                return value;
            }
        }
        return null;
    }

    /** 显示码值对应的中文；码值为 null 时返回空串。 */
    public static String labelOf(String code) {
        Urgency value = parse(code);
        return value == null ? "" : value.getLabel();
    }
}
