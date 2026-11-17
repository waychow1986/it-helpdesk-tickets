package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.ithelpdesk.support.LogCapture;
import com.ithelpdesk.user.SetupTokenVerifier.Result;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SetupTokenVerifierTest {

    private static final String TOKEN = "Synthetic-Setup-Token-0123456789";

    @Test
    void emptyBlankOrShortTokenIsNotConfiguredAndWarnsOnce() {
        for (String value : new String[] {null, "", "   ", " " + "y".repeat(15) + " "}) {
            try (LogCapture logs = new LogCapture(SetupTokenVerifier.class)) {
                SetupTokenVerifier verifier = new SetupTokenVerifier(value);
                assertThat(verifier.isConfigured()).isFalse();
                assertThat(verifier.verify(TOKEN)).isEqualTo(Result.NOT_CONFIGURED);
                assertThat(verifier.verify("")).isEqualTo(Result.NOT_CONFIGURED);
                assertThat(logs.events()).hasSize(1);
                assertThat(logs.has(Level.WARN)).isTrue();
                assertThat(logs.messages()).containsExactly("未提供有效的设置口令，设置初始管理员流程无法完成");
            }
        }
    }

    @Test
    void fifteenCharactersAfterTrimIsNotConfiguredSixteenIs() {
        assertThat(new SetupTokenVerifier("  " + "x".repeat(15) + "  ").isConfigured()).isFalse();
        assertThat(new SetupTokenVerifier("  " + "x".repeat(16) + "  ").isConfigured()).isTrue();
    }

    @Test
    void correctTokenPassesWrongAndEmptyFail() {
        SetupTokenVerifier verifier = new SetupTokenVerifier("  " + TOKEN + "\n");
        assertThat(verifier.isConfigured()).isTrue();
        assertThat(verifier.verify(TOKEN)).isEqualTo(Result.OK);
        assertThat(verifier.verify(" " + TOKEN + " ")).isEqualTo(Result.OK);
        assertThat(verifier.verify(TOKEN + "x")).isEqualTo(Result.MISMATCH);
        assertThat(verifier.verify(TOKEN.toLowerCase())).isEqualTo(Result.MISMATCH);
        assertThat(verifier.verify("")).isEqualTo(Result.EMPTY);
        assertThat(verifier.verify("   ")).isEqualTo(Result.EMPTY);
        assertThat(verifier.verify(null)).isEqualTo(Result.EMPTY);
    }

    @Test
    void onlyDigestIsKeptAndNothingLeaks() throws Exception {
        try (LogCapture logs = new LogCapture(SetupTokenVerifier.class)) {
            SetupTokenVerifier verifier = new SetupTokenVerifier(TOKEN);
            verifier.verify("wrong-token-value-xyz");
            verifier.verify(TOKEN);
            assertThat(logs.events()).isEmpty();
            for (Field field : SetupTokenVerifier.class.getDeclaredFields()) {
                assertThat(field.getType()).isNotEqualTo(String.class);
                if (field.getType() == byte[].class) {
                    field.setAccessible(true);
                    byte[] stored = (byte[]) field.get(verifier);
                    assertThat(stored).isEqualTo(SetupTokenVerifier.sha256(TOKEN));
                    assertThat(Arrays.equals(stored, TOKEN.getBytes())).isFalse();
                }
            }
            assertThat(verifier.toString()).doesNotContain(TOKEN).doesNotContain(String.valueOf(TOKEN.length()));
        }
    }

    @Test
    void comparisonUsesConstantTimeDigestEquality() throws Exception {
        String source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/ithelpdesk/user/SetupTokenVerifier.java"));
        assertThat(source).contains("MessageDigest.isEqual(");
        assertThat(source).doesNotContain(".equals(submitted").doesNotContain("Arrays.equals(");
    }
}
