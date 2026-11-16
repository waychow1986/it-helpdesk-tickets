package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.ithelpdesk.support.LogCapture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

class InitialAdminBootstrapTest {

    private final UserRepository users = mock(UserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
    private final InitialAdminBootstrap bootstrap = new InitialAdminBootstrap(users,
        TransactionOperations.withoutTransaction(), new BootstrapProperties("admin", "初始管理员"), clock);

    @Test
    void insertsCredentiallessAdminWhenNoAdminExists() {
        when(users.existsAdmin()).thenReturn(false);
        when(users.insertBootstrapAdmin("admin", "初始管理员", clock.instant())).thenReturn(true);
        try (LogCapture logs = new LogCapture(InitialAdminBootstrap.class)) {
            assertThat(bootstrap.bootstrap()).isTrue();
            assertThat(logs.messages()).anyMatch(m -> m.contains("已创建初始管理员账号 admin"));
            assertThat(logs.messages()).noneMatch(m -> m.contains("$2") || m.toLowerCase().contains("password"));
        }
        InOrder order = inOrder(users);
        order.verify(users).acquireBootstrapLock();
        order.verify(users).existsAdmin();
        order.verify(users).insertBootstrapAdmin("admin", "初始管理员", clock.instant());
    }

    @Test
    void doesNothingWhenAdminAlreadyExists() {
        when(users.existsAdmin()).thenReturn(true);
        try (LogCapture logs = new LogCapture(InitialAdminBootstrap.class)) {
            assertThat(bootstrap.bootstrap()).isFalse();
            assertThat(logs.events()).isEmpty();
        }
        verify(users).acquireBootstrapLock();
        verify(users, never()).insertBootstrapAdmin(anyString(), anyString(), any());
    }

    @Test
    void warnsWhenLoginNameTakenByNonAdmin() {
        when(users.existsAdmin()).thenReturn(false);
        when(users.insertBootstrapAdmin(anyString(), anyString(), any())).thenReturn(false);
        try (LogCapture logs = new LogCapture(InitialAdminBootstrap.class)) {
            assertThat(bootstrap.bootstrap()).isFalse();
            assertThat(logs.has(Level.WARN)).isTrue();
            assertThat(logs.messages()).containsExactly("初始管理员登录名已被占用，未创建");
        }
    }
}
