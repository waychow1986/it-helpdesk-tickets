package com.ithelpdesk.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.ValidationException;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class CategoryServiceTest {

    private final CategoryRepository repo = mock(CategoryRepository.class);
    private final CategoryService service = new CategoryService(repo, Clock.systemUTC());
    private final CurrentUser admin = new CurrentUser(1, "a", "管理员", Role.ADMIN, "x");

    @Test
    void blankNameRejected() {
        for (String name : new String[] {null, "", "   ", "\t\n"}) {
            assertThatThrownBy(() -> service.create(admin, name))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrors())
                    .containsEntry("name", "类别名称不能为空"));
        }
        verify(repo, never()).insert(anyString(), any());
    }

    @Test
    void overlongNameRejected() {
        assertThatThrownBy(() -> service.create(admin, "长".repeat(51))).isInstanceOf(ValidationException.class);
        assertThat(CategoryService.validateName("  " + "长".repeat(50) + " ")).hasSize(50);
    }

    @Test
    void nonAdminRejected() {
        CurrentUser employee = new CurrentUser(2, "e", "员工", Role.EMPLOYEE, "x");
        CurrentUser staff = new CurrentUser(3, "t", "IT", Role.IT_STAFF, "x");
        assertThatThrownBy(() -> service.create(employee, "x")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.rename(staff, 1, "x")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.remove(staff, 1)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listAll(employee)).isInstanceOf(AccessDeniedException.class);
    }
}
