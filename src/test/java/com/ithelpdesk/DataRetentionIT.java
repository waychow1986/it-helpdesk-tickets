package com.ithelpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.ithelpdesk.auth.AppUserDetailsService;
import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.category.Category;
import com.ithelpdesk.category.CategoryRepository;
import com.ithelpdesk.report.WeeklyReport;
import com.ithelpdesk.report.WeeklyReportService;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.support.MutableClock;
import com.ithelpdesk.support.RunPrefix;
import com.ithelpdesk.support.TestClockConfig;
import com.ithelpdesk.ticket.TicketCommandService;
import com.ithelpdesk.ticket.TicketDetail;
import com.ithelpdesk.ticket.TicketForm;
import com.ithelpdesk.ticket.TicketQueryService;
import com.ithelpdesk.user.Role;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 数据保留（FR-029、FR-030、NFR-001、INV-008；IQA-015 的开发者侧对应验证）：
 * 在真实 dev 实例上写入数据后，启动一个全新的应用上下文（再次执行 Flyway；启动不写入任何账号或业务数据），
 * 快照、报表与 app_user 行数完全一致，账号仍可按原身份认证。V1 Independent QA 不执行 IQA-015，本测试为开发者侧辅助证据。
 */
class DataRetentionIT extends IntegrationTestBase {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @Autowired
    private TicketCommandService commands;

    @Autowired
    private TicketQueryService queries;

    @Autowired
    private WeeklyReportService reports;

    @Autowired
    private CategoryRepository categories;

    @Test
    void dataUnchangedAfterRestartingApplication() {
        Instant now = RunPrefix.randomHistoricalWednesdayNoon(SH);
        Instant monday = now.minus(Duration.ofHours(60));
        String empPassword = RunPrefix.randomPassword();
        String staffPassword = RunPrefix.randomPassword();
        CurrentUser admin = data.user("admin", Role.ADMIN);
        CurrentUser emp = data.user("emp", Role.EMPLOYEE, empPassword);
        CurrentUser staff = data.user("staff", Role.IT_STAFF, staffPassword);
        long cat = data.category("保留");

        List<Long> ids = new ArrayList<>();
        String[] targets = {"OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"};
        for (int i = 0; i < targets.length; i++) {
            clock.set(monday.plus(Duration.ofHours(i)));
            long id = commands.submit(emp, new TicketForm("保留 " + i, "描述 " + i, String.valueOf(cat), "HIGH"));
            commands.addComment(emp, id, "补充 " + i);
            clock.advance(Duration.ofMinutes(5));
            commands.claim(staff, id);
            commands.addComment(staff, id, "回复 " + i);
            if (!"OPEN".equals(targets[i])) {
                clock.advance(Duration.ofHours(5));
                commands.changeStatus(staff, id, targets[i]);
            }
            ids.add(id);
        }
        clock.set(now);
        List<TicketDetail> before = ids.stream().map(id -> queries.detail(emp, id)).toList();
        WeeklyReport reportBefore = reports.currentWeek(admin);
        Category categoryBefore = categories.findById(cat).orElseThrow();
        long usersBefore = countUsers(jdbc);

        ConfigurableApplicationContext restarted = new SpringApplicationBuilder(HelpdeskApplication.class,
            TestClockConfig.class).web(WebApplicationType.NONE).profiles("it").run();
        try {
            restarted.getBean(MutableClock.class).set(now);
            TicketQueryService q2 = restarted.getBean(TicketQueryService.class);
            List<TicketDetail> after = ids.stream().map(id -> q2.detail(emp, id)).toList();
            assertThat(after).isEqualTo(before);
            assertThat(after).extracting(d -> d.ticket().status().name()).containsExactly(targets);
            assertThat(after).allSatisfy(d -> {
                assertThat(d.comments()).hasSize(2);
                assertThat(d.events()).isNotEmpty();
            });
            assertThat(restarted.getBean(WeeklyReportService.class).currentWeek(admin)).isEqualTo(reportBefore);
            assertThat(restarted.getBean(CategoryRepository.class).findById(cat)).contains(categoryBefore);
            assertThat(countUsers(restarted.getBean(NamedParameterJdbcTemplate.class))).isEqualTo(usersBefore);

            DaoAuthenticationProvider provider = new DaoAuthenticationProvider(
                restarted.getBean(AppUserDetailsService.class));
            provider.setPasswordEncoder(restarted.getBean(PasswordEncoder.class));
            Authentication empAuth = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(emp.getLoginName(), empPassword));
            assertThat(((CurrentUser) empAuth.getPrincipal()).getRole()).isEqualTo(Role.EMPLOYEE);
            Authentication staffAuth = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(staff.getLoginName(), staffPassword));
            assertThat(((CurrentUser) staffAuth.getPrincipal()).getRole()).isEqualTo(Role.IT_STAFF);
        } finally {
            restarted.close();
        }
    }

    private static long countUsers(NamedParameterJdbcTemplate template) {
        return template.queryForObject("SELECT COUNT(*) FROM app_user", Map.of(), Long.class);
    }
}
