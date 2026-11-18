package com.ithelpdesk.support;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.category.CategoryService;
import com.ithelpdesk.report.WeeklyReportService;
import com.ithelpdesk.ticket.TicketCommandService;
import com.ithelpdesk.ticket.TicketQueryService;
import com.ithelpdesk.user.AccountStatusService;
import com.ithelpdesk.user.AppUser;
import com.ithelpdesk.user.InitialAdminSetupService;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.user.SetupAttemptLimiter;
import com.ithelpdesk.user.SetupTokenVerifier;
import com.ithelpdesk.user.UserAdminService;
import com.ithelpdesk.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Web 切片测试基类：服务层与仓库层使用替身，只验证 Web 层授权、渲染与请求处理（纯逻辑替身，不作为功能证据）。
 */
public abstract class WebSliceTestBase {

    @Autowired
    protected MockMvc mvc;

    @MockitoBean
    protected UserRepository userRepository;

    @MockitoBean
    protected UserAdminService userAdminService;

    @MockitoBean
    protected AccountStatusService accountStatusService;

    @MockitoBean
    protected CategoryService categoryService;

    @MockitoBean
    protected TicketQueryService ticketQueryService;

    @MockitoBean
    protected TicketCommandService ticketCommandService;

    @MockitoBean
    protected WeeklyReportService weeklyReportService;

    @MockitoBean
    protected InitialAdminSetupService initialAdminSetupService;

    @MockitoBean
    protected SetupTokenVerifier setupTokenVerifier;

    @MockitoBean
    protected SetupAttemptLimiter setupAttemptLimiter;

    protected CurrentUser employee = new CurrentUser(101, "e1", "员工甲", Role.EMPLOYEE, "x");
    protected CurrentUser staff = new CurrentUser(201, "t1", "IT 甲", Role.IT_STAFF, "x");
    protected CurrentUser admin = new CurrentUser(301, "a1", "管理员甲", Role.ADMIN, "x");

    /** 以指定身份登录，并让 RoleRefreshFilter 从"库"中读到相同身份。 */
    protected RequestPostProcessor as(CurrentUser current) {
        when(userRepository.findById(current.getId())).thenReturn(Optional.of(new AppUser(current.getId(),
            current.getLoginName(), "hash", current.getDisplayName(), current.getRole(), Instant.EPOCH, null)));
        return user(current);
    }
}
