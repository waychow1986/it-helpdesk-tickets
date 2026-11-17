package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ithelpdesk.HelpdeskApplication;
import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.report.WeeklyReport;
import com.ithelpdesk.report.WeeklyReportService;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.support.RunPrefix;
import com.ithelpdesk.support.TestClockConfig;
import com.ithelpdesk.ticket.Ticket;
import com.ithelpdesk.ticket.TicketCommandService;
import com.ithelpdesk.ticket.TicketComment;
import com.ithelpdesk.ticket.TicketDetail;
import com.ithelpdesk.ticket.TicketForm;
import com.ithelpdesk.ticket.TicketQueryService;
import com.ithelpdesk.ticket.TicketRepository;
import com.ithelpdesk.ticket.TicketSearchCriteria;
import com.ithelpdesk.ticket.TicketStatus;
import com.ithelpdesk.web.ValidationException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 停用 / 启用在真实 dev 实例上的行为（TD-022、TD-025、TD-026、FR-007、FR-008、FR-012～FR-014、FR-016、
 * INV-003～INV-005、AC-005、AC-008、AC-009）。“唯一可用管理员”与并发互相停用在一次性 schema it_admin_&lt;runId&gt;
 * 中以独立应用上下文执行，结束时 DROP SCHEMA ... CASCADE；其余用例在 public schema 以运行前缀隔离。
 */
class AccountStatusIT extends IntegrationTestBase {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private AccountStatusService accountStatus;

    @Autowired
    private UserAdminService userAdmin;

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private TicketQueryService queries;

    @Autowired
    private WeeklyReportService reports;

    private String adminSchema;

    @AfterAll
    void dropAdminSchema() {
        if (adminSchema != null) {
            jdbc.getJdbcOperations().execute("DROP SCHEMA IF EXISTS " + adminSchema + " CASCADE");
            Long left = jdbc.queryForObject("SELECT COUNT(*) FROM pg_namespace WHERE nspname = :s",
                new MapSqlParameterSource("s", adminSchema), Long.class);
            assertThat(left).isZero();
        }
    }

    private Map<String, Object> accountRow(long id) {
        return jdbc.queryForMap("SELECT login_name, password_hash, display_name, role, created_at, created_by "
            + "FROM app_user WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    private boolean active(NamedParameterJdbcTemplate template, long id) {
        return Boolean.TRUE.equals(template.queryForObject("SELECT active FROM app_user WHERE id = :id",
            new MapSqlParameterSource("id", id), Boolean.class));
    }

    private long eventCount() {
        return data.count("SELECT COUNT(*) FROM ticket_event e JOIN app_user u ON u.id = e.actor_id "
            + "WHERE u.login_name LIKE :p", new MapSqlParameterSource("p", data.prefix() + "%"));
    }

    @Test
    void deactivateAndActivateOnlyChangeActive() {
        CurrentUser admin = data.user("admin", Role.ADMIN);
        CurrentUser e1 = data.user("e1", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t1", Role.IT_STAFF);
        long cat = data.category("状态");
        long ticket = commands.submit(e1, new TicketForm("状态不变", "d", String.valueOf(cat), "LOW"));
        commands.claim(t1, ticket);
        commands.addComment(t1, ticket, "回复");
        Map<String, Object> before = accountRow(t1.getId());
        Ticket ticketBefore = queries.detail(t1, ticket).ticket();
        long events = eventCount();

        assertThat(accountStatus.deactivate(admin, t1.getId()).message()).isEqualTo("已停用该账号");
        assertThat(active(jdbc, t1.getId())).isFalse();
        assertThat(accountRow(t1.getId())).isEqualTo(before);
        assertThat(accountStatus.deactivate(admin, t1.getId()))
            .isEqualTo(new AccountStatusService.Result(false, "账号已是停用状态"));
        assertThat(userAdmin.listUsers(admin)).filteredOn(u -> u.id() == t1.getId()).singleElement()
            .extracting(AppUser::active).isEqualTo(false);
        assertThat(accountStatus.activate(admin, t1.getId()).message()).isEqualTo("已启用该账号");
        assertThat(accountStatus.activate(admin, t1.getId()).message()).isEqualTo("账号已是启用状态");
        assertThat(active(jdbc, t1.getId())).isTrue();
        assertThat(accountRow(t1.getId())).isEqualTo(before);
        assertThat(eventCount()).isEqualTo(events);
        Ticket ticketAfter = queries.detail(t1, ticket).ticket();
        assertThat(ticketAfter).isEqualTo(ticketBefore);
        assertThatThrownBy(() -> accountStatus.deactivate(admin, Long.MAX_VALUE))
            .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("errors", Map.of("account", "账号不存在"));
    }

    @Test
    void newAccountsAreActiveAndDeactivatedLoginNameStaysTaken() {
        CurrentUser admin = data.user("admin2", Role.ADMIN);
        long id = userAdmin.createUser(admin, new UserForm(data.name("new"), RunPrefix.randomPassword(), "新账号",
            "EMPLOYEE"));
        assertThat(active(jdbc, id)).isTrue();
        accountStatus.deactivate(admin, id);
        assertThatThrownBy(() -> userAdmin.createUser(admin, new UserForm(data.name("new"),
            RunPrefix.randomPassword(), "同名", "EMPLOYEE")))
            .isInstanceOf(ValidationException.class)
            .hasFieldOrPropertyWithValue("errors", Map.of("loginName", "登录名已存在"));
        assertThat(data.count("SELECT COUNT(*) FROM app_user WHERE login_name = :l",
            new MapSqlParameterSource("l", data.name("new")))).isEqualTo(1);
    }

    @Test
    void historyOfDeactivatedAccountsIsShownAsBefore() {
        Instant now = RunPrefix.randomHistoricalWednesdayNoon(SH);
        Instant monday = now.minus(Duration.ofDays(2)).minus(Duration.ofHours(12));
        CurrentUser admin = data.user("admin3", Role.ADMIN);
        CurrentUser e3 = data.user("e3", Role.EMPLOYEE);
        CurrentUser t1 = data.user("t9", Role.IT_STAFF);
        CurrentUser t2 = data.user("t8", Role.IT_STAFF);
        long cat = data.category("历史");
        String keyword = data.prefix() + "历史词";
        clock.set(monday.plus(Duration.ofHours(9)));
        long w3 = commands.submit(e3, new TicketForm(keyword + "-W3", "d", String.valueOf(cat), "MEDIUM"));
        long open = commands.submit(e3, new TicketForm(keyword + "-未解决", "d", String.valueOf(cat), "LOW"));
        commands.addComment(e3, w3, "员工补充说明");
        commands.addComment(t1, w3, "IT 回复");
        commands.claim(t1, w3);
        commands.claim(t1, open);
        clock.set(monday.plus(Duration.ofHours(20)));
        commands.changeStatus(t1, w3, "RESOLVED");
        clock.set(now);
        long staffCountBefore = staffCount(admin, t1.getId());
        assertThat(staffCountBefore).isEqualTo(1);

        accountStatus.deactivate(admin, e3.getId());
        accountStatus.deactivate(admin, t1.getId());

        TicketSearchCriteria byKeyword = TicketSearchCriteria.parse(keyword, null, null, null, null, null, null, SH);
        TicketSearchCriteria byAssignee = TicketSearchCriteria.parse(null, null, null, String.valueOf(t1.getId()),
            null, null, null, SH);
        TicketSearchCriteria byCategory = TicketSearchCriteria.parse(null, null, String.valueOf(cat), null, null, null,
            null, SH);
        for (TicketSearchCriteria c : List.of(byKeyword, byAssignee, byCategory)) {
            List<Ticket> rows = queries.search(t2, c).items();
            assertThat(rows).extracting(Ticket::id).contains(w3, open);
            assertThat(rows).filteredOn(t -> t.id() == w3).singleElement().satisfies(t -> {
                assertThat(t.submitterName()).isEqualTo(e3.getDisplayName());
                assertThat(t.assigneeName()).isEqualTo(t1.getDisplayName());
            });
        }
        TicketDetail detail = queries.detail(t2, w3);
        assertThat(detail.comments()).extracting(TicketComment::authorName)
            .containsExactly(e3.getDisplayName(), t1.getDisplayName());
        assertThat(detail.comments()).extracting(c -> c.authorRole().name()).containsExactly("EMPLOYEE", "IT_STAFF");
        assertThat(detail.events()).isNotEmpty();
        assertThat(detail.events().get(0).actorName()).isEqualTo(t1.getDisplayName());
        assertThat(queries.assigneeOptions(t2)).extracting(TicketRepository.UserOption::id).contains(t1.getId());
        assertThat(staffCount(admin, t1.getId())).isEqualTo(staffCountBefore);
        Ticket unresolved = queries.detail(t2, open).ticket();
        assertThat(unresolved.assigneeId()).isEqualTo(t1.getId());
        assertThat(unresolved.status()).isEqualTo(TicketStatus.OPEN);
        // 停用的 IT 同事名下未解决工单不被改动；其他 IT 同事可重新认领
        commands.claim(t2, open);
        assertThat(queries.detail(t2, open).ticket().assigneeId()).isEqualTo(t2.getId());
    }

    private long staffCount(CurrentUser admin, long userId) {
        WeeklyReport report = reports.currentWeek(admin);
        return report.staff().stream().filter(s -> s.userId() == userId).mapToLong(WeeklyReport.StaffCount::count)
            .findFirst().orElse(-1);
    }

    @Test
    void lastUsableAdminIsProtectedEvenUnderConcurrency() throws Exception {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        adminSchema = "it_admin_" + HexFormat.of().formatHex(bytes);
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HelpdeskApplication.class,
            TestClockConfig.class).web(WebApplicationType.SERVLET).profiles("it")
            .run("--server.port=0", "--app.db.schema=" + adminSchema)) {
            NamedParameterJdbcTemplate db = ctx.getBean(NamedParameterJdbcTemplate.class);
            AccountStatusService service = ctx.getBean(AccountStatusService.class);
            assertThat(db.queryForObject("SELECT current_schema()", Map.of(), String.class)).isEqualTo(adminSchema);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM app_user", Map.of(), Long.class)).isZero();
            TestDataInSchema schemaData = new TestDataInSchema(db, data.prefix());
            CurrentUser a0 = schemaData.admin("a0", "$2a$04$syntheticHashA0");
            CurrentUser a1 = schemaData.admin("a1", "$2a$04$syntheticHashA1");
            schemaData.admin("legacy", null);

            // a0 停用后，a1 是唯一可用管理员（遗留 NULL 哈希 ADMIN 不计入）
            assertThat(service.deactivate(a1, a0.getId()).changed()).isTrue();
            assertThatThrownBy(() -> service.deactivate(a1, a1.getId())).isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("errors", Map.of("account", "系统必须至少保留一个可用的管理员"));
            assertThat(active(db, a1.getId())).isTrue();

            // 重新启用 a0 后两个可用管理员同时互相停用：只成功一个，始终至少保留一个可用管理员
            service.activate(a1, a0.getId());
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Boolean>> calls = List.of(
                CompletableFuture.supplyAsync(() -> attempt(service, go, a0, a1.getId())),
                CompletableFuture.supplyAsync(() -> attempt(service, go, a1, a0.getId())));
            go.countDown();
            List<Boolean> results = calls.stream().map(CompletableFuture::join).toList();
            assertThat(results).containsExactlyInAnyOrder(true, false);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND active "
                + "AND password_hash IS NOT NULL", Map.of(), Long.class)).isEqualTo(1);
        }
    }

    private static boolean attempt(AccountStatusService service, CountDownLatch go, CurrentUser actor, long target) {
        try {
            go.await(10, TimeUnit.SECONDS);
            return service.deactivate(actor, target).changed();
        } catch (ValidationException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 一次性 schema 内的合成管理员（哈希为合成占位值，不用于登录）。 */
    private record TestDataInSchema(NamedParameterJdbcTemplate db, String prefix) {
        CurrentUser admin(String suffix, String hash) {
            Long id = db.queryForObject("INSERT INTO app_user(login_name, password_hash, display_name, role, "
                    + "created_at) VALUES (:l, :h, :d, 'ADMIN', now()) RETURNING id",
                new MapSqlParameterSource().addValue("l", prefix + suffix).addValue("h", hash)
                    .addValue("d", prefix + suffix + "-姓名"), Long.class);
            return new CurrentUser(id, prefix + suffix, prefix + suffix + "-姓名", Role.ADMIN, hash);
        }
    }
}
