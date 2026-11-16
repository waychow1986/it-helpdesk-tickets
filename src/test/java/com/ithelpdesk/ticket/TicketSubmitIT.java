package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.category.CategoryService;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.ValidationException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 员工提交工单（FR-007、FR-008、FR-009、INV-004、IQA-002）。 */
class TicketSubmitIT extends IntegrationTestBase {

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private CategoryService categories;

    @Test
    void submitCreatesOpenUnassignedTicketAtClockTime() {
        CurrentUser employee = data.user("e1", Role.EMPLOYEE);
        long category = data.category("提交");
        Instant at = Instant.parse("2026-11-16T03:04:05Z");
        clock.set(at);
        long id1 = commands.submit(employee, new TicketForm(" 打印机卡纸 ", "描述\n第二行", String.valueOf(category),
            "HIGH"));
        long id2 = commands.submit(employee, new TicketForm("另一张", "描述", String.valueOf(category), "LOW"));
        assertThat(id1).isNotEqualTo(id2);
        Ticket t = tickets.findById(id1).orElseThrow();
        assertThat(t.title()).isEqualTo("打印机卡纸");
        assertThat(t.description()).isEqualTo("描述\n第二行");
        assertThat(t.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(t.assigneeId()).isNull();
        assertThat(t.assigneeLabel()).isEqualTo("未分配");
        assertThat(t.submittedAt()).isEqualTo(at);
        assertThat(t.submitterId()).isEqualTo(employee.getId());
        assertThat(t.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(t.firstResolvedAt()).isNull();
    }

    @Test
    void removedCategoryRejected() {
        CurrentUser admin = data.user("admin", Role.ADMIN);
        CurrentUser employee = data.user("e2", Role.EMPLOYEE);
        long category = data.category("将移除");
        categories.remove(admin, category);
        assertThatThrownBy(() -> commands.submit(employee,
            new TicketForm("t", "d", String.valueOf(category), "LOW")))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getErrors())
                .containsEntry("categoryId", "所选类别已不可用"));
    }

    @Test
    void onlyEmployeesCanSubmit() {
        long category = data.category("身份");
        for (Role role : new Role[] {Role.IT_STAFF, Role.ADMIN}) {
            CurrentUser user = data.user("x-" + role.name(), role);
            assertThatThrownBy(() -> commands.submit(user,
                new TicketForm("t", "d", String.valueOf(category), "LOW"))).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void repositoryHasNoStatementPathToModifyBasicInfo() {
        // 仓库层只暴露处理人与状态更新方法；不存在修改标题 / 描述 / 类别 / 紧急程度 / 提交人的方法（FR-009）。
        assertThat(Arrays.stream(TicketRepository.class.getDeclaredMethods()).map(Method::getName)
            .filter(n -> n.startsWith("update"))).containsExactlyInAnyOrder("updateAssignee", "updateStatus");
    }
}
