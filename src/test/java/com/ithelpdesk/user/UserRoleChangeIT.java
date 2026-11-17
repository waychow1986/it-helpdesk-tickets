package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.support.RunPrefix;
import com.ithelpdesk.ticket.TicketCommandService;
import com.ithelpdesk.ticket.TicketForm;
import com.ithelpdesk.web.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;

/** 设置 / 取消 IT 同事身份（FR-004、TD-006、INV-002、IQA-009）。 */
class UserRoleChangeIT extends IntegrationTestBase {

    @Autowired
    private UserAdminService service;

    @Autowired
    private UserRepository users;

    @Autowired
    private TicketCommandService tickets;

    @Test
    void switchBetweenEmployeeAndStaffOnly() {
        CurrentUser admin = data.user("admin", Role.ADMIN);
        CurrentUser u1 = data.user("u1", Role.EMPLOYEE);
        CurrentUser otherAdmin = data.user("admin-b", Role.ADMIN);
        service.changeRole(admin, u1.getId(), "IT_STAFF");
        assertThat(users.findById(u1.getId()).orElseThrow().role()).isEqualTo(Role.IT_STAFF);
        service.changeRole(admin, u1.getId(), "EMPLOYEE");
        assertThat(users.findById(u1.getId()).orElseThrow().role()).isEqualTo(Role.EMPLOYEE);
        assertThatThrownBy(() -> service.changeRole(admin, u1.getId(), "ADMIN"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.changeRole(admin, otherAdmin.getId(), "EMPLOYEE"))
            .isInstanceOf(ValidationException.class);
        assertThat(users.findById(otherAdmin.getId()).orElseThrow().role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void nextRequestOfExistingSessionUsesNewRoleAndHistoryKept() throws Exception {
        CurrentUser admin = data.user("admin2", Role.ADMIN);
        String password = RunPrefix.randomPassword();
        CurrentUser u1 = data.user("u2", Role.EMPLOYEE, password);
        CurrentUser employee = data.user("e2", Role.EMPLOYEE);
        long category = data.category("身份变更");
        long ticketId = tickets.submit(employee, new TicketForm("身份变更测试", "描述", String.valueOf(category), "LOW"));

        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin("/login").user(u1.getLoginName())
            .password(password)).andReturn().getRequest().getSession(false);
        mvc.perform(post("/tickets/" + ticketId + "/claim").session(session).with(csrf()))
            .andExpect(status().isForbidden());

        service.changeRole(admin, u1.getId(), "IT_STAFF");
        mvc.perform(post("/tickets/" + ticketId + "/claim").session(session).with(csrf()))
            .andExpect(status().isFound());
        assertThat(assignee(ticketId)).isEqualTo(u1.getId());
        assertThat(users.findCurrentItStaff()).extracting(AppUser::id).contains(u1.getId());

        service.changeRole(admin, u1.getId(), "EMPLOYEE");
        mvc.perform(post("/tickets/" + ticketId + "/status").param("status", "RESOLVED").session(session)
            .with(csrf())).andExpect(status().isForbidden());
        assertThat(users.findCurrentItStaff()).extracting(AppUser::id).doesNotContain(u1.getId());
        assertThat(assignee(ticketId)).isEqualTo(u1.getId());
    }

    private Long assignee(long ticketId) {
        return jdbc.queryForObject("SELECT assignee_id FROM ticket WHERE id = :id",
            new MapSqlParameterSource("id", ticketId), Long.class);
    }
}
