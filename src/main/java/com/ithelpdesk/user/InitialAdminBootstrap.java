package com.ithelpdesk.user;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 启动时幂等引导初始管理员（TD-011）：Flyway 迁移之后执行，事务内取 advisory lock，
 * 库中无任何 ADMIN 时插入 password_hash 为 NULL 的引导账号（不可登录，凭据由运维按 Runbook 设置）。
 * 不生成、读取或输出任何密码或哈希。
 */
@Component
public class InitialAdminBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(InitialAdminBootstrap.class);

    private final UserRepository users;
    private final TransactionOperations tx;
    private final BootstrapProperties properties;
    private final Clock clock;

    public InitialAdminBootstrap(UserRepository users, TransactionOperations tx, BootstrapProperties properties,
                                 Clock clock) {
        this.users = users;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** 执行一次引导；返回是否新建了账号。 */
    public boolean bootstrap() {
        Boolean created = tx.execute(status -> {
            users.acquireBootstrapLock();
            if (users.existsAdmin()) {
                return false;
            }
            boolean inserted = users.insertBootstrapAdmin(properties.adminLoginName(),
                properties.adminDisplayName(), clock.instant());
            if (inserted) {
                LOG.info("已创建初始管理员账号 {}，需按运维手册设置首个凭据", properties.adminLoginName());
            } else {
                LOG.warn("初始管理员登录名已被占用，未创建");
            }
            return inserted;
        });
        return Boolean.TRUE.equals(created);
    }
}
