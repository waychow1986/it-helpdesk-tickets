package com.ithelpdesk.category;

import java.time.Instant;

/** 工单类别；移除为软删除（active=false）。 */
public record Category(long id, String name, boolean active, Instant createdAt, Instant removedAt) {

    /** 显示名称：已移除的类别标注"（已移除）"。 */
    public String label() {
        return active ? name : name + "（已移除）";
    }
}
