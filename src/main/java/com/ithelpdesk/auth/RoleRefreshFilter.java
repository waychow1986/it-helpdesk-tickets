package com.ithelpdesk.auth;

import com.ithelpdesk.user.AppUser;
import com.ithelpdesk.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个已认证请求按用户 id 读库刷新身份（TD-006，FR-004"下一次操作即生效"）。
 * 账号不存在时使会话失效并重定向登录。由 SecurityConfig 放在 AuthorizationFilter 之前。
 */
public class RoleRefreshFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final SecurityContextRepository contextRepository;

    public RoleRefreshFilter(UserRepository users) {
        this(users, new HttpSessionSecurityContextRepository());
    }

    public RoleRefreshFilter(UserRepository users, SecurityContextRepository contextRepository) {
        this.users = users;
        this.contextRepository = contextRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof CurrentUser current) {
            Optional<AppUser> stored = users.findById(current.getId());
            if (stored.isEmpty()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                response.sendRedirect(request.getContextPath() + "/login");
                return;
            }
            if (stored.get().role() != current.getRole()) {
                CurrentUser refreshed = current.withRole(stored.get().role());
                UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(
                    refreshed, null, refreshed.getAuthorities());
                token.setDetails(auth.getDetails());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(token);
                SecurityContextHolder.setContext(context);
                contextRepository.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
