package com.ithelpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 迁移策略：只增不删（NFR-001、FR-030、TD-004）。 */
class MigrationPolicyTest {

    private static final Pattern NAME = Pattern.compile("V\\d+__[A-Za-z0-9_]+\\.sql");
    private static final Pattern FORBIDDEN = Pattern.compile("\\b(DROP|TRUNCATE|DELETE)\\b");

    @Test
    void migrationsAreAdditiveAndWellNamed() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*");
        assertThat(scripts).isNotEmpty();
        for (Resource script : scripts) {
            assertThat(script.getFilename()).matches(NAME);
            String sql = script.getContentAsString(StandardCharsets.UTF_8);
            String withoutComments = sql.replaceAll("--[^\\n]*", "").toUpperCase(Locale.ROOT);
            assertThat(FORBIDDEN.matcher(withoutComments).find())
                .as("%s 不得包含 DROP / TRUNCATE / DELETE", script.getFilename()).isFalse();
        }
    }
}
