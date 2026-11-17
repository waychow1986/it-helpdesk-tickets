package com.ithelpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** src/main 中唯一的机密引用是 application.yml 中的 AIDH_SECRET_INITIAL_SETUP_TOKEN（TD-018、TD-019）。 */
class SecretUsagePolicyTest {

    private static final Pattern SECRET_REF = Pattern.compile("AIDH_SECRET_[A-Z0-9_]*");

    @Test
    void onlySetupTokenSecretIsReferenced() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = SECRET_REF.matcher(text);
                while (m.find()) {
                    hits.add(file.toString().replace('\\', '/') + ":" + m.group());
                }
            }
        }
        assertThat(hits).containsExactly("src/main/resources/application.yml:AIDH_SECRET_INITIAL_SETUP_TOKEN");
        assertThat(hits).noneMatch(h -> h.contains("AIDH_SECRET_INITIAL_ADMIN_"));
    }
}
