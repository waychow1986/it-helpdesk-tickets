package com.ithelpdesk.upgrade;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 升级演练共用工具（TD-030、FR-019、NFR-001、IQA-009；spec 测试策略「开发者验证」）：
 * 在一次性 schema 上以 Flyway target=1 应用已提交的 V1__init.sql 构造 V1 状态；按 V1 数据契约以 SQL 写入
 * 覆盖 IQA-009 原前置条件的 V1 合成数据集；对 V1 全部表的 V1 列按主键排序生成快照并比对（差异只输出表名、id 与列名）。
 * 全部数据为合成数据；账号密码为运行时随机值，只存在于内存。
 */
public final class UpgradeFixtures {

    /** V1 表与其 V1 列（V2 只给 app_user 增加 active 列，不在 V1 快照内）。 */
    public static final Map<String, List<String>> V1_COLUMNS = v1Columns();

    private static final Pattern SCHEMA = Pattern.compile("^it_upgrade_[a-z0-9_]{1,40}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String[] URGENCIES = {"HIGH", "MEDIUM", "LOW"};
    private static final String[] STATUSES = {"OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"};

    private UpgradeFixtures() {
    }

    private static Map<String, List<String>> v1Columns() {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("app_user", List.of("id", "login_name", "password_hash", "display_name", "role", "created_at",
            "created_by"));
        columns.put("category", List.of("id", "name", "active", "created_at", "removed_at"));
        columns.put("ticket", List.of("id", "title", "description", "category_id", "urgency", "submitter_id",
            "submitted_at", "status", "assignee_id", "first_resolved_at", "first_resolved_assignee_id",
            "updated_at"));
        columns.put("ticket_comment", List.of("id", "ticket_id", "author_id", "author_role", "body", "created_at"));
        columns.put("ticket_event", List.of("id", "ticket_id", "actor_id", "event_type", "from_assignee_id",
            "to_assignee_id", "from_status", "to_status", "created_at"));
        return java.util.Collections.unmodifiableMap(columns);
    }

    /** 一次性 schema 名称：it_upgrade_&lt;随机十六进制&gt;[_suffix]。 */
    public static String newSchema(String suffix) {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return checked("it_upgrade_" + HexFormat.of().formatHex(bytes) + (suffix == null ? "" : "_" + suffix));
    }

    static String checked(String schema) {
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("一次性 schema 名称不合法");
        }
        return schema;
    }

    /** 与应用相同的 Flyway 配置（application.yml：baseline-on-migrate、baseline-version=0）；target 为 null 表示最新。 */
    public static Flyway flyway(DataSource dataSource, String schema, String target) {
        FluentConfiguration config = Flyway.configure().dataSource(dataSource).schemas(checked(schema))
            .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    public static void drop(NamedParameterJdbcTemplate jdbc, String schema) {
        jdbc.getJdbcOperations().execute("DROP SCHEMA IF EXISTS " + checked(schema) + " CASCADE");
    }

    public static boolean schemaExists(NamedParameterJdbcTemplate jdbc, String schema) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM pg_namespace WHERE nspname = :s",
            new MapSqlParameterSource("s", schema), Long.class);
        return n != null && n > 0;
    }

    public static boolean columnExists(NamedParameterJdbcTemplate jdbc, String schema, String table, String column) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = :s "
            + "AND table_name = :t AND column_name = :c",
            new MapSqlParameterSource().addValue("s", schema).addValue("t", table).addValue("c", column), Long.class);
        return n != null && n > 0;
    }

    /** 迁移历史（按 installed_rank）：version、description、type、script、checksum、success。 */
    public static List<Map<String, Object>> history(NamedParameterJdbcTemplate jdbc, String schema) {
        return jdbc.queryForList("SELECT version, description, type, script, checksum, success FROM "
            + checked(schema) + ".flyway_schema_history ORDER BY installed_rank", Map.of());
    }

    // ---------------- V1 合成数据集 ----------------

    /** 一条待写入的行：表名 + 有序列值（不含 id，id 由 identity 按写入顺序生成）。 */
    public record Row(String table, Map<String, Object> values) {
    }

    /** V1 合成数据集：账号（含原始随机密码，仅内存）与按写入顺序排列的行。 */
    public record Dataset(Instant now, Map<String, String> passwords, List<Row> rows) {
    }

    /**
     * 构造覆盖 IQA-009 原前置条件的 V1 数据集：员工 / IT 同事 / 管理员各至少一个（带随机密码的 BCrypt 哈希）、
     * 被取消 IT 同事身份的账号、有效 / 已移除 / 改名类别、三种紧急程度 × 四种状态 × 有无处理人、
     * 已超期与未超期的未解决工单、含补充说明 / 回复 / 认领 / 分派 / 状态事件的工单、本周首次已解决工单。
     * 写入顺序固定，因此在两个全新 schema 中写入同一 Dataset 得到相同的 id。
     */
    public static Dataset build(String prefix, Instant now) {
        Map<String, String> passwords = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        Instant created = now.minus(Duration.ofDays(40));
        String[][] users = {
            {"admin", "ADMIN", "V1 管理员"}, {"emp1", "EMPLOYEE", "V1 员工甲"}, {"emp2", "EMPLOYEE", "V1 员工乙"},
            {"it1", "IT_STAFF", "V1 IT 甲"}, {"it2", "IT_STAFF", "V1 IT 乙"},
            {"former", "EMPLOYEE", "V1 前 IT 同事"}};
        for (String[] u : users) {
            String password = randomPassword();
            passwords.put(u[0], password);
            rows.add(new Row("app_user", ordered("login_name", prefix + u[0], "password_hash", ENCODER.encode(password),
                "display_name", prefix + u[2], "role", u[1], "created_at", ts(created),
                "created_by", u[1].equals("ADMIN") ? null : 1L)));
        }
        // id：1 admin、2 emp1、3 emp2、4 it1、5 it2、6 former（已被取消 IT 同事身份，但处理过工单）
        rows.add(new Row("category", ordered("name", prefix + "网络", "active", true, "created_at", ts(created),
            "removed_at", null)));
        rows.add(new Row("category", ordered("name", prefix + "打印机（已移除）", "active", false, "created_at",
            ts(created), "removed_at", ts(now.minus(Duration.ofDays(5))))));
        rows.add(new Row("category", ordered("name", prefix + "账号权限-改名后", "active", true, "created_at",
            ts(created), "removed_at", null)));

        Instant weekStart = now.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(ZONE).toInstant();
        Instant inThisWeek = weekStart.plus(Duration.between(weekStart, now).dividedBy(2));
        long ticketId = 0;
        for (int u = 0; u < URGENCIES.length; u++) {
            long hours = new long[] {4, 24, 72}[u];
            for (int s = 0; s < STATUSES.length; s++) {
                for (int assigned = 0; assigned < 2; assigned++) {
                    ticketId++;
                    String status = STATUSES[s];
                    boolean unresolved = s < 2;
                    // 未解决工单交替构造已超期 / 未超期；其他工单提交于 10 天前
                    Instant submitted = unresolved
                        ? (assigned == 0 ? now.minus(Duration.ofHours(hours + 5)) : now.minus(Duration.ofMinutes(
                            hours * 60 / 2)))
                        : now.minus(Duration.ofDays(10)).plus(Duration.ofHours(ticketId));
                    long submitter = ticketId % 2 == 0 ? 3L : 2L;
                    long category = 1 + ticketId % 3;
                    Long assignee = assigned == 1 ? (ticketId % 4 == 0 ? 6L : 4L) : null;
                    Instant firstResolved = null;
                    if (status.equals("RESOLVED") || (status.equals("CLOSED") && assigned == 1)) {
                        firstResolved = ticketId % 3 == 0 ? inThisWeek : submitted.plus(Duration.ofHours(hours + 1));
                    }
                    Instant updated = firstResolved != null ? firstResolved : submitted.plus(Duration.ofMinutes(30));
                    rows.add(new Row("ticket", ordered("title", prefix + "V1 工单 " + ticketId + " " + URGENCIES[u]
                            + " " + status, "description", "V1 合成描述 " + ticketId, "category_id", category,
                        "urgency", URGENCIES[u], "submitter_id", submitter, "submitted_at", ts(submitted),
                        "status", status, "assignee_id", assignee, "first_resolved_at", ts(firstResolved),
                        "first_resolved_assignee_id", firstResolved == null ? null : assignee,
                        "updated_at", ts(updated))));
                    rows.add(new Row("ticket_comment", ordered("ticket_id", ticketId, "author_id", submitter,
                        "author_role", "EMPLOYEE", "body", "V1 补充说明 " + ticketId,
                        "created_at", ts(submitted.plus(Duration.ofMinutes(5))))));
                    if (assignee != null) {
                        boolean claimed = ticketId % 2 == 0;
                        rows.add(new Row("ticket_event", ordered("ticket_id", ticketId, "actor_id",
                            claimed ? assignee : 5L, "event_type", claimed ? "CLAIM" : "ASSIGN",
                            "from_assignee_id", null, "to_assignee_id", assignee, "from_status", null,
                            "to_status", null, "created_at", ts(submitted.plus(Duration.ofMinutes(10))))));
                        rows.add(new Row("ticket_comment", ordered("ticket_id", ticketId, "author_id", assignee,
                            "author_role", "IT_STAFF", "body", "V1 回复 " + ticketId,
                            "created_at", ts(submitted.plus(Duration.ofMinutes(12))))));
                    }
                    long actor = assignee != null ? assignee : 4L;
                    if (!status.equals("OPEN")) {
                        String from = "OPEN";
                        if (firstResolved != null) {
                            rows.add(statusEvent(ticketId, actor, from, "RESOLVED", firstResolved));
                            from = "RESOLVED";
                        }
                        if (!status.equals(from)) {
                            rows.add(statusEvent(ticketId, actor, from, status, updated.plus(Duration.ofMinutes(1))));
                        }
                    }
                }
            }
        }
        return new Dataset(now, passwords, rows);
    }

    private static Row statusEvent(long ticketId, long actor, String from, String to, Instant at) {
        return new Row("ticket_event", ordered("ticket_id", ticketId, "actor_id", actor, "event_type", "STATUS",
            "from_assignee_id", null, "to_assignee_id", null, "from_status", from, "to_status", to,
            "created_at", ts(at)));
    }

    /** 按写入顺序把数据集写入一次性 schema（V1 结构，active 列此时尚不存在）。 */
    public static void insert(NamedParameterJdbcTemplate jdbc, String schema, Dataset dataset) {
        checked(schema);
        for (Row row : dataset.rows()) {
            String columns = String.join(", ", row.values().keySet());
            String params = String.join(", ", row.values().keySet().stream().map(c -> ":" + c).toList());
            jdbc.update("INSERT INTO " + schema + "." + row.table() + "(" + columns + ") VALUES (" + params + ")",
                new MapSqlParameterSource(row.values()));
        }
    }

    // ---------------- 快照与比对 ----------------

    /** V1 全部表的指定列快照（按主键排序）；columns 为 null 时取 V1 列。 */
    public static Map<String, List<Map<String, Object>>> snapshot(NamedParameterJdbcTemplate jdbc, String schema,
                                                                 Map<String, List<String>> columns) {
        Map<String, List<String>> cols = columns == null ? V1_COLUMNS : columns;
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        cols.forEach((table, list) -> result.put(table, jdbc.queryForList("SELECT " + String.join(", ", list)
            + " FROM " + checked(schema) + "." + table + " ORDER BY id", Map.of())));
        return result;
    }

    /** V1 列 + V2 新增列（全部数据比对用）。 */
    public static Map<String, List<String>> allColumnsV2() {
        Map<String, List<String>> columns = new LinkedHashMap<>(V1_COLUMNS);
        List<String> users = new ArrayList<>(V1_COLUMNS.get("app_user"));
        users.add("active");
        columns.put("app_user", users);
        return columns;
    }

    /** 逐行逐列比对；差异只包含表名、id 与列名，不含任何值。 */
    public static List<String> diff(Map<String, List<Map<String, Object>>> before,
                                    Map<String, List<Map<String, Object>>> after) {
        List<String> differences = new ArrayList<>();
        for (String table : before.keySet()) {
            Map<Object, Map<String, Object>> a = byId(before.get(table));
            Map<Object, Map<String, Object>> b = byId(after.getOrDefault(table, List.of()));
            for (Object id : a.keySet()) {
                Map<String, Object> left = a.get(id);
                Map<String, Object> right = b.get(id);
                if (right == null) {
                    differences.add(table + "#" + id + " 缺失");
                    continue;
                }
                for (String column : left.keySet()) {
                    if (!Objects.equals(left.get(column), right.get(column))) {
                        differences.add(table + "#" + id + "." + column + " 不同");
                    }
                }
            }
            for (Object id : b.keySet()) {
                if (!a.containsKey(id)) {
                    differences.add(table + "#" + id + " 新增");
                }
            }
        }
        return differences;
    }

    private static Map<Object, Map<String, Object>> byId(List<Map<String, Object>> rows) {
        Map<Object, Map<String, Object>> map = new LinkedHashMap<>();
        rows.forEach(r -> map.put(r.get("id"), r));
        return map;
    }

    // ---------------- 工具 ----------------

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "Pw-" + HexFormat.of().formatHex(bytes);
    }
}
