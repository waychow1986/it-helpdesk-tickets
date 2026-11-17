package com.ithelpdesk.user;

import java.time.Instant;

/** 账号记录。passwordHash 为 null 表示尚未设置首个凭据的引导管理员（视为禁用）。 */
public record AppUser(long id, String loginName, String passwordHash, String displayName, Role role,
                      Instant createdAt, Long createdBy) {
}
