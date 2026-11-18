package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.LogCapture;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.ValidationException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 停用 / 启用服务规则（TD-025、FR-007、FR-014、FR-015、INV-004、AC-009）；仓库为纯逻辑替身。 */
class AccountStatusServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AccountStatusService service = new AccountStatusService(users);
    private final CurrentUser admin = new CurrentUser(1, "a1", "管理员甲", Role.ADMIN, "x");

    private static AppUser user(long id, Role role, String hash, boolean active) {
        return new AppUser(id, "login-" + id, hash, "姓名-" + id, role, Instant.EPOCH, null, active);
    }

    private void target(AppUser u) {
        when(users.lockForStatusChange(u.id())).thenReturn(Optional.of(u));
        when(users.updateActive(u.id(), !u.active(), u.active())).thenReturn(1);
    }

    @Test
    void nonAdminActorsAreRejected() {
        for (CurrentUser actor : new CurrentUser[] {null, new CurrentUser(2, "e", "员工", Role.EMPLOYEE, "x"),
            new CurrentUser(3, "t", "IT", Role.IT_STAFF, "x")}) {
            assertThatThrownBy(() -> service.deactivate(actor, 9)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.activate(actor, 9)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(users);
    }

    @Test
    void missingTargetIsRejected() {
        when(users.lockForStatusChange(9)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivate(admin, 9)).isInstanceOf(ValidationException.class)
            .extracting(e -> ((ValidationException) e).firstMessage()).isEqualTo("账号不存在");
        verify(users, never()).updateActive(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void alreadyInDesiredStateWritesNothing() {
        target(user(5, Role.EMPLOYEE, "h", false));
        assertThat(service.deactivate(admin, 5)).isEqualTo(new AccountStatusService.Result(false, "账号已是停用状态"));
        target(user(6, Role.EMPLOYEE, "h", true));
        assertThat(service.activate(admin, 6)).isEqualTo(new AccountStatusService.Result(false, "账号已是启用状态"));
        verify(users, never()).updateActive(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void lockThenRowLockThenUpdate() {
        target(user(7, Role.IT_STAFF, "h", true));
        assertThat(service.deactivate(admin, 7)).isEqualTo(new AccountStatusService.Result(true, "已停用该账号"));
        InOrder order = inOrder(users);
        order.verify(users).acquireAccountStatusLock();
        order.verify(users).lockForStatusChange(7);
        order.verify(users).updateActive(7, false, true);
        target(user(8, Role.IT_STAFF, "h", false));
        assertThat(service.activate(admin, 8)).isEqualTo(new AccountStatusService.Result(true, "已启用该账号"));
        verify(users).updateActive(8, true, false);
    }

    @Test
    void lastUsableAdminCannotBeDeactivatedIncludingSelf() {
        target(user(1, Role.ADMIN, "h", true));
        when(users.countOtherUsableAdmins(1)).thenReturn(0L);
        assertThatThrownBy(() -> service.deactivate(admin, 1)).isInstanceOf(ValidationException.class)
            .extracting(e -> ((ValidationException) e).firstMessage()).isEqualTo("系统必须至少保留一个可用的管理员");
        target(user(4, Role.ADMIN, "h", true));
        when(users.countOtherUsableAdmins(4)).thenReturn(0L);
        assertThatThrownBy(() -> service.deactivate(admin, 4)).isInstanceOf(ValidationException.class);
        verify(users, never()).updateActive(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void selfDeactivationAllowedWhenAnotherUsableAdminExists() {
        target(user(1, Role.ADMIN, "h", true));
        when(users.countOtherUsableAdmins(1)).thenReturn(1L);
        assertThat(service.deactivate(admin, 1).changed()).isTrue();
        verify(users).updateActive(1, false, true);
    }

    @Test
    void usableAdminDefinitionExcludesNullHashAndInactive() {
        assertThat(AccountStatusService.isUsableAdmin(user(1, Role.ADMIN, "h", true))).isTrue();
        assertThat(AccountStatusService.isUsableAdmin(user(1, Role.ADMIN, null, true))).isFalse();
        assertThat(AccountStatusService.isUsableAdmin(user(1, Role.ADMIN, "h", false))).isFalse();
        assertThat(AccountStatusService.isUsableAdmin(user(1, Role.IT_STAFF, "h", true))).isFalse();
        // 停用非可用管理员（如遗留的 NULL 哈希 ADMIN 行）不需要计数其余管理员
        target(new AppUser(12, "legacy", null, "遗留", Role.ADMIN, Instant.EPOCH, null, true));
        assertThat(service.deactivate(admin, 12).changed()).isTrue();
        verify(users, never()).countOtherUsableAdmins(12);
    }

    @Test
    void successLogContainsOnlyIds() {
        AppUser target = user(15, Role.EMPLOYEE, "secret-hash", true);
        target(target);
        try (LogCapture logs = new LogCapture(AccountStatusService.class)) {
            service.deactivate(admin, 15);
            assertThat(logs.messages()).containsExactly("账号已停用（用户 id=15，操作者 id=1）");
            assertThat(String.join("\n", logs.messages())).doesNotContain(target.loginName(), target.displayName(),
                "secret-hash", admin.getLoginName());
        }
    }
}
