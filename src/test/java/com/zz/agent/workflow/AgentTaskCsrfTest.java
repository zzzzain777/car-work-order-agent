package com.zz.agent.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 令牌缺失、跨源或失效时，写入不能进入业务控制器。 */
class AgentTaskCsrfTest {

    private static final String COOKIE = "SESSION=browser-session; other=1";
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T02:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void validSameOriginTokenPassesButTamperingOtherSessionAndExpiryFail() {
        AgentTaskCsrf csrf = new AgentTaskCsrf(clock);
        AgentTaskCsrf.Issued issued = csrf.issue(COOKIE);

        assertTrue(csrf.allowed(COOKIE, "http://localhost:8091", "http", "localhost", 8091,
                issued.token()));
        assertFalse(csrf.allowed("SESSION=another-session", "http://localhost:8091", "http",
                "localhost", 8091, issued.token()));
        assertFalse(csrf.allowed(COOKIE, "http://evil.example", "http", "localhost", 8091,
                issued.token()));
        assertFalse(csrf.allowed(COOKIE, "http://localhost:8091", "http", "localhost", 8091,
                issued.token() + "x"));
        now.set(issued.expiresAt());
        assertFalse(csrf.allowed(COOKIE, "http://localhost:8091", "http", "localhost", 8091,
                issued.token()));
    }

    @Test
    void filterProtectsEveryTaskMutationAndDoesNotBlockTaskReads() throws Exception {
        AgentTaskCsrf csrf = new AgentTaskCsrf(clock);
        AgentTaskCsrfFilter filter = new AgentTaskCsrfFilter(csrf);
        AtomicInteger reached = new AtomicInteger();
        MockHttpServletRequest blocked = request("POST", "/api/agent/tasks/123/decision");
        MockHttpServletResponse blockedResponse = new MockHttpServletResponse();

        filter.doFilter(blocked, blockedResponse, (request, response) -> reached.incrementAndGet());
        assertEquals(403, blockedResponse.getStatus());
        assertEquals(0, reached.get());

        AgentTaskCsrf.Issued issued = csrf.issue(COOKIE);
        MockHttpServletRequest accepted = request("POST", "/api/agent/tasks/123/decision");
        accepted.addHeader("Cookie", COOKIE);
        accepted.addHeader("Origin", "http://localhost:8091");
        accepted.addHeader("X-Agent-CSRF", issued.token());
        filter.doFilter(accepted, new MockHttpServletResponse(),
                (request, response) -> reached.incrementAndGet());
        assertEquals(1, reached.get());

        filter.doFilter(request("GET", "/api/agent/tasks/123"), new MockHttpServletResponse(),
                (request, response) -> reached.incrementAndGet());
        assertEquals(2, reached.get());
    }

    @Test
    void encodedTaskPathCannotBypassTheWriteGuard() throws Exception {
        AgentTaskCsrfFilter filter = new AgentTaskCsrfFilter(new AgentTaskCsrf(clock));
        AtomicInteger reached = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/%61gent/tasks/123/decision"), response,
                (request, output) -> reached.incrementAndGet());

        assertEquals(403, response.getStatus());
        assertEquals(0, reached.get());
        filter.doFilter(request("POST", "/api/policies/search"), new MockHttpServletResponse(),
                (request, output) -> reached.incrementAndGet());
        assertEquals(1, reached.get());
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8091);
        return request;
    }
}
