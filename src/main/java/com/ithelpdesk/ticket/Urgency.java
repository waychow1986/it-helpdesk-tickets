package com.ithelpdesk.ticket;

/** 紧急程度：固定三级低、中、高（PQ-001 A）。 */
public enum Urgency {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高");

    private final String label;

    Urgency(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
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
