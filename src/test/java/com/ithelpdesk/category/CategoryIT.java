package com.ithelpdesk.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.ticket.Ticket;
import com.ithelpdesk.ticket.TicketCommandService;
import com.ithelpdesk.ticket.TicketForm;
import com.ithelpdesk.ticket.TicketRepository;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.web.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 类别维护（FR-005、FR-006、INV-007、IQA-008）。 */
class CategoryIT extends IntegrationTestBase {

    @Autowired
    private CategoryService service;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private TicketCommandService tickets;

    @Autowired
    private TicketRepository ticketRepository;

    private static void assertNameExists(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getErrors()).containsEntry("name", "类别名称已存在"));
    }

    @Test
    void createRenameRemoveLifecycle() {
        CurrentUser admin = data.user("admin", Role.ADMIN);
        CurrentUser employee = data.user("e1", Role.EMPLOYEE);
        long id = service.create(admin, "  " + data.name("测试类别甲") + " ");
        assertThat(categories.findById(id).orElseThrow().name()).isEqualTo(data.name("测试类别甲"));
        assertThat(service.listActive()).extracting(Category::id).contains(id);

        long ticketId = tickets.submit(employee, new TicketForm("类别测试", "描述", String.valueOf(id), "MEDIUM"));

        service.rename(admin, id, data.name("测试类别乙"));
        Ticket renamed = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(renamed.categoryName()).isEqualTo(data.name("测试类别乙"));

        service.remove(admin, id);
        Category removed = categories.findById(id).orElseThrow();
        assertThat(removed.active()).isFalse();
        assertThat(removed.removedAt()).isNotNull();
        assertThat(service.listActive()).extracting(Category::id).doesNotContain(id);
        Ticket historical = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(historical.categoryId()).isEqualTo(id);
        assertThat(historical.categoryLabel()).isEqualTo(data.name("测试类别乙") + "（已移除）");
    }

    @Test
    void duplicateActiveNamesRejectedButRemovedNameReusable() {
        CurrentUser admin = data.user("admin2", Role.ADMIN);
        long a = service.create(admin, data.name("网络"));
        long b = service.create(admin, data.name("电脑"));
        assertNameExists(() -> service.create(admin, data.name("网络")));
        assertNameExists(() -> service.rename(admin, b, data.name("网络")));
        service.remove(admin, a);
        long again = service.create(admin, data.name("网络"));
        assertThat(again).isNotEqualTo(a);
        assertThatThrownBy(() -> service.create(admin, "   ")).isInstanceOf(ValidationException.class);
    }
}
