package com.ithelpdesk.user;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.WebSliceTestBase;
import com.ithelpdesk.support.WebSliceTestConfig;
import com.ithelpdesk.web.ValidationException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;

/** 账号管理停用 / 启用入口的 Web 层授权、校验与渲染（TD-027、FR-007、FR-008、FR-015、AC-005、AC-010）。 */
@WebMvcTest
@Import(WebSliceTestConfig.class)
class UserAdminControllerTest extends WebSliceTestBase {

    private static final String HASH = "$2a$10$syntheticHashValueNotRendered";

    private void listing(AppUser... rows) {
        when(userAdminService.listUsers(any())).thenReturn(List.of(rows));
    }

    @Test
    void nonAdminsAreForbiddenAndServiceNotCalled() throws Exception {
        for (CurrentUser actor : new CurrentUser[] {employee, staff}) {
            for (String action : new String[] {"deactivate", "activate"}) {
                mvc.perform(post("/admin/users/5/" + action).with(csrf()).with(as(actor)))
                    .andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(accountStatusService);
    }

    @Test
    void csrfIsRequired() throws Exception {
        mvc.perform(post("/admin/users/5/deactivate").with(as(admin))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/5/activate").with(as(admin))).andExpect(status().isForbidden());
        verifyNoInteractions(accountStatusService);
    }

    @Test
    void nonPositiveIdIsBadRequest() throws Exception {
        for (String id : new String[] {"0", "-1", "abc", "1.5", "99999999999999999999"}) {
            mvc.perform(post("/admin/users/" + id + "/deactivate").with(csrf()).with(as(admin)))
                .andExpect(status().isBadRequest());
            mvc.perform(post("/admin/users/" + id + "/activate").with(csrf()).with(as(admin)))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(accountStatusService);
    }

    @Test
    void successAndAlreadyStateRedirectWithFlash() throws Exception {
        when(accountStatusService.deactivate(any(), anyLong()))
            .thenReturn(new AccountStatusService.Result(true, "已停用该账号"));
        mvc.perform(post("/admin/users/5/deactivate").with(csrf()).with(as(admin)))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/users"))
            .andExpect(flash().attribute("flash", "已停用该账号"));
        verify(accountStatusService).deactivate(admin, 5);

        when(accountStatusService.activate(any(), anyLong()))
            .thenReturn(new AccountStatusService.Result(false, "账号已是启用状态"));
        mvc.perform(post("/admin/users/6/activate").with(csrf()).with(as(admin)))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/users"))
            .andExpect(flash().attribute("flash", "账号已是启用状态"));
    }

    @Test
    void validationFailureRedirectsWithFlashError() throws Exception {
        when(accountStatusService.deactivate(any(), anyLong()))
            .thenThrow(new ValidationException("account", "系统必须至少保留一个可用的管理员"));
        mvc.perform(post("/admin/users/301/deactivate").with(csrf()).with(as(admin)))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/users"))
            .andExpect(flash().attribute("flashError", "系统必须至少保留一个可用的管理员"));
    }

    @Test
    void selfDeactivationRendersResultDirectly() throws Exception {
        when(accountStatusService.deactivate(any(), anyLong()))
            .thenReturn(new AccountStatusService.Result(true, "已停用该账号"));
        listing(new AppUser(301, "a1", HASH, "管理员甲", Role.ADMIN, Instant.EPOCH, null, false),
            new AppUser(302, "a2", HASH, "管理员乙", Role.ADMIN, Instant.EPOCH, null, true));
        mvc.perform(post("/admin/users/301/deactivate").with(csrf()).with(as(admin)))
            .andExpect(status().isOk()).andExpect(view().name("admin/users"))
            .andExpect(content().string(allOf(containsString("已停用该账号"),
                containsString("data-active=\"false\""), not(containsString(HASH)))));
    }

    @Test
    void listShowsStatusAndOneButtonPerRow() throws Exception {
        listing(new AppUser(11, "on-user", HASH, "启用者", Role.EMPLOYEE, Instant.EPOCH, null, true),
            new AppUser(12, "off-user", HASH, "停用者", Role.IT_STAFF, Instant.EPOCH, null, false));
        String html = mvc.perform(get("/admin/users").with(as(admin))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html)
            .contains("data-testid=\"user-row-status\" data-active=\"true\">启用<")
            .contains("data-testid=\"user-row-status\" data-active=\"false\">停用<")
            .contains("/admin/users/11/deactivate").doesNotContain("/admin/users/11/activate")
            .contains("/admin/users/12/activate").doesNotContain("/admin/users/12/deactivate")
            .contains("data-testid=\"user-deactivate\"").contains("data-testid=\"user-activate\"")
            .contains("name=\"_csrf\"")
            .doesNotContain(HASH);
    }
}
