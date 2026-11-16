package com.ithelpdesk.ticket;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ithelpdesk.category.Category;
import com.ithelpdesk.support.WebSliceTestBase;
import com.ithelpdesk.support.WebSliceTestConfig;
import com.ithelpdesk.web.ValidationException;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;

@WebMvcTest(TicketController.class)
@Import(WebSliceTestConfig.class)
class TicketNewPageTest extends WebSliceTestBase {

    private static Matcher<String> has(String regex) {
        return matchesRegex("(?s).*" + regex + ".*");
    }

    @Test
    void noActiveCategoryShowsHintAndNoSubmit() throws Exception {
        when(categoryService.listActive()).thenReturn(List.of());
        mvc.perform(get("/tickets/new").with(as(employee)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("暂无可选类别，请联系管理员")))
            .andExpect(content().string(not(containsString("ticket-submit"))))
            .andExpect(content().string(not(containsString("ticket-category"))));
    }

    @Test
    void activeCategoriesRenderFormWithThreeUrgencies() throws Exception {
        when(categoryService.listActive()).thenReturn(List.of(new Category(3, "网络", true, Instant.EPOCH, null)));
        mvc.perform(get("/tickets/new").with(as(employee)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("ticket-submit")))
            .andExpect(content().string(has("<option value=\"3\"[^>]*>网络</option>")))
            .andExpect(content().string(has("data-testid=\"ticket-urgency\"[^>]*>\\s*"
                + "<option value=\"\">请选择</option>\\s*"
                + "<option value=\"LOW\">低</option>\\s*"
                + "<option value=\"MEDIUM\">中</option>\\s*"
                + "<option value=\"HIGH\">高</option>\\s*</select>")));
    }

    @Test
    void unavailableCategoryRerendersFormKeepingInput() throws Exception {
        when(categoryService.listActive()).thenReturn(List.of(new Category(3, "网络", true, Instant.EPOCH, null)));
        when(ticketCommandService.submit(any(), any()))
            .thenThrow(new ValidationException("categoryId", "所选类别已不可用"));
        mvc.perform(post("/tickets").with(csrf()).with(as(employee))
                .param("title", "合成标题保留").param("description", "合成描述保留")
                .param("categoryId", "9").param("urgency", "HIGH"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("所选类别已不可用")))
            .andExpect(content().string(has("value=\"合成标题保留\"")))
            .andExpect(content().string(has("data-testid=\"ticket-description\"[^>]*>合成描述保留</textarea>")))
            .andExpect(content().string(has("<option value=\"HIGH\" selected=\"selected\">高</option>")));
    }

    @Test
    void missingFieldsShowPerFieldErrors() throws Exception {
        when(categoryService.listActive()).thenReturn(List.of(new Category(3, "网络", true, Instant.EPOCH, null)));
        when(ticketCommandService.submit(any(), any())).thenAnswer(inv -> {
            throw new ValidationException(((TicketForm) inv.getArgument(1)).validate());
        });
        mvc.perform(post("/tickets").with(csrf()).with(as(employee)).param("title", "只有标题"))
            .andExpect(status().isOk())
            .andExpect(content().string(has("data-testid=\"field-error-description\">请填写问题描述<")))
            .andExpect(content().string(has("data-testid=\"field-error-categoryId\">请选择类别<")))
            .andExpect(content().string(has("data-testid=\"field-error-urgency\">请选择紧急程度<")))
            .andExpect(content().string(not(containsString("field-error-title"))))
            .andExpect(content().string(has("value=\"只有标题\"")));
    }
}
