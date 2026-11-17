package com.ithelpdesk.user;

import java.time.Instant;

/**
 * 账号记录。passwordHash 为 null 表示尚未设置首个凭据的引导管理员（视为禁用）；
 * active 为 V2 启用 / 停用状态（TD-022），只由 AccountStatusService 修改。
 */
public record AppUser(long id, String loginName, String passwordHash, String displayName, Role role,
                      Instant createdAt, Long createdBy, boolean active) {

    /** 启用状态的账号（新账号默认启用）。 */
    public AppUser(long id, String loginName, String passwordHash, String displayName, Role role,
                   Instant createdAt, Long createdBy) {
        this(id, loginName, passwordHash, displayName, role, createdAt, createdBy, true);
    }
}
