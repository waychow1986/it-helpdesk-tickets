package com.ithelpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 迁移在真实 dev 实例上可重复执行、结构与约束生效（V1 TD-004、FR-030、NFR-001、INV-006、INV-007；
 * V2 TD-022、TD-029、FR-021、FR-023、AC-015：当前版本为 2，app_user.active 为 BOOLEAN NOT NULL DEFAULT TRUE）。
 */
class MigrationIT extends IntegrationTestBase {

    @Autowired
    private Flyway flyway;

    @Test
    void migrateIsRepeatableAndKeepsData() {
        long categoryId = data.category("迁移保留");
        MigrateResult again = flyway.migrate();
        assertThat(again.migrationsExecuted).isZero();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        String name = jdbc.queryForObject("SELECT name FROM category WHERE id = :id",
            new MapSqlParameterSource("id", categoryId), String.class);
        assertThat(name).isEqualTo(data.name("迁移保留"));
    }

    @Test
    void versionsOneAndTwoAppliedSuccessfullyOnce() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT version, success FROM flyway_schema_history "
            + "WHERE version IN ('1','2') ORDER BY installed_rank", Map.of());
        assertThat(rows).extracting(r -> r.get("version")).containsExactly("1", "2");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("success")).isEqualTo(Boolean.TRUE));
        Long failed = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success", Map.of(),
            Long.class);
        assertThat(failed).isZero();
    }

    @Test
    void activeColumnIsBooleanNotNullDefaultTrue() {
        Map<String, Object> column = jdbc.queryForMap("SELECT data_type, is_nullable, column_default "
            + "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'app_user' "
            + "AND column_name = 'active'", Map.of());
        assertThat(column.get("data_type")).isEqualTo("boolean");
        assertThat(column.get("is_nullable")).isEqualTo("NO");
        assertThat(column.get("column_default")).isEqualTo("true");
        long id = data.user("默认启用", com.ithelpdesk.user.Role.EMPLOYEE).getId();
        assertThat(jdbc.queryForObject("SELECT active FROM app_user WHERE id = :id",
            new MapSqlParameterSource("id", id), Boolean.class)).isTrue();
    }

    @Test
    void v1TablesKeepTheirColumns() {
        Map<String, List<String>> expected = Map.of(
            "app_user", List.of("id", "login_name", "password_hash", "display_name", "role", "created_at",
                "created_by", "active"),
            "category", List.of("id", "name", "active", "created_at", "removed_at"),
            "ticket", List.of("id", "title", "description", "category_id", "urgency", "submitter_id", "submitted_at",
                "status", "assignee_id", "first_resolved_at", "first_resolved_assignee_id", "updated_at"),
            "ticket_comment", List.of("id", "ticket_id", "author_id", "author_role", "body", "created_at"),
            "ticket_event", List.of("id", "ticket_id", "actor_id", "event_type", "from_assignee_id", "to_assignee_id",
                "from_status", "to_status", "created_at"));
        expected.forEach((table, columns) -> assertThat(jdbc.queryForList("SELECT column_name FROM "
                + "information_schema.columns WHERE table_schema = current_schema() AND table_name = :t "
                + "ORDER BY ordinal_position", new MapSqlParameterSource("t", table), String.class))
            .as(table).containsExactlyElementsOf(columns));
    }

    @Test
    void tablesAndIndexesExist() {
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
            + "WHERE table_schema = current_schema() AND table_name IN "
            + "('app_user','category','ticket','ticket_comment','ticket_event') ORDER BY table_name",
            Map.of(), String.class);
        assertThat(tables).containsExactly("app_user", "category", "ticket", "ticket_comment", "ticket_event");
        List<String> indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()",
            Map.of(), String.class);
        assertThat(indexes).contains("ux_category_active_name", "ix_ticket_submitter", "ix_ticket_submitted",
            "ix_ticket_status", "ix_ticket_category", "ix_ticket_assignee", "ix_ticket_first_resolved",
            "ix_comment_ticket", "ix_event_ticket");
    }

    @Test
    void nullPasswordOnlyAllowedForAdmin() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user(login_name, password_hash, display_name, role, "
                + "created_at) VALUES (:l, NULL, 'x', 'EMPLOYEE', now())",
            new MapSqlParameterSource("l", data.name("nullhash-emp"))))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_app_user_password");
    }

    @Test
    void activeCategoryNamesUnique() {
        data.category("唯一");
        assertThatThrownBy(() -> data.category("唯一")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void checkConstraintsEnforced() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user(login_name, password_hash, display_name, role, "
                + "created_at) VALUES (:l, 'h', 'x', 'ROOT', now())",
            new MapSqlParameterSource("l", data.name("badrole"))))
            .isInstanceOf(DataIntegrityViolationException.class);
        long cat = data.category("约束");
        long submitter = data.user("约束员工", com.ithelpdesk.user.Role.EMPLOYEE).getId();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", cat).addValue("s", submitter);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ticket(title, description, category_id, urgency, "
            + "submitter_id, submitted_at, status, updated_at) VALUES ('t','d',:c,'URGENT',:s,now(),'OPEN',now())", p))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ticket(title, description, category_id, urgency, "
            + "submitter_id, submitted_at, status, updated_at) VALUES ('t','d',:c,'LOW',:s,now(),'DONE',now())", p))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO ticket(title, description, category_id, urgency, submitter_id, submitted_at, status, "
            + "updated_at) VALUES ('t','d',:c,'LOW',:s,now(),'OPEN',now())", p);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ticket_event(ticket_id, actor_id, event_type, created_at) "
            + "SELECT id, :s, 'DELETE', now() FROM ticket WHERE submitter_id = :s", p))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
