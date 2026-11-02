package com.zz.agent.workflow;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/** 演示配置下的 HTTP 写请求统一校验；纯检索 POST 是唯一只读例外。 */
public final class AgentTaskCsrfFilter extends OncePerRequestFilter {

    private final AgentTaskCsrf csrf;

    public AgentTaskCsrfFilter(AgentTaskCsrf csrf) {
        this.csrf = Objects.requireNonNull(csrf, "令牌校验器不能为空");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        boolean mutation = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        boolean readOnlyPolicySearch = "POST".equals(method) && "/api/policies/search".equals(path);
        if (mutation && !readOnlyPolicySearch
                && !csrf.allowed(request.getHeader("Cookie"), request.getHeader("Origin"),
                request.getScheme(), request.getServerName(), request.getServerPort(),
                request.getHeader("X-Agent-CSRF"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "同源或 CSRF 校验失败");
            return;
        }
        chain.doFilter(request, response);
    }
}
