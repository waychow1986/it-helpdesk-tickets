package com.ithelpdesk.user;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ithelpdesk.support.WebSliceTestBase;
import com.ithelpdesk.support.WebSliceTestConfig;
import com.ithelpdesk.user.SetupTokenVerifier.Result;
import com.ithelpdesk.web.ValidationException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** “设置初始管理员”页面（Web 切片，服务、口令校验与限流为替身，只验证 Web 层判定顺序与渲染）。 */
@WebMvcTest
@Import(WebSliceTestConfig.class)
class InitialAdminSetupControllerTest extends WebSliceTestBase {

    private static final String TOKEN = "Synthetic-Token-For-Slice-Test-01";
    private static final String PASSWORD = "Synthetic-Pass-99";

    @BeforeEach
    void open() {
        when(initialAdminSetupService.isSetupOpen()).thenReturn(true);
        when(setupTokenVerifier.isConfigured()).thenReturn(true);
        when(setupTokenVerifier.verify(TOKEN)).thenReturn(Result.OK);
        when(setupAttemptLimiter.isBlocked(anyString())).thenReturn(false);
    }

    private MockHttpServletRequestBuilder submit(String token) {
        return post("/setup").with(csrf()).with(r -> {
            r.setRemoteAddr("198.51.100.9");
            return r;
        })
            .param("setupToken", token == null ? "" : token)
            .param("loginName", "root-admin").param("password", PASSWORD).param("displayName", "合成甲某");
    }

    @Test
    void openFormRendersFieldsWithoutRoleChoiceOrNavigation() throws Exception {
        mvc.perform(get("/setup"))
            .andExpect(status().isOk())
            .andExpect(content().string(allOf(
                containsString("type=\"password\" name=\"setupToken\" value=\"\" data-testid=\"setup-token\" "
                    + "autocomplete=\"off\""),
                containsString("data-testid=\"setup-login-name\""),
                containsString("data-testid=\"setup-password\""),
                containsString("data-testid=\"setup-display-name\""),
                containsString("data-testid=\"setup-submit\""),
                containsString("novalidate"))))
            .andExpect(content().string(allOf(
                not(containsString("name=\"role\"")),
                not(containsString("data-testid=\"nav-")),
                not(containsString("data-testid=\"logout\"")),
                not(containsString("setup-token-missing")),
                not(containsString("注册")))));
    }

    @Test
    void openFormShowsMissingTokenHint() throws Exception {
        when(setupTokenVerifier.isConfigured()).thenReturn(false);
        mvc.perform(get("/setup"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-testid=\"setup-token-missing\"")))
            .andExpect(content().string(containsString("此部署未提供设置口令，暂不能完成设置")));
    }

    @Test
    void closedRejectsGetAndPostWithoutCheckingToken() throws Exception {
        when(initialAdminSetupService.isSetupOpen()).thenReturn(false);
        mvc.perform(get("/setup"))
            .andExpect(status().isForbidden())
            .andExpect(content().string(containsString("data-testid=\"setup-closed\"")))
            .andExpect(content().string(containsString("系统已完成初始管理员设置，此入口不可用")))
            .andExpect(content().string(containsString("href=\"/login\"")))
            .andExpect(content().string(not(containsString("data-testid=\"setup-token\""))));
        mvc.perform(submit(TOKEN)).andExpect(status().isForbidden())
            .andExpect(content().string(containsString("data-testid=\"setup-closed\"")));
        mvc.perform(submit(null)).andExpect(status().isForbidden());
        verify(setupTokenVerifier, never()).verify(any());
        verify(setupAttemptLimiter, never()).recordFailure(any());
        verify(initialAdminSetupService, never()).setup(any());
    }

    @Test
    void throttledAddressGets429() throws Exception {
        when(setupAttemptLimiter.isBlocked("198.51.100.9")).thenReturn(true);
        mvc.perform(submit(TOKEN))
            .andExpect(status().isTooManyRequests())
            .andExpect(content().string(containsString("data-testid=\"setup-throttled\"")))
            .andExpect(content().string(containsString("尝试次数过多，请 15 分钟后再试")))
            .andExpect(content().string(not(containsString(TOKEN))))
            .andExpect(content().string(not(containsString(PASSWORD))));
        verify(setupTokenVerifier, never()).verify(any());
        verify(initialAdminSetupService, never()).setup(any());
    }

    @Test
    void tokenFailuresRerenderCountAndDoNotWrite() throws Exception {
        Map<Result, String> cases = Map.of(
            Result.NOT_CONFIGURED, "此部署未提供设置口令，暂不能完成设置",
            Result.EMPTY, "请填写设置口令",
            Result.MISMATCH, "设置口令不正确");
        int failures = 0;
        for (Map.Entry<Result, String> c : cases.entrySet()) {
            String submitted = "wrong-" + c.getKey().name();
            when(setupTokenVerifier.verify(submitted)).thenReturn(c.getKey());
            mvc.perform(submit(submitted))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-testid=\"field-error-setupToken\"")))
                .andExpect(content().string(containsString(c.getValue())))
                .andExpect(content().string(containsString("value=\"root-admin\"")))
                .andExpect(content().string(containsString("value=\"合成甲某\"")))
                .andExpect(content().string(not(containsString(submitted))))
                .andExpect(content().string(not(containsString(PASSWORD))));
            failures++;
            verify(setupAttemptLimiter, org.mockito.Mockito.times(failures)).recordFailure("198.51.100.9");
        }
        verify(initialAdminSetupService, never()).setup(any());
    }

    @Test
    void fieldValidationFailureAfterCorrectTokenIsNotCounted() throws Exception {
        when(initialAdminSetupService.setup(any())).thenThrow(new ValidationException("loginName", "请填写登录名"));
        mvc.perform(submit(TOKEN))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-testid=\"field-error-loginName\"")))
            .andExpect(content().string(containsString("请填写登录名")))
            .andExpect(content().string(not(containsString(TOKEN))))
            .andExpect(content().string(not(containsString(PASSWORD))));
        verify(setupAttemptLimiter, never()).recordFailure(any());
    }

    @Test
    void concurrentCloseDuringSubmitGives403() throws Exception {
        when(initialAdminSetupService.setup(any())).thenThrow(new SetupClosedException());
        mvc.perform(submit(TOKEN)).andExpect(status().isForbidden())
            .andExpect(content().string(containsString("data-testid=\"setup-closed\"")));
    }

    @Test
    void successRedirectsToLoginWithoutAutoLogin() throws Exception {
        when(initialAdminSetupService.setup(any())).thenReturn(5L);
        mvc.perform(submit(TOKEN))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/login?setup"))
            .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers
                .unauthenticated());
        verify(setupAttemptLimiter, never()).recordFailure(any());
    }

    @Test
    void postWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/setup").param("setupToken", TOKEN).param("loginName", "x")
                .param("password", "y").param("displayName", "z"))
            .andExpect(status().isForbidden());
        verify(initialAdminSetupService, never()).setup(any());
    }

    @Test
    void loginPageShowsSetupLinkOnlyWhenOpen() throws Exception {
        mvc.perform(get("/login"))
            .andExpect(content().string(containsString("data-testid=\"setup-link\"")))
            .andExpect(content().string(containsString("href=\"/setup\"")))
            .andExpect(content().string(not(containsString("注册"))));
        when(initialAdminSetupService.isSetupOpen()).thenReturn(false);
        mvc.perform(get("/login"))
            .andExpect(content().string(not(containsString("setup-link"))))
            .andExpect(content().string(not(containsString("注册"))));
        mvc.perform(get("/login").param("setup", ""))
            .andExpect(content().string(containsString("data-testid=\"setup-success\"")))
            .andExpect(content().string(containsString("初始管理员已设置，请使用该账号登录")));
    }
}
