package com.ithelpdesk.ticket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ithelpdesk.support.WebSliceTestBase;
import com.ithelpdesk.support.WebSliceTestConfig;
import com.ithelpdesk.web.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;

@WebMvcTest(TicketController.class)
@Import(WebSliceTestConfig.class)
class TicketActionAuthorizationTest extends WebSliceTestBase {

    @Test
    void employeeCannotClaimAssignOrChangeStatus() throws Exception {
        mvc.perform(post("/tickets/5/claim").with(csrf()).with(as(employee))).andExpect(status().isForbidden());
        mvc.perform(post("/tickets/5/assign").param("assigneeId", "201").with(csrf()).with(as(employee)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/tickets/5/status").param("status", "RESOLVED").with(csrf()).with(as(employee)))
            .andExpect(status().isForbidden());
        verifyNoInteractions(ticketCommandService);
    }

    @Test
    void invalidStatusCodeIs400() throws Exception {
        when(ticketCommandService.changeStatus(any(), anyLong(), anyString()))
            .thenThrow(new BadRequestException("status"));
        mvc.perform(post("/tickets/5/status").param("status", "DONE").with(csrf()).with(as(staff)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void staffActionsRedirectBackToDetail() throws Exception {
        when(ticketCommandService.claim(any(), eq(5L))).thenReturn("已认领该工单");
        mvc.perform(post("/tickets/5/claim").with(csrf()).with(as(staff)))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/tickets/5"));
        verify(ticketCommandService).claim(any(), eq(5L));
    }
}
