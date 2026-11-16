package com.ithelpdesk.ticket;

/** 处理记录类型。 */
public enum EventType {
    CLAIM("认领"),
    ASSIGN("分派"),
    STATUS("状态修改");

    private final String label;

    EventType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 解析码值；为空或无法识别时返回 null。 */
    public static EventType parse(String code) {
        if (code == null) {
            return null;
        }
        for (EventType value : values()) {
            if (value.name().equals(code.trim())) {
                return value;
            }
        }
        return null;
    }

    /** 显示码值对应的中文；码值为 null 时返回空串。 */
    public static String labelOf(String code) {
        EventType value = parse(code);
        return value == null ? "" : value.getLabel();
    }
}
