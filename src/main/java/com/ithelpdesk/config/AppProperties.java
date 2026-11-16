package com.ithelpdesk.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 应用配置：公司时区（TD-009）。 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String timeZone) {

    public AppProperties {
        if (timeZone == null || timeZone.isBlank()) {
            timeZone = "Asia/Shanghai";
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }
}
