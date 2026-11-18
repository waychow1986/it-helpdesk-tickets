package com.ithelpdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.support.RunPrefix;
import com.ithelpdesk.user.AccountStatusService;
import com.ithelpdesk.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 停用账号的登录与已登录会话（真实 dev 实例 + 完整安全过滤链；TD-023、TD-024、FR-009、FR-010、AC-006）。
 */
class DeactivatedLoginIT extends IntegrationTestBase {

    @Autowired
    private AccountStatusService accountStatus;

    private MvcResult login(String login, String password) throws Exception {
        return mvc.perform(post("/login").with(csrf()).param("username", login).param("password", password))
            .andReturn();
    }

    @Test
    void deactivatedAccountCannotLoginAndExistingSessionIsEnded() throws Exception {
        CurrentUser admin = data.user("admin", Role.ADMIN);
        String password = RunPrefix.randomPassword();
        CurrentUser e2 = data.user("e2", Role.EMPLOYEE, password);

        MvcResult ok = login(e2.getLoginName(), password);
        assertThat(ok.getResponse().getRedirectedUrl()).isEqualTo("/");
        MockHttpSession session = (MockHttpSession) ok.getRequest().getSession(false);
        assertThat(mvc.perform(get("/tickets").session(session)).andReturn().getResponse().getStatus())
            .isEqualTo(200);

        accountStatus.deactivate(admin, e2.getId());

        // 已登录会话的下一次操作（POST）被引导至登录入口、会话失效且不产生写入
        long comments = data.count("SELECT COUNT(*) FROM ticket_comment WHERE author_id = :id",
            new MapSqlParameterSource("id", e2.getId()));
        MockHttpServletResponse next = mvc.perform(post("/tickets").with(csrf()).session(session)
            .param("title", "停用后提交").param("description", "d").param("categoryId", "1").param("urgency", "LOW"))
            .andReturn().getResponse();
        assertThat(next.getStatus()).isEqualTo(302);
        assertThat(next.getRedirectedUrl()).isEqualTo("/login");
        assertThat(session.isInvalid()).isTrue();
        assertThat(data.count("SELECT COUNT(*) FROM ticket WHERE submitter_id = :id",
            new MapSqlParameterSource("id", e2.getId()))).isZero();
        assertThat(data.count("SELECT COUNT(*) FROM ticket_comment WHERE author_id = :id",
            new MapSqlParameterSource("id", e2.getId()))).isEqualTo(comments);

        // 正确凭据与错误凭据：同一结果与提示
        MvcResult right = login(e2.getLoginName(), password);
        MvcResult wrong = login(e2.getLoginName(), password + "x");
        assertThat(right.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(wrong.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        String page = mvc.perform(get("/login").param("error", "")).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("登录名或密码错误").doesNotContain("停用");
        MockHttpSession refused = (MockHttpSession) right.getRequest().getSession(false);
        if (refused != null) {
            assertThat(mvc.perform(get("/tickets").session(refused)).andReturn().getResponse().getStatus())
                .isEqualTo(302);
        }

        // 重新启用后原凭据可登录并按原身份访问
        accountStatus.activate(admin, e2.getId());
        MvcResult again = login(e2.getLoginName(), password);
        assertThat(again.getResponse().getRedirectedUrl()).isEqualTo("/");
        MockHttpSession renewed = (MockHttpSession) again.getRequest().getSession(false);
        assertThat(mvc.perform(get("/tickets").session(renewed)).andReturn().getResponse().getContentAsString())
            .contains("我的工单");
        assertThat(mvc.perform(get("/admin/users").session(renewed)).andReturn().getResponse().getStatus())
            .isEqualTo(403);
    }
}
