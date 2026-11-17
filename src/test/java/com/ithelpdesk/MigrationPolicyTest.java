package com.ithelpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 迁移策略：只增不删、不含任何数据写入（V1 NFR-001、FR-030、TD-004；V2 TD-029、FR-023、NFR-001、INV-006）；
 * 已提交的 V1 迁移文件永不修改（Flyway 校验和保护）。
 */
class MigrationPolicyTest {

    private static final Pattern NAME = Pattern.compile("V\\d+__[A-Za-z0-9_]+\\.sql");
    private static final Pattern FORBIDDEN =
        Pattern.compile("\\b(DROP|TRUNCATE|DELETE|UPDATE|INSERT|RENAME)\\b");
    private static final Pattern ALTER_TYPE = Pattern.compile("\\bALTER\\b[^;]*\\bTYPE\\b");

    /** V1 交付提交（7977bdd）中 V1__init.sql 的 SHA-256。 */
    private static final String V1_SHA256 = "a38c9e0a5fa044f5ac9819ebc3a279d6e5c0451c560c610d9ae7baf0767108f3";

    static String strip(String sql) {
        return sql.replaceAll("--[^\\n]*", "").toUpperCase(Locale.ROOT);
    }

    static boolean violates(String sql) {
        String body = strip(sql);
        return FORBIDDEN.matcher(body).find() || ALTER_TYPE.matcher(body).find();
    }

    @Test
    void migrationsAreAdditiveAndWellNamed() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*");
        assertThat(scripts).hasSizeGreaterThanOrEqualTo(2);
        for (Resource script : scripts) {
            assertThat(script.getFilename()).matches(NAME);
            String sql = script.getContentAsString(StandardCharsets.UTF_8);
            assertThat(violates(sql))
                .as("%s 不得包含 DROP / TRUNCATE / DELETE / UPDATE / INSERT / ALTER … TYPE / RENAME",
                    script.getFilename())
                .isFalse();
        }
    }

    @Test
    void policyDetectsForbiddenStatements() {
        assertThat(violates("ALTER TABLE app_user ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;"))
            .isFalse();
        assertThat(violates("-- UPDATE in comment only\nCREATE TABLE x (id INT);")).isFalse();
        for (String bad : new String[] {"UPDATE app_user SET active = TRUE;", "insert into t values (1);",
            "DELETE FROM t;", "DROP TABLE t;", "TRUNCATE t;", "ALTER TABLE t RENAME TO u;",
            "ALTER TABLE t ALTER COLUMN c TYPE TEXT;"}) {
            assertThat(violates(bad)).as(bad).isTrue();
        }
    }

    @Test
    void v2MigrationOnlyAddsActiveColumn() throws IOException {
        String sql = new ClassPathResource("db/migration/V2__account_active.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        String body = strip(sql).trim();
        assertThat(body).isEqualTo(
            "ALTER TABLE APP_USER ADD COLUMN IF NOT EXISTS ACTIVE BOOLEAN NOT NULL DEFAULT TRUE;");
    }

    @Test
    void v1MigrationIsUnchanged() throws IOException, NoSuchAlgorithmException {
        try (InputStream in = new ClassPathResource("db/migration/V1__init.sql").getInputStream()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            assertThat(HexFormat.of().formatHex(digest)).isEqualTo(V1_SHA256);
        }
    }
}
