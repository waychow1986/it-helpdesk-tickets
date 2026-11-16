package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import com.ithelpdesk.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 启动引导在真实 dev 实例上的行为（TD-011、FR-002、spec「前置条件」）。 */
class InitialAdminBootstrapIT extends IntegrationTestBase {

    @Autowired
    private InitialAdminBootstrap bootstrap;

    private long adminCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN'", Map.of(), Long.class);
    }

    @Test
    void contextStartupLeavesAtLeastOneAdmin() {
        assertThat(adminCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rerunningBootstrapDoesNotAddAdmins() {
        long before = adminCount();
        assertThat(bootstrap.bootstrap()).isFalse();
        assertThat(adminCount()).isEqualTo(before);
    }

    @Test
    void nullHashAdminCannotLoginWithAnyPassword() throws Exception {
        data.user("nullhash-admin", Role.ADMIN, null);
        for (String password : new String[] {"", "admin", "anything-123", "null"}) {
            mvc.perform(formLogin("/login").user(data.name("nullhash-admin")).password(password))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
        }
        // 与凭据错误的表现完全相同
        mvc.perform(formLogin("/login").user(data.name("no-such-user")).password("x"))
            .andExpect(unauthenticated())
            .andExpect(redirectedUrl("/login?error"));
    }
}
