package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.support.RunPrefix;
import com.ithelpdesk.web.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** 管理员创建账号（FR-001、FR-002、INV-006、IQA-001）。 */
class UserAdminIT extends IntegrationTestBase {

    @Autowired
    private UserAdminService service;

    @Autowired
    private UserRepository users;

    @Test
    void adminCreatesThreeRolesAndTheyCanLogin() throws Exception {
        CurrentUser admin = data.user("admin", Role.ADMIN);
        for (Role role : Role.values()) {
            String password = RunPrefix.randomPassword();
            String login = data.name("new-" + role.name().toLowerCase());
            long id = service.createUser(admin, new UserForm(login, password, "新账号" + role.getLabel(), role.name()));
            AppUser stored = users.findById(id).orElseThrow();
            assertThat(stored.role()).isEqualTo(role);
            assertThat(stored.createdBy()).isEqualTo(admin.getId());
            assertThat(stored.passwordHash()).startsWith("$2a$10$").doesNotContain(password);
            mvc.perform(formLogin("/login").user(login).password(password))
                .andExpect(authenticated().withRoles(role.name()));
            mvc.perform(formLogin("/login").user(login).password(password + "x")).andExpect(unauthenticated());
        }
    }

    @Test
    void duplicateLoginNameRejectedWithoutCreatingAccount() {
        CurrentUser admin = data.user("admin2", Role.ADMIN);
        String login = data.name("dup");
        service.createUser(admin, new UserForm(login, "p1", "甲", "EMPLOYEE"));
        assertThatThrownBy(() -> service.createUser(admin, new UserForm(login, "p2", "乙", "IT_STAFF")))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getErrors()).containsEntry("loginName", "登录名已存在"));
        assertThat(data.count("SELECT COUNT(*) FROM app_user WHERE login_name = :l",
            new MapSqlParameterSource("l", login))).isEqualTo(1);
    }
}
