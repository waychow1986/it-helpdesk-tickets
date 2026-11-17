package com.ithelpdesk.config;

import java.time.ZoneId;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置：公司时区（TD-009）、数据库 schema（TD-016、TD-017）、一次性设置口令（TD-019）。
 * 不读取 AIDELIVERY_* 决定数据位置；toString 不输出设置口令。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String timeZone, Db db, Setup setup) {

    public static final String DEFAULT_SCHEMA = "public";
    public static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    public AppProperties {
        if (timeZone == null || timeZone.isBlank()) {
            timeZone = "Asia/Shanghai";
        }
        if (db == null) {
            db = new Db(null);
        }
        if (setup == null) {
            setup = new Setup(null);
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }

    @Override
    public String toString() {
        return "AppProperties[timeZone=" + timeZone + ", db=" + db + ", setup=" + setup + "]";
    }

    /** 数据库 schema：默认 public；非法值使启动失败（TD-016）。 */
    public record Db(String schema) {

        public Db {
            if (schema == null) {
                schema = DEFAULT_SCHEMA;
            }
            if (!SCHEMA_PATTERN.matcher(schema).matches()) {
                throw new IllegalArgumentException("app.db.schema 必须匹配 " + SCHEMA_PATTERN.pattern());
            }
        }
    }

    /** 一次性设置口令（TD-019）：只由 SetupTokenVerifier 读取，toString 不输出值。 */
    public record Setup(String token) {

        public Setup {
            if (token == null) {
                token = "";
            }
        }

        @Override
        public String toString() {
            return "Setup[token=***]";
        }
    }
}
