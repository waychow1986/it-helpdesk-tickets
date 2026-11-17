package com.ithelpdesk.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.LogCapture;
import com.ithelpdesk.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

class RequestLogFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void logsOnlyMethodRouteStatusDurationAndUserId() throws Exception {
        CurrentUser user = new CurrentUser(42, "secret-login", "敏感姓名", Role.EMPLOYEE, "$2a$10$hash");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(user, "pw-secret", user.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/tickets/77/comments");
        request.setQueryString("q=打印机卡纸");
        request.addParameter("q", "打印机卡纸");
        request.addParameter("body", "客户内容VPN");
        request.addParameter("password", "pw-secret");
        request.setContent("body=客户内容VPN&password=pw-secret".getBytes());
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/tickets/{id}/comments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(302);

        try (LogCapture logs = new LogCapture(RequestLogFilter.class)) {
            new RequestLogFilter().doFilter(request, response, new MockFilterChain());
            assertThat(logs.messages()).hasSize(1);
            String line = logs.messages().get(0);
            assertThat(line).startsWith("POST /tickets/{id}/comments 302 ").contains("user=42");
            assertThat(line).doesNotContain("打印机", "客户内容", "pw-secret", "secret-login", "敏感姓名", "$2a$",
                "77");
        }
    }

    @Test
    void unmatchedRouteMasksNumericSegmentsAndQuery() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tickets/123");
        request.setQueryString("q=xyz");
        assertThat(RequestLogFilter.route(request)).isEqualTo("/tickets/{id}");
    }

    @Test
    void anonymousUserLoggedAsDash() throws Exception {
        try (LogCapture logs = new LogCapture(RequestLogFilter.class)) {
            new RequestLogFilter().doFilter(new MockHttpServletRequest("GET", "/login"),
                new MockHttpServletResponse(), new MockFilterChain());
            assertThat(logs.messages().get(0)).startsWith("GET /login 200 ").endsWith("user=-");
        }
    }
}
