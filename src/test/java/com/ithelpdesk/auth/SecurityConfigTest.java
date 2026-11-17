package com.ithelpdesk.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ithelpdesk.support.WebSliceTestBase;
import com.ithelpdesk.support.WebSliceTestConfig;
import com.ithelpdesk.web.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;

@WebMvcTest
@Import(WebSliceTestConfig.class)
class SecurityConfigTest extends WebSliceTestBase {

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mvc.perform(get("/tickets")).andExpect(status().isFound()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/admin/users")).andExpect(status().isFound()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/admin/reports/weekly")).andExpect(status().isFound())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void publicEndpointsAreAnonymous() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
            .andExpect(content().string(containsString("data-testid=\"login-username\"")))
            .andExpect(content().string(not(containsString("注册"))));
        mvc.perform(get("/api/candidate-identity")).andExpect(status().isOk());
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
        when(initialAdminSetupService.isSetupOpen()).thenReturn(false);
        mvc.perform(get("/setup")).andExpect(status().isForbidden())
            .andExpect(content().string(containsString("data-testid=\"setup-closed\"")));
        mvc.perform(post("/setup").with(csrf()).param("setupToken", "x"))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("data-testid=\"setup-closed\"")));
        // 平台 healthPath 匿名可达：切片中无 Actuator，结果不应是重定向到登录页
        int health = mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(health).isNotEqualTo(302);
    }

    @Test
    void setupPostWithoutCsrfIsForbidden() throws Exception {
        when(initialAdminSetupService.isSetupOpen()).thenReturn(true);
        mvc.perform(post("/setup").param("setupToken", "x").param("loginName", "y"))
            .andExpect(status().isForbidden())
            .andExpect(content().string(not(containsString("setup-closed"))));
    }

    @Test
    void postWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/tickets").with(as(employee))).andExpect(status().isForbidden());
        mvc.perform(post("/login").param("username", "a").param("password", "b"))
            .andExpect(status().isForbidden());
    }

    @Test
    void loginFailureRedirectsToGenericError() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "nobody").param("password", "bad"))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"));
        mvc.perform(get("/login").param("error", ""))
            .andExpect(content().string(containsString("登录名或密码错误")));
    }

    @Test
    void wrongRoleGetsForbiddenPageWithoutData() throws Exception {
        mvc.perform(get("/admin/users").with(as(employee))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/reports/weekly").with(as(staff))).andExpect(status().isForbidden());
        mvc.perform(get("/tickets").with(as(admin))).andExpect(status().isForbidden());
        mvc.perform(get("/tickets/new").with(as(staff))).andExpect(status().isForbidden());
    }

    @Test
    void serviceLevelDenialRendersForbiddenTemplate() throws Exception {
        when(ticketQueryService.detail(any(), anyLong())).thenThrow(new AccessDeniedException());
        mvc.perform(get("/tickets/999").with(as(employee)))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("您无权访问该页面或执行该操作")))
            .andExpect(content().string(containsString("data-testid=\"forbidden\"")));
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/login"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Content-Security-Policy", "default-src 'self'"));
    }

    @Test
    void homeRedirectsByRole() throws Exception {
        mvc.perform(get("/").with(as(employee))).andExpect(redirectedUrl("/tickets"));
        mvc.perform(get("/").with(as(staff))).andExpect(redirectedUrl("/tickets"));
        mvc.perform(get("/").with(as(admin))).andExpect(redirectedUrl("/admin/users"));
    }

    @Test
    void navigationMatchesRole() throws Exception {
        when(userAdminService.listUsers(any())).thenReturn(java.util.List.of());
        mvc.perform(get("/admin/users").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("nav-admin-users")))
            .andExpect(content().string(containsString("nav-report")))
            .andExpect(content().string(not(containsString("nav-my-tickets"))))
            .andExpect(content().string(not(containsString("nav-all-tickets"))));
    }
}
