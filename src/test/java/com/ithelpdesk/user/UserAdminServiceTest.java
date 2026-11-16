package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserAdminServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserAdminService service = new UserAdminService(users, new BCryptPasswordEncoder(4),
        Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC));
    private final CurrentUser admin = new CurrentUser(1, "a", "管理员", Role.ADMIN, "x");

    @Test
    void blankFieldsAreRejectedPerField() {
        Map<String, String> errors = new UserForm("  ", " ", "\t", "").validate();
        assertThat(errors).containsOnlyKeys("loginName", "password", "displayName", "role");
        assertThat(errors.get("loginName")).isEqualTo("请填写登录名");
        assertThat(errors.get("password")).isEqualTo("请填写初始密码");
        assertThat(errors.get("displayName")).isEqualTo("请填写显示姓名");
        assertThat(errors.get("role")).isEqualTo("请选择身份");
    }

    @Test
    void overlongFieldsAreRejected() {
        Map<String, String> errors = new UserForm("x".repeat(65), "密".repeat(25), "y".repeat(101), "EMPLOYEE")
            .validate();
        assertThat(errors).containsOnlyKeys("loginName", "password", "displayName");
        assertThat(new UserForm("x".repeat(64), "p".repeat(72), "y".repeat(100), "EMPLOYEE").validate()).isEmpty();
    }

    @Test
    void onlyThreeRoleCodesAccepted() {
        assertThat(new UserForm("u", "p", "n", "EMPLOYEE").validate()).isEmpty();
        assertThat(new UserForm("u", "p", "n", "IT_STAFF").validate()).isEmpty();
        assertThat(new UserForm("u", "p", "n", "ADMIN").validate()).isEmpty();
        assertThat(new UserForm("u", "p", "n", "ROOT").validate()).containsKey("role");
    }

    @Test
    void nonAdminCannotCreateOrChangeRole() {
        CurrentUser staff = new CurrentUser(2, "t", "IT", Role.IT_STAFF, "x");
        assertThatThrownBy(() -> service.createUser(staff, new UserForm("u", "p", "n", "EMPLOYEE")))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.changeRole(staff, 5, "IT_STAFF")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listUsers(null)).isInstanceOf(AccessDeniedException.class);
        verify(users, never()).insert(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void duplicateLoginNameRejected() {
        when(users.findByLoginName("dup")).thenReturn(Optional.of(
            new AppUser(9, "dup", "h", "n", Role.EMPLOYEE, Instant.EPOCH, null)));
        assertThatThrownBy(() -> service.createUser(admin, new UserForm(" dup ", "p", "n", "EMPLOYEE")))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getErrors()).containsEntry("loginName", "登录名已存在"));
    }

    @Test
    void createStoresBcryptHashAndCreator() {
        when(users.findByLoginName("new")).thenReturn(Optional.empty());
        when(users.insert(anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(10L);
        org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
        assertThat(service.createUser(admin, new UserForm("new", "secret-pw", " 新人 ", "IT_STAFF"))).isEqualTo(10L);
        verify(users).insert(org.mockito.ArgumentMatchers.eq("new"), hash.capture(),
            org.mockito.ArgumentMatchers.eq("新人"), org.mockito.ArgumentMatchers.eq(Role.IT_STAFF), any(),
            org.mockito.ArgumentMatchers.eq(1L));
        assertThat(hash.getValue()).startsWith("$2a$").doesNotContain("secret-pw");
    }

    @Test
    void redisplayDoesNotEchoPassword() {
        UserForm form = new UserForm("u", "secret-pw", "n", "EMPLOYEE").forRedisplay();
        assertThat(form.getPassword()).isNull();
        assertThat(form.getLoginName()).isEqualTo("u");
        assertThat(form.toString()).doesNotContain("secret-pw");
    }

    @Test
    void changeRoleOnlyBetweenEmployeeAndStaff() {
        assertThatThrownBy(() -> service.changeRole(admin, 5, "ADMIN")).isInstanceOf(ValidationException.class);
        when(users.findById(6)).thenReturn(Optional.of(
            new AppUser(6, "adm", "h", "n", Role.ADMIN, Instant.EPOCH, null)));
        assertThatThrownBy(() -> service.changeRole(admin, 6, "EMPLOYEE")).isInstanceOf(ValidationException.class);
        verify(users, never()).updateStaffRole(anyLong(), any());
    }
}
