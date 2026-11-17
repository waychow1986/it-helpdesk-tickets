package com.ithelpdesk.web;

import com.ithelpdesk.auth.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 请求日志：只记录方法、路由模板、状态码、耗时、用户 id。
 * 不记录查询参数、请求体、工单内容、密码或令牌（宪章「安全与隐私」）。
 */
@Component
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            LOG.info("{} {} {} {}ms user={}", request.getMethod(), route(request), response.getStatus(), millis,
                userId());
        }
    }

    static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            return pattern.toString();
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return "-";
        }
        // 未匹配到控制器（如安全过滤器处理的 /login）：只取路径，并把数字段替换为占位符。
        return uri.replaceAll("/\\d+(?=/|$)", "/{id}");
    }

    private static String userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser user) {
            return String.valueOf(user.getId());
        }
        return "-";
    }
}
