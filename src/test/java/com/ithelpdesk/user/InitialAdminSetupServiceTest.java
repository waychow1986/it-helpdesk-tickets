package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ithelpdesk.support.LogCapture;
import com.ithelpdesk.web.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 设置初始管理员服务的分支逻辑（数据访问为替身，只验证纯逻辑，不作为功能证据）。 */
class InitialAdminSetupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T01:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final InitialAdminSetupService service =
        new InitialAdminSetupService(users, encoder, Clock.fixed(NOW, ZoneId.of("UTC")));

    private static SetupForm valid() {
        return new SetupForm("ignored-by-service", "  root-admin  ", "Secret-Pass-1", "  合成甲某  ");
    }

    private void verifyNoInsert() {
        verify(users, never()).insertInitialAdmin(anyString(), anyString(), anyString(), any());
        verify(users, never()).insert(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void openDependsOnCredentialedAdminAndIsQueriedEveryTime() {
        when(users.existsCredentialedAdmin()).thenReturn(false, true, false);
        assertThat(service.isSetupOpen()).isTrue();
        assertThat(service.isSetupOpen()).isFalse();
        assertThat(service.isSetupOpen()).isTrue();
        verify(users, times(3)).existsCredentialedAdmin();
    }

    @Test
    void closedRejectsWithoutWriting() {
        when(users.existsCredentialedAdmin()).thenReturn(true);
        assertThatThrownBy(() -> service.setup(valid())).isInstanceOf(SetupClosedException.class);
        verifyNoInsert();
    }

    @Test
    void lockIsTakenBeforeRecheck() {
        when(users.existsCredentialedAdmin()).thenReturn(false);
        when(users.findByLoginName(anyString())).thenReturn(Optional.empty());
        when(users.insertInitialAdmin(anyString(), anyString(), anyString(), any())).thenReturn(7L);
        service.setup(valid());
        InOrder order = inOrder(users);
        order.verify(users).acquireSetupLock();
        order.verify(users).existsCredentialedAdmin();
        order.verify(users).insertInitialAdmin(anyString(), anyString(), anyString(), any());
    }

    @Test
    void blankFieldsAreRejectedPerField() {
        when(users.existsCredentialedAdmin()).thenReturn(false);
        assertThatThrownBy(() -> service.setup(new SetupForm("t", "  ", " ", "\t")))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getErrors())
                .containsEntry("loginName", "请填写登录名")
                .containsEntry("password", "请填写密码")
                .containsEntry("displayName", "请填写姓名"));
        assertThatThrownBy(() -> service.setup(new SetupForm("t", null, null, null)))
            .isInstanceOf(ValidationException.class);
        verifyNoInsert();
    }

    @Test
    void overlongFieldsAreRejected() {
        when(users.existsCredentialedAdmin()).thenReturn(false);
        SetupForm form = new SetupForm("t", "l".repeat(65), "密".repeat(25), "n".repeat(101));
        assertThatThrownBy(() -> service.setup(form))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getErrors())
                .containsKeys("loginName", "password", "displayName"));
        verifyNoInsert();
        assertThat(new SetupForm("t", "l".repeat(64), "p".repeat(72), "n".repeat(100)).validate()).isEmpty();
    }

    @Test
    void existingLoginNameIsRejected() {
        when(users.existsCredentialedAdmin()).thenReturn(false);
        when(users.findByLoginName("root-admin")).thenReturn(Optional.of(
            new AppUser(3, "root-admin", null, "遗留", Role.ADMIN, Instant.EPOCH, null)));
        assertThatThrownBy(() -> service.setup(valid()))
            .isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.getErrors()).containsExactly(java.util.Map.entry("loginName", "登录名已存在")));
        verifyNoInsert();
    }

    @Test
    void uniqueViolationIsTranslated() {
        when(users.existsCredentialedAdmin()).thenReturn(false);
        when(users.findByLoginName(anyString())).thenReturn(Optional.empty());
        when(users.insertInitialAdmin(anyString(), anyString(), anyString(), any()))
            .thenThrow(new DuplicateKeyException("dup"));
        assertThatThrownBy(() -> service.setup(valid()))
            .isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.getErrors()).containsEntry("loginName", "登录名已存在"));
    }

    @Test
    void successInsertsBcryptAdminAndLogsOnlyId() {
        when(users.existsCredentialedAdmin()).thenReturn(false);
        when(users.findByLoginName(anyString())).thenReturn(Optional.empty());
        when(users.insertInitialAdmin(anyString(), anyString(), anyString(), any())).thenReturn(42L);
        try (LogCapture logs = new LogCapture(InitialAdminSetupService.class)) {
            assertThat(service.setup(valid())).isEqualTo(42L);
            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            verify(users).insertInitialAdmin(eq("root-admin"), hash.capture(), eq("合成甲某"), eq(NOW));
            assertThat(hash.getValue()).startsWith("$2a$").isNotEqualTo("Secret-Pass-1");
            assertThat(encoder.matches("Secret-Pass-1", hash.getValue())).isTrue();
            assertThat(logs.messages()).containsExactly("初始管理员设置完成（用户 id=42）");
            assertThat(logs.messages()).noneMatch(m -> m.contains("root-admin") || m.contains("Secret-Pass-1")
                || m.contains("合成甲某") || m.contains(hash.getValue()));
        }
    }

    @Test
    void repositoryInsertsAdminWithoutCreator() {
        UserRepository real = mock(UserRepository.class);
        when(real.insertInitialAdmin(anyString(), anyString(), anyString(), any())).thenCallRealMethod();
        real.insertInitialAdmin("a", "h", "d", NOW);
        verify(real).insert("a", "h", "d", Role.ADMIN, NOW, null);
    }
}
