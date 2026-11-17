package com.ithelpdesk.ticket;

/** 工单状态（INV-001）。 */
public enum TicketStatus {
    OPEN("待处理"),
    IN_PROGRESS("处理中"),
    RESOLVED("已解决"),
    CLOSED("已关闭");

    private final String label;

    TicketStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 解析码值；为空或无法识别时返回 null。 */
    public static TicketStatus parse(String code) {
        if (code == null) {
            return null;
        }
        for (TicketStatus value : values()) {
            if (value.name().equals(code.trim())) {
                return value;
            }
        }
        return null;
    }

    /** 显示码值对应的中文；码值为 null 时返回空串。 */
    public static String labelOf(String code) {
        TicketStatus value = parse(code);
        return value == null ? "" : value.getLabel();
    }
}
