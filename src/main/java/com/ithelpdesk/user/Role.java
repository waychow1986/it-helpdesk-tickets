package com.ithelpdesk.user;

/** 账号身份：每个账号恰有一种（INV-006）。 */
public enum Role {
    EMPLOYEE("员工"),
    IT_STAFF("IT 同事"),
    ADMIN("管理员");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 解析码值；无法识别时返回 null。 */
    public static Role parse(String code) {
        if (code == null) {
            return null;
        }
        for (Role role : values()) {
            if (role.name().equals(code.trim())) {
                return role;
            }
        }
        return null;
    }
}
