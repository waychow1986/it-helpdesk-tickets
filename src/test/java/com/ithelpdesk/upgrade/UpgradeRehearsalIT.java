package com.ithelpdesk.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.ticket.ExpectedResolution;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 升级演练（真实 dev 实例，一次性 schema it_upgrade_&lt;runId&gt;；TD-029、TD-030、FR-019～FR-021、FR-023、
 * NFR-001、AC-013～AC-015）：target=1 构造 V1 → 写入 V1 数据集 → 快照 → 升级 → 快照逐行逐列相等、active 全为 TRUE、
 * 迁移历史有且仅有版本 1、2 且均成功 → 期望解决时间 = 提交时间 + 4/24/72 小时 → 再次升级执行 0 个且快照仍相等。
 */
class UpgradeRehearsalIT extends IntegrationTestBase {

    private static final Map<String, Long> HOURS = Map.of("HIGH", 4L, "MEDIUM", 24L, "LOW", 72L);

    @Autowired
    private DataSource dataSource;

    private final String schema = UpgradeFixtures.newSchema(null);

    @AfterAll
    void dropSchema() {
        UpgradeFixtures.drop(jdbc, schema);
        assertThat(UpgradeFixtures.schemaExists(jdbc, schema)).isFalse();
    }

    @Test
    void v1DataSurvivesUpgradeAndRepeatedUpgrade() {
        MigrateResult v1 = UpgradeFixtures.flyway(dataSource, schema, "1").migrate();
        assertThat(v1.targetSchemaVersion).isEqualTo("1");
        assertThat(UpgradeFixtures.columnExists(jdbc, schema, "app_user", "active")).isFalse();

        UpgradeFixtures.Dataset dataset = UpgradeFixtures.build(data.prefix(), Instant.now());
        UpgradeFixtures.insert(jdbc, schema, dataset);
        Map<String, List<Map<String, Object>>> before = UpgradeFixtures.snapshot(jdbc, schema, null);
        assertThat(before.get("ticket")).hasSize(24);
        assertThat(before.get("app_user")).hasSize(6);

        MigrateResult upgrade = UpgradeFixtures.flyway(dataSource, schema, null).migrate();
        assertThat(upgrade.migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.targetSchemaVersion).isEqualTo("2");
        assertThat(UpgradeFixtures.diff(before, UpgradeFixtures.snapshot(jdbc, schema, null))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".app_user WHERE NOT active", Map.of(),
            Long.class)).isZero();

        List<Map<String, Object>> history = UpgradeFixtures.history(jdbc, schema);
        assertThat(history).filteredOn(r -> r.get("version") != null && !"0".equals(r.get("version")))
            .extracting(r -> r.get("version")).containsExactly("1", "2");
        assertThat(history).allSatisfy(r -> assertThat(r.get("success")).isEqualTo(Boolean.TRUE));

        List<Map<String, Object>> expected = jdbc.queryForList("SELECT t.id, t.urgency, t.submitted_at, "
            + ExpectedResolution.sql("t") + " AS expected FROM " + schema + ".ticket t ORDER BY t.id", Map.of());
        assertThat(expected).hasSize(24).allSatisfy(r -> assertThat(((Timestamp) r.get("expected")).toInstant())
            .isEqualTo(((Timestamp) r.get("submitted_at")).toInstant()
                .plus(Duration.ofHours(HOURS.get((String) r.get("urgency"))))));

        MigrateResult again = UpgradeFixtures.flyway(dataSource, schema, null).migrate();
        assertThat(again.migrationsExecuted).isZero();
        assertThat(UpgradeFixtures.diff(before, UpgradeFixtures.snapshot(jdbc, schema, null))).isEmpty();
        assertThat(UpgradeFixtures.history(jdbc, schema)).isEqualTo(history);
    }

    @Test
    void diffReportsOnlyIdsAndColumns() {
        Map<String, List<Map<String, Object>>> a = Map.of("ticket", List.of(Map.of("id", 1L, "title", "x")));
        Map<String, List<Map<String, Object>>> b = Map.of("ticket", List.of(Map.of("id", 1L, "title", "y"),
            Map.of("id", 2L, "title", "z")));
        assertThat(UpgradeFixtures.diff(a, b)).containsExactly("ticket#1.title 不同", "ticket#2 新增");
        assertThat(UpgradeFixtures.diff(b, a)).containsExactly("ticket#1.title 不同", "ticket#2 缺失");
    }
}
