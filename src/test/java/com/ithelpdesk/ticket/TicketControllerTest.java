package com.ithelpdesk.ticket;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ithelpdesk.support.TicketSamples;
import com.ithelpdesk.support.WebSliceTestBase;
import com.ithelpdesk.support.WebSliceTestConfig;
import com.ithelpdesk.web.AccessDeniedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;

@WebMvcTest(TicketController.class)
@Import(WebSliceTestConfig.class)
class TicketControllerTest extends WebSliceTestBase {

    @Test
    void employeeDeniedOtherOrMissingTicketWithoutLeakingContent() throws Exception {
        when(ticketQueryService.detail(any(), eq(55L))).thenThrow(new AccessDeniedException());
        when(ticketQueryService.detail(any(), eq(99999L))).thenThrow(new AccessDeniedException());
        for (long id : new long[] {55L, 99999L}) {
            mvc.perform(get("/tickets/" + id).with(as(employee)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("data-testid=\"forbidden\"")))
                .andExpect(content().string(not(containsString("合成标题"))))
                .andExpect(content().string(not(containsString("ticket-status"))))
                .andExpect(content().string(not(containsString("ticket-title"))));
        }
    }

    @Test
    void employeeDetailHasNoStaffActions() throws Exception {
        when(ticketQueryService.detail(any(), eq(5L))).thenReturn(TicketSamples.detail(5, employee.getId()));
        mvc.perform(get("/tickets/5").with(as(employee)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-testid=\"ticket-id\"")))
            .andExpect(content().string(containsString("comment-submit")))
            .andExpect(content().string(not(containsString("claim-button"))))
            .andExpect(content().string(not(containsString("assign-select"))))
            .andExpect(content().string(not(containsString("status-select"))));
    }

    @Test
    void staffDetailHasActions() throws Exception {
        when(ticketQueryService.detail(any(), eq(5L))).thenReturn(TicketSamples.detail(5, employee.getId()));
        when(ticketQueryService.assignableStaff(any())).thenReturn(List.of());
        mvc.perform(get("/tickets/5").with(as(staff)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("claim-button")))
            .andExpect(content().string(containsString("assign-select")))
            .andExpect(content().string(containsString("status-select")));
    }

    @Test
    void adminCannotSeeTicketListOrDetail() throws Exception {
        mvc.perform(get("/tickets").with(as(admin))).andExpect(status().isForbidden());
        mvc.perform(get("/tickets/5").with(as(admin))).andExpect(status().isForbidden());
        verifyNoInteractions(ticketQueryService);
    }

    @Test
    void listShowsEmptyMessageAndTitleByRole() throws Exception {
        when(ticketQueryService.search(any(), any())).thenReturn(new TicketPage(List.of(), 1, false));
        mvc.perform(get("/tickets").with(as(employee)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("我的工单")))
            .andExpect(content().string(containsString("无匹配工单")));
        mvc.perform(get("/tickets").with(as(staff)))
            .andExpect(content().string(containsString("全部工单")));
    }

    @Test
    void invalidFilterParametersReturn400() throws Exception {
        mvc.perform(get("/tickets").param("from", "not-a-date").with(as(staff)))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("请求参数无效")));
        mvc.perform(get("/tickets/abc").with(as(staff))).andExpect(status().isBadRequest());
    }
}
