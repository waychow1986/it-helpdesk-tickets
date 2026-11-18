package com.ithelpdesk.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * ticket-db 认证方式启动校验（TD-031）：应用只实现绑定时选定的 {@code platform-identity}。
 * 在数据源创建前读取 {@code AIDH_DEP_TICKET_DB_AUTH_MODE}：为空或缺失视为 platform-identity；
 * 其他值（含 credential）以 ERROR 日志终止启动（非零退出）。不读取任何凭据变量。
 */
public class DatabaseAuthModeCheck implements EnvironmentPostProcessor {

    public static final String AUTH_MODE_VARIABLE = "AIDH_DEP_TICKET_DB_AUTH_MODE";
    public static final String SUPPORTED_MODE = "platform-identity";

    private final Log log;

    public DatabaseAuthModeCheck(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DatabaseAuthModeCheck.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String mode = environment.getProperty(AUTH_MODE_VARIABLE);
        String problem = check(mode);
        if (problem != null) {
            log.error(problem);
            throw new IllegalStateException(problem);
        }
    }

    /** 返回 null 表示认证方式受支持；否则返回错误说明（只含认证方式名称，不含任何凭据）。 */
    public static String check(String mode) {
        if (mode == null || mode.isBlank() || SUPPORTED_MODE.equals(mode.trim())) {
            return null;
        }
        return "ticket-db 认证方式 " + sanitize(mode.trim()) + " 未在本版本实现";
    }

    /** 认证方式名称只保留可打印的安全字符并限制长度，避免日志注入。 */
    private static String sanitize(String mode) {
        String cleaned = mode.replaceAll("[^A-Za-z0-9._-]", "?");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) + "…" : cleaned;
    }
}
