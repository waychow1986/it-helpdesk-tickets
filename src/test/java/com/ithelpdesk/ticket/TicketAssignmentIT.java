package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.support.LogCapture;
import com.ithelpdesk.user.AccountStatusService;
import com.ithelpdesk.user.AppUser;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.user.UserAdminService;
import com.ithelpdesk.web.ValidationException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 认领与分派（V1 FR-014、FR-015、FR-016、FR-020、INV-002、INV-005、IQA-005；
 * V2 分派对象只含启用状态的 IT 同事、提交时校验启用状态、与停用并发按提交顺序生效：TD-026、FR-011、FR-012、AC-007）。
 */
class TicketAssignmentIT extends IntegrationTestBase {

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private TicketQueryService queries;

    @Autowired
    private UserAdminService userAdmin;

    @Autowired
    private AccountStatusService accountStatus;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void staffSeesTicketsOfAllSubmitters() {
        CurrentUser e1 = data.user("e1", Role.EMPLOYEE);
        CurrentUser e2 = data.user("e2", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t1", Role.IT_STAFF);
        long cat = data.category("全部");
        long a = commands.submit(e1, new TicketForm("全部可见甲", "d", String.valueOf(cat), "LOW"));
        long b = commands.submit(e2, new TicketForm("全部可见乙", "d", String.valueOf(cat), "LOW"));
        TicketPage page = queries.search(t1, TicketSearchCriteria.parse(null, null, String.valueOf(cat), null, null,
            null, null, ZoneId.of("Asia/Shanghai")));
        assertThat(page.items()).extracting(Ticket::id).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void claimAndAssignRecordEventsWithBeforeAndAfter() {
        CurrentUser e1 = data.user("e3", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t3", Role.IT_STAFF);
        CurrentUser t2 = data.user("t4", Role.IT_STAFF);
        long cat = data.category("认领");
        long id = commands.submit(e1, new TicketForm("认领分派", "d", String.valueOf(cat), "LOW"));

        clock.set(Instant.parse("2026-11-16T02:00:00Z"));
        assertThat(commands.claim(t1, id)).isEqualTo("已认领该工单");
        assertThat(commands.claim(t1, id)).isEqualTo("您已是该工单处理人");
        clock.set(Instant.parse("2026-11-16T02:01:00Z"));
        assertThat(commands.assign(t1, id, String.valueOf(t2.getId()))).isEqualTo("已分派该工单");
        assertThat(commands.assign(t1, id, String.valueOf(t2.getId()))).isEqualTo("处理人未变化");
        clock.set(Instant.parse("2026-11-16T02:02:00Z"));
        commands.claim(t1, id);

        TicketDetail detail = queries.detail(e1, id);
        assertThat(detail.ticket().assigneeId()).isEqualTo(t1.getId());
        List<TicketEvent> events = detail.events();
        assertThat(events).hasSize(3);
        assertThat(events).extracting(TicketEvent::eventType)
            .containsExactly(EventType.CLAIM, EventType.ASSIGN, EventType.CLAIM);
        assertThat(events.get(0).fromValue()).isEqualTo("未分配");
        assertThat(events.get(0).toValue()).isEqualTo(t1.getDisplayName());
        assertThat(events.get(0).actorName()).isEqualTo(t1.getDisplayName());
        assertThat(events.get(0).createdAt()).isEqualTo(Instant.parse("2026-11-16T02:00:00Z"));
        assertThat(events.get(1).fromValue()).isEqualTo(t1.getDisplayName());
        assertThat(events.get(1).toValue()).isEqualTo(t2.getDisplayName());
        assertThat(events.get(2).fromValue()).isEqualTo(t2.getDisplayName());
        assertThat(events.get(2).toValue()).isEqualTo(t1.getDisplayName());
    }

    @Test
    void assignTargetMustBeAnotherCurrentStaff() {
        CurrentUser admin = data.user("admin", Role.ADMIN);
        CurrentUser e1 = data.user("e5", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t5", Role.IT_STAFF);
        CurrentUser former = data.user("t6", Role.IT_STAFF);
        userAdmin.changeRole(admin, former.getId(), "EMPLOYEE");
        long cat = data.category("分派校验");
        long id = commands.submit(e1, new TicketForm("分派校验", "d", String.valueOf(cat), "LOW"));
        for (long target : new long[] {e1.getId(), admin.getId(), former.getId(), t1.getId(), Long.MAX_VALUE}) {
            assertThatThrownBy(() -> commands.assign(t1, id, String.valueOf(target)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrors())
                    .containsEntry("assigneeId", "分派对象必须是其他 IT 同事"));
        }
        TicketDetail detail = queries.detail(t1, id);
        assertThat(detail.ticket().assigneeId()).isNull();
        assertThat(detail.events()).isEmpty();
        assertThat(queries.assignableStaff(t1)).extracting(u -> u.id())
            .doesNotContain(t1.getId(), former.getId(), e1.getId(), admin.getId());
    }

    @Test
    void failedTransactionRollsBackEntirely() {
        CurrentUser e1 = data.user("e7", Role.EMPLOYEE);
        long cat = data.category("回滚");
        long id = commands.submit(e1, new TicketForm("回滚", "d", String.valueOf(cat), "LOW"));
        CurrentUser ghost = new CurrentUser(Long.MAX_VALUE, "ghost", "幽灵", Role.IT_STAFF, null);
        assertThatThrownBy(() -> commands.claim(ghost, id)).isInstanceOf(DataIntegrityViolationException.class);
        TicketDetail detail = queries.detail(e1, id);
        assertThat(detail.ticket().assigneeId()).isNull();
        assertThat(detail.events()).isEmpty();
    }

    @Test
    void candidatesAreActiveStaffSortedAndDeactivatedTargetIsRejected() {
        CurrentUser admin = data.user("admin-v2", Role.ADMIN);
        CurrentUser e1 = data.user("e-v2", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t-v2-a", Role.IT_STAFF);
        CurrentUser t2 = data.user("t-v2-c", Role.IT_STAFF);
        CurrentUser t3 = data.user("t-v2-b", Role.IT_STAFF);
        long cat = data.category("停用分派");
        long id = commands.submit(e1, new TicketForm("停用分派", "d", String.valueOf(cat), "LOW"));
        assertThat(prefixCandidates(t1)).containsExactly(t3.getId(), t2.getId());

        accountStatus.deactivate(admin, t2.getId());
        assertThat(prefixCandidates(t1)).containsExactly(t3.getId());
        try (LogCapture logs = new LogCapture(TicketCommandService.class)) {
            assertThatThrownBy(() -> commands.assign(t1, id, String.valueOf(t2.getId())))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrors())
                    .containsEntry("assigneeId", "该 IT 同事已停用，不能分派，请刷新后重新选择"));
            assertThat(logs.messages()).containsExactly(
                "分派被拒绝：目标账号已停用（工单 id=" + id + "，目标 id=" + t2.getId() + "）");
        }
        TicketDetail detail = queries.detail(t1, id);
        assertThat(detail.ticket().assigneeId()).isNull();
        assertThat(detail.events()).isEmpty();
        // 非 IT 同事或本人：沿用 V1 提示
        for (long target : new long[] {e1.getId(), t1.getId()}) {
            assertThatThrownBy(() -> commands.assign(t1, id, String.valueOf(target)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrors())
                    .containsEntry("assigneeId", "分派对象必须是其他 IT 同事"));
        }
        accountStatus.activate(admin, t2.getId());
        assertThat(prefixCandidates(t1)).containsExactly(t3.getId(), t2.getId());
        assertThat(commands.assign(t1, id, String.valueOf(t2.getId()))).isEqualTo("已分派该工单");
    }

    private List<Long> prefixCandidates(CurrentUser actor) {
        return queries.assignableStaff(actor).stream().filter(u -> u.loginName().startsWith(data.prefix()))
            .map(AppUser::id).toList();
    }

    @Test
    void deactivationCommittedFirstMakesConcurrentAssignFail() throws Exception {
        CurrentUser admin = data.user("admin-c1", Role.ADMIN);
        CurrentUser e1 = data.user("e-c1", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t-c1", Role.IT_STAFF);
        CurrentUser t2 = data.user("t-c2", Role.IT_STAFF);
        long id = commands.submit(e1, new TicketForm("并发停用在先", "d", String.valueOf(data.category("并发1")), "LOW"));

        CompletableFuture<Object> assign = holdThenRace(
            () -> accountStatus.deactivate(admin, t2.getId()),
            () -> commands.assign(t1, id, String.valueOf(t2.getId())));
        assertThat(assign.handle((ok, error) -> error).get(30, TimeUnit.SECONDS))
            .hasRootCauseInstanceOf(ValidationException.class);
        assertThat(queries.detail(t1, id).ticket().assigneeId()).isNull();
        assertThat(queries.detail(t1, id).events()).isEmpty();
    }

    @Test
    void assignCommittedFirstIsKeptAfterConcurrentDeactivation() throws Exception {
        CurrentUser admin = data.user("admin-c2", Role.ADMIN);
        CurrentUser e1 = data.user("e-c2", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t-c3", Role.IT_STAFF);
        CurrentUser t2 = data.user("t-c4", Role.IT_STAFF);
        long id = commands.submit(e1, new TicketForm("并发分派在先", "d", String.valueOf(data.category("并发2")), "LOW"));

        CompletableFuture<Object> deactivate = holdThenRace(
            () -> commands.assign(t1, id, String.valueOf(t2.getId())),
            () -> accountStatus.deactivate(admin, t2.getId()));
        assertThat(deactivate.get(30, TimeUnit.SECONDS)).isEqualTo(new AccountStatusService.Result(true, "已停用该账号"));
        TicketDetail detail = queries.detail(t1, id);
        assertThat(detail.ticket().assigneeId()).isEqualTo(t2.getId());
        assertThat(detail.events()).extracting(TicketEvent::eventType).containsExactly(EventType.ASSIGN);
    }

    /**
     * 在事务中执行 first 并保持事务不提交，同时启动 second；确认 second 被行锁阻塞后提交 first，返回 second 的结果。
     */
    private CompletableFuture<Object> holdThenRace(Supplier<Object> first, Supplier<Object> second) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> holder = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            Object result = first.get();
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result;
        }));
        assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Object> racer = CompletableFuture.supplyAsync(second);
        Thread.sleep(1500);
        assertThat(racer.isDone()).as("后提交的操作应被行锁阻塞").isFalse();
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);
        return racer;
    }
}
