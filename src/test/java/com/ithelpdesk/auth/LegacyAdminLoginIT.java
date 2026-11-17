package com.ithelpdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.user.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 遗留 NULL 哈希 ADMIN 账号在真实 dev 实例 public schema 上不可登录（TD-011、FR-002）。
 * 只使用本次运行前缀插入的行，不修改或依赖库中其他 ADMIN 行。
 */
class LegacyAdminLoginIT extends IntegrationTestBase {

    @Test
    void nullHashAdminCannotLoginWithAnyPassword() throws Exception {
        data.user("legacy-admin", Role.ADMIN, null);
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE login_name = :l",
            Map.of("l", data.name("legacy-admin")), String.class);
        assertThat(hash).isNull();
        for (String password : new String[] {"", "admin", "anything-123", "null"}) {
            mvc.perform(formLogin("/login").user(data.name("legacy-admin")).password(password))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
        }
        // 与凭据错误的表现完全相同
        mvc.perform(formLogin("/login").user(data.name("no-such-user")).password("x"))
            .andExpect(unauthenticated())
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void nullHashIsRejectedForNonAdminRoles() {
        for (Role role : new Role[] {Role.EMPLOYEE, Role.IT_STAFF}) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user(login_name, password_hash, display_name, role, "
                    + "created_at) VALUES (:l, NULL, :d, :r, now())",
                new MapSqlParameterSource().addValue("l", data.name("nullhash-" + role.name().toLowerCase()))
                    .addValue("d", "x").addValue("r", role.name())))
                .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
