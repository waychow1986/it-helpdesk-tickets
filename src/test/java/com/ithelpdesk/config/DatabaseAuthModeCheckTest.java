package com.ithelpdesk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** ticket-db 认证方式启动校验（TD-031）：只接受 platform-identity，不读取任何凭据变量。 */
class DatabaseAuthModeCheckTest {

    private static final String PASSWORD_VARIABLE = "AIDH_DEP_TICKET_DB_PASSWORD";
    private static final String FAKE_SECRET = "synthetic-not-a-real-secret";

    /** 记录被读取的属性名，用于断言不读取密码变量。 */
    static final class RecordingEnvironment extends MockEnvironment {
        final List<String> reads = new ArrayList<>();

        @Override
        public String getProperty(String key) {
            reads.add(key);
            return super.getProperty(key);
        }
    }

    private final Log log = Mockito.mock(Log.class);
    private final DatabaseAuthModeCheck check = new DatabaseAuthModeCheck(type -> log);

    private RecordingEnvironment env(String mode) {
        RecordingEnvironment env = new RecordingEnvironment();
        if (mode != null) {
            env.setProperty("AIDH_DEP_TICKET_DB_AUTH_MODE", mode);
        }
        env.setProperty(PASSWORD_VARIABLE, FAKE_SECRET);
        return env;
    }

    @Test
    void platformIdentityEmptyOrMissingPass() {
        for (String mode : new String[] {null, "", "  ", "platform-identity"}) {
            RecordingEnvironment env = env(mode);
            check.postProcessEnvironment(env, new SpringApplication());
            assertThat(env.reads).doesNotContain(PASSWORD_VARIABLE);
        }
        Mockito.verifyNoInteractions(log);
    }

    @Test
    void credentialOrOtherModesFailStartupWithoutReadingPassword() {
        for (String mode : new String[] {"credential", "managed-identity"}) {
            RecordingEnvironment env = env(mode);
            assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ticket-db 认证方式 " + mode + " 未在本版本实现");
            assertThat(env.reads).doesNotContain(PASSWORD_VARIABLE);
        }
        ArgumentCaptor<Object> logged = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(log, Mockito.times(2)).error(logged.capture());
        assertThat(logged.getAllValues()).allSatisfy(message ->
            assertThat(message.toString()).contains("未在本版本实现").doesNotContain(FAKE_SECRET));
    }

    @Test
    void unsafeCharactersAreNotEchoed() {
        assertThat(DatabaseAuthModeCheck.check("bad\nmode")).isEqualTo("ticket-db 认证方式 bad?mode 未在本版本实现");
        assertThat(DatabaseAuthModeCheck.check("platform-identity")).isNull();
    }
}
