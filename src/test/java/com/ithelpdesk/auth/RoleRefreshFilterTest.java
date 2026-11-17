package com.ithelpdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ithelpdesk.user.AppUser;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class RoleRefreshFilterTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRefreshFilter filter = new RoleRefreshFilter(users);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(CurrentUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
            user.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    @Test
    void rebuildsAuthenticationWhenRoleChanged() throws Exception {
        login(new CurrentUser(7, "u1", "用户", Role.EMPLOYEE, "x"));
        when(users.findById(7)).thenReturn(Optional.of(
            new AppUser(7, "u1", "hash", "用户", Role.IT_STAFF, Instant.EPOCH, null)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tickets");
        request.setSession(new MockHttpSession());
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(((CurrentUser) auth.getPrincipal()).getRole()).isEqualTo(Role.IT_STAFF);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_IT_STAFF");
        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getSession().getAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
    }

    @Test
    void keepsAuthenticationWhenRoleUnchanged() throws Exception {
        CurrentUser user = new CurrentUser(8, "u2", "用户", Role.EMPLOYEE, "x");
        login(user);
        when(users.findById(8)).thenReturn(Optional.of(
            new AppUser(8, "u2", "hash", "用户", Role.EMPLOYEE, Instant.EPOCH, null)));
        filter.doFilter(new MockHttpServletRequest("GET", "/tickets"), new MockHttpServletResponse(),
            new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
    }

    @Test
    void invalidatesSessionWhenUserMissing() throws Exception {
        login(new CurrentUser(9, "gone", "用户", Role.EMPLOYEE, "x"));
        when(users.findById(9)).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tickets");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ignoresAnonymousRequests() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/login"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
