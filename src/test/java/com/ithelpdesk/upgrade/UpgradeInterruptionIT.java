package com.ithelpdesk.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.ithelpdesk.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 升级中途中断后重跑（真实 dev 实例，一次性 schema；TD-029、TD-030、FR-023、NFR-001、AC-016）：
 * V1 数据集就绪后，测试连接对 app_user 持 ACCESS EXCLUSIVE 锁使 V2 迁移阻塞，再以 pg_terminate_backend 终止迁移连接；
 * dev 角色无权终止会话时（permission denied）改为强制关闭迁移所用的物理连接（design 验证矩阵允许的同等方式），
 * 并等待服务端迁移会话结束 → 迁移失败、迁移历史最高成功版本仍为 1、active 列不存在、快照不变 → 释放锁重跑 →
 * 结构、全部数据与迁移历史与另一 schema 中一次成功升级的结果相同。不使用任何替身。
 */
class UpgradeInterruptionIT extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    private final String interrupted = UpgradeFixtures.newSchema("int");
    private final String reference = UpgradeFixtures.newSchema("ref");

    @AfterAll
    void dropSchemas() {
        UpgradeFixtures.drop(jdbc, interrupted);
        UpgradeFixtures.drop(jdbc, reference);
        assertThat(UpgradeFixtures.schemaExists(jdbc, interrupted)).isFalse();
        assertThat(UpgradeFixtures.schemaExists(jdbc, reference)).isFalse();
    }

    /** 迁移专用数据源：每次新建物理连接并记录，便于强制关闭；服务端每秒检查客户端是否已断开。 */
    static final class AbortableDataSource extends DriverManagerDataSource {
        final List<Connection> opened = new CopyOnWriteArrayList<>();

        AbortableDataSource(String url, String username) {
            super(url, username, null);
            Properties properties = new Properties();
            properties.setProperty("options", "-c client_connection_check_interval=1000");
            setConnectionProperties(properties);
        }

        @Override
        protected Connection getConnectionFromDriver(Properties props) throws SQLException {
            Connection connection = super.getConnectionFromDriver(props);
            opened.add(connection);
            return connection;
        }

        void abortAll() throws SQLException {
            for (Connection connection : opened) {
                connection.abort(Runnable::run);
            }
        }
    }

    private boolean sessionAlive(int pid) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE pid = :pid",
            new MapSqlParameterSource("pid", pid), Long.class);
        return n != null && n > 0;
    }

    /** 先尝试 pg_terminate_backend；无权限时返回 false（由调用方改为关闭物理连接）。 */
    private boolean terminate(int pid) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_terminate_backend(:pid)",
                new MapSqlParameterSource("pid", pid), Boolean.class));
        } catch (DataAccessException e) {
            return false;
        }
    }

    private List<Integer> waitingMigrationPids() {
        return jdbc.queryForList("SELECT l.pid FROM pg_locks l JOIN pg_class c ON c.oid = l.relation "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = :s AND c.relname = 'app_user' "
            + "AND NOT l.granted", new MapSqlParameterSource("s", interrupted), Integer.class);
    }

    private List<Map<String, Object>> structure(String schema) {
        return jdbc.queryForList("SELECT table_name, column_name, ordinal_position, data_type, is_nullable, "
            + "column_default FROM information_schema.columns WHERE table_schema = :s AND table_name <> "
            + "'flyway_schema_history' ORDER BY table_name, ordinal_position", new MapSqlParameterSource("s", schema));
    }

    @Test
    void interruptedUpgradeRollsBackAndRerunEqualsSingleSuccessfulUpgrade() throws Exception {
        UpgradeFixtures.Dataset dataset = UpgradeFixtures.build(data.prefix(), Instant.now());
        for (String schema : List.of(interrupted, reference)) {
            UpgradeFixtures.flyway(dataSource, schema, "1").migrate();
            UpgradeFixtures.insert(jdbc, schema, dataset);
        }
        Map<String, List<Map<String, Object>>> before = UpgradeFixtures.snapshot(jdbc, interrupted, null);
        assertThat(UpgradeFixtures.diff(before, UpgradeFixtures.snapshot(jdbc, reference, null))).isEmpty();

        // 迁移使用独立的物理连接（不经连接池），便于终止其后端会话
        AbortableDataSource migrationSource = new AbortableDataSource(url, username);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("LOCK TABLE " + interrupted + ".app_user IN ACCESS EXCLUSIVE MODE");
            }
            CompletableFuture<MigrateResult> migration = CompletableFuture.supplyAsync(
                () -> UpgradeFixtures.flyway(migrationSource, interrupted, null).migrate());
            List<Integer> pids = List.of();
            for (int i = 0; i < 120 && pids.isEmpty(); i++) {
                Thread.sleep(250);
                pids = waitingMigrationPids();
            }
            assertThat(pids).as("V2 迁移应在 app_user 上等待锁").isNotEmpty();
            boolean terminatedByServer = pids.stream().allMatch(this::terminate);
            if (!terminatedByServer) {
                migrationSource.abortAll();
            }
            Throwable failure = null;
            try {
                migration.get(60, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                failure = e.getCause();
            }
            assertThat(failure).as("被中断的迁移必须失败").isNotNull();
            for (int i = 0; i < 120 && pids.stream().anyMatch(this::sessionAlive); i++) {
                Thread.sleep(250);
            }
            assertThat(pids.stream().anyMatch(this::sessionAlive)).as("迁移会话应在持锁期间结束").isFalse();
            holder.rollback();
        }

        assertThat(UpgradeFixtures.history(jdbc, interrupted)).noneMatch(r -> "2".equals(r.get("version"))
            && Boolean.TRUE.equals(r.get("success")));
        assertThat(UpgradeFixtures.columnExists(jdbc, interrupted, "app_user", "active")).isFalse();
        assertThat(UpgradeFixtures.diff(before, UpgradeFixtures.snapshot(jdbc, interrupted, null))).isEmpty();

        MigrateResult rerun = UpgradeFixtures.flyway(dataSource, interrupted, null).migrate();
        assertThat(rerun.migrationsExecuted).isEqualTo(1);
        MigrateResult once = UpgradeFixtures.flyway(dataSource, reference, null).migrate();
        assertThat(once.migrationsExecuted).isEqualTo(1);

        assertThat(structure(interrupted)).isEqualTo(structure(reference));
        Map<String, List<String>> all = UpgradeFixtures.allColumnsV2();
        assertThat(UpgradeFixtures.diff(UpgradeFixtures.snapshot(jdbc, reference, all),
            UpgradeFixtures.snapshot(jdbc, interrupted, all))).isEmpty();
        assertThat(UpgradeFixtures.diff(before, UpgradeFixtures.snapshot(jdbc, interrupted, null))).isEmpty();
        // 迁移历史：版本化迁移行完全相同（Flyway 建 schema 的标记行含 schema 名，不参与比较），且无失败行
        assertThat(sqlHistory(interrupted)).isEqualTo(sqlHistory(reference));
        assertThat(sqlHistory(interrupted)).extracting(r -> r.get("version")).containsExactly("1", "2");
        assertThat(UpgradeFixtures.history(jdbc, interrupted)).allMatch(r -> Boolean.TRUE.equals(r.get("success")));
    }

    private List<Map<String, Object>> sqlHistory(String schema) {
        return UpgradeFixtures.history(jdbc, schema).stream().filter(r -> "SQL".equals(r.get("type"))).toList();
    }
}
