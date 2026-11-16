package com.ithelpdesk.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 初始管理员引导配置（非机密，TD-011）。 */
@ConfigurationProperties(prefix = "app.bootstrap")
public record BootstrapProperties(String adminLoginName, String adminDisplayName) {

    public BootstrapProperties {
        if (adminLoginName == null || adminLoginName.isBlank()) {
            adminLoginName = "admin";
        }
        if (adminDisplayName == null || adminDisplayName.isBlank()) {
            adminDisplayName = "初始管理员";
        }
    }
}
